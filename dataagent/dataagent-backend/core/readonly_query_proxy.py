from __future__ import annotations

"""
只读 SQL 执行代理 — 把交互式查询转发到 backend-agent-api 的受治理通道。

dataagent-backend 不直连任何业务数据库：只读校验、limit/字节守卫、
数据范围隔离与数据源凭据全部由 Java 侧 `/v1/ai/query/read` 权威执行，
本模块仅做参数钳制、请求转发与错误归一。
"""

import logging
import os
from typing import Any

import httpx

from core.data_scope import runtime_data_scope_header

logger = logging.getLogger(__name__)

DEFAULT_LIMIT = 100
MAX_LIMIT = 1000
DEFAULT_TIMEOUT_SECONDS = 30
MAX_TIMEOUT_SECONDS = 120

DATA_SCOPE_HEADER_NAME = "X-Agent-Data-Scope"


class QueryProxyConfigError(RuntimeError):
    """执行通道未配置（缺少 base url 或服务令牌）。"""


class QueryProxyUpstreamError(RuntimeError):
    def __init__(self, message: str, *, status_code: int):
        super().__init__(message)
        self.status_code = status_code


def _ai_base_url() -> str:
    raw = str(os.getenv("ODW_BACKEND_BASE_URL") or "").strip().rstrip("/")
    if not raw:
        return ""
    # 与 odw-cli 一致：兼容旧值 /api/v1/ai/metadata，规范化到 AI 根路径。
    if raw.endswith("/metadata"):
        return raw[: -len("/metadata")]
    return raw


def _service_token() -> str:
    return str(os.getenv("ODW_AGENT_SERVICE_TOKEN") or "").strip()


def _service_token_header_name() -> str:
    return str(os.getenv("ODW_AGENT_SERVICE_TOKEN_HEADER_NAME") or "").strip() or "X-Agent-Service-Token"


def clamp_limit(value: Any) -> int:
    try:
        limit = int(value)
    except (TypeError, ValueError):
        return DEFAULT_LIMIT
    return max(1, min(limit, MAX_LIMIT))


def clamp_timeout_seconds(value: Any) -> int:
    try:
        timeout = int(value)
    except (TypeError, ValueError):
        return DEFAULT_TIMEOUT_SECONDS
    return max(1, min(timeout, MAX_TIMEOUT_SECONDS))


def _result_state(error: str | None, row_count: int) -> str:
    if error:
        return "failed"
    if row_count <= 0:
        return "empty_result"
    return "success"


def _normalize_query_result(payload: dict[str, Any], *, sql: str, database: str) -> dict[str, Any]:
    rows = payload.get("rows") if isinstance(payload.get("rows"), list) else []
    columns = list(rows[0].keys()) if rows and isinstance(rows[0], dict) else []
    error = payload.get("error")
    error_text = str(error).strip() if error else None
    row_count_raw = payload.get("row_count")
    row_count = row_count_raw if isinstance(row_count_raw, int) else len(rows)
    return {
        "kind": "sql_execution",
        "engine": payload.get("engine"),
        "database": payload.get("database") or database,
        "sql": payload.get("sql") or sql,
        "columns": columns,
        "rows": rows,
        "row_count": row_count,
        "has_more": bool(payload.get("has_more")),
        "truncated_by_size": bool(payload.get("truncated_by_size")),
        "notice": payload.get("notice"),
        "duration_ms": payload.get("duration_ms"),
        "result_state": _result_state(error_text, row_count),
        "error": error_text,
    }


def _extract_upstream_message(response: httpx.Response) -> str:
    try:
        data = response.json()
    except ValueError:
        data = None
    if isinstance(data, dict):
        for key in ("message", "detail", "error"):
            text = str(data.get(key) or "").strip()
            if text:
                return text
    body = str(response.text or "").strip()
    return body[:500] if body else f"backend agent api request failed: HTTP {response.status_code}"


async def execute_readonly_query(
    sql: str,
    database: str,
    *,
    engine: str | None = None,
    limit: Any = None,
    timeout_seconds: Any = None,
    data_scope_header: str | None = None,
) -> dict[str, Any]:
    base_url = _ai_base_url()
    token = _service_token()
    if not base_url or not token:
        raise QueryProxyConfigError(
            "SQL 执行通道未配置：需要 ODW_BACKEND_BASE_URL 与 ODW_AGENT_SERVICE_TOKEN"
        )

    clamped_limit = clamp_limit(limit)
    clamped_timeout = clamp_timeout_seconds(timeout_seconds)

    headers = {_service_token_header_name(): token}
    data_scope = data_scope_header or runtime_data_scope_header()
    if data_scope:
        headers[DATA_SCOPE_HEADER_NAME] = data_scope

    body: dict[str, Any] = {
        "database": database,
        "sql": sql,
        "limit": clamped_limit,
        "timeoutSeconds": clamped_timeout,
    }
    preferred_engine = str(engine or "").strip()
    if preferred_engine:
        body["preferredEngine"] = preferred_engine

    try:
        async with httpx.AsyncClient(timeout=clamped_timeout + 10) as client:
            response = await client.post(f"{base_url}/query/read", json=body, headers=headers)
    except httpx.HTTPError as exc:
        logger.warning("readonly query proxy network error: %s", exc)
        raise QueryProxyUpstreamError(f"查询通道请求失败: {exc}", status_code=502) from exc

    if response.status_code >= 500:
        raise QueryProxyUpstreamError(_extract_upstream_message(response), status_code=502)
    if response.status_code >= 400:
        status = response.status_code if response.status_code in (400, 401, 403, 404) else 400
        raise QueryProxyUpstreamError(_extract_upstream_message(response), status_code=status)

    try:
        payload = response.json()
    except ValueError as exc:
        raise QueryProxyUpstreamError("查询通道返回了非 JSON 响应", status_code=502) from exc
    if not isinstance(payload, dict):
        raise QueryProxyUpstreamError("查询通道返回了非预期的响应结构", status_code=502)

    return _normalize_query_result(payload, sql=sql, database=database)
