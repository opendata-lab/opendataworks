from __future__ import annotations

import sys
import json
import runpy
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core import mcp_admin_service


class FakeRegistry:
    def __init__(self, rows=None):
        self.rows = {row["server_id"]: dict(row) for row in (rows or [])}
        self.save_calls = 0

    def list_mcp_servers(self):
        return [dict(row) for row in self.rows.values()]

    def get_mcp_server(self, server_id):
        row = self.rows.get(server_id)
        return dict(row) if row else None

    def find_mcp_server_by_name(self, name):
        return next((dict(row) for row in self.rows.values() if row["name"] == name), None)

    def save_mcp_server(self, payload, *, insert_only=False):
        self.save_calls += 1
        if insert_only and payload["server_id"] in self.rows:
            return dict(self.rows[payload["server_id"]])
        self.rows[payload["server_id"]] = dict(payload)
        return dict(payload)

    def delete_mcp_server(self, server_id):
        return self.rows.pop(server_id, None) is not None


def test_configured_mcp_server_crud_round_trip(monkeypatch):
    registry = FakeRegistry()
    monkeypatch.setattr(mcp_admin_service, "get_runtime_registry_store", lambda: registry)

    server_id = mcp_admin_service.create_mcp_server(
        {
            "name": "analytics",
            "transport": "http",
            "url": "https://mcp.example.test/v1",
            "headers": {"Authorization": "Bearer test"},
        }
    )
    assert server_id == "srv_analytics"
    assert mcp_admin_service.list_mcp_servers()["configured"][0]["enabled"] is True

    mcp_admin_service.update_mcp_server(
        server_id,
        {"name": "analytics-v2", "enabled": False},
    )
    updated = registry.get_mcp_server(server_id)
    assert updated["name"] == "analytics-v2"
    assert updated["enabled"] is False
    assert updated["url"] == "https://mcp.example.test/v1"

    mcp_admin_service.delete_mcp_server(server_id)
    assert mcp_admin_service.list_mcp_servers()["configured"] == []


def test_plugin_mcp_server_is_read_only(monkeypatch):
    registry = FakeRegistry(
        [
            {
                "server_id": "portal",
                "name": "Portal MCP",
                "source": "plugin",
                "transport": "http",
                "url": "http://portal-mcp:8801/mcp/",
                "headers": {},
                "command": "",
                "args": [],
                "env": {},
                "enabled": True,
            }
        ]
    )
    monkeypatch.setattr(mcp_admin_service, "get_runtime_registry_store", lambda: registry)

    with pytest.raises(ValueError, match="read-only"):
        mcp_admin_service.update_mcp_server("portal", {"enabled": False})
    with pytest.raises(ValueError, match="read-only"):
        mcp_admin_service.delete_mcp_server("portal")


def test_import_validates_entire_payload_before_writing(monkeypatch):
    registry = FakeRegistry()
    monkeypatch.setattr(mcp_admin_service, "get_runtime_registry_store", lambda: registry)

    with pytest.raises(ValueError, match="command is required"):
        mcp_admin_service.import_mcp_servers(
            {
                "mcpServers": {
                    "valid": {"command": "npx", "args": ["server"]},
                    "broken": {"transport": "stdio"},
                }
            }
        )

    assert registry.save_calls == 0
    assert registry.rows == {}


def test_runtime_resolution_uses_selected_enabled_rows_and_preserves_transport(monkeypatch):
    registry = FakeRegistry(
        [
            {
                "server_id": "stdio-tools",
                "name": "stdio-tools",
                "source": "configured",
                "transport": "stdio",
                "url": "",
                "headers": {},
                "command": "uvx",
                "args": ["mcp-tools"],
                "env": {"MODE": "readonly"},
                "enabled": True,
            },
            {
                "server_id": "disabled",
                "name": "disabled",
                "source": "configured",
                "transport": "sse",
                "url": "https://disabled.example.test/sse",
                "headers": {},
                "command": "",
                "args": [],
                "env": {},
                "enabled": False,
            },
        ]
    )
    monkeypatch.setattr(mcp_admin_service, "get_runtime_registry_store", lambda: registry)

    assert mcp_admin_service.resolve_runtime_mcp_servers(["stdio-tools", "disabled"]) == {
        "stdio-tools": {
            "type": "stdio",
            "command": "uvx",
            "args": ["mcp-tools"],
            "env": {"MODE": "readonly"},
        }
    }


def test_runtime_resolution_distinguishes_default_selection_from_explicit_empty(monkeypatch):
    registry = FakeRegistry(
        [
            {
                "server_id": "portal",
                "name": "Portal MCP",
                "source": "plugin",
                "transport": "http",
                "url": "http://portal-mcp:8801/mcp",
                "headers": {"X-Portal-MCP-Token": "registry-token"},
                "command": "",
                "args": [],
                "env": {},
                "enabled": True,
            }
        ]
    )
    monkeypatch.setattr(mcp_admin_service, "get_runtime_registry_store", lambda: registry)

    assert set(mcp_admin_service.resolve_runtime_mcp_servers(None)) == {"portal"}
    assert mcp_admin_service.resolve_runtime_mcp_servers([]) == {}


def test_registry_migration_preserves_custom_provider_and_backfills_current_legacy_provider():
    migration = runpy.run_path(
        str(BACKEND_ROOT / "alembic" / "versions" / "20260911_000025_add_runtime_registries.py")
    )
    settings = {
        "provider_id": "anthropic",
        "model_name": "claude-sonnet-4.5",
        "anthropic_api_key": "legacy-key",
        "anthropic_auth_token": "",
        "anthropic_base_url": "https://api.anthropic.test",
        "raw_json": json.dumps(
            {
                "provider_settings": {
                    "custom_gateway": {
                        "provider_id": "custom_gateway",
                        "name": "Custom Gateway",
                        "provider_enabled": True,
                        "base_url": "https://gateway.example.test",
                        "auth_token": "gateway-token",
                        "models": [{"id": "gateway-model", "context_window": 128000}],
                    }
                }
            }
        ),
    }

    rows = {row["provider_id"]: row for row in migration["_legacy_provider_rows"](settings)}

    assert rows["custom_gateway"]["provider_type"] == "anthropic_compatible"
    assert json.loads(rows["custom_gateway"]["models_json"]) == [
        {"id": "gateway-model", "context_window": 128000}
    ]
    assert json.loads(rows["custom_gateway"]["enabled_models_json"]) == ["gateway-model"]
    assert rows["anthropic"]["api_key"] == "legacy-key"
    assert json.loads(rows["anthropic"]["enabled_models_json"]) == ["claude-sonnet-4.5"]
