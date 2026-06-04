from __future__ import annotations

import json
from pathlib import Path

import pytest

from config import get_settings, update_settings
from core.skill_discovery import (
    resolve_agent_project_cwd,
    resolve_skill_discovery_root_dir,
    resolve_skills_root_dir,
)


@pytest.fixture(autouse=True)
def restore_skill_settings():
    original = get_settings().skills_output_dir
    original_runtime_cwd = get_settings().dataagent_runtime_project_cwd
    try:
        yield
    finally:
        update_settings(
            {
                "skills_output_dir": original,
                "dataagent_runtime_project_cwd": original_runtime_cwd,
            }
        )


def _write_skill(root: Path, folder: str):
    skill_dir = root / folder
    skill_dir.mkdir(parents=True, exist_ok=True)
    (skill_dir / "SKILL.md").write_text(f"# {folder}\n", encoding="utf-8")
    (skill_dir / "assets").mkdir()
    (skill_dir / "assets" / "not-loaded.json").write_text(
        json.dumps({"items": [{"value": folder}]}),
        encoding="utf-8",
    )


def test_discovery_paths_are_based_on_primary_skill_root(tmp_path: Path):
    project = tmp_path / "project"
    skills_root = project / ".claude" / "skills"
    _write_skill(skills_root, "opendataworks-business-knowledge")
    update_settings({"skills_output_dir": str(skills_root / "opendataworks-business-knowledge")})

    assert resolve_skills_root_dir() == skills_root / "opendataworks-business-knowledge"
    assert resolve_skill_discovery_root_dir() == skills_root
    assert resolve_agent_project_cwd() == project
