from __future__ import annotations

import argparse
import os
import re

from _opendataworks_runtime import (
    ensure_read_only,
    env_int,
    error_payload,
    print_json,
    query_readonly,
    serializable_rows,
)

LINEAGE_QUESTION_KEYWORDS = ("上游", "下游", "血缘", "lineage")
LINEAGE_SQL_PATTERNS = (
    r"\bdata_lineage\b",
    r"\bupstream_table_id\b",
    r"\bdownstream_table_id\b",
    r"\blineage_type\b",
)


def classify_sql_execution_failure(message: str) -> dict[str, object]:
    text = str(message or "").strip()
    lower = text.lower()

    if "select command denied" in lower or "access denied" in lower or "权限不足" in text or "无权限" in text:
        return {
            "result_state": "failed",
            "error_code": "permission_denied",
            "failure_attribution": ["permission_denied"],
            "retryable": False,
            "stop_reason": "权限不足，已停止继续换库、换表或重复试探；需要补齐只读权限或更换可访问数据源。",
        }
    if "引擎不匹配" in text or "engine mismatch" in lower:
        return {
            "result_state": "failed",
            "error_code": "datasource_mismatch",
            "failure_attribution": ["datasource_mismatch"],
            "retryable": False,
            "stop_reason": "database 与 engine 不匹配，已停止继续试探；需要先用数据源路由确认正确的 database/engine。",
        }
    if "unknown column" in lower or "field list" in lower and "unknown" in lower:
        return {
            "result_state": "failed",
            "error_code": "unknown_column",
            "failure_attribution": ["schema_mismatch"],
            "retryable": False,
            "stop_reason": "字段不存在或字段映射过期，已停止继续换字段试探；需要先确认 live DDL 或本体字段映射。",
        }
    if "unknown table" in lower or "table doesn't exist" in lower:
        return {
            "result_state": "failed",
            "error_code": "unknown_table",
            "failure_attribution": ["schema_mismatch"],
            "retryable": False,
            "stop_reason": "表不存在或表映射过期，已停止继续换表试探；需要先确认元数据、DDL 或本体表映射。",
        }
    if "timeout" in lower or "timed out" in lower or "超时" in text:
        return {
            "result_state": "failed",
            "error_code": "tool_timeout",
            "failure_attribution": ["tool_timeout"],
            "retryable": False,
            "stop_reason": "只读查询超时，已停止重复执行；需要缩小时间范围、增加过滤条件或转后台任务。",
        }
    if "仅允许只读 sql" in lower or "检测到非只读关键字" in text:
        return {
            "result_state": "failed",
            "error_code": "non_readonly_sql",
            "failure_attribution": ["invalid_sql"],
            "retryable": False,
            "stop_reason": "SQL 未通过只读安全检查，已停止执行；只能改写为 SELECT/WITH/SHOW/DESC/EXPLAIN 类只读语句。",
        }
    if "请先使用 `mcp__portal__portal_get_lineage`" in text or "dataagent_allow_lineage_sql_fallback" in lower:
        return {
            "result_state": "failed",
            "error_code": "lineage_guard",
            "failure_attribution": ["invalid_tool_path"],
            "retryable": False,
            "stop_reason": "血缘问题必须先走 lineage 专用工具；不要继续猜 data_lineage SQL。",
        }
    return {
        "result_state": "failed",
        "error_code": "query_failed",
        "failure_attribution": ["tool_error"],
        "retryable": False,
        "stop_reason": "只读 SQL 执行失败，已停止等价重试；需要根据错误信息修正查询口径或说明缺口。",
    }


def empty_result_detail() -> dict[str, object]:
    return {
        "result_state": "empty_result",
        "error_code": "empty_result",
        "failure_attribution": ["empty_result"],
        "retryable": False,
        "stop_reason": "SQL 已成功执行但返回 0 行；说明当前口径无数据或未命中，不要继续换表、换字段或重复试探。",
    }


def _looks_like_lineage_question(question: str) -> bool:
    lowered = str(question or "").strip().lower()
    if not lowered:
        return False
    return any(keyword in lowered for keyword in LINEAGE_QUESTION_KEYWORDS)


def _looks_like_lineage_sql(sql: str) -> bool:
    statement = str(sql or "").strip()
    if not statement:
        return False
    return any(re.search(pattern, statement, flags=re.IGNORECASE) for pattern in LINEAGE_SQL_PATTERNS)


def _lineage_sql_fallback_allowed() -> bool:
    raw = str(os.getenv("DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK", "") or "").strip().lower()
    return raw in {"1", "true", "yes", "on"}


def enforce_lineage_first_guard(sql: str):
    question = str(os.getenv("DATAAGENT_ORIGINAL_QUESTION", "") or "").strip()
    if not _looks_like_lineage_question(question):
        return
    if _lineage_sql_fallback_allowed():
        return
    if not _looks_like_lineage_sql(sql):
        return
    raise ValueError(
        "当前问题是上游/下游/血缘诊断，请先使用 `mcp__portal__portal_get_lineage`；"
        "无 MCP 时先执行 `get_lineage.py`。只有 lineage 快照仍缺字段时，才允许带 "
        "`DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1` 再补充 `run_sql.py`。"
    )


def main():
    parser = argparse.ArgumentParser(description="Execute read-only SQL through the backend agent query path")
    parser.add_argument("--database", required=True)
    parser.add_argument("--engine", default="")
    parser.add_argument("--sql", required=True)
    parser.add_argument("--limit", type=int, default=env_int("DATAAGENT_QUERY_LIMIT", 1000))
    args = parser.parse_args()

    database = str(args.database or "").strip()
    preferred_engine = str(args.engine or "").strip().lower() or None
    sql = str(args.sql or "").strip()
    limit = max(1, int(args.limit if args.limit is not None else 1000))

    try:
        ensure_read_only(sql)
        enforce_lineage_first_guard(sql)
        result = query_readonly(
            database=database,
            sql=sql,
            preferred_engine=preferred_engine,
            limit=limit,
            timeout_seconds=max(1, env_int("DATAAGENT_SQL_READ_TIMEOUT_SECONDS", 60)),
        )

        preview_rows = list(result.get("rows") or [])[:limit]
        serialized_rows = serializable_rows(preview_rows)
        columns = list(serialized_rows[0].keys()) if serialized_rows else []
        execution_detail = (
            empty_result_detail()
            if not serialized_rows
            else {
                "result_state": "success",
                "error_code": None,
                "failure_attribution": [],
                "retryable": False,
                "stop_reason": "",
            }
        )

        print_json(
            {
                "kind": "sql_execution",
                "tool_label": "SQL 执行",
                "engine": result.get("engine"),
                "database": result.get("database"),
                "sql": sql,
                "columns": columns,
                "rows": serialized_rows,
                "row_count": len(serialized_rows),
                "has_more": bool(result.get("has_more")),
                "duration_ms": int(result.get("duration_ms") or 0),
                "summary": f"返回 {len(serialized_rows)} 行结果",
                "error": None,
                **execution_detail,
            }
        )
    except Exception as exc:
        failure_detail = classify_sql_execution_failure(str(exc))
        print_json(
            error_payload(
                "sql_execution",
                str(exc),
                tool_label="SQL 执行",
                database=database,
                engine=preferred_engine,
                sql=sql,
                columns=[],
                rows=[],
                row_count=0,
                has_more=False,
                duration_ms=0,
                **failure_detail,
            )
        )


if __name__ == "__main__":
    main()
