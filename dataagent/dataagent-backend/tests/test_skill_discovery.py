from __future__ import annotations

import json
from pathlib import Path

import pytest

from config import get_settings, update_settings
from core.skill_discovery import (
    resolve_builtin_skill_root_dir,
    resolve_agent_project_cwd,
    resolve_skill_discovery_root_dir,
    resolve_skills_root_dir,
)


@pytest.fixture(autouse=True)
def restore_skill_settings():
    original = get_settings().skills_output_dir
    original_root = getattr(get_settings(), "skills_root_dir", "")
    try:
        yield
    finally:
        update_settings({"skills_output_dir": original, "skills_root_dir": original_root})


def _write_skill(root: Path, folder: str):
    skill_dir = root / folder
    skill_dir.mkdir(parents=True, exist_ok=True)
    (skill_dir / "SKILL.md").write_text(f"# {folder}\n", encoding="utf-8")
    (skill_dir / "assets").mkdir()
    (skill_dir / "assets" / "not-loaded.json").write_text(
        json.dumps({"items": [{"value": folder}]}),
        encoding="utf-8",
    )


def test_discovery_paths_are_based_on_skills_root_dir(tmp_path: Path):
    project = tmp_path / "project"
    skills_root = project / ".claude" / "skills"
    _write_skill(skills_root, "opendataworks-business-knowledge")
    update_settings({"skills_root_dir": str(skills_root)})

    assert resolve_builtin_skill_root_dir() == skills_root
    assert resolve_skills_root_dir() == skills_root
    assert resolve_skill_discovery_root_dir() == skills_root
    assert resolve_agent_project_cwd() == project


def test_discovery_requires_skills_root_dir():
    update_settings({"skills_root_dir": ""})

    with pytest.raises(Exception, match="SKILLS_ROOT_DIR"):
        resolve_skill_discovery_root_dir()


def test_discovery_rejects_non_claude_skills_root(tmp_path: Path):
    root = tmp_path / "skills"
    root.mkdir(parents=True)
    update_settings({"skills_root_dir": str(root)})

    with pytest.raises(Exception, match=".claude/skills"):
        resolve_skill_discovery_root_dir()
