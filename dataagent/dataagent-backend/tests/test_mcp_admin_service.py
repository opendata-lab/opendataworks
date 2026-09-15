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


def test_normalize_mcp_server_supports_type_and_transport_standard_values():
    http_srv = mcp_admin_service.normalize_mcp_server(
        {"name": "my-http", "type": "http", "url": "http://example.test/mcp"}
    )
    assert http_srv["transport"] == "http"
    assert http_srv["url"] == "http://example.test/mcp"
    assert http_srv["command"] == ""

    sse_srv = mcp_admin_service.normalize_mcp_server(
        {"name": "my-sse", "type": "sse", "url": "https://example.test/sse"}
    )
    assert sse_srv["transport"] == "sse"
    assert sse_srv["url"] == "https://example.test/sse"

    stdio_srv = mcp_admin_service.normalize_mcp_server(
        {"name": "my-stdio", "type": "stdio", "command": "python", "args": ["server.py"]}
    )
    assert stdio_srv["transport"] == "stdio"
    assert stdio_srv["command"] == "python"
    assert stdio_srv["url"] == ""


def test_normalize_mcp_server_rejects_unsupported_or_remote_transport():
    with pytest.raises(ValueError, match="不支持的 MCP 传输类型 'remote'"):
        mcp_admin_service.normalize_mcp_server(
            {"name": "test-remote", "type": "remote", "url": "http://example.test/mcp"}
        )

    with pytest.raises(ValueError, match="不支持的 MCP 传输类型 'remote'"):
        mcp_admin_service.normalize_mcp_server(
            {"name": "test-remote", "transport": "remote", "url": "http://example.test/mcp"}
        )

    with pytest.raises(ValueError, match="不支持的 MCP 传输类型 'websocket'"):
        mcp_admin_service.normalize_mcp_server(
            {"name": "test-ws", "transport": "websocket", "url": "ws://example.test/ws"}
        )


def test_normalize_mcp_server_rejects_missing_transport_without_command():
    with pytest.raises(ValueError, match="缺少 MCP 传输类型"):
        mcp_admin_service.normalize_mcp_server(
            {"name": "no-transport", "url": "http://example.test/mcp"}
        )


def test_normalize_mcp_server_validates_url_format():
    with pytest.raises(ValueError, match="是以 http:// 或 https:// 开头的合法地址"):
        mcp_admin_service.normalize_mcp_server(
            {"name": "bad-scheme", "transport": "http", "url": "ftp://example.test"}
        )

    with pytest.raises(ValueError, match="是以 http:// 或 https:// 开头的合法地址"):
        mcp_admin_service.normalize_mcp_server(
            {"name": "not-url", "transport": "sse", "url": "not-a-valid-url"}
        )

    with pytest.raises(ValueError, match="缺少有效的主机地址"):
        mcp_admin_service.normalize_mcp_server(
            {"name": "no-host", "transport": "http", "url": "http://"}
        )


def test_import_mcp_servers_with_type_http_round_trip(monkeypatch):
    registry = FakeRegistry()
    monkeypatch.setattr(mcp_admin_service, "get_runtime_registry_store", lambda: registry)

    imported = mcp_admin_service.import_mcp_servers(
        {
            "mcpServers": {
                "custom-http": {
                    "type": "http",
                    "url": "http://192.168.1.100:8000/mcp/",
                    "headers": {"Authorization": "Bearer token123"},
                }
            }
        }
    )
    assert imported == 1
    assert "srv_custom-http" in registry.rows
    server = registry.rows["srv_custom-http"]
    assert server["transport"] == "http"
    assert server["url"] == "http://192.168.1.100:8000/mcp/"
    assert server["headers"] == {"Authorization": "Bearer token123"}


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


def test_redact_mcp_servers_strips_credentials_but_keeps_shape():
    """Opening the MCP page to non-admins must not hand out credentials.

    MCP rows carry `headers` (bearer tokens for http/sse servers) and `env`
    (API keys for stdio servers); the shipped portal server stores a real
    `X-Portal-MCP-Token`. Non-admin readers get key names and a "configured"
    flag, never values — the same treatment the settings endpoint already gives
    `anthropic_api_key` and `mysql_password`.
    """
    listing = {
        "configured": [
            {
                "server_id": "srv_stdio",
                "name": "local tool",
                "source": "configured",
                "transport": "stdio",
                "url": "",
                "headers": {},
                "command": "/usr/local/bin/secret-binary",
                "args": ["--token", "s3cret"],
                "env": {"API_KEY": "super-secret", "REGION": "cn"},
                "enabled": True,
                "oauth_required": False,
                "tool_count": 3,
                "description": "d",
            }
        ],
        "plugin": [
            {
                "server_id": "portal",
                "name": "Portal MCP",
                "source": "plugin",
                "transport": "http",
                "url": "http://127.0.0.1:8801/mcp/",
                "headers": {"X-Portal-MCP-Token": "odw-portal-mcp-token"},
                "command": "",
                "args": [],
                "env": {},
                "enabled": True,
                "oauth_required": False,
                "tool_count": 6,
                "description": "portal",
            }
        ],
    }

    redacted = mcp_admin_service.redact_mcp_servers(listing)

    stdio = redacted["configured"][0]
    assert stdio["env"] == {"API_KEY": "", "REGION": ""}, "key names kept, values dropped"
    assert stdio["env_set"] is True
    assert stdio["command"] == ""
    assert stdio["args"] == []
    assert "s3cret" not in json.dumps(redacted)
    assert "super-secret" not in json.dumps(redacted)
    assert "secret-binary" not in json.dumps(redacted)

    portal = redacted["plugin"][0]
    assert portal["headers"] == {"X-Portal-MCP-Token": ""}
    assert portal["headers_set"] is True
    assert "odw-portal-mcp-token" not in json.dumps(redacted)

    # Everything a read-only viewer legitimately needs survives.
    assert portal["name"] == "Portal MCP"
    assert portal["url"] == "http://127.0.0.1:8801/mcp/"
    assert portal["transport"] == "http"
    assert portal["enabled"] is True
    assert portal["tool_count"] == 6
    assert stdio["env_set"] is True and portal["env_set"] is False


def test_list_mcp_servers_marks_credential_presence_for_admins():
    """Admins keep the real values, plus the same presence flags."""
    listing = {
        "configured": [
            {
                "server_id": "srv",
                "name": "n",
                "source": "configured",
                "transport": "http",
                "url": "https://x.test",
                "headers": {"Authorization": "Bearer abc"},
                "command": "",
                "args": [],
                "env": {},
                "enabled": True,
                "oauth_required": False,
                "tool_count": 0,
                "description": "",
            }
        ],
        "plugin": [],
    }

    annotated = mcp_admin_service.annotate_mcp_servers(listing)

    row = annotated["configured"][0]
    assert row["headers"] == {"Authorization": "Bearer abc"}
    assert row["headers_set"] is True
    assert row["env_set"] is False
