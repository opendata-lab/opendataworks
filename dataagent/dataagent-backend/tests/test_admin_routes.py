from __future__ import annotations

import sys
import types
from pathlib import Path

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
            "skills_output_dir": "../.claude/skills/dataagent-nl2sql",
            "session_mysql_database": "dataagent",
        },
    )
    monkeypatch.setattr(admin_routes, "resolve_skills_root_dir", lambda: "/tmp/.claude/skills/dataagent-nl2sql")
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


def test_skill_document_routes_contract(monkeypatch):
    summary = {
        "id": 1,
        "folder": "dataagent-nl2sql",
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
    assert list_response.json()[0]["folder"] == "dataagent-nl2sql"
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

    runtime_response = client.put("/api/v1/dataagent/skills/runtime/dataagent-nl2sql", json={"enabled": True})
    assert runtime_response.status_code == 200
    assert runtime_response.json()["skill_id"] == "dataagent-nl2sql"
    assert runtime_response.json()["enabled"] is True

    runtime_disable_response = client.put("/api/v1/dataagent/skills/runtime/dataagent-nl2sql", json={"enabled": False})
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
    response = client.delete("/api/v1/dataagent/skills/dataagent-nl2sql")

    assert response.status_code == 400
    assert response.json()["detail"] == "内置 Skill 不支持卸载"
