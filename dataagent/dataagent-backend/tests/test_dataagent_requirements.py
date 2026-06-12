from __future__ import annotations

from pathlib import Path


REQUIREMENTS = Path(__file__).resolve().parents[1] / "requirements.txt"


def test_dataagent_runtime_preinstalls_pytest_for_skill_validation():
    requirements = REQUIREMENTS.read_text(encoding="utf-8").splitlines()

    assert any(line.strip().startswith("pytest") for line in requirements)
