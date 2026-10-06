from __future__ import annotations

import sys
import inspect
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core.topic_task_store import (
    TopicTaskStore,
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


def test_admin_list_widget_users_groups_users_and_forwards_filters(monkeypatch):
    class FakeCursor:
        def __init__(self, conn):
            self.conn = conn

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def execute(self, sql, params):
            self.conn.executed.append((sql, list(params)))

        def fetchall(self):
            return [
                {"kind": "ext", "user_id": "u_10086", "topic_count": 3, "last_active_at": None},
                {"kind": "vis", "user_id": "", "topic_count": 1, "last_active_at": None},
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

    users = store.admin_list_widget_users(website_id="site_a", keyword="u_", limit=50)

    # Rows without a user_id are dropped; counts are coerced to int.
    assert users == [{"kind": "ext", "user_id": "u_10086", "topic_count": 3}]

    sql, params = conn.executed[0]
    assert "GROUP BY kind, user_id" in sql
    assert "t.source = %s" in sql
    # widget source + website + keyword (LIKE twice) + limit
    assert params == ["widget", "site_a", "%u_%", "%u_%", 50]
    # Admin facet must not reuse the per-user isolation predicate.
    assert "COALESCE(t.source" not in sql


# ---------------------------------------------------------------------------
# 可见性谓词矩阵（docs/design/2026-07-01-dataagent-auth-design.md 3.5）
# ---------------------------------------------------------------------------

def test_predicate_auth_disabled_is_legacy_byte_identical(monkeypatch):
    monkeypatch.setattr("core.topic_task_store.is_auth_enabled", lambda: False)
    store = TopicTaskStore()

    sql, params = store._topic_context_predicate({"source": "portal"}, alias="t")  # noqa: SLF001
    assert sql == "COALESCE(t.source, 'portal') = %s"
    assert params == ["portal"]

    # 即使 context 意外携带 owner 键，关闭态也不加 owner 过滤。
    sql, params = store._topic_context_predicate(  # noqa: SLF001
        {"source": "portal", "auth_user_id": "SSO:42"}, alias="t"
    )
    assert sql == "COALESCE(t.source, 'portal') = %s"
    assert params == ["portal"]


def test_predicate_auth_enabled_matrix(monkeypatch):
    monkeypatch.setattr("core.topic_task_store.is_auth_enabled", lambda: True)
    store = TopicTaskStore()

    # 匿名/无标记客户端：等值比较，匿名池不泄漏用户会话。
    sql, params = store._topic_context_predicate({"source": "portal"}, alias="t")  # noqa: SLF001
    assert sql == "t.source = %s AND t.auth_user_id = '' AND t.is_agent_preview = 0"
    assert params == ["portal"]

    # 登录普通用户：只看自己。
    sql, params = store._topic_context_predicate(  # noqa: SLF001
        {"source": "portal", "auth_user_id": "SSO:42", "auth_role": "user"}, alias="t"
    )
    assert sql == "t.source = %s AND t.auth_user_id = %s AND t.is_agent_preview = 0"
    assert params == ["portal", "SSO:42"]

    # admin 聊天列表：portal 全量（跨源全量走管理端点 context=None）。
    sql, params = store._topic_context_predicate(  # noqa: SLF001
        {"source": "portal", "auth_user_id": "local:admin", "auth_role": "admin"}, alias="t"
    )
    assert sql == "t.source = %s"
    assert params == ["portal"]

    # context=None（管理端点）不过滤。
    sql, params = store._topic_context_predicate(None, alias="t")  # noqa: SLF001
    assert sql == "1 = 1"
    assert params == []


def test_predicate_widget_branch_unchanged_regardless_of_auth(monkeypatch):
    widget_context = {"source": "widget", "website_id": "demo", "external_user_id": "u1"}
    expected_sql = "t.source = %s AND t.website_id = %s AND t.external_user_id = %s"
    store = TopicTaskStore()

    for enabled in (True, False):
        monkeypatch.setattr("core.topic_task_store.is_auth_enabled", lambda enabled=enabled: enabled)
        sql, params = store._topic_context_predicate(widget_context, alias="t")  # noqa: SLF001
        assert sql == expected_sql
        assert params == ["widget", "demo", "u1"]


def test_create_topic_writes_auth_owner(monkeypatch):
    class FakeCursor:
        def __init__(self, conn):
            self.conn = conn

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def execute(self, sql, params):
            self.conn.executed.append((sql, list(params)))

    class FakeConnection:
        def __init__(self):
            self.executed = []

        def cursor(self):
            return FakeCursor(self)

        def commit(self):
            return None

        def close(self):
            return None

    monkeypatch.setattr("core.topic_task_store.is_auth_enabled", lambda: True)
    store = TopicTaskStore()
    conn = FakeConnection()
    monkeypatch.setattr(store, "_ensure_ready", lambda: None)
    monkeypatch.setattr(store, "_connect", lambda database: conn)
    monkeypatch.setattr(store, "get_topic", lambda topic_id, context=None: {"topic_id": topic_id})

    store.create_topic(
        title="趋势分析",
        context={
            "source": "portal",
            "auth_user_id": "SSO:42",
            "auth_display_name": "alice",
            "auth_role": "user",
        },
    )

    sql, params = conn.executed[0]
    assert "auth_user_id, auth_username" in sql
    assert params[-2] == "alice"
    assert params[-3:] == ["SSO:42", "alice", 0]

    # 匿名 portal 创建：owner 为空串。
    conn.executed.clear()
    store.create_topic(title="匿名会话", context={"source": "portal"})
    _, params = conn.executed[0]
    assert params[-3:] == ["", "", 0]


def test_admin_list_auth_users_groups_by_stable_id_and_forwards_keyword(monkeypatch):
    class FakeCursor:
        def __init__(self, conn):
            self.conn = conn

        def __enter__(self):
            return self

        def __exit__(self, exc_type, exc, tb):
            return False

        def execute(self, sql, params):
            self.conn.executed.append((sql, list(params)))

        def fetchall(self):
            return [
                {"user_id": "SSO:42", "display_name": "alice", "topic_count": 3, "last_active_at": None},
                {"user_id": "", "display_name": "", "topic_count": 1, "last_active_at": None},
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

    users = store.admin_list_auth_users(keyword="ali", limit=50)

    # 无 user_id 的行被丢弃；计数强制为 int；时间转 ISO 字符串。
    assert users == [
        {"user_id": "SSO:42", "display_name": "alice", "topic_count": 3, "last_active_at": ""}
    ]

    sql, params = conn.executed[0]
    assert "GROUP BY t.auth_user_id" in sql
    assert "NULLIF(t.auth_user_id, '') IS NOT NULL" in sql
    # keyword 同时匹配稳定 ID 与展示名（LIKE 两次）+ limit。
    assert params == ["%ali%", "%ali%", 50]
    # 管理侧查询不得复用按用户隔离谓词。
    assert "COALESCE(t.source" not in sql
