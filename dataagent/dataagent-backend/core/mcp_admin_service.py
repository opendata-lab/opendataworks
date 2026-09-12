from __future__ import annotations

import re
import uuid
from typing import Any

from config import get_settings
from core.data_scope import encode_scope_header
from core.runtime_registry_store import get_runtime_registry_store

MCP_TRANSPORTS = {"http", "sse", "stdio"}
MCP_SOURCES = {"configured", "plugin"}
PORTAL_MCP_SERVER_ID = "portal"
PORTAL_MCP_TOOL_COUNT = 6
_SERVER_ID_RE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


def _string_list(raw: Any) -> list[str]:
    if not isinstance(raw, (list, tuple)):
        raise ValueError("args must be an array")
    return [str(item) for item in raw]


def _string_map(raw: Any, *, field: str) -> dict[str, str]:
    if not isinstance(raw, dict):
        raise ValueError(f"{field} must be an object")
    return {str(key): str(value) for key, value in raw.items() if str(key).strip()}


def _new_server_id(name: str) -> str:
    slug = re.sub(r"[^a-z0-9._-]+", "-", str(name or "").strip().lower()).strip("-._")[:80]
    return f"srv_{slug or uuid.uuid4().hex[:12]}"


def normalize_mcp_server(
    payload: dict[str, Any],
    *,
    existing: dict[str, Any] | None = None,
    source: str = "configured",
) -> dict[str, Any]:
    data = dict(existing or {})
    data.update(dict(payload or {}))
    server_id = str(data.get("server_id") or (existing or {}).get("server_id") or _new_server_id(data.get("name"))).strip()
    if not _SERVER_ID_RE.match(server_id):
        raise ValueError("server_id must match A-Za-z0-9._- and be at most 128 characters")
    name = str(data.get("name") or "").strip()
    if not name:
        raise ValueError("name is required")
    if len(name) > 128:
        raise ValueError("name must be at most 128 characters")
    transport = str(data.get("transport") or ("stdio" if data.get("command") else "sse")).strip().lower()
    if transport not in MCP_TRANSPORTS:
        raise ValueError("transport must be one of http/sse/stdio")
    url = str(data.get("url") or "").strip()
    command = str(data.get("command") or "").strip()
    if transport == "stdio" and not command:
        raise ValueError("command is required for stdio transport")
    if transport in {"http", "sse"} and not url:
        raise ValueError("url is required for http/sse transport")
    if source not in MCP_SOURCES:
        raise ValueError("source must be configured or plugin")

    return {
        "server_id": server_id,
        "name": name,
        "source": source,
        "transport": transport,
        "url": url if transport != "stdio" else "",
        "headers": _string_map(data.get("headers") or {}, field="headers"),
        "command": command if transport == "stdio" else "",
        "args": _string_list(data.get("args") or []),
        "env": _string_map(data.get("env") or {}, field="env"),
        "enabled": bool(data.get("enabled", True)),
        "oauth_required": bool(data.get("oauth_required", False)),
        "tool_count": max(0, int(data.get("tool_count") or 0)),
        "description": str(data.get("description") or "").strip()[:512],
    }


def list_mcp_servers() -> dict[str, list[dict[str, Any]]]:
    rows = get_runtime_registry_store().list_mcp_servers()
    return {
        "configured": [row for row in rows if row.get("source") == "configured"],
        "plugin": [row for row in rows if row.get("source") == "plugin"],
    }


def create_mcp_server(payload: dict[str, Any]) -> str:
    store = get_runtime_registry_store()
    normalized = normalize_mcp_server(payload, source="configured")
    if store.get_mcp_server(normalized["server_id"]):
        raise ValueError("server_id already exists")
    if store.find_mcp_server_by_name(normalized["name"]):
        raise ValueError("MCP server name already exists")
    store.save_mcp_server(normalized)
    return normalized["server_id"]


def update_mcp_server(server_id: str, payload: dict[str, Any]) -> None:
    store = get_runtime_registry_store()
    existing = store.get_mcp_server(str(server_id or "").strip())
    if not existing:
        raise KeyError("MCP server not found")
    if existing.get("source") != "configured":
        raise ValueError("plugin MCP server is read-only")
    requested_id = str((payload or {}).get("server_id") or server_id).strip()
    if requested_id != server_id:
        raise ValueError("server_id cannot be changed")
    normalized = normalize_mcp_server(payload, existing=existing, source="configured")
    name_owner = store.find_mcp_server_by_name(normalized["name"])
    if name_owner and name_owner.get("server_id") != server_id:
        raise ValueError("MCP server name already exists")
    store.save_mcp_server(normalized)


def delete_mcp_server(server_id: str) -> None:
    store = get_runtime_registry_store()
    existing = store.get_mcp_server(str(server_id or "").strip())
    if not existing:
        raise KeyError("MCP server not found")
    if existing.get("source") != "configured":
        raise ValueError("plugin MCP server is read-only")
    store.delete_mcp_server(server_id)


def import_mcp_servers(payload: dict[str, Any]) -> int:
    if not isinstance(payload, dict):
        raise ValueError("MCP import payload must be an object")
    raw_servers = payload.get("mcpServers") if "mcpServers" in payload else payload
    if not isinstance(raw_servers, dict) or not raw_servers:
        raise ValueError("MCP import must contain at least one server")

    store = get_runtime_registry_store()
    normalized_rows: list[dict[str, Any]] = []
    seen_names: set[str] = set()
    for raw_name, raw_config in raw_servers.items():
        name = str(raw_name or "").strip()
        if not name or not isinstance(raw_config, dict):
            raise ValueError("each imported MCP server must be an object with a name")
        existing = store.find_mcp_server_by_name(name)
        if existing and existing.get("source") == "plugin":
            raise ValueError(f"plugin MCP server is read-only: {name}")
        config = dict(raw_config)
        config["name"] = name
        config["server_id"] = existing.get("server_id") if existing else _new_server_id(name)
        normalized = normalize_mcp_server(config, existing=existing, source="configured")
        if name in seen_names:
            raise ValueError(f"duplicate MCP server name: {name}")
        seen_names.add(name)
        normalized_rows.append(normalized)

    for row in normalized_rows:
        store.save_mcp_server(row)
    return len(normalized_rows)


def bootstrap_portal_mcp_server() -> dict[str, Any] | None:
    """Persist the legacy environment-backed portal server exactly once.

    Environment variables are an upgrade input only. Runtime resolution below
    never falls back to them, so a database disable/edit remains authoritative.
    """
    cfg = get_settings()
    store = get_runtime_registry_store()
    existing = store.get_mcp_server(PORTAL_MCP_SERVER_ID)
    if existing:
        return existing
    enabled = bool(getattr(cfg, "dataagent_portal_mcp_enabled", True))
    url = str(getattr(cfg, "dataagent_portal_mcp_base_url", "") or "").strip()
    token = str(getattr(cfg, "dataagent_portal_mcp_token", "") or "").strip()
    if not enabled or not url or not token:
        return None
    header_name = str(getattr(cfg, "dataagent_portal_mcp_token_header_name", "") or "").strip() or "X-Portal-MCP-Token"
    normalized = normalize_mcp_server(
        {
            "server_id": PORTAL_MCP_SERVER_ID,
            "name": "Portal MCP",
            "transport": "http",
            "url": url.rstrip("/") + "/",
            "headers": {header_name: token},
            "enabled": True,
            "oauth_required": False,
            "tool_count": PORTAL_MCP_TOOL_COUNT,
            "description": "OpenDataWorks 平台元数据、血缘与只读查询工具。",
        },
        source="plugin",
    )
    return store.save_mcp_server(normalized, insert_only=True)


def available_mcp_servers() -> list[dict[str, Any]]:
    return [
        {
            "id": str(row.get("server_id") or ""),
            "name": str(row.get("name") or ""),
            "enabled": bool(row.get("enabled")),
            "tool_names": [],
        }
        for row in get_runtime_registry_store().list_mcp_servers()
    ]


def resolve_runtime_mcp_servers(
    mcp_server_ids: list[str] | tuple[str, ...] | None,
    *,
    agent_snapshot: dict[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    raw_selected = [PORTAL_MCP_SERVER_ID] if mcp_server_ids is None else mcp_server_ids
    selected = [str(item or "").strip() for item in raw_selected]
    selected_set = {item for item in selected if item}
    if not selected_set:
        return {}
    resolved: dict[str, dict[str, Any]] = {}
    for row in get_runtime_registry_store().list_mcp_servers():
        server_id = str(row.get("server_id") or "")
        if server_id not in selected_set or not row.get("enabled"):
            continue
        transport = str(row.get("transport") or "stdio")
        if transport == "stdio":
            resolved[server_id] = {
                "type": "stdio",
                "command": str(row.get("command") or ""),
                "args": list(row.get("args") or []),
                "env": dict(row.get("env") or {}),
            }
            continue
        headers = dict(row.get("headers") or {})
        if server_id == PORTAL_MCP_SERVER_ID and agent_snapshot is not None:
            headers["X-Agent-Data-Scope"] = encode_scope_header((agent_snapshot or {}).get("data_scope") or {})
        url = str(row.get("url") or "")
        if server_id == PORTAL_MCP_SERVER_ID:
            url = url.rstrip("/") + "/"
        resolved[server_id] = {"type": transport, "url": url, "headers": headers}
    return resolved
