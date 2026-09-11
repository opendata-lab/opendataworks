from __future__ import annotations

import importlib.util
import logging
from contextlib import contextmanager
from pathlib import Path
from types import ModuleType

import pytest

from core.topic_task_store import TopicTaskStore


class _FinishCursor:
    def __init__(self, connection):
        self.connection = connection
        self.rowcount = 0
        self._selecting_source = False

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        return False

    def execute(self, sql, params):
        normalized = " ".join(sql.split())
        self.connection.executed.append((normalized, params))
        self._selecting_source = normalized.startswith(
            "SELECT source_queue_id, source_schedule_id, source_schedule_log_id"
        )
        if normalized.startswith("UPDATE da_agent_task"):
            self.connection.task_status = params[0]
            self.rowcount = 1
        elif normalized.startswith("UPDATE da_agent_topic"):
            self.rowcount = 1
        else:
            self.rowcount = 0

    def fetchone(self):
        return {} if self._selecting_source else None


class _FinishConnection:
    def __init__(self):
        self.executed = []
        self.task_status = "running"
        self.commit_count = 0
        self.closed = False

    def cursor(self):
        return _FinishCursor(self)

    def commit(self):
        self.commit_count += 1

    def close(self):
        self.closed = True


def _finish_store(monkeypatch):
    store = TopicTaskStore()
    connection = _FinishConnection()
    monkeypatch.setattr(store, "_ensure_ready", lambda: None)
    monkeypatch.setattr(store, "_schema_name", lambda: "dataagent")
    monkeypatch.setattr(store, "_connect", lambda database: connection)
    monkeypatch.setattr(
        store,
        "get_task",
        lambda task_id: {"task_id": task_id, "task_status": connection.task_status},
    )
    return store, connection


@pytest.mark.parametrize("terminal_status", ["finished", "error", "suspended"])
def test_finish_task_deletes_deltas_only_after_terminal_state_commit(monkeypatch, terminal_status):
    store, connection = _finish_store(monkeypatch)
    cleanup_calls = []

    def delete_deltas(task_id):
        cleanup_calls.append((task_id, connection.commit_count, connection.task_status))
        return 12

    monkeypatch.setattr(store, "_delete_task_content_deltas", delete_deltas)

    task = store.finish_task(task_id="task_1", task_status=terminal_status)

    assert task["task_status"] == terminal_status
    assert cleanup_calls == [("task_1", 1, terminal_status)]
    assert connection.closed is True


def test_finish_task_keeps_terminal_state_when_delta_delete_fails(monkeypatch, caplog):
    store, connection = _finish_store(monkeypatch)
    cleanup_calls = []

    def fail_delete(task_id):
        cleanup_calls.append(task_id)
        raise RuntimeError("delete unavailable")

    monkeypatch.setattr(store, "_delete_task_content_deltas", fail_delete)

    with caplog.at_level(logging.WARNING):
        task = store.finish_task(task_id="task_2", task_status="error", error={"message": "boom"})

    assert cleanup_calls == ["task_2"]
    assert connection.commit_count == 1
    assert task["task_status"] == "error"
    assert "task.store.content_delta_delete_failed task_id=task_2" in caplog.text


def test_task_delta_delete_is_task_scoped_and_idempotent(monkeypatch):
    class DeleteCursor:
        def __init__(self, rowcount):
            self.rowcount = rowcount
            self.executed = []

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def execute(self, sql, params):
            self.executed.append((" ".join(sql.split()), params))

    class DeleteConnection:
        def __init__(self, rowcount):
            self.cursor_value = DeleteCursor(rowcount)
            self.committed = False
            self.closed = False

        def cursor(self):
            return self.cursor_value

        def commit(self):
            self.committed = True

        def close(self):
            self.closed = True

    store = TopicTaskStore()
    all_connections = [DeleteConnection(3), DeleteConnection(0)]
    connections = list(all_connections)
    monkeypatch.setattr(store, "_ensure_ready", lambda: None)
    monkeypatch.setattr(store, "_schema_name", lambda: "dataagent")
    monkeypatch.setattr(store, "_connect", lambda database: connections.pop(0))

    first = store._delete_task_content_deltas("task_3")
    second = store._delete_task_content_deltas("task_3")

    assert (first, second) == (3, 0)
    for connection in all_connections:
        assert connection.committed and connection.closed
        [(sql, params)] = connection.cursor_value.executed
        assert "WHERE task_id = %s AND event_type = 'content.delta'" in sql
        assert params == ("task_3",)


_MIGRATION_PATH = (
    Path(__file__).resolve().parents[1]
    / "alembic"
    / "versions"
    / "20260911_000024_purge_content_deltas.py"
)


def _load_migration() -> ModuleType:
    spec = importlib.util.spec_from_file_location("purge_content_deltas", _MIGRATION_PATH)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_migration_deletes_in_autocommitted_bounded_batches(monkeypatch):
    migration = _load_migration()

    class Result:
        def __init__(self, rowcount):
            self.rowcount = rowcount

    class Bind:
        def __init__(self):
            self.rowcounts = iter((5_000, 5_000, 17))
            self.statements = []

        def exec_driver_sql(self, sql):
            self.statements.append(" ".join(sql.split()))
            return Result(next(self.rowcounts))

    class Context:
        def __init__(self):
            self.entered = 0

        @contextmanager
        def autocommit_block(self):
            self.entered += 1
            yield

    bind = Bind()
    context = Context()
    monkeypatch.setattr(migration, "_has_table", lambda table_name: True)
    monkeypatch.setattr(migration.op, "get_bind", lambda: bind)
    monkeypatch.setattr(migration.op, "get_context", lambda: context)

    migration.upgrade()

    assert context.entered == 1
    assert len(bind.statements) == 3
    assert all("WHERE event_type = 'content.delta'" in sql for sql in bind.statements)
    assert all("ORDER BY id LIMIT 5000" in sql for sql in bind.statements)


def test_migration_downgrade_is_intentionally_empty(monkeypatch):
    migration = _load_migration()
    monkeypatch.setattr(
        migration.op,
        "get_bind",
        lambda: (_ for _ in ()).throw(AssertionError("downgrade must not execute SQL")),
    )

    assert migration.downgrade() is None
