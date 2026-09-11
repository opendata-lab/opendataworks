from __future__ import annotations

import sys
import types
from pathlib import Path

from fastapi.testclient import TestClient

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

import api.admin_routes as admin_routes
from main import app


def test_admin_settings_contract(monkeypatch):
    captured = {}

    def _persist(payload):
        captured["payload"] = payload
        return {"updated_at": "2026-03-06T12:00:00"}

    monkeypatch.setattr(
        admin_routes,
        "current_settings_payload",
        lambda: {
            "provider_id": "openrouter",
            "model": "anthropic/claude-sonnet-4.5",
            "anthropic_api_key": "k",
            "anthropic_auth_token": "t",
            "anthropic_base_url": "https://example.com",
            "mysql_host": "127.0.0.1",
            "mysql_port": 3306,
            "mysql_user": "root",
            "mysql_password": "pwd",
            "mysql_database": "opendataworks",
            "doris_host": "127.0.0.1",
            "doris_port": 9030,
            "doris_user": "root",
            "doris_password": "pwd",
            "doris_database": "ods",
            "skills_output_dir": "../.claude/skills/opendataworks-business-knowledge",
            "session_mysql_database": "dataagent",
        },
    )
    monkeypatch.setattr(admin_routes, "resolve_skills_root_dir", lambda: "/tmp/.claude/skills/opendataworks-business-knowledge")
    monkeypatch.setattr(
        admin_routes,
        "_provider_catalog",
        lambda: [
            admin_routes.ProviderConfig(
                provider_id="openrouter",
                display_name="OpenRouter",
                provider_group="聚合路由",
                models=["anthropic/claude-sonnet-4.5"],
                supported_models=["anthropic/claude-sonnet-4.5"],
                default_model="anthropic/claude-sonnet-4.5",
                enabled=True,
                provider_enabled=True,
                supports_partial_messages=False,
                validation_status="verified",
                model_detections={
                    "anthropic/claude-sonnet-4.5": {
                        "status": "verified",
                        "message": "模型检测通过",
                        "checked_at": "2026-04-17T10:00:00",
                    }
                },
            )
        ],
    )
    monkeypatch.setattr(
        admin_routes,
        "persist_admin_settings",
        _persist,
    )

    client = TestClient(app)

    response = client.get("/api/v1/nl2sql-admin/settings")
    assert response.status_code == 200
    assert response.json()["provider_id"] == "openrouter"
    assert response.json()["providers"][0]["supports_partial_messages"] is False
    assert response.json()["providers"][0]["model_detections"]["anthropic/claude-sonnet-4.5"]["status"] == "verified"

    update = client.put(
        "/api/v1/nl2sql-admin/settings",
        json={
            "provider_id": "openrouter",
            "model": "anthropic/claude-sonnet-4.5",
            "providers": [
                {
                    "provider_id": "openrouter",
                    "supports_partial_messages": False,
                    "enabled_models": ["anthropic/claude-sonnet-4.5"],
                }
            ],
        },
    )
    assert update.status_code == 200
    assert update.json()["updated_at"] == "2026-03-06T12:00:00"
    assert captured["payload"]["providers"][0]["supports_partial_messages"] is False
    assert client.get("/api/v1/dataagent/settings").status_code == 404


def test_model_detection_route_contract(monkeypatch):
    captured = {}

    async def _detect(payload):
        captured["payload"] = payload
        return {
            "provider_id": payload["provider_id"],
            "model": payload["model"],
            "status": "verified",
            "message": "模型检测通过",
            "checked_at": "2026-04-17T10:00:00",
        }

    monkeypatch.setattr(admin_routes, "detect_model_availability", _detect)

    client = TestClient(app)
    response = client.post(
        "/api/v1/nl2sql-admin/model-detections",
        json={
            "provider_id": "openrouter",
            "model": "anthropic/claude-sonnet-4.5",
            "auth_token": "token",
            "base_url": "https://openrouter.ai/api",
            "supports_partial_messages": False,
        },
    )

    assert response.status_code == 200
    assert response.json()["status"] == "verified"
    assert captured["payload"]["supports_partial_messages"] is False


def test_mcp_registry_routes_contract(monkeypatch):
    captured = {}
    row = {
        "server_id": "srv_demo",
        "name": "demo",
        "source": "configured",
        "transport": "http",
        "url": "https://mcp.example.test/v1",
        "headers": {},
        "command": "",
        "args": [],
        "env": {},
        "enabled": True,
        "oauth_required": False,
        "tool_count": 0,
        "description": "",
    }
    def _create_mcp(payload):
        captured["created"] = payload
        return "srv_demo"

    monkeypatch.setattr(admin_routes, "list_mcp_servers", lambda: {"configured": [row], "plugin": []})
    monkeypatch.setattr(admin_routes, "create_mcp_server", _create_mcp)
    monkeypatch.setattr(
        admin_routes,
        "update_mcp_server",
        lambda server_id, payload: captured.setdefault("updated", (server_id, payload)),
    )
    monkeypatch.setattr(
        admin_routes,
        "delete_mcp_server",
        lambda server_id: captured.setdefault("deleted", server_id),
    )
    monkeypatch.setattr(
        admin_routes,
        "import_mcp_servers",
        lambda payload: captured.setdefault("imported_payload", payload) and 1,
    )
    client = TestClient(app)

    assert client.get("/api/v1/dataagent/mcp/servers").json()["configured"][0]["server_id"] == "srv_demo"
    created = client.post(
        "/api/v1/dataagent/mcp/servers",
        json={"name": "demo", "transport": "http", "url": "https://mcp.example.test/v1"},
    )
    assert created.status_code == 200
    assert created.json() == {"server_id": "srv_demo"}
    assert client.patch("/api/v1/dataagent/mcp/servers/srv_demo", json={"enabled": False}).json() == {"ok": True}
    assert client.delete("/api/v1/dataagent/mcp/servers/srv_demo").json() == {"ok": True}
    imported = client.post(
        "/api/v1/dataagent/mcp/servers/import",
        json={"mcpServers": {"demo": {"command": "npx"}}},
    )
    assert imported.json() == {"imported": 1}
    assert captured["updated"] == ("srv_demo", {"enabled": False})
    assert captured["deleted"] == "srv_demo"


def test_skill_document_routes_contract(monkeypatch):
    summary = {
        "id": 1,
        "folder": "opendataworks-platform-tools",
        "relative_path": "reference/40-runtime-metadata.md",
        "file_name": "40-runtime-metadata.md",
        "category": "reference",
        "content_type": "markdown",
        "source": "bundled",
        "current_hash": "hash",
        "current_version_id": 3,
        "version_count": 3,
        "last_change_source": "sync",
        "last_change_summary": "manual sync",
        "created_at": "2026-03-06T10:00:00",
        "updated_at": "2026-03-06T12:00:00",
        "editable": True,
        "enabled": True,
    }
    detail = {
        **summary,
        "current_content": "{\"schema_version\":\"1.0\"}",
        "versions": [
            {
                "id": 3,
                "document_id": 1,
                "version_no": 3,
                "change_source": "sync",
                "change_summary": "manual sync",
                "actor": "ui",
                "content_hash": "hash",
                "file_size": 20,
                "metadata": None,
                "parent_version_id": None,
                "created_at": "2026-03-06T12:00:00",
                "is_current": True,
            }
        ],
    }

    monkeypatch.setattr(admin_routes, "list_documents", lambda: [summary])
    monkeypatch.setattr(admin_routes, "get_document_detail", lambda document_id: detail if document_id == 1 else None)
    monkeypatch.setattr(admin_routes, "save_document_content", lambda document_id, content, change_summary=None: detail)
    monkeypatch.setattr(
        admin_routes,
        "compare_document_versions",
        lambda document_id, left_version_id=None, right_version_id=None: {
            "document_id": document_id,
            "left_label": "V2",
            "right_label": "当前版本",
            "left_content": "{}",
            "right_content": "{\"schema_version\":\"1.0\"}",
            "diff_text": "--- V2\n+++ 当前版本",
            "added_lines": 1,
            "removed_lines": 1,
            "changed_lines": 2,
        },
    )
    monkeypatch.setattr(admin_routes, "rollback_document", lambda document_id, version_id: detail)
    monkeypatch.setattr(
        admin_routes,
        "update_skill_runtime",
        lambda folder, enabled: {
            "skill_id": folder,
            "enabled": enabled,
        },
    )
    monkeypatch.setattr(
        admin_routes,
        "import_skill_from_zip",
        lambda file_name, content: {
            "skill_id": "marketing-insights",
            "source": "managed",
            "enabled": False,
            "imported_documents": [
                {
                    **summary,
                    "id": 2,
                    "folder": "marketing-insights",
                    "relative_path": "SKILL.md",
                    "file_name": "SKILL.md",
                    "source": "managed",
                    "enabled": False,
                }
            ],
            "document_count": 2,
        },
    )
    monkeypatch.setattr(
        admin_routes,
        "uninstall_skill",
        lambda folder: {
            "skill_id": folder,
            "removed_documents": [
                {
                    **summary,
                    "id": 2,
                    "folder": folder,
                    "relative_path": "SKILL.md",
                    "file_name": "SKILL.md",
                    "source": "managed",
                    "enabled": False,
                }
            ],
            "was_enabled": False,
            "document_count": 1,
        },
    )
    client = TestClient(app)

    list_response = client.get("/api/v1/dataagent/skills/documents")
    assert list_response.status_code == 200
    assert list_response.json()[0]["folder"] == "opendataworks-platform-tools"
    assert list_response.json()[0]["relative_path"] == "reference/40-runtime-metadata.md"
    assert list_response.json()[0]["source"] == "bundled"
    assert list_response.json()[0]["enabled"] is True
    assert list_response.json()[0]["editable"] is True

    detail_response = client.get("/api/v1/dataagent/skills/documents/1")
    assert detail_response.status_code == 200
    assert detail_response.json()["versions"][0]["version_no"] == 3

    save_response = client.put(
        "/api/v1/dataagent/skills/documents/1",
        json={"content": "{\"schema_version\":\"1.0\"}", "change_summary": "save"},
    )
    assert save_response.status_code == 200
    assert save_response.json()["id"] == 1

    compare_response = client.post(
        "/api/v1/dataagent/skills/documents/1/compare",
        json={"left_version_id": 2},
    )
    assert compare_response.status_code == 200
    assert compare_response.json()["changed_lines"] == 2

    rollback_response = client.post("/api/v1/dataagent/skills/documents/1/versions/2/rollback")
    assert rollback_response.status_code == 200
    assert rollback_response.json()["id"] == 1

    runtime_response = client.put("/api/v1/dataagent/skills/runtime/opendataworks-business-knowledge", json={"enabled": True})
    assert runtime_response.status_code == 200
    assert runtime_response.json()["skill_id"] == "opendataworks-business-knowledge"
    assert runtime_response.json()["enabled"] is True

    runtime_disable_response = client.put("/api/v1/dataagent/skills/runtime/opendataworks-business-knowledge", json={"enabled": False})
    assert runtime_disable_response.status_code == 200
    assert runtime_disable_response.json()["enabled"] is False

    import_response = client.post(
        "/api/v1/dataagent/skills/imports",
        files={"file": ("marketing-insights.zip", b"zip-content", "application/zip")},
    )
    assert import_response.status_code == 200
    assert import_response.json()["skill_id"] == "marketing-insights"
    assert import_response.json()["source"] == "managed"
    assert import_response.json()["enabled"] is False
    assert import_response.json()["imported_documents"][0]["source"] == "managed"

    uninstall_response = client.delete("/api/v1/dataagent/skills/marketing-insights")
    assert uninstall_response.status_code == 200
    assert uninstall_response.json()["skill_id"] == "marketing-insights"
    assert uninstall_response.json()["removed_documents"][0]["folder"] == "marketing-insights"

    sync_response = client.post("/api/v1/dataagent/skills/sync")
    assert sync_response.status_code == 405


def test_skill_uninstall_route_rejects_service_errors(monkeypatch):
    def _reject_uninstall(folder):
        raise ValueError("内置 Skill 不支持卸载")

    monkeypatch.setattr(admin_routes, "uninstall_skill", _reject_uninstall)

    client = TestClient(app)
    response = client.delete("/api/v1/dataagent/skills/opendataworks-business-knowledge")

    assert response.status_code == 400
    assert response.json()["detail"] == "内置 Skill 不支持卸载"


def test_agent_profile_routes_contract(monkeypatch):
    profile = {
        "agent_id": "agent_1",
        "name": "营销分析智能体",
        "description": "营销场景",
        "system_prompt": "只回答营销问题",
        "permission_mode": "inherit",
        "allowed_tools": ["Skill", "Read"],
        "mcp_server_ids": ["portal"],
        "skill_folders": ["marketing-insights"],
        "max_turns": 12,
        "env_vars": {"SAFE_FLAG": "1"},
        "is_default": False,
        "is_builtin": False,
        "created_at": "2026-05-21T10:00:00",
        "updated_at": "2026-05-21T10:00:00",
    }
    calls = {}

    monkeypatch.setattr(
        admin_routes,
        "list_documents",
        lambda: [
            {
                "folder": "marketing-insights",
                "relative_path": "SKILL.md",
                "source": "managed",
                "enabled": True,
            }
        ],
    )
    monkeypatch.setattr(admin_routes, "list_agent_profiles", lambda: [profile])
    monkeypatch.setattr(admin_routes, "get_agent_profile", lambda agent_id: profile if agent_id == "agent_1" else None)
    monkeypatch.setattr(admin_routes, "agent_capabilities", lambda documents: {
        "tools": ["Skill", "Read"],
        "mcp_servers": [{"id": "portal", "name": "Portal MCP", "enabled": True, "tool_names": ["portal_query_readonly"]}],
        "skills": [{"folder": "marketing-insights", "source": "managed", "enabled": True}],
        "permission_modes": ["inherit", "default", "bypassPermissions"],
    })

    def _create(payload, *, available_skill_folders):
        calls["create"] = {"payload": payload, "available_skill_folders": available_skill_folders}
        return profile

    def _update(agent_id, payload, *, available_skill_folders):
        calls["update"] = {"agent_id": agent_id, "payload": payload, "available_skill_folders": available_skill_folders}
        return {**profile, **payload}

    monkeypatch.setattr(admin_routes, "create_agent_profile", _create)
    monkeypatch.setattr(admin_routes, "update_agent_profile", _update)
    monkeypatch.setattr(admin_routes, "delete_agent_profile", lambda agent_id: agent_id == "agent_1")

    client = TestClient(app)

    capabilities = client.get("/api/v1/dataagent/agents/capabilities")
    assert capabilities.status_code == 200
    assert capabilities.json()["skills"][0]["folder"] == "marketing-insights"
    assert capabilities.json()["mcp_servers"][0]["id"] == "portal"

    listed = client.get("/api/v1/dataagent/agents")
    assert listed.status_code == 200
    assert listed.json()[0]["agent_id"] == "agent_1"
    assert listed.json()[0]["is_builtin"] is False

    created = client.post(
        "/api/v1/dataagent/agents",
        json={
            "name": "营销分析智能体",
            "allowed_tools": ["Skill", "Read"],
            "mcp_server_ids": ["portal"],
            "skill_folders": ["marketing-insights"],
            "env_vars": {"SAFE_FLAG": "1"},
        },
    )
    assert created.status_code == 200
    assert calls["create"]["payload"]["name"] == "营销分析智能体"
    assert calls["create"]["available_skill_folders"] == {"marketing-insights"}

    detail = client.get("/api/v1/dataagent/agents/agent_1")
    assert detail.status_code == 200

    updated = client.put("/api/v1/dataagent/agents/agent_1", json={"description": "更新后"})
    assert updated.status_code == 200
    assert calls["update"]["agent_id"] == "agent_1"
    assert updated.json()["description"] == "更新后"

    deleted = client.delete("/api/v1/dataagent/agents/agent_1")
    assert deleted.status_code == 200
    assert deleted.json()["status"] == "ok"


def test_agent_slash_commands_route_contract(monkeypatch):
    from core import slash_command_cache

    slash_command_cache._AGENT_SLASH_COMMANDS.clear()
    profile = {"agent_id": "agent_1", "name": "x", "skill_folders": ["marketing-insights", "platform-tools"]}
    monkeypatch.setattr(admin_routes, "get_agent_profile", lambda agent_id: profile if agent_id == "agent_1" else None)

    client = TestClient(app)

    # Cold start: no run reported yet, fall back to the agent's enabled skill folders.
    fallback = client.get("/api/v1/dataagent/agents/agent_1/slash-commands")
    assert fallback.status_code == 200
    assert fallback.json() == {"slash_commands": ["marketing-insights", "platform-tools"], "source": "fallback"}

    # Once a run reports the authoritative list, it wins.
    slash_command_cache.record_agent_slash_commands("agent_1", ["clear", "compact", "marketing-insights"])
    authoritative = client.get("/api/v1/dataagent/agents/agent_1/slash-commands")
    assert authoritative.json() == {"slash_commands": ["clear", "compact", "marketing-insights"], "source": "sdk"}

    missing = client.get("/api/v1/dataagent/agents/unknown/slash-commands")
    assert missing.status_code == 404


class _FakeWidgetStore:
    """Records admin store calls so the route contract can be asserted without MySQL."""

    def __init__(self):
        self.list_calls = []
        self.message_calls = []

    def admin_list_topics(self, **kwargs):
        self.list_calls.append(kwargs)
        return {
            "items": [
                {
                    "topic_id": "topic_widget_1",
                    "title": "嵌入站会话",
                    "chat_topic_id": "chat_1",
                    "chat_conversation_id": "conv_1",
                    "agent_id": "agent_default",
                    "current_task_id": None,
                    "current_task_status": None,
                    "message_count": 4,
                    "last_message_preview": "最近一条",
                    "source": "widget",
                    "website_id": "site_a",
                    "external_user_id": "",
                    "visitor_id": "visitor_x",
                    "created_at": "2026-06-01T10:00:00",
                    "updated_at": "2026-06-01T12:00:00",
                }
            ],
            "total": 1,
            "page": kwargs.get("page", 1),
            "page_size": kwargs.get("page_size", 20),
        }

    def get_topic(self, topic_id, context=None):
        return {"topic_id": topic_id} if topic_id == "topic_widget_1" else None

    def list_topic_messages_page(self, *, topic_id, page, page_size, order, context=None):
        self.message_calls.append({"topic_id": topic_id, "context": context, "order": order})
        return {
            "topic_id": topic_id,
            "page": page,
            "page_size": page_size,
            "order": order,
            "total": 1,
            "items": [],
        }


def test_admin_widget_topics_routes_contract(monkeypatch):
    store = _FakeWidgetStore()
    monkeypatch.setattr(admin_routes, "get_topic_task_store", lambda: store)

    client = TestClient(app)

    listing = client.get(
        "/api/v1/nl2sql-admin/widget-topics",
        params={"website_id": "site_a", "keyword": "嵌入", "page": 1, "page_size": 20},
    )
    assert listing.status_code == 200
    body = listing.json()
    assert body["total"] == 1
    assert body["items"][0]["source"] == "widget"
    assert body["items"][0]["website_id"] == "site_a"
    assert body["items"][0]["visitor_id"] == "visitor_x"
    # Admin listing must always scope to widget source and forward filters.
    assert store.list_calls[0]["source"] == "widget"
    assert store.list_calls[0]["website_id"] == "site_a"
    assert store.list_calls[0]["keyword"] == "嵌入"

    messages = client.get("/api/v1/nl2sql-admin/widget-topics/topic_widget_1/messages")
    assert messages.status_code == 200
    assert messages.json()["topic_id"] == "topic_widget_1"
    # Admin message read bypasses owner isolation via context=None.
    assert store.message_calls[0]["context"] is None

    missing = client.get("/api/v1/nl2sql-admin/widget-topics/unknown/messages")
    assert missing.status_code == 404


# ---------------------------------------------------------------------------
# 认证启用后的管理端点门禁（auth 关闭时以上所有既有用例即回归：全部开放）。
# ---------------------------------------------------------------------------

VALID_SECRET = "unit-test-secret-key-0123456789abcdef-0123456789"


def _enable_auth(monkeypatch, tmp_path):
    import bcrypt
    import core.auth as auth

    password_hash = bcrypt.hashpw(b"admin-pass", bcrypt.gensalt(rounds=4)).decode()
    config_file = tmp_path / "auth_config.py"
    config_file.write_text(
        f"""
AUTH_ENABLED = True
SECRET_KEY = {VALID_SECRET!r}
LOCAL_ADMINS = [{{"username": "admin", "password_bcrypt": {password_hash!r}}}]
""",
        encoding="utf-8",
    )
    monkeypatch.setenv(auth.AUTH_CONFIG_ENV, str(config_file))
    auth.reset_auth_for_tests()
    auth.init_auth()
    return auth


def _bearer(auth, *, role):
    identity = auth.AuthIdentity(user_id=f"local:{role}", display_name=role, role=role, provider="local")
    return {"Authorization": f"Bearer {auth.issue_session_token(identity)}"}


def test_admin_routes_require_admin_when_auth_enabled(monkeypatch, tmp_path):
    auth = _enable_auth(monkeypatch, tmp_path)
    try:
        store = _FakeWidgetStore()
        monkeypatch.setattr(admin_routes, "get_topic_task_store", lambda: store)
        monkeypatch.setattr(admin_routes, "list_documents", lambda: [])
        client = TestClient(app)

        # 未登录 → 401
        assert client.get("/api/v1/nl2sql-admin/settings").status_code == 401
        assert client.get("/api/v1/nl2sql-admin/widget-topics").status_code == 401
        assert client.get("/api/v1/nl2sql-admin/topics").status_code == 401
        assert client.get("/api/v1/dataagent/skills/documents").status_code == 401
        assert client.post("/api/v1/dataagent/agents", json={"name": "x"}).status_code == 401

        # 普通用户 → 403
        user_headers = _bearer(auth, role="user")
        assert client.get("/api/v1/nl2sql-admin/topics", headers=user_headers).status_code == 403
        assert client.get("/api/v1/dataagent/skills/documents", headers=user_headers).status_code == 200
        assert client.put(
            "/api/v1/dataagent/skills/runtime/demo",
            json={"enabled": True},
            headers=user_headers,
        ).status_code == 403

        # admin → 放行
        admin_headers = _bearer(auth, role="admin")
        listing = client.get("/api/v1/nl2sql-admin/topics", headers=admin_headers)
        assert listing.status_code == 200
        assert store.list_calls[0]["source"] == ""
    finally:
        auth.reset_auth_for_tests()


def test_agent_responses_are_layered_by_authentication(monkeypatch, tmp_path):
    auth = _enable_auth(monkeypatch, tmp_path)
    try:
        profile = {
            "agent_id": "agent_1",
            "name": "x",
            "description": "readable",
            "system_prompt": "internal instructions",
            "allowed_tools": ["Read"],
            "mcp_server_ids": ["portal"],
            "skill_folders": ["demo"],
            "data_scope": {"allowed_scopes": [{"database": "ads"}]},
            "env_vars": {"SECRET_TOKEN": "hidden"},
        }
        monkeypatch.setattr(admin_routes, "list_agent_profiles", lambda: [profile])
        monkeypatch.setattr(admin_routes, "get_agent_profile", lambda agent_id: profile if agent_id == "agent_1" else None)
        client = TestClient(app)

        # Widget / 匿名嵌入依赖的三个端点保持公开，但目录 DTO 不泄露配置。
        public_list = client.get("/api/v1/dataagent/agents")
        assert public_list.status_code == 200
        assert set(public_list.json()[0]) == {
            "agent_id", "name", "description", "is_default", "is_builtin", "preset_questions"
        }
        public_detail = client.get("/api/v1/dataagent/agents/agent_1")
        assert public_detail.status_code == 200
        assert "system_prompt" not in public_detail.json()
        assert "env_vars" not in public_detail.json()
        assert client.get("/api/v1/dataagent/agents/agent_1/slash-commands").status_code == 200

        # 普通用户读取完整只读配置，但 env_vars 始终排除。
        user_headers = _bearer(auth, role="user")
        readable = client.get("/api/v1/dataagent/agents/agent_1/profile", headers=user_headers)
        assert readable.status_code == 200
        assert readable.json()["system_prompt"] == "internal instructions"
        assert readable.json()["mcp_server_ids"] == ["portal"]
        assert readable.json()["data_scope"]["allowed_scopes"][0]["database"] == "ads"
        assert "env_vars" not in readable.json()

        # 管理员配置端点保留完整 Profile。
        configuration = client.get(
            "/api/v1/dataagent/agents/agent_1/configuration",
            headers=_bearer(auth, role="admin"),
        )
        assert configuration.status_code == 200
        assert configuration.json()["env_vars"] == {"SECRET_TOKEN": "hidden"}
    finally:
        auth.reset_auth_for_tests()


def test_agents_capabilities_not_shadowed_by_public_dynamic_route(monkeypatch, tmp_path):
    """路由遮蔽回归：/agents/capabilities（admin 静态路径）必须先于公开的
    /agents/{agent_id} 匹配，否则 capabilities 会被当作 agent_id 返回 404/泄漏。"""
    auth = _enable_auth(monkeypatch, tmp_path)
    try:
        monkeypatch.setattr(admin_routes, "list_documents", lambda: [])
        monkeypatch.setattr(
            admin_routes,
            "agent_capabilities",
            lambda documents: {"tools": [], "mcp_servers": [], "skills": [], "permission_modes": []},
        )
        # 公开动态路由若吞掉 capabilities，会走 get_agent_profile 而不是 401/200。
        monkeypatch.setattr(admin_routes, "get_agent_profile", lambda agent_id: None)
        client = TestClient(app)

        # 未登录：capabilities 属于 admin router → 401（而不是被公开动态路由当作 agent_id 返回 404）。
        assert client.get("/api/v1/dataagent/agents/capabilities").status_code == 401
        # admin：正常返回 capabilities 契约。
        response = client.get("/api/v1/dataagent/agents/capabilities", headers=_bearer(auth, role="admin"))
        assert response.status_code == 200
        assert "tools" in response.json()
    finally:
        auth.reset_auth_for_tests()


def test_admin_all_topics_forwards_filters(monkeypatch, tmp_path):
    auth = _enable_auth(monkeypatch, tmp_path)
    try:
        store = _FakeWidgetStore()
        monkeypatch.setattr(admin_routes, "get_topic_task_store", lambda: store)
        client = TestClient(app)

        response = client.get(
            "/api/v1/nl2sql-admin/topics",
            params={"source": "portal", "auth_user_id": "SSO:42", "keyword": "趋势"},
            headers=_bearer(auth, role="admin"),
        )
        assert response.status_code == 200
        assert store.list_calls[0]["source"] == "portal"
        assert store.list_calls[0]["auth_user_id"] == "SSO:42"
        assert store.list_calls[0]["keyword"] == "趋势"

        bad_source = client.get(
            "/api/v1/nl2sql-admin/topics",
            params={"source": "evil"},
            headers=_bearer(auth, role="admin"),
        )
        assert bad_source.status_code == 422
    finally:
        auth.reset_auth_for_tests()


def test_agent_visibility_scope_enforced_across_read_tiers(monkeypatch, tmp_path):
    """可见范围矩阵：匿名只见 all 档；登录用户按 authenticated/selected 过滤；
    admin 全量；不可见详情与 slash-commands 返回与不存在一致的 404。"""
    auth = _enable_auth(monkeypatch, tmp_path)
    try:
        profiles = [
            {"agent_id": "agent_open", "name": "开放", "skill_folders": ["demo"]},
            {
                "agent_id": "agent_auth",
                "name": "仅登录",
                "visibility": {"mode": "authenticated", "allowed_users": [], "allowed_groups": []},
            },
            {
                "agent_id": "agent_sel",
                "name": "指定用户",
                "visibility": {"mode": "selected", "allowed_users": ["local:user"], "allowed_groups": []},
            },
            {
                "agent_id": "agent_sel_other",
                "name": "指定他人",
                "visibility": {"mode": "selected", "allowed_users": ["SSO:someone-else"], "allowed_groups": []},
            },
        ]
        by_id = {item["agent_id"]: item for item in profiles}
        monkeypatch.setattr(admin_routes, "list_agent_profiles", lambda: list(profiles))
        monkeypatch.setattr(admin_routes, "get_agent_profile", lambda agent_id: by_id.get(agent_id))
        client = TestClient(app)

        spa = {"X-ODW-Client": "dataagent"}
        user_headers = {**_bearer(auth, role="user"), **spa}
        admin_headers = {**_bearer(auth, role="admin"), **spa}

        # 匿名（widget/门户嵌入，无 SPA 标记）：仅 mode=all 可见。
        anonymous = client.get("/api/v1/dataagent/agents")
        assert anonymous.status_code == 200
        assert [item["agent_id"] for item in anonymous.json()] == ["agent_open"]
        assert client.get("/api/v1/dataagent/agents/agent_auth").status_code == 404
        assert client.get("/api/v1/dataagent/agents/agent_sel/slash-commands").status_code == 404

        # 携带会话但无 dataagent SPA 标记：公开端点遵循三分支语义，保持匿名。
        marked_less = client.get("/api/v1/dataagent/agents", headers=_bearer(auth, role="user"))
        assert [item["agent_id"] for item in marked_less.json()] == ["agent_open"]

        # 登录普通用户（SPA 标记 + 会话）：all + authenticated + selected 命中。
        listed = client.get("/api/v1/dataagent/agents", headers=user_headers)
        assert [item["agent_id"] for item in listed.json()] == ["agent_open", "agent_auth", "agent_sel"]
        assert client.get("/api/v1/dataagent/agents/agent_sel", headers=user_headers).status_code == 200
        assert client.get("/api/v1/dataagent/agents/agent_sel_other", headers=user_headers).status_code == 404
        assert (
            client.get("/api/v1/dataagent/agents/agent_sel_other/slash-commands", headers=user_headers).status_code
            == 404
        )

        # 登录用户读侧只暴露 visibility_mode 摘要，不泄露 allowed_users 名单。
        readable_list = client.get("/api/v1/dataagent/agents/profiles", headers=_bearer(auth, role="user"))
        assert readable_list.status_code == 200
        readable_ids = {item["agent_id"]: item for item in readable_list.json()}
        assert set(readable_ids) == {"agent_open", "agent_auth", "agent_sel"}
        assert readable_ids["agent_sel"]["visibility_mode"] == "selected"
        assert "visibility" not in readable_ids["agent_sel"]
        assert (
            client.get("/api/v1/dataagent/agents/agent_sel_other/profile", headers=_bearer(auth, role="user")).status_code
            == 404
        )

        # 目录 DTO 不因可见性新增泄露字段。
        assert set(listed.json()[0]) == {
            "agent_id", "name", "description", "is_default", "is_builtin", "preset_questions"
        }

        # admin：列表全量，configuration 返回完整 visibility 配置。
        admin_listed = client.get("/api/v1/dataagent/agents", headers=admin_headers)
        assert len(admin_listed.json()) == 4
        configuration = client.get(
            "/api/v1/dataagent/agents/agent_sel_other/configuration", headers=admin_headers
        )
        assert configuration.status_code == 200
        assert configuration.json()["visibility"]["allowed_users"] == ["SSO:someone-else"]
    finally:
        auth.reset_auth_for_tests()


def test_admin_auth_users_route_contract(monkeypatch, tmp_path):
    auth = _enable_auth(monkeypatch, tmp_path)
    try:
        calls = []

        class _FakeAuthUserStore:
            def admin_list_auth_users(self, *, keyword=None, limit=100):
                calls.append({"keyword": keyword, "limit": limit})
                return [
                    {
                        "user_id": "SSO:42",
                        "display_name": "alice",
                        "topic_count": 3,
                        "last_active_at": "2026-07-19T10:00:00",
                    }
                ]

        monkeypatch.setattr(admin_routes, "get_topic_task_store", lambda: _FakeAuthUserStore())
        client = TestClient(app)

        assert client.get("/api/v1/nl2sql-admin/auth-users").status_code == 401
        assert (
            client.get("/api/v1/nl2sql-admin/auth-users", headers=_bearer(auth, role="user")).status_code == 403
        )

        response = client.get(
            "/api/v1/nl2sql-admin/auth-users",
            params={"keyword": "ali", "limit": 20},
            headers=_bearer(auth, role="admin"),
        )
        assert response.status_code == 200
        assert response.json()["items"] == [
            {
                "user_id": "SSO:42",
                "display_name": "alice",
                "topic_count": 3,
                "last_active_at": "2026-07-19T10:00:00",
            }
        ]
        assert calls == [{"keyword": "ali", "limit": 20}]
    finally:
        auth.reset_auth_for_tests()
