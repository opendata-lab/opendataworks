from __future__ import annotations

import importlib.util
import os
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
SKILL_SCRIPTS_ROOT = BACKEND_ROOT.parent / ".claude" / "skills" / "dataagent-nl2sql" / "scripts"
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))
if str(SKILL_SCRIPTS_ROOT) not in sys.path:
    sys.path.insert(0, str(SKILL_SCRIPTS_ROOT))


def _load_skill_module(file_name: str, module_name: str):
    module_path = SKILL_SCRIPTS_ROOT / file_name
    spec = importlib.util.spec_from_file_location(module_name, module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec is not None and spec.loader is not None
    spec.loader.exec_module(module)
    return module


def _load_runtime_module():
    return _load_skill_module("_opendataworks_runtime.py", "dataagent_odw_runtime")


def _bind_existing_cli(monkeypatch, runtime):
    monkeypatch.setattr(runtime, "metadata_cli_bin", lambda: str(SKILL_SCRIPTS_ROOT.parent / "bin" / "odw-cli"))


def test_resolve_datasource_uses_backend_cli(monkeypatch):
    runtime = _load_runtime_module()
    captured = {}

    def fake_call_metadata_cli(subcommand, **options):
        captured["subcommand"] = subcommand
        captured["options"] = options
        return {
            "engine": "doris",
            "database": "doris_ods",
            "source_type": "DORIS",
            "cluster_id": 8,
            "cluster_name": "cluster-a",
            "resolved_by": "readonly_user",
        }

    monkeypatch.setattr(runtime, "call_metadata_cli", fake_call_metadata_cli)

    result = runtime.resolve_datasource("doris_ods", preferred_engine="doris")

    assert captured["subcommand"] == "resolve-datasource"
    assert captured["options"]["database"] == "doris_ods"
    assert captured["options"]["preferred_engine"] == "doris"
    assert set(result.keys()) == {"engine", "database", "source_type", "cluster_id", "cluster_name", "resolved_by"}
    assert result["cluster_id"] == 8
    assert result["resolved_by"] == "readonly_user"


def test_get_table_ddl_uses_backend_cli(monkeypatch):
    runtime = _load_runtime_module()
    captured = {}

    def fake_call_metadata_cli(subcommand, **options):
        captured["subcommand"] = subcommand
        captured["options"] = options
        return {
            "kind": "table_ddl",
            "database": "opendataworks",
            "table_name": "workflow_publish_record",
            "table_id": 12,
            "engine": "mysql",
            "fields": [{"field_name": "workflow_id"}],
            "ddl": "CREATE TABLE workflow_publish_record (...)",
        }

    monkeypatch.setattr(runtime, "call_metadata_cli", fake_call_metadata_cli)

    result = runtime.get_table_ddl(database="opendataworks", table="workflow_publish_record")

    assert captured["subcommand"] == "ddl"
    assert captured["options"]["database"] == "opendataworks"
    assert captured["options"]["table"] == "workflow_publish_record"
    assert result["kind"] == "table_ddl"
    assert result["table_name"] == "workflow_publish_record"
    assert result["fields"] == [{"field_name": "workflow_id"}]


def test_get_lineage_uses_backend_cli(monkeypatch):
    runtime = _load_runtime_module()
    captured = {}

    def fake_call_metadata_cli(subcommand, **options):
        captured["subcommand"] = subcommand
        captured["options"] = options
        return {
            "kind": "lineage_snapshot",
            "db_name": "doris_ods",
            "table": "some_table",
            "table_id": 12,
            "depth": 2,
            "lineage": [{"lineage_type": "upstream"}],
        }

    monkeypatch.setattr(runtime, "call_metadata_cli", fake_call_metadata_cli)

    result = runtime.get_lineage(table="some_table", db_name="doris_ods", depth=2)

    assert captured["subcommand"] == "lineage"
    assert captured["options"]["table"] == "some_table"
    assert captured["options"]["db_name"] == "doris_ods"
    assert captured["options"]["depth"] == 2
    assert result["kind"] == "lineage_snapshot"
    assert result["lineage"] == [{"lineage_type": "upstream"}]


def test_query_readonly_uses_backend_cli(monkeypatch):
    runtime = _load_runtime_module()
    captured = {}

    def fake_call_metadata_cli(subcommand, **options):
        captured["subcommand"] = subcommand
        captured["options"] = options
        return {
            "kind": "query_result",
            "engine": "mysql",
            "database": "opendataworks",
            "sql": "SELECT 1",
            "limit": 50,
            "rows": [{"value": 1}],
            "row_count": 1,
            "has_more": False,
            "duration_ms": 12,
        }

    monkeypatch.setattr(runtime, "call_metadata_cli", fake_call_metadata_cli)

    result = runtime.query_readonly(
        database="opendataworks",
        sql="SELECT 1",
        preferred_engine="mysql",
        limit=50,
        timeout_seconds=20,
    )

    assert captured["subcommand"] == "query-readonly"
    assert captured["options"]["database"] == "opendataworks"
    assert captured["options"]["sql"] == "SELECT 1"
    assert captured["options"]["preferred_engine"] == "mysql"
    assert captured["options"]["limit"] == 50
    assert captured["options"]["timeout_seconds"] == 20
    assert result["rows"] == [{"value": 1}]
    assert result["duration_ms"] == 12


def test_call_metadata_cli_rejects_invalid_json(monkeypatch):
    runtime = _load_runtime_module()
    _bind_existing_cli(monkeypatch, runtime)

    def fake_run(command, check, capture_output, text):
        return SimpleNamespace(returncode=0, stdout="not-json", stderr="")

    monkeypatch.setattr(runtime.subprocess, "run", fake_run)

    with pytest.raises(RuntimeError, match="不是合法 JSON"):
        runtime.call_metadata_cli("inspect", keyword="工作流")


def test_call_metadata_cli_surfaces_non_zero_exit(monkeypatch):
    runtime = _load_runtime_module()
    _bind_existing_cli(monkeypatch, runtime)

    def fake_run(command, check, capture_output, text):
        return SimpleNamespace(returncode=22, stdout="", stderr="agent api token 无效")

    monkeypatch.setattr(runtime.subprocess, "run", fake_run)

    with pytest.raises(RuntimeError, match="agent api token 无效"):
        runtime.call_metadata_cli("export", kind="tables")


def test_metadata_cli_bin_defaults_to_bundled_skill_cli(monkeypatch):
    runtime = _load_runtime_module()
    monkeypatch.delenv("DATAAGENT_SKILL_ROOT", raising=False)

    cli_path = Path(runtime.metadata_cli_bin())

    assert cli_path.name == "odw-cli"
    assert cli_path.parent.name == "bin"
    assert str(cli_path) == str(SKILL_SCRIPTS_ROOT.parent / "bin" / "odw-cli")


def test_call_metadata_cli_non_executable_bin_falls_back_to_sh(monkeypatch):
    runtime = _load_runtime_module()
    _bind_existing_cli(monkeypatch, runtime)
    cli_path = Path(runtime.metadata_cli_bin())
    captured = {}

    def fake_access(path, mode):
        if Path(path) == cli_path and mode == runtime.os.X_OK:
            return False
        return os.access(path, mode)

    def fake_run(command, check, capture_output, text):
        captured["command"] = command
        return SimpleNamespace(returncode=0, stdout='{"kind":"ok"}', stderr="")

    monkeypatch.setattr(runtime.os, "access", fake_access)
    monkeypatch.setattr(runtime.subprocess, "run", fake_run)

    payload = runtime.call_metadata_cli("inspect", keyword="工作流")

    assert payload == {"kind": "ok"}
    assert captured["command"][:3] == ["sh", str(cli_path), "inspect"]
    assert captured["command"][3:] == ["--keyword", "工作流"]


def test_call_metadata_cli_permission_denied_falls_back_to_sh(monkeypatch):
    runtime = _load_runtime_module()
    _bind_existing_cli(monkeypatch, runtime)
    cli_path = Path(runtime.metadata_cli_bin())
    captured = {"calls": 0}

    def fake_run(command, check, capture_output, text):
        captured["calls"] += 1
        captured["command"] = command
        if captured["calls"] == 1:
            raise PermissionError("Permission denied")
        return SimpleNamespace(returncode=0, stdout='{"kind":"ok"}', stderr="")

    monkeypatch.setattr(runtime.subprocess, "run", fake_run)

    payload = runtime.call_metadata_cli("inspect", keyword="工作流")

    assert payload == {"kind": "ok"}
    assert captured["calls"] == 2
    assert captured["command"][:3] == ["sh", str(cli_path), "inspect"]
    assert captured["command"][3:] == ["--keyword", "工作流"]


def test_call_metadata_cli_missing_binary_requires_user_install(monkeypatch):
    runtime = _load_runtime_module()
    missing_path = SKILL_SCRIPTS_ROOT.parent / "bin" / "missing-odw-cli"

    monkeypatch.setattr(runtime, "metadata_cli_bin", lambda: str(missing_path))

    with pytest.raises(RuntimeError, match="请先由用户自行安装到该路径后再重试"):
        runtime.call_metadata_cli("inspect", keyword="工作流")


def test_run_sql_script_delegates_to_query_cli(monkeypatch):
    module = _load_skill_module("run_sql.py", "dataagent_run_sql")
    captured = {}
    payload = {}

    def fake_query_readonly(database, sql, preferred_engine=None, limit=None, timeout_seconds=None):
        captured["database"] = database
        captured["sql"] = sql
        captured["preferred_engine"] = preferred_engine
        captured["limit"] = limit
        captured["timeout_seconds"] = timeout_seconds
        return {
            "kind": "query_result",
            "engine": "mysql",
            "database": database,
            "sql": sql,
            "rows": [{"value": 1}],
            "row_count": 1,
            "has_more": False,
            "duration_ms": 9,
        }

    monkeypatch.setattr(module, "query_readonly", fake_query_readonly)
    monkeypatch.setattr(module, "print_json", lambda value: payload.setdefault("result", value))
    monkeypatch.setenv("DATAAGENT_SQL_READ_TIMEOUT_SECONDS", "45")
    monkeypatch.setattr(sys, "argv", ["run_sql.py", "--database", "opendataworks", "--engine", "mysql", "--sql", "SELECT 1"])

    module.main()

    assert captured == {
        "database": "opendataworks",
        "sql": "SELECT 1",
        "preferred_engine": "mysql",
        "limit": 1000,
        "timeout_seconds": 45,
    }
    assert payload["result"]["kind"] == "sql_execution"
    assert payload["result"]["engine"] == "mysql"
    assert payload["result"]["database"] == "opendataworks"
    assert payload["result"]["rows"] == [{"value": 1}]


def test_run_sql_script_blocks_lineage_sql_before_query(monkeypatch):
    module = _load_skill_module("run_sql.py", "dataagent_run_sql")
    payload = {}
    captured = {"called": False}

    def fake_query_readonly(database, sql, preferred_engine=None, limit=None, timeout_seconds=None):
        captured["called"] = True
        return {}

    monkeypatch.setattr(module, "query_readonly", fake_query_readonly)
    monkeypatch.setattr(module, "print_json", lambda value: payload.setdefault("result", value))
    monkeypatch.setenv("DATAAGENT_ORIGINAL_QUESTION", "workflow_publish_record 的上游表有哪些")
    monkeypatch.delenv("DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK", raising=False)
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "run_sql.py",
            "--database",
            "opendataworks",
            "--engine",
            "mysql",
            "--sql",
            "SELECT lineage_type, upstream_table_id FROM opendataworks.data_lineage LIMIT 10",
        ],
    )

    module.main()

    assert captured["called"] is False
    assert payload["result"]["kind"] == "sql_execution"
    assert "请先使用 `mcp__portal__portal_get_lineage`" in payload["result"]["error"]
    assert "DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1" in payload["result"]["error"]


def test_run_sql_script_allows_lineage_sql_with_explicit_fallback(monkeypatch):
    module = _load_skill_module("run_sql.py", "dataagent_run_sql")
    captured = {}
    payload = {}

    def fake_query_readonly(database, sql, preferred_engine=None, limit=None, timeout_seconds=None):
        captured["database"] = database
        captured["sql"] = sql
        captured["preferred_engine"] = preferred_engine
        captured["limit"] = limit
        captured["timeout_seconds"] = timeout_seconds
        return {
            "kind": "query_result",
            "engine": "mysql",
            "database": database,
            "sql": sql,
            "rows": [{"lineage_type": "upstream"}],
            "row_count": 1,
            "has_more": False,
            "duration_ms": 7,
        }

    monkeypatch.setattr(module, "query_readonly", fake_query_readonly)
    monkeypatch.setattr(module, "print_json", lambda value: payload.setdefault("result", value))
    monkeypatch.setenv("DATAAGENT_ORIGINAL_QUESTION", "workflow_publish_record 的上游表有哪些")
    monkeypatch.setenv("DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK", "1")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "run_sql.py",
            "--database",
            "opendataworks",
            "--engine",
            "mysql",
            "--sql",
            "SELECT lineage_type, upstream_table_id FROM opendataworks.data_lineage LIMIT 10",
        ],
    )

    module.main()

    assert captured["database"] == "opendataworks"
    assert "data_lineage" in captured["sql"]
    assert captured["limit"] == 1000
    assert payload["result"]["kind"] == "sql_execution"
    assert payload["result"]["rows"] == [{"lineage_type": "upstream"}]


def test_get_table_ddl_script_delegates_to_cli(monkeypatch):
    module = _load_skill_module("get_table_ddl.py", "dataagent_get_table_ddl")
    captured = {}
    payload = {}

    def fake_get_table_ddl(database=None, table=None, table_id=None):
        captured["database"] = database
        captured["table"] = table
        captured["table_id"] = table_id
        return {
            "kind": "table_ddl",
            "database": database,
            "table_name": table,
            "table_id": table_id,
            "fields": [{"field_name": "workflow_id"}],
            "ddl": "CREATE TABLE workflow_publish_record (...)",
        }

    monkeypatch.setattr(module, "get_table_ddl", fake_get_table_ddl)
    monkeypatch.setattr(module, "print_json", lambda value: payload.setdefault("result", value))
    monkeypatch.setattr(sys, "argv", ["get_table_ddl.py", "--database", "opendataworks", "--table", "workflow_publish_record"])

    module.main()

    assert captured == {
        "database": "opendataworks",
        "table": "workflow_publish_record",
        "table_id": None,
    }
    assert payload["result"]["kind"] == "table_ddl"
    assert payload["result"]["table_name"] == "workflow_publish_record"


def test_get_lineage_script_delegates_to_cli(monkeypatch):
    module = _load_skill_module("get_lineage.py", "dataagent_get_lineage")
    captured = {}
    payload = {}

    def fake_get_lineage(table=None, db_name=None, table_id=None, depth=None):
        captured["table"] = table
        captured["db_name"] = db_name
        captured["table_id"] = table_id
        captured["depth"] = depth
        return {
            "kind": "lineage_snapshot",
            "db_name": db_name,
            "table": table,
            "table_id": table_id,
            "depth": depth,
            "lineage": [{"lineage_type": "upstream"}],
        }

    monkeypatch.setattr(module, "get_lineage", fake_get_lineage)
    monkeypatch.setattr(module, "print_json", lambda value: payload.setdefault("result", value))
    monkeypatch.setattr(sys, "argv", ["get_lineage.py", "--table", "some_table", "--db-name", "doris_ods", "--depth", "2"])

    module.main()

    assert captured == {
        "table": "some_table",
        "db_name": "doris_ods",
        "table_id": None,
        "depth": 2,
    }
    assert payload["result"]["kind"] == "lineage_snapshot"
    assert payload["result"]["table"] == "some_table"
