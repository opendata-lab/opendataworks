from __future__ import annotations

import os
from dataclasses import dataclass


def _env_int(name: str, default: int) -> int:
    value = str(os.getenv(name, "")).strip()
    if not value:
        return default
    try:
        return int(value)
    except ValueError:
        return default


@dataclass(frozen=True)
class Settings:
    backend_base_url: str
    backend_service_token: str
    backend_token_header_name: str
    backend_timeout_seconds: int
    host: str
    port: int
    mcp_mount_path: str


def load_settings() -> Settings:
    backend_base_url = str(
        os.getenv("ODW_MCP_SERVER_BACKEND_BASE_URL", "http://backend:8080/api")
    ).strip().rstrip("/")
    return Settings(
        backend_base_url=backend_base_url,
        backend_service_token=str(os.getenv("ODW_MCP_SERVER_BACKEND_SERVICE_TOKEN", "")).strip(),
        backend_token_header_name=str(
            os.getenv("ODW_MCP_SERVER_BACKEND_TOKEN_HEADER_NAME", "X-Agent-Service-Token")
        ).strip()
        or "X-Agent-Service-Token",
        backend_timeout_seconds=max(1, _env_int("ODW_MCP_SERVER_BACKEND_TIMEOUT_SECONDS", 30)),
        host=str(os.getenv("ODW_MCP_SERVER_HOST", "0.0.0.0")).strip() or "0.0.0.0",
        port=max(1, _env_int("ODW_MCP_SERVER_PORT", 8801)),
        mcp_mount_path=str(os.getenv("ODW_MCP_SERVER_MOUNT_PATH", "/mcp")).strip() or "/mcp",
    )
