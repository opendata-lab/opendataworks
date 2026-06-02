from __future__ import annotations

import sys
import inspect
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core.topic_task_store import (
    TopicTaskStore,
    _project_task_history,
    WIDGET_EVENT_TYPES,
    MAX_WIDGET_EVENTS_PER_BATCH,
    _parse_client_ts,
)


def test_get_message_accepts_request_context_for_scoped_callers():
    signature = inspect.signature(TopicTaskStore.get_message)

    assert "context" in signature.parameters


def test_get_message_applies_topic_context_predicate(monkeypatch):
    class FakeCursor:
        def __init__(self, conn):
            self.conn = conn

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def execute(self, sql, params):
            self.conn.executed.append((sql, list(params)))

        def fetchone(self):
            return self.conn.rows.pop(0)

    class FakeConnection:
        def __init__(self):
            self.executed = []
            self.rows = [
                {
                    "message_id": "msg_1",
                    "topic_id": "topic_1",
                    "task_id": "task_1",
                    "sender_type": "user",
                    "type": "chat",
                    "status": "success",
                    "content": "问题",
                    "event": "",
                    "steps_json": None,
                    "tool_json": None,
                    "seq_id": 1,
                    "correlation_id": None,
                    "parent_correlation_id": None,
                    "content_type": None,
                    "usage_json": None,
                    "feedback": "",
                    "error_json": None,
                    "show_in_ui": 1,
                    "created_at": None,
                    "updated_at": None,
                }
            ]

        def cursor(self):
            return FakeCursor(self)

        def close(self):
            return None

    store = TopicTaskStore()
    conn = FakeConnection()
    monkeypatch.setattr(store, "_ensure_ready", lambda: None)
    monkeypatch.setattr(store, "_connect", lambda database: conn)

    message = store.get_message("msg_1", context={"source": "portal"})

    assert message["message_id"] == "msg_1"
    sql, params = conn.executed[0]
    assert "JOIN da_agent_topic t ON t.topic_id = m.topic_id" in sql
    assert "COALESCE(t.source, 'portal') = %s" in sql
    assert params == ["msg_1", "portal"]


def test_project_task_history_rebuilds_finished_blocks():
    payload = _project_task_history(
        [
            {
                "record_type": "event",
                "seq_id": 1,
                "event_type": "BEFORE_AGENT_REPLY",
                "correlation_id": "reasoning_1",
                "content_type": "reasoning",
                "data": {"status": "running"},
            },
            {
                "record_type": "chunk",
                "seq_id": 2,
                "content": "先定位指标。",
                "delta": {"status": "END"},
                "metadata": {"correlation_id": "reasoning_1", "content_type": "reasoning"},
            },
            {
                "record_type": "event",
                "seq_id": 3,
                "event_type": "PENDING_TOOL_CALL",
                "correlation_id": "tool_1",
                "data": {"tool": {"id": "tool_1", "name": "Read", "status": "pending", "input": {"path": "skill.md"}}},
            },
            {
                "record_type": "event",
                "seq_id": 4,
                "event_type": "AFTER_TOOL_CALL",
                "correlation_id": "tool_1",
                "data": {"tool": {"id": "tool_1", "name": "Read", "status": "success", "output": "读取完成"}},
            },
            {
                "record_type": "event",
                "seq_id": 5,
                "event_type": "BEFORE_AGENT_REPLY",
                "correlation_id": "content_1",
                "content_type": "content",
                "data": {"status": "running"},
            },
            {
                "record_type": "chunk",
                "seq_id": 6,
                "content": "最近 30 天累计发布 4 次。",
                "delta": {"status": "END"},
                "metadata": {"correlation_id": "content_1", "content_type": "content"},
            },
        ]
    )

    assert payload["resume_after_seq"] == 6
    assert [block["type"] for block in payload["blocks"]] == ["thinking", "tool", "main_text"]
    assert payload["blocks"][0]["text"] == "先定位指标。"
    assert payload["blocks"][1]["tool_name"] == "Read"
    assert payload["blocks"][1]["output"] == "读取完成"
    assert payload["blocks"][2]["text"] == "最近 30 天累计发布 4 次。"


def test_project_task_history_preserves_running_blocks_and_fallback_cursor():
    payload = _project_task_history(
        [
            {
                "record_type": "event",
                "seq_id": 2,
                "event_type": "BEFORE_AGENT_REPLY",
                "correlation_id": "reasoning_2",
                "content_type": "reasoning",
                "data": {"status": "running"},
            },
            {
                "record_type": "chunk",
                "seq_id": 3,
                "content": "正在检查数据源",
                "delta": {"status": "STREAMING"},
                "metadata": {"correlation_id": "reasoning_2", "content_type": "reasoning"},
            },
            {
                "record_type": "event",
                "seq_id": 4,
                "event_type": "PENDING_TOOL_CALL",
                "correlation_id": "tool_2",
                "data": {"tool": {"id": "tool_2", "name": "Bash", "status": "pending"}},
            },
        ],
        fallback_resume_after_seq=9,
    )

    assert payload["resume_after_seq"] == 9
    assert [block["type"] for block in payload["blocks"]] == ["thinking", "tool"]
    assert payload["blocks"][0]["status"] == "streaming"
    assert payload["blocks"][1]["status"] == "pending"


def test_project_task_history_builds_error_block():
    payload = _project_task_history(
        [
            {
                "record_type": "event",
                "seq_id": 7,
                "event_type": "ERROR",
                "data": {"status": "error", "error": {"message": "SQL 执行失败"}},
            }
        ]
    )

    assert payload["resume_after_seq"] == 7
    assert len(payload["blocks"]) == 1
    assert payload["blocks"][0]["type"] == "error"
    assert payload["blocks"][0]["text"] == "SQL 执行失败"


def test_normalize_message_row_only_attaches_history_to_assistant():
    store = TopicTaskStore()

    assistant = store._normalize_message_row(  # noqa: SLF001
        {
            "message_id": "msg_1",
            "topic_id": "topic_1",
            "task_id": "task_1",
            "sender_type": "assistant",
            "type": "assistant",
            "status": "finished",
            "content": "最终结果",
            "event": "",
            "steps_json": None,
            "tool_json": None,
            "seq_id": 2,
            "correlation_id": None,
            "parent_correlation_id": None,
            "content_type": None,
            "usage_json": None,
            "feedback": "like",
            "error_json": None,
            "show_in_ui": 1,
            "created_at": None,
            "updated_at": None,
        },
        history={"blocks": [{"block_id": "main_1", "type": "main_text", "text": "最终结果", "status": "success"}], "resume_after_seq": 12},
    )
    user = store._normalize_message_row(  # noqa: SLF001
        {
            "message_id": "msg_2",
            "topic_id": "topic_1",
            "task_id": "task_1",
            "sender_type": "user",
            "type": "chat",
            "status": "success",
            "content": "问题",
            "event": "",
            "steps_json": None,
            "tool_json": None,
            "seq_id": 1,
            "correlation_id": None,
            "parent_correlation_id": None,
            "content_type": None,
            "usage_json": None,
            "error_json": None,
            "show_in_ui": 1,
            "created_at": None,
            "updated_at": None,
        },
        history={"blocks": [{"block_id": "ignored", "type": "thinking", "text": "忽略", "status": "success"}], "resume_after_seq": 9},
    )

    assert assistant["blocks"][0]["type"] == "main_text"
    assert assistant["resume_after_seq"] == 12
    assert assistant["feedback"] == "like"
    assert "blocks" not in user
    assert "resume_after_seq" not in user
    assert user["feedback"] == ""


# ---------------------------------------------------------------------------
# record_widget_events
# ---------------------------------------------------------------------------

def _make_store_with_fake_db(inserted_rows):
    """Return a TopicTaskStore whose _connect returns a fake connection."""

    class FakeCursor:
        def __init__(self):
            self.executed_many = []

        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def executemany(self, sql, rows):
            for row in rows:
                inserted_rows.append(row)

    class FakeConn:
        def __init__(self):
            self._cursor = FakeCursor()

        def cursor(self):
            return self._cursor

        def commit(self):
            pass

        def close(self):
            pass

    store = TopicTaskStore()
    store._ready = True

    fake_conn = FakeConn()
    store._connect = lambda database=None: fake_conn
    store._schema_name = lambda: "dataagent"
    return store


def test_record_widget_events_persists_valid_events():
    rows = []
    store = _make_store_with_fake_db(rows)
    context = {"source": "widget", "website_id": "site1", "external_user_id": "u1", "visitor_id": ""}

    accepted = store.record_widget_events(
        [
            {"event_type": "widget_open", "agent_id": "agent_a"},
            {"event_type": "message_send", "payload": {"input_source": "typed", "length": 10}},
        ],
        context,
    )

    assert accepted == 2
    assert len(rows) == 2
    # Row tuple: (event_type, source, website_id, external_user_id, visitor_id, agent_id, topic_id, task_id, message_id, payload_json, client_ts)
    assert rows[0][0] == "widget_open"
    assert rows[0][1] == "widget"
    assert rows[0][2] == "site1"
    assert rows[0][3] == "u1"
    assert rows[1][0] == "message_send"
    assert rows[1][9] is not None  # payload_json


def test_record_widget_events_rejects_unknown_event_types():
    rows = []
    store = _make_store_with_fake_db(rows)
    context = {"source": "widget", "website_id": "site1", "external_user_id": "u1", "visitor_id": ""}

    accepted = store.record_widget_events(
        [
            {"event_type": "unknown_event"},
            {"event_type": "widget_open"},
        ],
        context,
    )

    assert accepted == 1
    assert rows[0][0] == "widget_open"


def test_record_widget_events_ignores_oversized_payload():
    rows = []
    store = _make_store_with_fake_db(rows)
    context = {"source": "widget", "website_id": "site1", "external_user_id": "u1", "visitor_id": ""}
    big_payload = {"data": "x" * 5000}

    accepted = store.record_widget_events(
        [{"event_type": "widget_open", "payload": big_payload}],
        context,
    )

    assert accepted == 1
    assert rows[0][9] is None  # payload_json dropped


def test_record_widget_events_enforces_batch_limit():
    rows = []
    store = _make_store_with_fake_db(rows)
    context = {"source": "widget", "website_id": "site1", "external_user_id": "u1", "visitor_id": ""}
    events = [{"event_type": "widget_open"}] * (MAX_WIDGET_EVENTS_PER_BATCH + 10)

    accepted = store.record_widget_events(events, context)

    assert accepted == MAX_WIDGET_EVENTS_PER_BATCH


def test_record_widget_events_uses_only_header_identity():
    rows = []
    store = _make_store_with_fake_db(rows)
    # Identity from context only — body fields should be ignored
    context = {"source": "widget", "website_id": "real_site", "external_user_id": "real_user", "visitor_id": ""}

    store.record_widget_events(
        [{"event_type": "widget_open", "website_id": "injected_site", "external_user_id": "injected_user"}],
        context,
    )

    assert rows[0][2] == "real_site"
    assert rows[0][3] == "real_user"


def test_record_widget_events_returns_zero_for_empty_list():
    rows = []
    store = _make_store_with_fake_db(rows)
    context = {"source": "widget", "website_id": "site1", "external_user_id": "u1", "visitor_id": ""}

    accepted = store.record_widget_events([], context)

    assert accepted == 0
    assert rows == []


def test_parse_client_ts_handles_iso_and_z_suffix():
    from datetime import datetime

    ts = _parse_client_ts("2026-05-29T10:00:00Z")
    assert isinstance(ts, datetime)

    ts2 = _parse_client_ts("2026-05-29T10:00:00")
    assert isinstance(ts2, datetime)

    assert _parse_client_ts("") is None
    assert _parse_client_ts("not-a-date") is None


def test_widget_event_types_allowlist_contains_expected_values():
    assert "widget_open" in WIDGET_EVENT_TYPES
    assert "widget_close" in WIDGET_EVENT_TYPES
    assert "history_open" in WIDGET_EVENT_TYPES
    assert "history_close" in WIDGET_EVENT_TYPES
    assert "conversation_new" in WIDGET_EVENT_TYPES
    assert "message_send" in WIDGET_EVENT_TYPES


def test_admin_list_topics_scopes_to_widget_and_forwards_filters(monkeypatch):
    class FakeCursor:
        def __init__(self, conn):
            self.conn = conn

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def execute(self, sql, params):
            self.conn.executed.append((sql, list(params)))

        def fetchone(self):
            return {"total": 1}

        def fetchall(self):
            return [
                {
                    "topic_id": "topic_widget_1",
                    "title": "嵌入站会话",
                    "chat_topic_id": "chat_1",
                    "chat_conversation_id": "conv_1",
                    "current_task_id": None,
                    "current_task_status": None,
                    "source": "widget",
                    "website_id": "site_a",
                    "external_user_id": "",
                    "visitor_id": "visitor_x",
                    "agent_id": "agent_default",
                    "agent_snapshot_json": None,
                    "created_at": None,
                    "updated_at": None,
                    "message_count": 3,
                    "last_message_preview": "最近一条",
                }
            ]

    class FakeConnection:
        def __init__(self):
            self.executed = []

        def cursor(self):
            return FakeCursor(self)

        def close(self):
            return None

    store = TopicTaskStore()
    conn = FakeConnection()
    monkeypatch.setattr(store, "_ensure_ready", lambda: None)
    monkeypatch.setattr(store, "_connect", lambda database: conn)

    result = store.admin_list_topics(website_id="site_a", visitor_id="visitor_x", page=2, page_size=10)

    assert result["total"] == 1
    assert result["page"] == 2
    assert result["items"][0]["source"] == "widget"
    assert result["items"][0]["website_id"] == "site_a"

    count_sql, count_params = conn.executed[0]
    assert "COUNT(*)" in count_sql
    assert "t.source = %s" in count_sql
    assert count_params == ["widget", "site_a", "visitor_x"]

    list_sql, list_params = conn.executed[1]
    assert "LIMIT %s OFFSET %s" in list_sql
    # widget source + filters first, then pagination (page 2, size 10 -> offset 10)
    assert list_params == ["widget", "site_a", "visitor_x", 10, 10]
    # Per-user isolation predicate must never be reused by the admin path.
    assert "COALESCE(t.source" not in list_sql
