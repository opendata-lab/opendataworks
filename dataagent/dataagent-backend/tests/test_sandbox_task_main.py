from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

import sandbox_task_main
from core.task_executor import TaskExecutionResult


def _payload() -> dict:
    return {
        "task_id": "task-1",
        "topic_id": "topic-1",
        "question": "hello",
        "history": [],
        "resume_session_id": None,
        "provider_id": "openrouter",
        "model": "anthropic/claude-sonnet-4.5",
        "database_hint": None,
        "debug": False,
        "timeout_seconds": 60,
        "sql_read_timeout_seconds": 30,
        "sql_write_timeout_seconds": 30,
        "execution_mode": "background",
        "agent_snapshot": None,
    }


def test_serve_loop_processes_multiple_payloads_then_exits_on_eof(monkeypatch, tmp_path: Path):
    captured: list[str] = []

    async def fake_execute_and_emit(params):
        captured.append(params.task_id)
        return TaskExecutionResult(
            task_status="finished",
            content=f"warm-{params.task_id}",
            provider_id=params.provider_id,
            model=params.model,
        )

    async def fake_reader():
        reader = asyncio.StreamReader()
        first = json.dumps(_payload())
        second_payload = _payload()
        second_payload["task_id"] = "task-2"
        second = json.dumps(second_payload)
        reader.feed_data((first + "\n" + second + "\n").encode("utf-8"))
        reader.feed_eof()
        return reader

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(sandbox_task_main, "_execute_and_emit", fake_execute_and_emit)
    monkeypatch.setattr(sandbox_task_main, "_stdin_reader", fake_reader)

    exit_code = asyncio.run(sandbox_task_main._serve_loop())

    assert exit_code == 0
    assert captured == ["task-1", "task-2"]


def test_serve_loop_exits_on_idle_timeout(monkeypatch, tmp_path: Path):
    async def fake_reader():
        reader = asyncio.StreamReader()
        # never feeds data and never EOFs -> readline blocks until idle timeout
        return reader

    monkeypatch.setenv("DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT", "0.1")
    monkeypatch.setattr(sandbox_task_main, "_stdin_reader", fake_reader)

    exit_code = asyncio.run(sandbox_task_main._serve_loop())

    assert exit_code == 0


def test_sandbox_task_main_uses_process_cwd_as_prepared_workspace(monkeypatch, tmp_path: Path, capsys):
    captured: dict[str, object] = {}

    async def fake_execute(params, *, emit, is_cancel_requested=None, prepared_workspace_dir=None):
        captured["task_id"] = params.task_id
        captured["prepared_workspace_dir"] = prepared_workspace_dir
        await emit({"record_type": "stream", "event_type": "message_start"})
        return TaskExecutionResult(
            task_status="finished",
            content="sandbox-task-ok",
            provider_id=params.provider_id,
            model=params.model,
            session_id="sdk-session-task",
        )

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(sandbox_task_main, "_load_payload", _payload)
    monkeypatch.setattr(sandbox_task_main, "_execute_task_stream_local", fake_execute)

    exit_code = asyncio.run(sandbox_task_main._main())

    assert exit_code == 0
    assert captured["task_id"] == "task-1"
    assert captured["prepared_workspace_dir"] == tmp_path
    lines = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert lines[0]["type"] == "record"
    assert lines[-1]["type"] == "result"
    assert lines[-1]["result"]["content"] == "sandbox-task-ok"
