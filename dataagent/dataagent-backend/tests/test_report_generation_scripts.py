from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SKILL_ROOT = ROOT / ".claude" / "skills" / "report-generation"
GENERATE_REPORT = SKILL_ROOT / "scripts" / "generate_report.py"
FORMAT_ANSWER = SKILL_ROOT / "scripts" / "format_answer.py"

SAMPLE_ROWS = [
    {"layer": "ODS", "table_count": 28},
    {"layer": "DWD", "table_count": 3},
]


def _run(script: Path, *args: str) -> dict:
    result = subprocess.run(
        [sys.executable, str(script), *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return json.loads(result.stdout)


def _run_report(*args: str) -> dict:
    return _run(GENERATE_REPORT, *args)


def _run_format_answer(payload: object) -> dict:
    return _run(FORMAT_ANSWER, "--input", json.dumps(payload, ensure_ascii=False))


# --- generate_report.py -------------------------------------------------


def test_generate_report_writes_xlsx_from_json_array(tmp_path):
    output = tmp_path / "report.xlsx"

    payload = _run_report(
        "--input", json.dumps(SAMPLE_ROWS, ensure_ascii=False),
        "--output", str(output),
        "--title", "分层表数量",
    )

    assert payload["kind"] == "report_generation"
    assert payload["status"] == "success"
    assert payload["error"] is None
    assert payload["title"] == "分层表数量"
    assert payload["row_count"] == 2
    assert payload["format"] == "Excel 报表"
    assert payload["output_path"] == str(output)
    assert output.exists()
    assert payload["file_size"] == output.stat().st_size


def test_generate_report_writes_html_from_rows_object(tmp_path):
    output = tmp_path / "nested" / "report.html"

    payload = _run_report(
        "--input", json.dumps({"rows": SAMPLE_ROWS}, ensure_ascii=False),
        "--output", str(output),
    )

    assert payload["status"] == "success"
    assert payload["format"] == "HTML 报告"
    assert payload["row_count"] == 2
    # 父目录不存在时应自动创建
    assert output.exists()

    html = output.read_text(encoding="utf-8")
    assert "<!DOCTYPE html>" in html
    assert "数据分析报告" in html
    assert "ODS" in html


def test_generate_report_reads_csv_input(tmp_path):
    csv_path = tmp_path / "rows.csv"
    csv_path.write_text("layer,table_count\nODS,28\nDWD,3\n", encoding="utf-8")
    output = tmp_path / "report.xlsx"

    payload = _run_report("--input", str(csv_path), "--output", str(output))

    assert payload["status"] == "success"
    assert payload["row_count"] == 2


def test_generate_report_rejects_unsupported_output_suffix(tmp_path):
    output = tmp_path / "report.pdf"

    payload = _run_report(
        "--input", json.dumps(SAMPLE_ROWS, ensure_ascii=False),
        "--output", str(output),
    )

    assert payload["kind"] == "report_generation"
    assert payload["status"] == "failed"
    assert payload["error"]
    assert ".pdf" in payload["error"]
    assert not output.exists()


def test_generate_report_rejects_empty_rows(tmp_path):
    output = tmp_path / "report.xlsx"

    payload = _run_report("--input", "[]", "--output", str(output))

    assert payload["status"] == "failed"
    assert payload["error"]
    assert not output.exists()


def test_generate_report_rejects_missing_csv_file(tmp_path):
    payload = _run_report(
        "--input", str(tmp_path / "missing.csv"),
        "--output", str(tmp_path / "report.xlsx"),
    )

    assert payload["status"] == "failed"
    assert payload["error"]


# --- format_answer.py ---------------------------------------------------


def test_format_answer_previews_first_row():
    payload = _run_format_answer({"rows": SAMPLE_ROWS, "summary": "共 2 层"})

    assert payload["kind"] == "python_execution"
    assert payload["script"] == "format_answer.py"
    assert payload["summary"] == "共 2 层"
    assert payload["error"] is None

    observations = payload["result"]["observations"]
    assert any("首行样例" in item for item in observations)
    assert any("layer=ODS" in item for item in observations)


def test_format_answer_flags_truncated_result():
    payload = _run_format_answer({"rows": SAMPLE_ROWS, "has_more": True})

    observations = payload["result"]["observations"]
    assert any("结果已截断" in item for item in observations)


def test_format_answer_falls_back_to_default_summary():
    payload = _run_format_answer({"rows": [], "summary": ""})

    assert payload["summary"] == "已提取查询结果摘要"
    assert payload["result"]["observations"] == []


def test_format_answer_reports_error_on_invalid_json():
    result = subprocess.run(
        [sys.executable, str(FORMAT_ANSWER), "--input", "{not json"],
        check=True,
        capture_output=True,
        text=True,
    )
    payload = json.loads(result.stdout)

    assert payload["kind"] == "python_execution"
    assert payload["error"]
