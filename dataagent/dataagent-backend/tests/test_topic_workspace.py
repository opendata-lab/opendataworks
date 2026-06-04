from __future__ import annotations

import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from config import get_settings, update_settings
from core.topic_workspace import cleanup_orphan_topic_workspaces, delete_topic_workspace, prepare_topic_workspace, resolve_topic_workspace


def _write_skill(root: Path, folder: str) -> None:
    skill_dir = root / folder
    skill_dir.mkdir(parents=True, exist_ok=True)
    (skill_dir / "SKILL.md").write_text(f"# {folder}\n", encoding="utf-8")


def test_resolve_topic_workspace_uses_topic_id_only(monkeypatch, tmp_path: Path):
    original_root = get_settings().dataagent_sandbox_root
    update_settings({"dataagent_sandbox_root": str(tmp_path / "topics")})
    try:
        workspace = resolve_topic_workspace("topic unsafe/id")
    finally:
        update_settings({"dataagent_sandbox_root": original_root})

    assert workspace == tmp_path / "topics" / "topic-unsafe-id"


def test_prepare_topic_workspace_uses_container_skill_links(monkeypatch, tmp_path: Path):
    original_root = get_settings().dataagent_sandbox_root
    original_skills = get_settings().skills_output_dir
    project = tmp_path / "project"
    skills_root = project / ".claude" / "skills"
    _write_skill(skills_root, "opendataworks-business-knowledge")
    _write_skill(skills_root, "opendataworks-platform-tools")
    update_settings(
        {
            "dataagent_sandbox_root": str(tmp_path / "topics"),
            "skills_output_dir": str(skills_root / "opendataworks-business-knowledge"),
        }
    )
    try:
        workspace = prepare_topic_workspace(
            "topic_1",
            ["opendataworks-business-knowledge", "opendataworks-platform-tools"],
            skill_link_root="/skills",
        )
    finally:
        update_settings({"dataagent_sandbox_root": original_root, "skills_output_dir": original_skills})

    skill_link = workspace / ".claude" / "skills" / "opendataworks-business-knowledge"
    platform_link = workspace / ".claude" / "skills" / "opendataworks-platform-tools"
    assert workspace == tmp_path / "topics" / "topic_1"
    assert skill_link.is_symlink()
    assert platform_link.is_symlink()
    assert skill_link.readlink() == Path("/skills/opendataworks-business-knowledge")
    assert platform_link.readlink() == Path("/skills/opendataworks-platform-tools")


def test_prepare_topic_workspace_can_use_pre_mounted_workspace(monkeypatch, tmp_path: Path):
    original_root = get_settings().dataagent_sandbox_root
    original_skills = get_settings().skills_output_dir
    project = tmp_path / "project"
    skills_root = project / ".claude" / "skills"
    mounted_workspace = tmp_path / "mounted-workspace"
    _write_skill(skills_root, "opendataworks-business-knowledge")
    update_settings(
        {
            "dataagent_sandbox_root": str(tmp_path / "topics"),
            "skills_output_dir": str(skills_root / "opendataworks-business-knowledge"),
        }
    )
    try:
        workspace = prepare_topic_workspace(
            "topic_1",
            ["opendataworks-business-knowledge"],
            skill_link_root="/skills",
            workspace_dir=mounted_workspace,
        )
    finally:
        update_settings({"dataagent_sandbox_root": original_root, "skills_output_dir": original_skills})

    assert workspace == mounted_workspace.resolve()
    assert workspace != tmp_path / "topics" / "topic_1"
    assert (workspace / ".claude" / "skills" / "opendataworks-business-knowledge").readlink() == Path(
        "/skills/opendataworks-business-knowledge"
    )


def test_delete_topic_workspace_removes_only_topic_directory(tmp_path: Path):
    root = tmp_path / "topics"
    workspace = root / "topic_1"
    sibling = root / "topic_2"
    (workspace / ".claude").mkdir(parents=True)
    (sibling / ".claude").mkdir(parents=True)

    deleted = delete_topic_workspace("topic_1", sandbox_root=root)

    assert deleted is True
    assert not workspace.exists()
    assert sibling.exists()


def test_cleanup_orphan_topic_workspaces_keeps_active_topics(tmp_path: Path):
    root = tmp_path / "topics"
    (root / "topic_keep").mkdir(parents=True)
    (root / "topic_orphan").mkdir(parents=True)

    removed = cleanup_orphan_topic_workspaces({"topic_keep"}, sandbox_root=root)

    assert removed == ["topic_orphan"]
    assert (root / "topic_keep").exists()
    assert not (root / "topic_orphan").exists()
