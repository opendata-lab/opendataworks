from __future__ import annotations

import base64
import json
import os
from typing import Any


def normalize_data_scope(value: Any) -> dict[str, list[dict[str, Any]]]:
    payload = _safe_json_load(value)
    scopes = payload.get("allowed_scopes") if isinstance(payload, dict) else []
    if not isinstance(scopes, list):
        scopes = []

    result: list[dict[str, Any]] = []
    seen: set[tuple[int | None, str, str]] = set()
    for item in scopes:
        if not isinstance(item, dict):
            continue
        database = str(item.get("database") or "").strip()
        if not database:
            continue
        cluster_id = _to_optional_int(item.get("cluster_id"))
        source_type = str(item.get("source_type") or "").strip().upper()
        key = (cluster_id, source_type, database)
        if key in seen:
            continue
        seen.add(key)
        result.append(
            {
                "cluster_id": cluster_id,
                "source_type": source_type,
                "database": database,
            }
        )
    return {"allowed_scopes": result}


def scope_allows_database(scope: Any, database: str | None, cluster_id: int | None = None) -> bool:
    db = str(database or "").strip()
    if not db:
        return False
    normalized = normalize_data_scope(scope)
    for item in normalized.get("allowed_scopes", []):
        if str(item.get("database") or "") != db:
            continue
        item_cluster_id = item.get("cluster_id")
        if cluster_id is None or item_cluster_id is None or int(item_cluster_id) == int(cluster_id):
            return True
    return False


def allowed_databases(scope: Any) -> list[str]:
    result: list[str] = []
    seen: set[str] = set()
    for item in normalize_data_scope(scope).get("allowed_scopes", []):
        database = str(item.get("database") or "").strip()
        if database and database not in seen:
            result.append(database)
            seen.add(database)
    return result


def current_env_scope() -> dict[str, list[dict[str, Any]]]:
    return normalize_data_scope(os.getenv("DATAAGENT_DATA_SCOPE_JSON") or {})


def encode_scope_header(scope: Any) -> str:
    payload = json.dumps(normalize_data_scope(scope), ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii").rstrip("=")


def runtime_data_scope_header() -> str:
    """解析当前运行时的 data-scope 请求头值。

    与 skill 运行时 `runtime_data_scope_header()` 同一契约：优先使用预计算的
    `ODW_AGENT_DATA_SCOPE_HEADER` / `DATAAGENT_DATA_SCOPE_HEADER`，否则由
    `DATAAGENT_DATA_SCOPE_JSON` 归一后 base64url 编码。
    """
    configured = str(
        os.getenv("ODW_AGENT_DATA_SCOPE_HEADER")
        or os.getenv("DATAAGENT_DATA_SCOPE_HEADER")
        or ""
    ).strip()
    if configured:
        return configured

    raw_scope = str(os.getenv("DATAAGENT_DATA_SCOPE_JSON") or "").strip()
    if not raw_scope:
        return ""
    return encode_scope_header(raw_scope)


def _safe_json_load(value: Any) -> Any:
    if isinstance(value, dict):
        return value
    if isinstance(value, str) and value.strip():
        try:
            return json.loads(value)
        except Exception:
            return {}
    return {}


def _to_optional_int(value: Any) -> int | None:
    if value in (None, ""):
        return None
    try:
        return int(value)
    except Exception:
        return None
