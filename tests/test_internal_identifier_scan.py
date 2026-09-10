"""Guards for the internal-identifier scanner.

The scanner is what stops real table names and business terms from reaching a
public repository, so a silently broken scanner is worse than none: it reports
success over content nobody is checking any more.
"""

from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]
SCANNER = REPO_ROOT / "scripts" / "check-internal-identifiers.py"


def _load_scanner():
    spec = importlib.util.spec_from_file_location("_internal_scan", SCANNER)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_tracked_files_contain_no_internal_identifiers():
    """The scan the CI workflow runs, so a local test run catches it too."""
    result = subprocess.run(
        [sys.executable, str(SCANNER)],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    assert result.returncode == 0, result.stderr


def test_scanner_flags_a_real_table_name(tmp_path, monkeypatch):
    """A scanner that cannot fail is not a guard."""
    scanner = _load_scanner()
    probe = tmp_path / "leak.py"
    probe.write_text('sql = "SELECT a FROM public.dim_tech_env_workflow_df"', encoding="utf-8")

    findings = scanner.scan([str(probe)])
    assert findings, "scanner missed an internal table name"
    assert findings[0][2] == "dim_tech_env_workflow_df"


def test_scanner_flags_an_internal_business_term(tmp_path):
    scanner = _load_scanner()
    probe = tmp_path / "doc.md"
    probe.write_text("这里提到了分级保障组件的口径", encoding="utf-8")

    findings = scanner.scan([str(probe)])
    assert findings, "scanner missed an internal business term"


def test_scanner_does_not_flag_ordinary_content(tmp_path):
    """A noisy check trains people to skip it."""
    scanner = _load_scanner()
    probe = tmp_path / "ok.py"
    probe.write_text(
        'sql = "SELECT component_name FROM public.dim_demo_component_df"\n'
        '# dimension tables, technical debt, guaranteed delivery\n',
        encoding="utf-8",
    )

    assert scanner.scan([str(probe)]) == []
