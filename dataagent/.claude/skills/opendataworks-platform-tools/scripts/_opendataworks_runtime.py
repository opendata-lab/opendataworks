from __future__ import annotations

import base64
import json
import os
import re
import subprocess
import sys
from datetime import date, datetime
from decimal import Decimal
from pathlib import Path
from typing import Any

READ_ONLY_PREFIXES = ("SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN")
PLATFORM_TOOLS_SKILL_FOLDER = "opendataworks-platform-tools"


def env_int(name: str, default: int) -> int:
    try:
        return int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        return default


def skill_root_dir() -> Path:
    configured = str(os.getenv("DATAAGENT_PLATFORM_SKILL_ROOT", "")).strip()
    if configured:
        return Path(configured).expanduser()
    enabled_roots_raw = str(os.getenv("DATAAGENT_ENABLED_SKILL_ROOTS", "")).strip()
    if enabled_roots_raw:
        try:
            enabled_roots = json.loads(enabled_roots_raw)
        except json.JSONDecodeError:
            enabled_roots = {}
        if isinstance(enabled_roots, dict):
            platform_root = str(enabled_roots.get(PLATFORM_TOOLS_SKILL_FOLDER) or "").strip()
            if platform_root:
                return Path(platform_root).expanduser()
    return Path(__file__).resolve().parents[1]


def metadata_cli_bin() -> str:
    return str(skill_root_dir() / "bin" / "odw-cli")


def metadata_cli_command(subcommand: str, **options: Any) -> list[str]:
    cli_path = Path(metadata_cli_bin())
    if not cli_path.is_file():
        raise RuntimeError(
            f"metadata cli 不存在: {cli_path}。请先由用户自行安装到该路径后再重试。"
        )

    # 部署时 bind mount 可能丢失执行位，退回 sh 直读脚本即可继续运行。
    command = [str(cli_path)] if os.access(cli_path, os.X_OK) else ["sh", str(cli_path)]
    command.append(str(subcommand).strip())

    for key, value in options.items():
        if value is None:
            continue
        text = str(value).strip()
        if not text:
            continue
        command.extend([f"--{str(key).replace('_', '-')}", text])

    return command


def runtime_data_scope_header() -> str:
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
    try:
        parsed = json.loads(raw_scope)
    except json.JSONDecodeError:
        return ""
    payload = json.dumps(parsed, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return base64.urlsafe_b64encode(payload.encode("utf-8")).decode("ascii").rstrip("=")


def serializable_value(value: Any) -> Any:
    if value is None or isinstance(value, (int, float, str, bool)):
        return value
    if isinstance(value, Decimal):
        return float(value)
    if isinstance(value, (datetime, date)):
        return value.isoformat()
    return str(value)


def serializable_rows(rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {str(key): serializable_value(val) for key, val in dict(row).items()}
        for row in rows
    ]


def print_json(payload: dict[str, Any]):
    print(json.dumps(payload, ensure_ascii=False, indent=2))


def error_payload(kind: str, message: str, **extra: Any) -> dict[str, Any]:
    payload = {"kind": kind, "error": message}
    payload.update(extra)
    return payload


def ensure_read_only(sql: str):
    statement = str(sql or "").strip()
    if not statement:
        raise ValueError("SQL 为空")
    upper = statement.lstrip().upper()
    if not upper.startswith(READ_ONLY_PREFIXES):
        raise ValueError("仅允许只读 SQL")
    if re.search(r"(^|\\s)(INSERT|UPDATE|DELETE|DROP|TRUNCATE|ALTER|CREATE|REPLACE)\\b", upper):
        raise ValueError("检测到非只读关键字")


def call_metadata_cli(subcommand: str, **options: Any) -> Any:
    command = metadata_cli_command(subcommand, **options)
    cli_path = metadata_cli_bin()
    scope_header = runtime_data_scope_header()
    cli_env = None
    if scope_header:
        cli_env = dict(os.environ)
        cli_env["ODW_AGENT_DATA_SCOPE_HEADER"] = scope_header

    try:
        completed = subprocess.run(
            command,
            check=False,
            capture_output=True,
            text=True,
            env=cli_env,
        )
    except PermissionError as exc:
        # 离线部署包常见场景是文件保留了 +x，但挂载介质本身是 noexec；
        # 这种情况下直接 exec 会被拒绝，退回 sh 解释执行仍可继续工作。
        if command[:1] == ["sh"]:
            raise RuntimeError(
                f"metadata cli 不可执行: {cli_path}。请先由用户修正该路径下文件权限后再重试。"
            ) from exc
        completed = subprocess.run(
            ["sh", cli_path, *command[1:]],
            check=False,
            capture_output=True,
            text=True,
            env=cli_env,
        )

    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout or "").strip()
        raise RuntimeError(detail or f"metadata cli 执行失败: {' '.join(command[:2])}")

    raw_output = str(completed.stdout or "").strip()
    if not raw_output:
        raise RuntimeError("metadata cli 未返回 JSON")
    try:
        return json.loads(raw_output)
    except json.JSONDecodeError as exc:
        raise RuntimeError("metadata cli 返回的不是合法 JSON") from exc


def resolve_datasource(database: str, preferred_engine: str | None = None) -> dict[str, Any]:
    target_database = str(database or "").strip()
    if not target_database:
        raise ValueError("database 不能为空")

    payload = call_metadata_cli(
        "resolve-datasource",
        database=target_database,
        preferred_engine=preferred_engine,
    )
    return {
        "engine": payload.get("engine"),
        "database": payload.get("database"),
        "source_type": payload.get("source_type"),
        "cluster_id": payload.get("cluster_id"),
        "cluster_name": payload.get("cluster_name"),
        "resolved_by": payload.get("resolved_by"),
    }


def get_lineage(
    table: str | None = None,
    db_name: str | None = None,
    table_id: int | None = None,
    depth: int | None = None,
) -> dict[str, Any]:
    target_table = str(table or "").strip()
    target_db_name = str(db_name or "").strip()
    if table_id is None and not target_table:
        raise ValueError("table 或 table_id 至少提供一个")

    payload = call_metadata_cli(
        "lineage",
        table=target_table,
        db_name=target_db_name,
        table_id=table_id,
        depth=depth,
    )
    return {
        "kind": payload.get("kind"),
        "db_name": payload.get("db_name"),
        "table": payload.get("table"),
        "table_id": payload.get("table_id"),
        "depth": payload.get("depth"),
        "lineage": payload.get("lineage") or [],
        "error": payload.get("error"),
    }


def get_table_ddl(
    database: str | None = None,
    table: str | None = None,
    table_id: int | None = None,
) -> dict[str, Any]:
    target_database = str(database or "").strip()
    target_table = str(table or "").strip()
    if table_id is None and (not target_database or not target_table):
        raise ValueError("table_id 或 database + table 至少提供一组")

    payload = call_metadata_cli(
        "ddl",
        database=target_database,
        table=target_table,
        table_id=table_id,
    )
    return {
        "kind": payload.get("kind"),
        "database": payload.get("database"),
        "table_name": payload.get("table_name"),
        "table_id": payload.get("table_id"),
        "cluster_id": payload.get("cluster_id"),
        "cluster_name": payload.get("cluster_name"),
        "engine": payload.get("engine"),
        "source_type": payload.get("source_type"),
        "resolved_by": payload.get("resolved_by"),
        "table_comment": payload.get("table_comment"),
        "fields": payload.get("fields") or [],
        "ddl": payload.get("ddl"),
    }


def query_readonly(
    database: str,
    sql: str,
    preferred_engine: str | None = None,
    limit: int | None = None,
    timeout_seconds: int | None = None,
) -> dict[str, Any]:
    target_database = str(database or "").strip()
    if not target_database:
        raise ValueError("database 不能为空")

    statement = str(sql or "").strip()
    ensure_read_only(statement)

    payload = call_metadata_cli(
        "query-readonly",
        database=target_database,
        sql=statement,
        preferred_engine=preferred_engine,
        limit=limit,
        timeout_seconds=timeout_seconds,
    )
    return {
        "kind": payload.get("kind"),
        "engine": payload.get("engine"),
        "database": payload.get("database"),
        "sql": payload.get("sql"),
        "limit": payload.get("limit"),
        "rows": payload.get("rows") or [],
        "row_count": payload.get("row_count"),
        "has_more": payload.get("has_more"),
        "duration_ms": payload.get("duration_ms"),
        "truncated_by_size": payload.get("truncated_by_size"),
        "notice": payload.get("notice"),
    }


def load_json_input(raw: str | None = None, file_path: str | None = None) -> Any:
    if raw:
        return json.loads(raw)
    if file_path:
        with open(file_path, "r", encoding="utf-8") as handle:
            return json.load(handle)
    data = sys.stdin.read().strip()
    if not data:
        raise ValueError("未提供 JSON 输入")
    return json.loads(data)
