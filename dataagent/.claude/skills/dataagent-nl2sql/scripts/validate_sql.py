from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any

from _opendataworks_runtime import ensure_read_only, print_json


SQL_KEYWORDS = {
    "select",
    "from",
    "where",
    "and",
    "or",
    "not",
    "in",
    "is",
    "null",
    "as",
    "on",
    "join",
    "left",
    "right",
    "inner",
    "outer",
    "cross",
    "full",
    "group",
    "by",
    "order",
    "asc",
    "desc",
    "having",
    "limit",
    "offset",
    "count",
    "sum",
    "avg",
    "max",
    "min",
    "distinct",
    "case",
    "when",
    "then",
    "else",
    "end",
    "between",
    "like",
    "exists",
    "union",
    "all",
    "with",
    "recursive",
    "interval",
    "day",
    "month",
    "year",
    "date_sub",
    "date_add",
    "now",
    "true",
    "false",
}


class ValidationContext:
    def __init__(
        self,
        tables: set[str] | None = None,
        all_fields: set[str] | None = None,
        source: str = "",
    ):
        self.tables = tables or set()
        self.all_fields = all_fields or set()
        self.source = source


class ValidationResult:
    def __init__(self):
        self.errors: list[str] = []
        self.warnings: list[str] = []
        self.passed: list[str] = []

    @property
    def is_valid(self) -> bool:
        return not self.errors

    def error(self, message: str):
        self.errors.append(message)

    def warn(self, message: str):
        self.warnings.append(message)

    def ok(self, message: str):
        self.passed.append(message)

    def to_payload(self) -> dict[str, Any]:
        return {
            "kind": "sql_validation",
            "valid": self.is_valid,
            "errors": self.errors,
            "warnings": self.warnings,
            "passed": self.passed,
            "error_code": None if self.is_valid else "sql_validation_failed",
            "failure_attribution": [] if self.is_valid else ["invalid_sql"],
            "retryable": not self.is_valid,
            "stop_reason": "" if self.is_valid else "SQL 未通过验证；请修正错误后再执行 run_sql.py。",
        }

    def report(self) -> str:
        lines = ["SQL 验证报告"]
        for message in self.passed:
            lines.append(f"- PASS: {message}")
        for message in self.warnings:
            lines.append(f"- WARN: {message}")
        for message in self.errors:
            lines.append(f"- FAIL: {message}")
        lines.append(f"结果: {'PASS' if self.is_valid else 'FAIL'}")
        return "\n".join(lines)


def _as_list(value: Any) -> list[Any]:
    return value if isinstance(value, list) else []


def _register_table(context: ValidationContext, table: Any):
    value = str(table or "").strip()
    if value:
        context.tables.add(value)


def _register_field(context: ValidationContext, field_value: Any):
    for column in str(field_value or "").split("|"):
        column = column.strip()
        if column:
            context.all_fields.add(column)


def load_ontology(path: str | Path | None) -> ValidationContext:
    if not path:
        return ValidationContext()

    ontology_path = Path(path).expanduser()
    data = json.loads(ontology_path.read_text(encoding="utf-8"))
    context = ValidationContext(source=str(ontology_path))

    for obj in _as_list(data.get("object_types")):
        for source in _as_list(obj.get("physical_sources")):
            _register_table(context, source.get("table"))
        for prop in _as_list(obj.get("properties")):
            _register_field(context, prop.get("column"))
        for func in _as_list(obj.get("query_functions")):
            for field_name in _as_list(func.get("output_fields")):
                _register_field(context, field_name)
            for table in extract_tables_from_sql(func.get("sql_template_mysql", "")):
                _register_table(context, table)

    for rel in _as_list(data.get("object_relations")):
        for step in _as_list(rel.get("join_or_calc_path")):
            _register_table(context, step.get("table"))
            _register_field(context, step.get("from_column"))
            _register_field(context, step.get("to_column"))
        for join_key in _as_list(rel.get("join_keys")):
            _register_field(context, join_key.get("from"))
            _register_field(context, join_key.get("to"))

    # Shared technical fields are common in snapshot and platform tables; they
    # are not business defaults and do not encode a tenant-specific口径.
    context.all_fields.update({"id", "ds", "created_at", "updated_at"})
    return context


def strip_literals(sql: str) -> str:
    without_strings = re.sub(r"'([^']|'')*'", "''", str(sql or ""))
    return re.sub(r'"([^"]|"")*"', '""', without_strings)


def extract_cte_names(sql: str) -> set[str]:
    if not re.search(r"\bWITH\b", sql, flags=re.IGNORECASE):
        return set()
    return set(re.findall(r"(?:\bWITH\b|,)\s+([a-zA-Z_]\w*)\s+AS\s*\(", sql, flags=re.IGNORECASE))


def extract_tables_from_sql(sql: str) -> set[str]:
    cleaned = strip_literals(sql)
    table_pattern = r"(?:FROM|JOIN)\s+`?([a-zA-Z_]\w*(?:\.[a-zA-Z_]\w*)?)`?"
    tables = {match for match in re.findall(table_pattern, cleaned, flags=re.IGNORECASE)}
    return {table for table in tables if table.lower() not in SQL_KEYWORDS}


def extract_bare_tables(sql: str) -> set[str]:
    cte_names = extract_cte_names(sql)
    return {
        table
        for table in extract_tables_from_sql(sql)
        if "." not in table and table not in cte_names
    }


def extract_fields_from_sql(sql: str) -> set[str]:
    cleaned = strip_literals(sql)
    fields: set[str] = set()

    fields.update(re.findall(r"\b[a-zA-Z_]\w*\.([a-zA-Z_]\w*)\b", cleaned))

    select_match = re.search(r"\bSELECT\b(.*?)\bFROM\b", cleaned, flags=re.IGNORECASE | re.DOTALL)
    if select_match:
        select_part = select_match.group(1)
        for token in re.findall(r"\b([a-zA-Z_]\w*)\b", select_part):
            fields.add(token)

    for token in re.findall(
        r"(?:WHERE|AND|OR|ON|GROUP\s+BY|ORDER\s+BY|HAVING|,)\s+([a-zA-Z_]\w*)",
        cleaned,
        flags=re.IGNORECASE,
    ):
        fields.add(token)

    aliases = set(re.findall(r"\bAS\s+([a-zA-Z_]\w*)", cleaned, flags=re.IGNORECASE))
    table_suffixes = {table.split(".")[-1] for table in extract_tables_from_sql(sql)}
    return {
        field
        for field in fields
        if field.lower() not in SQL_KEYWORDS
        and field not in aliases
        and field not in table_suffixes
        and not field.endswith(("_cnt", "_sum", "_avg", "_max", "_min"))
    }


def validate_sql(sql: str, context: ValidationContext | None = None) -> ValidationResult:
    context = context or ValidationContext()
    result = ValidationResult()
    statement = str(sql or "").strip()

    try:
        ensure_read_only(statement)
        result.ok("只读 SQL 检查通过")
    except Exception as exc:
        result.error(f"只读 SQL 检查失败: {exc}")
        return result

    tables = extract_tables_from_sql(statement)
    if not tables:
        result.warn("未提取到 FROM/JOIN 表名，请确认是否为 SHOW/DESC/EXPLAIN 或简单表达式查询。")
    else:
        result.ok(f"提取到 {len(tables)} 张表")

    bare_tables = extract_bare_tables(statement)
    if bare_tables:
        result.error(f"表名缺少 schema 前缀: {', '.join(sorted(bare_tables))}")
    else:
        result.ok("表名 schema 前缀检查通过")

    if context.tables:
        unknown_tables = tables - context.tables - extract_cte_names(statement)
        if unknown_tables:
            result.error(f"未在 ontology 中注册的表: {', '.join(sorted(unknown_tables))}")
        else:
            result.ok("ontology 表名检查通过")

    if re.search(r"\bSELECT\s+\*", statement, flags=re.IGNORECASE):
        result.error("禁止 SELECT *，请明确选择字段")
    else:
        result.ok("无 SELECT *")

    relative_dates = re.findall(r"(今天|昨天|本季度|本月|上个月|最近|当前|当日|本周|上周)", statement)
    if relative_dates:
        result.error(f"SQL 中包含相对日期词: {', '.join(sorted(set(relative_dates)))}")
    else:
        result.ok("日期绝对化检查通过")

    placeholders = re.findall(r"\{[a-zA-Z_]+\}", statement)
    if placeholders:
        result.error(f"未替换的占位符: {', '.join(sorted(set(placeholders)))}")
    else:
        result.ok("无残留占位符")

    raw_reserved_sql = re.findall(r"(?:SELECT|,)\s+(?:\w+\.)?(sql)\b(?!\s*\()", statement, flags=re.IGNORECASE)
    if raw_reserved_sql and "`sql`" not in statement:
        result.error("`sql` 是 MySQL 保留字，SELECT 中必须写成 `sql`")

    if context.all_fields:
        fields = extract_fields_from_sql(statement)
        unknown_fields = fields - context.all_fields
        if unknown_fields:
            result.warn(f"ontology 中未登记的字段，请核实: {', '.join(sorted(unknown_fields))}")
        elif fields:
            result.ok("ontology 字段检查通过")

    return result


def _read_sql(args: argparse.Namespace) -> str:
    if args.file:
        return Path(args.file).read_text(encoding="utf-8")
    if args.stdin:
        return sys.stdin.read()
    return str(args.sql or "")


def main():
    parser = argparse.ArgumentParser(description="Validate read-only SQL before run_sql.py execution")
    parser.add_argument("sql", nargs="?", help="SQL statement to validate")
    parser.add_argument("--file", "-f", help="Read SQL from a file")
    parser.add_argument("--stdin", action="store_true", help="Read SQL from stdin")
    parser.add_argument("--ontology", help="Optional business ontology JSON for table/field checks")
    parser.add_argument("--json", action="store_true", help="Print JSON output")
    args = parser.parse_args()

    statement = _read_sql(args).strip()
    context = load_ontology(args.ontology)
    result = validate_sql(statement, context)
    if args.json:
        payload = result.to_payload()
        payload["ontology"] = context.source or None
        print_json(payload)
    else:
        print(result.report())
    sys.exit(0 if result.is_valid else 1)


if __name__ == "__main__":
    main()
