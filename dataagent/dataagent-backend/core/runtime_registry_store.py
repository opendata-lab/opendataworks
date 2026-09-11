from __future__ import annotations

import json
import threading
from datetime import datetime
from typing import Any

import pymysql

from config import get_settings


def _to_iso(value: Any) -> str:
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value) if value is not None else ""


def _json_load(raw: Any, fallback: Any) -> Any:
    if isinstance(raw, (dict, list)):
        return raw
    try:
        return json.loads(str(raw)) if raw not in (None, "") else fallback
    except Exception:
        return fallback


def _json_dump(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


class RuntimeRegistryStore:
    def __init__(self):
        self._ready = False
        self._ready_lock = threading.Lock()

    def _connect(self):
        cfg = get_settings()
        return pymysql.connect(
            host=cfg.mysql_host,
            port=cfg.mysql_port,
            user=cfg.mysql_user,
            password=cfg.mysql_password,
            database=cfg.session_mysql_database,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            autocommit=False,
        )

    def init_schema(self) -> None:
        if self._ready:
            return
        with self._ready_lock:
            self._ready = True

    def _ensure_ready(self) -> None:
        if not self._ready:
            self.init_schema()

    def list_mcp_servers(self) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT server_id, name, source, transport, url, headers_json,
                           command_name, args_json, env_json, enabled, oauth_required,
                           tool_count, description, created_at, updated_at
                    FROM da_mcp_server
                    ORDER BY FIELD(source, 'configured', 'plugin'), name, server_id
                    """
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return [self._normalize_mcp_row(row) for row in rows]

    def get_mcp_server(self, server_id: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT server_id, name, source, transport, url, headers_json,
                           command_name, args_json, env_json, enabled, oauth_required,
                           tool_count, description, created_at, updated_at
                    FROM da_mcp_server WHERE server_id = %s LIMIT 1
                    """,
                    (server_id,),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_mcp_row(row) if row else None

    def find_mcp_server_by_name(self, name: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT server_id, name, source, transport, url, headers_json,
                           command_name, args_json, env_json, enabled, oauth_required,
                           tool_count, description, created_at, updated_at
                    FROM da_mcp_server WHERE name = %s LIMIT 1
                    """,
                    (name,),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_mcp_row(row) if row else None

    def save_mcp_server(self, payload: dict[str, Any], *, insert_only: bool = False) -> dict[str, Any]:
        self._ensure_ready()
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                verb = "INSERT IGNORE" if insert_only else "INSERT"
                duplicate = "" if insert_only else """
                    ON DUPLICATE KEY UPDATE
                        name = VALUES(name), transport = VALUES(transport), url = VALUES(url),
                        headers_json = VALUES(headers_json), command_name = VALUES(command_name),
                        args_json = VALUES(args_json), env_json = VALUES(env_json),
                        enabled = VALUES(enabled), oauth_required = VALUES(oauth_required),
                        tool_count = VALUES(tool_count), description = VALUES(description),
                        updated_at = CURRENT_TIMESTAMP
                """
                cur.execute(
                    f"""
                    {verb} INTO da_mcp_server (
                        server_id, name, source, transport, url, headers_json, command_name,
                        args_json, env_json, enabled, oauth_required, tool_count, description
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    {duplicate}
                    """,
                    (
                        payload["server_id"], payload["name"], payload["source"], payload["transport"],
                        payload.get("url", ""), _json_dump(payload.get("headers") or {}),
                        payload.get("command", ""), _json_dump(payload.get("args") or []),
                        _json_dump(payload.get("env") or {}), int(bool(payload.get("enabled", True))),
                        int(bool(payload.get("oauth_required", False))), int(payload.get("tool_count") or 0),
                        payload.get("description", ""),
                    ),
                )
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()
        return self.get_mcp_server(str(payload["server_id"])) or dict(payload)

    def delete_mcp_server(self, server_id: str) -> bool:
        self._ensure_ready()
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM da_mcp_server WHERE server_id = %s", (server_id,))
                deleted = int(cur.rowcount or 0) > 0
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()
        return deleted

    def list_providers(self) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT provider_id, provider_type, display_name, provider_group, base_url,
                           api_key, auth_token, provider_enabled, supports_partial_messages,
                           enabled_models_json, custom_models_json, models_json, model_detections_json,
                           validation_status, validation_message, validated_at, created_at, updated_at
                    FROM da_model_provider
                    ORDER BY provider_group, display_name, provider_id
                    """
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return [self._normalize_provider_row(row) for row in rows]

    def get_provider(self, provider_id: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT provider_id, provider_type, display_name, provider_group, base_url,
                           api_key, auth_token, provider_enabled, supports_partial_messages,
                           enabled_models_json, custom_models_json, models_json, model_detections_json,
                           validation_status, validation_message, validated_at, created_at, updated_at
                    FROM da_model_provider WHERE provider_id = %s LIMIT 1
                    """,
                    (provider_id,),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_provider_row(row) if row else None

    def save_provider(self, payload: dict[str, Any]) -> dict[str, Any]:
        self._ensure_ready()
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_model_provider (
                        provider_id, provider_type, display_name, provider_group, base_url,
                        api_key, auth_token, provider_enabled, supports_partial_messages,
                        enabled_models_json, custom_models_json, models_json, model_detections_json,
                        validation_status, validation_message, validated_at
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    ON DUPLICATE KEY UPDATE
                        provider_type = VALUES(provider_type), display_name = VALUES(display_name),
                        provider_group = VALUES(provider_group), base_url = VALUES(base_url),
                        api_key = VALUES(api_key), auth_token = VALUES(auth_token),
                        provider_enabled = VALUES(provider_enabled),
                        supports_partial_messages = VALUES(supports_partial_messages),
                        enabled_models_json = VALUES(enabled_models_json),
                        custom_models_json = VALUES(custom_models_json), models_json = VALUES(models_json),
                        model_detections_json = VALUES(model_detections_json),
                        validation_status = VALUES(validation_status),
                        validation_message = VALUES(validation_message), validated_at = VALUES(validated_at),
                        updated_at = CURRENT_TIMESTAMP
                    """,
                    (
                        payload["provider_id"], payload["provider_type"], payload["display_name"],
                        payload.get("provider_group", ""), payload.get("base_url", ""),
                        payload.get("api_key", ""), payload.get("auth_token", ""),
                        int(bool(payload.get("provider_enabled"))),
                        int(bool(payload.get("supports_partial_messages", True))),
                        _json_dump(payload.get("enabled_models") or []),
                        _json_dump(payload.get("custom_models") or []),
                        _json_dump(payload.get("models") or []),
                        _json_dump(payload.get("model_detections") or {}),
                        payload.get("validation_status", "unverified"),
                        payload.get("validation_message", ""), payload.get("validated_at") or None,
                    ),
                )
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()
        return self.get_provider(str(payload["provider_id"])) or dict(payload)

    def delete_provider(self, provider_id: str) -> bool:
        self._ensure_ready()
        conn = self._connect()
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM da_model_provider WHERE provider_id = %s", (provider_id,))
                deleted = int(cur.rowcount or 0) > 0
            conn.commit()
        except Exception:
            conn.rollback()
            raise
        finally:
            conn.close()
        return deleted

    @staticmethod
    def _normalize_mcp_row(row: dict[str, Any]) -> dict[str, Any]:
        return {
            "server_id": str(row.get("server_id") or ""),
            "name": str(row.get("name") or ""),
            "source": str(row.get("source") or "configured"),
            "transport": str(row.get("transport") or "stdio"),
            "url": str(row.get("url") or ""),
            "headers": dict(_json_load(row.get("headers_json"), {}) or {}),
            "command": str(row.get("command_name") or ""),
            "args": list(_json_load(row.get("args_json"), []) or []),
            "env": dict(_json_load(row.get("env_json"), {}) or {}),
            "enabled": bool(row.get("enabled")),
            "oauth_required": bool(row.get("oauth_required")),
            "tool_count": int(row.get("tool_count") or 0),
            "description": str(row.get("description") or ""),
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }

    @staticmethod
    def _normalize_provider_row(row: dict[str, Any]) -> dict[str, Any]:
        return {
            "provider_id": str(row.get("provider_id") or ""),
            "provider_type": str(row.get("provider_type") or "anthropic_compatible"),
            "display_name": str(row.get("display_name") or row.get("provider_id") or ""),
            "provider_group": str(row.get("provider_group") or ""),
            "base_url": str(row.get("base_url") or ""),
            "api_key": str(row.get("api_key") or ""),
            "auth_token": str(row.get("auth_token") or ""),
            "provider_enabled": bool(row.get("provider_enabled")),
            "supports_partial_messages": bool(row.get("supports_partial_messages")),
            "enabled_models": list(_json_load(row.get("enabled_models_json"), []) or []),
            "custom_models": list(_json_load(row.get("custom_models_json"), []) or []),
            "models": list(_json_load(row.get("models_json"), []) or []),
            "model_detections": dict(_json_load(row.get("model_detections_json"), {}) or {}),
            "validation_status": str(row.get("validation_status") or "unverified"),
            "validation_message": str(row.get("validation_message") or ""),
            "validated_at": _to_iso(row.get("validated_at")),
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }


_runtime_registry_store = RuntimeRegistryStore()


def get_runtime_registry_store() -> RuntimeRegistryStore:
    return _runtime_registry_store

