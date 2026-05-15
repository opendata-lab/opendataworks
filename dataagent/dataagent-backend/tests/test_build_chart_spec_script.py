from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".claude" / "skills" / "opendataworks-platform-tools" / "scripts" / "build_chart_spec.py"


def _run_chart_spec(payload: dict, *extra_args: str) -> dict:
    result = subprocess.run(
        [sys.executable, str(SCRIPT), *extra_args, "--input", json.dumps(payload, ensure_ascii=False)],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


def test_comparison_defaults_to_bar_when_not_explicitly_pie():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"layer": "ODS", "table_count": 28},
            {"layer": "DWD", "table_count": 3},
            {"layer": "DWS", "table_count": 1},
        ],
    }

    chart = _run_chart_spec(payload)

    assert chart["kind"] == "chart_spec"
    assert chart["version"] == 1
    assert chart["chart_type"] == "bar"
    assert chart["x_field"] == "layer"


def test_share_can_request_pie_explicitly():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"engine": "dolphin", "task_count": 24},
            {"engine": "dinky", "task_count": 8},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "pie")

    assert chart["version"] == 1
    assert chart["chart_type"] == "pie"
    assert chart["x_field"] == "engine"


def test_share_with_multiple_numeric_fields_still_builds_pie():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"操作类型": "deploy", "记录数": 33, "占比百分比": 68.75},
            {"操作类型": "online", "记录数": 9, "占比百分比": 18.75},
            {"操作类型": "offline", "记录数": 6, "占比百分比": 12.5},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "pie", "--category-field", "操作类型", "--value-field", "记录数")

    assert chart["version"] == 1
    assert chart["chart_type"] == "pie"
    assert chart["x_field"] == "操作类型"
    assert chart["series"][0]["field"] == "记录数"


def test_trend_can_request_line_explicitly():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"stat_day": "2026-03-01", "publish_count": 3},
            {"stat_day": "2026-03-02", "publish_count": 5},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "line")

    assert chart["version"] == 1
    assert chart["chart_type"] == "line"
    assert chart["x_field"] == "stat_day"


def test_table_can_be_requested_explicitly():
    payload = {
        "kind": "sql_execution",
        "columns": ["workflow_id", "status"],
        "rows": [
            {"workflow_id": 173, "status": "success"},
            {"workflow_id": 172, "status": "failed"},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "table", "--title", "最近工作流发布记录")

    assert chart["version"] == 1
    assert chart["chart_type"] == "table"
    assert chart["columns"] == ["workflow_id", "status"]
    assert chart["dataset"][0]["workflow_id"] == 173


def test_legacy_chart_arguments_are_supported():
    data = [
        {"操作类型": "deploy", "记录数": 33, "占比百分比": 68.75},
        {"操作类型": "online", "记录数": 9, "占比百分比": 18.75},
        {"操作类型": "offline", "记录数": 6, "占比百分比": 12.5},
    ]
    result = subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--chart-type",
            "pie",
            "--title",
            "各工作流发布操作类型占比",
            "--data",
            json.dumps(data, ensure_ascii=False),
            "--category-field",
            "操作类型",
            "--value-field",
            "记录数",
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    chart = json.loads(result.stdout)

    assert chart["version"] == 1
    assert chart["chart_type"] == "pie"
    assert chart["title"] == "各工作流发布操作类型占比"


def test_x_y_field_aliases_are_supported():
    data = [
        {"stat_day": "2026-02-26", "publish_cnt": 4},
        {"stat_day": "2026-02-27", "publish_cnt": 0},
    ]
    result = subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--chart-type",
            "line",
            "--data",
            json.dumps(data, ensure_ascii=False),
            "--x-field",
            "stat_day",
            "--y-field",
            "publish_cnt",
        ],
        check=True,
        capture_output=True,
        text=True,
    )

    chart = json.loads(result.stdout)

    assert chart["version"] == 1
    assert chart["chart_type"] == "line"
    assert chart["x_field"] == "stat_day"
    assert chart["series"][0]["field"] == "publish_cnt"
