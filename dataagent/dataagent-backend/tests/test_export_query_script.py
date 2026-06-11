from __future__ import annotations

import csv
import importlib.util
import json
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
PLATFORM_TOOLS_ROOT = BACKEND_ROOT.parent / ".claude" / "skills" / "opendataworks-platform-tools"
SKILL_SCRIPTS_ROOT = PLATFORM_TOOLS_ROOT / "scripts"
if str(SKILL_SCRIPTS_ROOT) not in sys.path:
    sys.path.insert(0, str(SKILL_SCRIPTS_ROOT))


def _load_export_module():
    module_path = SKILL_SCRIPTS_ROOT / "export_query.py"
    spec = importlib.util.spec_from_file_location("dataagent_export_query", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec is not None and spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_export_query_writes_full_csv_and_returns_preview(tmp_path, monkeypatch, capsys):
    module = _load_export_module()

    rows = [{"id": i, "name": f"row-{i}"} for i in range(120)]
    captured = {}

    def fake_query_readonly(database, sql, preferred_engine=None, limit=None, timeout_seconds=None, for_export=False):
        captured["for_export"] = for_export
        captured["limit"] = limit
        return {
            "engine": "mysql",
            "database": database,
            "rows": rows,
            "row_count": len(rows),
            "has_more": False,
        }

    monkeypatch.setattr(module, "query_readonly", fake_query_readonly)

    output = tmp_path / "exports" / "result.csv"
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "export_query.py",
            "--database",
            "opendataworks",
            "--sql",
            "SELECT id, name FROM demo",
            "--output",
            str(output),
            "--preview-rows",
            "5",
        ],
    )

    module.main()

    # 导出模式必须开启，且行数上限传入。
    assert captured["for_export"] is True

    payload = json.loads(capsys.readouterr().out)
    assert payload["kind"] == "sql_export"
    assert payload["result_state"] == "success"
    assert payload["row_count"] == 120
    assert payload["file_path"] == str(output)
    assert len(payload["preview_rows"]) == 5

    # 全量数据落盘，CSV 行数与全量一致（不受预览行数限制）。
    assert output.exists()
    with open(output, "r", encoding="utf-8-sig", newline="") as handle:
        csv_rows = list(csv.DictReader(handle))
    assert len(csv_rows) == 120
    assert csv_rows[0]["name"] == "row-0"
    assert csv_rows[-1]["name"] == "row-119"


def test_export_query_rejects_non_readonly_sql(tmp_path, monkeypatch, capsys):
    module = _load_export_module()

    output = tmp_path / "out.csv"
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "export_query.py",
            "--database",
            "opendataworks",
            "--sql",
            "DELETE FROM demo",
            "--output",
            str(output),
        ],
    )

    module.main()

    payload = json.loads(capsys.readouterr().out)
    assert payload["kind"] == "sql_export"
    assert payload["result_state"] == "failed"
    assert payload["error_code"] == "export_failed"
    assert not output.exists()
