from __future__ import annotations

"""
Tool Runtime（单一路径）
- 仅保留内置 mysql.query / doris.query
- 执行连接信息来自 opendataworks 平台表
"""

import logging
import os
import time
from typing import Any

import pymysql

from core.data_scope import current_env_scope, scope_allows_database
from core.datasource_resolver import (
    DatasourceResolutionError,
    resolve_engine_for_database,
    resolve_query_datasource,
)

logger = logging.getLogger(__name__)

_READ_ONLY_PREFIXES = ("SELECT", "SHOW", "DESC", "DESCRIBE", "EXPLAIN", "WITH")


class ToolRuntimeError(RuntimeError):
    pass


def list_tools() -> list[dict[str, Any]]:
    return [
        {
            "name": "mysql.query",
            "description": "查询 MySQL（只读）",
            "input": {
                "database": "schema 名称（必填）",
                "sql": "SELECT/SHOW/DESC/EXPLAIN 语句",
                "params": "SQL 参数列表（可选）",
                "limit": "结果上限（可选）",
            },
        },
        {
            "name": "doris.query",
            "description": "查询 Doris（只读）",
            "input": {
                "database": "database 名称（必填）",
                "sql": "SELECT 语句",
                "limit": "fetch 行数上限（可选）",
            },
        },
    ]


def invoke_tool(tool_name: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    payload = payload or {}
    if tool_name == "mysql.query":
        return _native_mysql_query(payload)
    if tool_name == "doris.query":
        return _native_doris_query(payload)
    raise ToolRuntimeError(f"Unknown tool: {tool_name}")


def run_query(sql: str, database: str | None = None, limit: int | None = None) -> dict[str, Any]:
    if not str(database or "").strip():
        raise ToolRuntimeError("database is required")
    _ensure_database_in_scope(str(database or "").strip())
    try:
        engine = resolve_engine_for_database(database)
    except DatasourceResolutionError as e:
        raise ToolRuntimeError(str(e)) from e
    payload = {"database": database, "sql": sql}
    if limit is not None:
        payload["limit"] = limit
    tool_name = "mysql.query" if engine == "mysql" else "doris.query"
    result = invoke_tool(tool_name, payload)
    result["engine"] = engine
    result["tool_name"] = tool_name
    return result


def _native_mysql_query(payload: dict[str, Any]) -> dict[str, Any]:
    sql = str(payload.get("sql") or "").strip()
    if not sql:
        raise ToolRuntimeError("mysql.query sql is empty")
    _ensure_read_only(sql)

    database = str(payload.get("database") or "").strip()
    _ensure_database_in_scope(database)
    params = payload.get("params") or []
    limit = _to_positive_int(payload.get("limit"))
    datasource = _resolve_datasource("mysql", database)

    conn = None
    start = time.time()
    try:
        conn = pymysql.connect(
            host=datasource.host,
            port=datasource.port,
            user=datasource.user,
            password=datasource.password,
            database=datasource.database or None,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            connect_timeout=10,
            read_timeout=60,
            write_timeout=30,
        )
        with conn.cursor() as cur:
            cur.execute(sql, params)
            rows = cur.fetchall()

        has_more = False
        if limit and len(rows) > limit:
            rows = rows[:limit]
            has_more = True

        return {
            "rows": rows,
            "row_count": len(rows),
            "has_more": has_more,
            "duration_ms": int((time.time() - start) * 1000),
        }
    except Exception as e:
        raise ToolRuntimeError(f"mysql.query failed: {e}") from e
    finally:
        if conn:
            try:
                conn.close()
            except Exception:
                pass


def _native_doris_query(payload: dict[str, Any]) -> dict[str, Any]:
    sql = str(payload.get("sql") or "").strip()
    if not sql:
        raise ToolRuntimeError("doris.query sql is empty")
    _ensure_read_only(sql)

    database = str(payload.get("database") or "").strip()
    _ensure_database_in_scope(database)
    limit = _to_positive_int(payload.get("limit"))
    datasource = _resolve_datasource("doris", database)

    conn = None
    start = time.time()
    try:
        conn = pymysql.connect(
            host=datasource.host,
            port=datasource.port,
            user=datasource.user,
            password=datasource.password,
            database=datasource.database or None,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            connect_timeout=10,
            read_timeout=60,
            write_timeout=30,
        )
        with conn.cursor() as cur:
            cur.execute(sql)
            rows = cur.fetchmany(limit) if limit else cur.fetchall()

        return {
            "rows": rows,
            "row_count": len(rows),
            "duration_ms": int((time.time() - start) * 1000),
        }
    except Exception as e:
        raise ToolRuntimeError(f"doris.query failed: {e}") from e
    finally:
        if conn:
            try:
                conn.close()
            except Exception:
                pass


def _ensure_read_only(sql: str):
    upper_sql = sql.lstrip().upper()
    if upper_sql.startswith(_READ_ONLY_PREFIXES):
        return
    raise ToolRuntimeError("Only read-only SQL is allowed for tool runtime")


def _to_positive_int(value: Any) -> int | None:
    if value is None:
        return None
    try:
        iv = int(value)
    except Exception:
        return None
    return iv if iv > 0 else None


def _resolve_datasource(engine: str, database: str) -> Any:
    try:
        return resolve_query_datasource(engine, database)
    except DatasourceResolutionError as e:
        raise ToolRuntimeError(str(e)) from e


def _ensure_database_in_scope(database: str) -> None:
    if "DATAAGENT_DATA_SCOPE_JSON" in os.environ and not scope_allows_database(current_env_scope(), database):
        raise ToolRuntimeError(f"数据范围限制: 未授权访问 database `{str(database or '').strip()}`")
