from __future__ import annotations

import argparse
import csv
import os
from pathlib import Path

from _opendataworks_runtime import (
    ensure_read_only,
    env_int,
    error_payload,
    print_json,
    query_readonly,
    serializable_rows,
)

# 行数硬上限与后端 MAX_LIMIT 对齐：导出仍是有界的，超出范围应改用更精确的过滤或聚合。
EXPORT_MAX_LIMIT = 10000


def _resolve_output_path(raw: str) -> Path:
    path = Path(str(raw or "").strip()).expanduser()
    if not path.is_absolute():
        path = Path.cwd() / path
    return path


def _ordered_columns(rows: list[dict[str, object]]) -> list[str]:
    columns: list[str] = []
    seen: set[str] = set()
    for row in rows:
        for key in row.keys():
            if key not in seen:
                seen.add(key)
                columns.append(str(key))
    return columns


def main():
    parser = argparse.ArgumentParser(
        description="Export read-only SQL results to a workspace CSV file (full data stays out of the model context)"
    )
    parser.add_argument("--database", required=True)
    parser.add_argument("--sql", required=True)
    parser.add_argument("--output", required=True, help="CSV output path; write downloadable deliverables under the workspace output/ directory, e.g. output/result.csv (relative paths resolve under the workspace cwd)")
    parser.add_argument("--engine", default="")
    parser.add_argument("--limit", type=int, default=EXPORT_MAX_LIMIT)
    parser.add_argument(
        "--preview-rows",
        type=int,
        default=env_int("DATAAGENT_RESULT_PREVIEW_ROWS", 20),
    )
    args = parser.parse_args()

    database = str(args.database or "").strip()
    preferred_engine = str(args.engine or "").strip().lower() or None
    sql = str(args.sql or "").strip()
    limit = max(1, min(int(args.limit or EXPORT_MAX_LIMIT), EXPORT_MAX_LIMIT))
    preview_rows = max(0, int(args.preview_rows if args.preview_rows is not None else 20))

    try:
        ensure_read_only(sql)
        result = query_readonly(
            database=database,
            sql=sql,
            preferred_engine=preferred_engine,
            limit=limit,
            timeout_seconds=max(1, env_int("DATAAGENT_SQL_READ_TIMEOUT_SECONDS", 60)),
            for_export=True,
        )

        rows = serializable_rows(list(result.get("rows") or []))
        columns = _ordered_columns(rows)

        output_path = _resolve_output_path(args.output)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        with open(output_path, "w", encoding="utf-8-sig", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=columns, extrasaction="ignore")
            writer.writeheader()
            for row in rows:
                writer.writerow({col: row.get(col, "") for col in columns})

        has_more = bool(result.get("has_more"))
        preview = rows[:preview_rows]
        stop_reason = (
            f"已导出 {len(rows)} 行（命中行数上限 {limit}，可能仍有更多数据，请用更精确的过滤或聚合）。"
            if has_more
            else ""
        )

        print_json(
            {
                "kind": "sql_export",
                "tool_label": "SQL 导出",
                "engine": result.get("engine"),
                "database": result.get("database"),
                "sql": sql,
                "file_path": str(output_path),
                "file_format": "csv",
                "columns": columns,
                "row_count": len(rows),
                "has_more": has_more,
                "preview_rows": preview,
                "summary": f"已导出 {len(rows)} 行到 {output_path}",
                "result_state": "success",
                "error_code": None,
                "failure_attribution": [],
                "retryable": False,
                "stop_reason": stop_reason,
                "error": None,
            }
        )
    except Exception as exc:
        print_json(
            error_payload(
                "sql_export",
                str(exc),
                tool_label="SQL 导出",
                database=database,
                engine=preferred_engine,
                sql=sql,
                file_path=None,
                columns=[],
                row_count=0,
                has_more=False,
                result_state="failed",
                error_code="export_failed",
                failure_attribution=["tool_error"],
                retryable=False,
                stop_reason="SQL 结果导出失败，请根据错误信息修正查询或输出路径后重试。",
            )
        )


if __name__ == "__main__":
    main()
