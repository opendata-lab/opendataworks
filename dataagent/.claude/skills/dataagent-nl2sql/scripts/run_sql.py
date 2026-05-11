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
            }
        )
    except Exception as exc:
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
            )
        )


if __name__ == "__main__":
    main()
