"""Every registered methodology must validate statically and run under mock."""

import json
import subprocess
import sys
from pathlib import Path

import pytest

from engine import _platform_skill_root, run_methodology
from registry import coerce_params, load_registry
from validate_methodology import validate_methodology

SKILL_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = SKILL_ROOT / "scripts"
REGISTRY = load_registry()

# Mock tables for every query node in the registry, so the whole registry is
# exercised end to end without touching a data store.
MOCK_TABLES = {
    "current": {"rows": [{"layer": "ODS", "table_cnt": 12}, {"layer": "DWD", "table_cnt": 8}]},
    "previous": {"rows": [{"layer": "ODS", "table_cnt": 6}, {"layer": "DWD", "table_cnt": 8}]},
    "top_owners": {"rows": [{"owner": "alice", "total_cnt": 30}, {"owner": "bob", "total_cnt": 12}]},
    "recent_by_owner": {"rows": [{"owner": "alice", "recent_cnt": 6}]},
    "publish_all": {
        "rows": [
            {"stat_date": "2026-07-02", "publish_cnt": 10, "failed_cnt": 2},
            {"stat_date": "2026-07-01", "publish_cnt": 4, "failed_cnt": 0},
        ]
    },
    "publish_scoped": {"rows": [{"stat_date": "2026-07-01", "publish_cnt": 3, "failed_cnt": 1}]},
}


def _run_script(name, *args):
    return subprocess.run(
        [sys.executable, str(SCRIPTS / name), *args],
        cwd=str(SCRIPTS),
        check=False,
        text=True,
        capture_output=True,
    )


def test_the_registry_is_not_empty():
    assert REGISTRY, "注册表为空；至少要有可回归的方法论"


@pytest.mark.parametrize("identifier", sorted(REGISTRY))
def test_each_methodology_passes_static_validation(identifier):
    report = validate_methodology(REGISTRY[identifier], registry=REGISTRY)
    assert report.ok, report.errors


@pytest.mark.parametrize("identifier", sorted(REGISTRY))
def test_each_methodology_reaches_its_target_under_mock(identifier):
    methodology = REGISTRY[identifier]
    params, missing = coerce_params(list(methodology.get("params") or []), {})
    assert not missing, f"{identifier} 的默认参数不足以完成一次 mock 运行: {missing}"

    result, trace = run_methodology(methodology, params, registry=REGISTRY, mock=MOCK_TABLES)

    assert trace, f"{identifier} 没有执行任何节点"
    assert result.columns, f"{identifier} 的 target 没有产出任何列"
    declared = methodology.get("output_fields") or []
    if declared:
        assert list(result.columns) == list(declared), (
            f"{identifier} 的实际输出列与 output_fields 声明不一致"
        )


@pytest.mark.parametrize("identifier", sorted(REGISTRY))
def test_each_methodology_sql_passes_the_platform_validator(identifier):
    """Bind with stub parameters and hand the SQL to platform-tools.

    This catches what a methodology-only check cannot — engine-side rules such as
    the schema-prefix requirement. It is skipped rather than silently passing when
    platform-tools is not installed, because a check that quietly does nothing is
    how such a defect hides.
    """
    if not (_platform_skill_root() / "scripts" / "validate_sql.py").is_file():
        pytest.skip("opendataworks-platform-tools 未安装，无法做 SQL 校验")

    report = validate_methodology(REGISTRY[identifier], registry=REGISTRY, check_sql=True)

    assert report.ok, report.errors
    skipped = [warning for warning in report.warnings if "校验被跳过" in warning]
    assert not skipped, f"SQL 校验被跳过而不是真的执行了: {skipped}"


@pytest.mark.parametrize("identifier", sorted(REGISTRY))
def test_each_methodology_declares_a_caliber_and_an_owner(identifier):
    methodology = REGISTRY[identifier]
    assert len(str(methodology["caliber"]).strip()) >= 10, "caliber 会随结果回给用户，必须写清楚"
    assert str(methodology["owner"]).strip()


def test_registry_ids_match_their_filenames():
    for path in sorted((SKILL_ROOT / "assets" / "registry").glob("*.json")):
        payload = json.loads(path.read_text(encoding="utf-8"))
        assert payload["id"] == path.stem, f"{path.name} 的 id 与文件名不一致"


# -- CLI contracts ----------------------------------------------------------

def test_validate_all_exits_zero_for_the_shipped_registry():
    completed = _run_script("validate_methodology.py", "--all")
    assert completed.returncode == 0, completed.stdout + completed.stderr
    payload = json.loads(completed.stdout)
    assert payload["valid"] is True
    assert payload["checked"] == len(REGISTRY)


def test_lookup_finds_a_methodology_from_a_chinese_question():
    completed = _run_script("lookup_methodology.py", "--query", "最近30天工作流发布次数趋势")
    assert completed.returncode == 0, completed.stderr
    payload = json.loads(completed.stdout)
    assert payload["kind"] == "methodology_lookup"
    assert payload["results"][0]["id"] == "workflow_publish_trend"


def test_lookup_tells_the_caller_to_fall_back_when_nothing_matches():
    completed = _run_script("lookup_methodology.py", "--query", "今天天气怎么样")
    payload = json.loads(completed.stdout)
    assert payload["matched"] == 0
    assert "回落" in payload["stop_reason"]


def test_run_reports_an_unknown_id_without_crashing():
    completed = _run_script("run_methodology.py", "--id", "definitely_absent")
    assert completed.returncode == 0, completed.stderr
    payload = json.loads(completed.stdout)
    assert payload["kind"] == "sql_execution"
    assert payload["error_code"] == "methodology_not_found"
    assert payload["result_state"] == "failed"


def test_run_rejects_a_value_outside_an_enum():
    completed = _run_script(
        "run_methodology.py", "--id", "workflow_publish_trend", "--params", '{"operation": "drop"}'
    )
    payload = json.loads(completed.stdout)
    assert payload["error_code"] == "param_rejected"


def test_run_rejects_an_undeclared_parameter():
    completed = _run_script(
        "run_methodology.py", "--id", "workflow_publish_trend", "--params", '{"ghost": 1}'
    )
    payload = json.loads(completed.stdout)
    assert payload["error_code"] == "param_rejected"


def test_run_emits_the_sql_execution_contract_under_mock(tmp_path):
    mock_path = tmp_path / "mock.json"
    mock_path.write_text(json.dumps(MOCK_TABLES), encoding="utf-8")
    completed = _run_script(
        "run_methodology.py",
        "--id",
        "table_growth_ratio",
        "--params",
        '{"days": 30}',
        "--mock",
        str(mock_path),
    )
    assert completed.returncode == 0, completed.stderr
    payload = json.loads(completed.stdout)

    assert payload["kind"] == "sql_execution"
    assert payload["result_state"] == "success"
    assert payload["methodology"]["id"] == "table_growth_ratio"
    assert payload["methodology"]["caliber"]
    assert payload["rows"][0]["growth"] == 1.0
    # The trace is the free per-node observability the declarative form buys.
    assert {entry["name"] for entry in payload["trace"]["nodes"]} == {"current", "previous", "growth"}


def test_conditional_pruning_is_visible_in_the_emitted_trace(tmp_path):
    mock_path = tmp_path / "mock.json"
    mock_path.write_text(json.dumps(MOCK_TABLES), encoding="utf-8")
    completed = _run_script(
        "run_methodology.py",
        "--id",
        "workflow_publish_trend",
        "--params",
        '{"days": 7}',
        "--mock",
        str(mock_path),
    )
    payload = json.loads(completed.stdout)
    executed = {entry["name"] for entry in payload["trace"]["nodes"]}

    assert "publish_all" in executed
    assert "publish_scoped" not in executed
    assert payload["trace"]["pruned"] == 1
