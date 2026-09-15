from __future__ import annotations

import json
import subprocess
import sys
from datetime import date, timedelta
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / ".claude" / "skills" / "chart-visualization" / "scripts" / "build_chart_spec.py"


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


def test_trend_chart_keeps_all_daily_rows():
    start = date(2024, 1, 1)
    rows = [
        {"stat_day": (start + timedelta(days=offset)).isoformat(), "publish_cnt": offset % 7}
        for offset in range(366)
    ]
    payload = {
        "kind": "sql_execution",
        "rows": rows,
        "has_more": False,
        "truncated_by_size": False,
    }

    chart = _run_chart_spec(payload, "--chart-type", "line")

    assert chart["version"] == 1
    assert chart["chart_type"] == "line"
    assert len(chart["dataset"]) == 366
    assert chart["dataset"][0]["stat_day"] == "2024-01-01"
    assert chart["dataset"][-1]["stat_day"] == "2024-12-31"


def test_chart_refuses_incomplete_sql_execution_rows():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"stat_day": "2026-01-01", "publish_cnt": 1},
            {"stat_day": "2026-01-02", "publish_cnt": 2},
        ],
        "has_more": True,
        "truncated_by_size": False,
    }

    chart = _run_chart_spec(payload, "--chart-type", "line")

    assert chart["kind"] == "chart_spec"
    assert chart["error"]
    assert "不完整数据" in chart["error"]
    assert chart["dataset"] == []
    assert chart["series"] == []


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


def test_area_chart_sets_area_flag_and_line_series():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"stat_day": "2026-03-01", "active_users": 120},
            {"stat_day": "2026-03-02", "active_users": 150},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "area")

    assert chart["chart_type"] == "area"
    assert chart["x_field"] == "stat_day"
    assert chart["area"] is True
    assert chart["series"][0]["type"] == "line"


def test_bar_chart_stack_flag_sets_stack_true():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"layer": "ODS", "a_count": 10, "b_count": 4},
            {"layer": "DWD", "a_count": 6, "b_count": 9},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "bar", "--stack")

    assert chart["chart_type"] == "bar"
    assert chart["stack"] is True
    assert len(chart["series"]) == 2


def test_scatter_chart_uses_numeric_x_and_y_series():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"table_rows": 100, "column_count": 12},
            {"table_rows": 250, "column_count": 18},
            {"table_rows": 80, "column_count": 7},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "scatter")

    assert chart["chart_type"] == "scatter"
    assert chart["x_field"] == "table_rows"
    assert chart["series"][0]["field"] == "column_count"
    assert chart["series"][0]["type"] == "scatter"


def test_combo_chart_splits_bar_left_and_line_right():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"month": "2026-01", "amount": 1200, "growth_rate": 0.12},
            {"month": "2026-02", "amount": 1500, "growth_rate": 0.25},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "combo")

    assert chart["chart_type"] == "combo"
    assert chart["x_field"] == "month"
    assert chart["series"][0]["type"] == "bar"
    assert chart["series"][0]["axis"] == "left"
    assert chart["series"][1]["type"] == "line"
    assert chart["series"][1]["axis"] == "right"


def test_radar_chart_requires_three_indicators():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"metric": "完整性", "score": 90},
            {"metric": "及时性", "score": 80},
            {"metric": "准确性", "score": 85},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "radar")

    assert chart["chart_type"] == "radar"
    assert chart["x_field"] == "metric"
    assert chart["series"][0]["field"] == "score"


def test_funnel_chart_keeps_single_series():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"stage": "曝光", "cnt": 1000},
            {"stage": "点击", "cnt": 400},
            {"stage": "下单", "cnt": 120},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "funnel")

    assert chart["chart_type"] == "funnel"
    assert chart["x_field"] == "stage"
    assert len(chart["series"]) == 1


def test_gauge_chart_allows_missing_x_field():
    payload = {
        "kind": "sql_execution",
        "rows": [
            {"completion_rate": 73},
        ],
    }

    chart = _run_chart_spec(payload, "--chart-type", "gauge")

    assert chart["chart_type"] == "gauge"
    assert "x_field" not in chart or not chart["x_field"]
    assert chart["series"][0]["field"] == "completion_rate"
