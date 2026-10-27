"""Every registered methodology must validate statically before MCP execution."""

import json
import subprocess
import sys
from pathlib import Path

import pytest

from registry import coerce_params, load_registry
from validate_methodology import validate_methodology

SKILL_ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = SKILL_ROOT / "scripts"
REGISTRY = load_registry()

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
