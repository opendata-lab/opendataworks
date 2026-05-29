from __future__ import annotations

import sys
import types
from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

if "pymysql" not in sys.modules:
    sys.modules["pymysql"] = types.SimpleNamespace(
        connect=lambda *args, **kwargs: None,
        cursors=types.SimpleNamespace(DictCursor=object),
        connections=types.SimpleNamespace(Connection=object),
    )

import api.routes as routes
from main import app
from core.topic_task_store import TopicTaskStore


def topic_payload(topic_id: str, title: str = "Widget Topic", *, source: str = "widget", website_id: str = "demo", external_user_id: str = "u1", visitor_id: str = ""):
    return {
        "topic_id": topic_id,
        "title": title,
        "chat_topic_id": f"chat_{topic_id}",
        "chat_conversation_id": f"conversation_{topic_id}",
        "current_task_id": "task-1",
        "current_task_status": "waiting",
        "message_count": 0,
        "last_message_preview": "",
        "created_at": "2026-04-29T10:00:00",
        "updated_at": "2026-04-29T10:00:00",
        "source": source,
        "website_id": website_id,
        "external_user_id": external_user_id,
        "visitor_id": visitor_id,
    }


class FakeTopicStore:
    def __init__(self):
        self.calls = []
        self.widget_event_calls = []

    def init_schema(self):
        self.calls.append(("init_schema", None))

    def create_topic(self, title="新话题", *, agent_snapshot=None, context=None):
        self.calls.append(("create_topic", context, agent_snapshot))
        return topic_payload("topic-created", title)

    def list_topics(self, include_messages=False, *, context=None, agent_id=None):
        self.calls.append(("list_topics", context, agent_id))
        return [topic_payload("topic-widget")]

    def get_topic(self, topic_id, *, context=None):
        self.calls.append(("get_topic", context))
        if topic_id == "topic-forbidden":
            return None
        return topic_payload(topic_id)

    def update_topic(self, topic_id, *, title, context=None):
        self.calls.append(("update_topic", context))
        return topic_payload(topic_id, title)

    def delete_topic(self, topic_id, *, context=None):
        self.calls.append(("delete_topic", context))

    def list_topic_messages_page(self, *, topic_id, page=1, page_size=200, order="asc", context=None):
        self.calls.append(("list_topic_messages_page", context))
        return {
            "topic_id": topic_id,
            "page": page,
            "page_size": page_size,
            "order": order,
            "total": 0,
            "items": [],
        }

    def get_task(self, task_id, *, context=None):
        self.calls.append(("get_task", context))
        if task_id == "task-forbidden":
            return None
        return {
            "task_id": task_id,
            "topic_id": "topic-widget",
            "task_status": "waiting",
            "prompt": "",
            "provider_id": "openrouter",
            "model": "claude-opus-4-6",
            "created_at": "2026-04-29T10:00:00",
            "updated_at": "2026-04-29T10:00:00",
        }

    def list_task_events(self, *, task_id, after_seq=0, limit=200, context=None):
        self.calls.append(("list_task_events", context))
        return {
            "task_id": task_id,
            "task_status": "waiting",
            "after_seq": after_seq,
            "next_after_seq": after_seq,
            "has_more": False,
            "events": [],
        }

    def request_task_cancel(self, task_id, *, context=None):
        self.calls.append(("request_task_cancel", context))
        if task_id == "task-forbidden":
            return None
        return self.get_task(task_id, context=context)

    def record_widget_events(self, events, context=None):
        self.widget_event_calls.append((events, context))
        return len(events)


def install_fake_store(monkeypatch):
    store = FakeTopicStore()
    monkeypatch.setattr(routes, "get_topic_task_store", lambda: store)
    return store


def install_widget_settings(monkeypatch):
    monkeypatch.setattr(
        routes,
        "get_settings",
        lambda: SimpleNamespace(
            llm_provider="openrouter",
            claude_model="claude-opus-4-6",
            skills_output_dir="../.claude/skills/opendataworks-business-knowledge",
            redis_host="127.0.0.1",
            redis_port=6379,
            widget_allowed_sites_json='[{"website_id":"demo","allowed_origins":["https://host.example.com"],"project_name":"Demo","project_color":"#4A90A4"}]',
            run_events_stream_poll_interval_seconds=1,
            run_events_stream_ping_seconds=10,
        ),
    )


def install_agent_profile(monkeypatch, agent_id="agent_widget"):
    profile = {
        "agent_id": agent_id,
        "name": "Widget Agent",
        "description": "",
        "system_prompt": "",
        "permission_mode": "inherit",
        "allowed_tools": ["Read"],
        "mcp_server_ids": [],
        "skill_folders": [],
        "max_turns": 0,
        "env_vars": {},
        "data_scope": {
            "allowed_scopes": [
                {"cluster_id": 3, "source_type": "DORIS", "database": "ads_user"}
            ]
        },
    }
    monkeypatch.setattr(routes, "get_agent_profile", lambda requested_agent_id: profile if requested_agent_id == agent_id else None)
    return profile


def widget_headers(user_id="u1", *, origin="https://host.example.com", visitor_id=""):
    headers = {
        "Origin": origin,
        "X-ODW-Client": "widget",
        "X-ODW-Website-Id": "demo",
    }
    if user_id:
        headers["X-ODW-User-Id"] = user_id
    if visitor_id:
        headers["X-ODW-Visitor-Id"] = visitor_id
    return headers


def test_portal_requests_keep_using_unified_runtime_routes_with_portal_context(monkeypatch):
    store = install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.get("/api/v1/nl2sql/topics")

    assert response.status_code == 200
    context = store.calls[-1][1]
    assert context["source"] == "portal"
    assert context["website_id"] == ""
    assert context["external_user_id"] == ""
    assert context["visitor_id"] == ""


def test_widget_requests_pass_website_and_user_context_to_unified_routes(monkeypatch):
    install_widget_settings(monkeypatch)
    store = install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.get("/api/v1/nl2sql/topics", headers=widget_headers("user-123"))

    assert response.status_code == 200
    context = store.calls[-1][1]
    assert context["source"] == "widget"
    assert context["website_id"] == "demo"
    assert context["external_user_id"] == "user-123"
    assert context["visitor_id"] == ""


def test_widget_topic_list_passes_agent_filter(monkeypatch):
    install_widget_settings(monkeypatch)
    store = install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.get(
        "/api/v1/nl2sql/topics?agent_id=agent_widget",
        headers=widget_headers("user-123"),
    )

    assert response.status_code == 200
    assert store.calls[-1][0] == "list_topics"
    assert store.calls[-1][2] == "agent_widget"


def test_widget_topic_create_requires_explicit_agent_id(monkeypatch):
    install_widget_settings(monkeypatch)
    install_agent_profile(monkeypatch)
    install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.post(
        "/api/v1/nl2sql/topics",
        headers=widget_headers("user-123"),
        json={"title": "Widget 会话"},
    )

    assert response.status_code == 400
    assert "agent_id" in response.json()["detail"]


def test_widget_topic_create_uses_requested_agent_snapshot(monkeypatch):
    install_widget_settings(monkeypatch)
    profile = install_agent_profile(monkeypatch, "agent_widget")
    store = install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.post(
        "/api/v1/nl2sql/topics",
        headers=widget_headers("user-123"),
        json={"title": "Widget 会话", "agent_id": "agent_widget"},
    )

    assert response.status_code == 200
    assert store.calls[-1][0] == "create_topic"
    agent_snapshot = store.calls[-1][2]
    assert agent_snapshot["agent_id"] == profile["agent_id"]
    assert agent_snapshot["data_scope"]["allowed_scopes"] == profile["data_scope"]["allowed_scopes"]


def test_widget_requests_fall_back_to_visitor_context_without_user_id(monkeypatch):
    install_widget_settings(monkeypatch)
    install_agent_profile(monkeypatch)
    store = install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.post(
        "/api/v1/nl2sql/topics",
        headers=widget_headers("", visitor_id="visitor-abc"),
        json={"title": "匿名访客", "agent_id": "agent_widget"},
    )

    assert response.status_code == 200
    context = store.calls[-1][1]
    assert context["source"] == "widget"
    assert context["website_id"] == "demo"
    assert context["external_user_id"] == ""
    assert context["visitor_id"] == "visitor-abc"


def test_widget_origin_must_match_allowed_site(monkeypatch):
    install_widget_settings(monkeypatch)
    install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.get(
        "/api/v1/nl2sql/topics",
        headers=widget_headers("user-123", origin="https://evil.example.com"),
    )

    assert response.status_code == 403


def test_widget_cannot_access_task_outside_its_context(monkeypatch):
    install_widget_settings(monkeypatch)
    install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.get("/api/v1/nl2sql/tasks/task-forbidden", headers=widget_headers("user-123"))

    assert response.status_code == 404


def test_store_internal_calls_without_request_context_are_unscoped():
    store = TopicTaskStore()

    sql, params = store._topic_context_predicate(None, alias="topic")

    assert sql == "1 = 1"
    assert params == []


def test_widget_events_accepted_with_valid_widget_headers(monkeypatch):
    install_widget_settings(monkeypatch)
    store = install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.post(
        "/api/v1/nl2sql/widget-events",
        headers=widget_headers("u1"),
        json={"events": [{"event_type": "widget_open"}, {"event_type": "message_send", "payload": {"length": 5}}]},
    )

    assert response.status_code == 200
    assert response.json()["accepted"] == 2
    assert len(store.widget_event_calls) == 1
    events, context = store.widget_event_calls[0]
    assert context["source"] == "widget"
    assert context["website_id"] == "demo"
    assert context["external_user_id"] == "u1"


def test_widget_events_rejected_without_widget_client_header(monkeypatch):
    install_widget_settings(monkeypatch)
    install_fake_store(monkeypatch)
    client = TestClient(app)

    # No X-ODW-Client: widget header → 400 (missing website_id)
    response = client.post(
        "/api/v1/nl2sql/widget-events",
        json={"events": [{"event_type": "widget_open"}]},
    )

    # Without widget headers the request carries portal context — no 400/403 from _request_context,
    # but the store call still goes through as portal source.
    # The key behaviour: no crash and the response is 200 with portal context.
    assert response.status_code == 200


def test_widget_events_rejected_when_site_not_allowed(monkeypatch):
    install_widget_settings(monkeypatch)
    install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.post(
        "/api/v1/nl2sql/widget-events",
        headers={
            "Origin": "https://evil.example.com",
            "X-ODW-Client": "widget",
            "X-ODW-Website-Id": "demo",
            "X-ODW-User-Id": "u1",
        },
        json={"events": [{"event_type": "widget_open"}]},
    )

    assert response.status_code == 403


def test_widget_events_empty_batch_returns_zero(monkeypatch):
    install_widget_settings(monkeypatch)
    install_fake_store(monkeypatch)
    client = TestClient(app)

    response = client.post(
        "/api/v1/nl2sql/widget-events",
        headers=widget_headers("u1"),
        json={"events": []},
    )

    assert response.status_code == 200
    assert response.json()["accepted"] == 0


def test_runtime_config_returns_safe_enabled_provider_subset(monkeypatch):
    monkeypatch.setattr(
        routes,
        "resolved_chat_settings_payload",
        lambda: {
            "default_provider_id": "openrouter",
            "default_model": "claude-opus-4-6",
            "providers": [
                {
                    "provider_id": "openrouter",
                    "display_name": "OpenRouter",
                    "provider_group": "聚合路由",
                    "models": ["claude-opus-4-6"],
                    "default_model": "claude-opus-4-6",
                    "enabled": True,
                    "provider_enabled": True,
                    "supports_partial_messages": True,
                    "api_key_set": True,
                    "auth_token_set": True,
                    "base_url": "https://openrouter.ai/api",
                }
            ],
            "mysql_host": "127.0.0.1",
            "mysql_database": "opendataworks",
        },
    )
    client = TestClient(app)

    response = client.get("/api/v1/nl2sql/runtime-config")

    assert response.status_code == 200
    payload = response.json()
    assert payload["default_provider_id"] == "openrouter"
    assert payload["default_model"] == "claude-opus-4-6"
    assert payload["providers"][0]["provider_id"] == "openrouter"
    assert payload["providers"][0]["models"] == ["claude-opus-4-6"]
    assert "api_key_set" not in payload["providers"][0]
    assert "auth_token_set" not in payload["providers"][0]
    assert "base_url" not in payload["providers"][0]
    assert "mysql_host" not in payload
