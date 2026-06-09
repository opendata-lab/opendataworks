from __future__ import annotations

import os
import sys
import time
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from config import get_settings, update_settings
from core.topic_workspace import (
    cleanup_orphan_topic_workspaces,
    delete_topic_workspace,
    prepare_topic_workspace,
    resolve_topic_root,
    resolve_topic_workspace,
)


def _write_skill(root: Path, folder: str) -> None:
    skill_dir = root / folder
    skill_dir.mkdir(parents=True, exist_ok=True)
    (skill_dir / "SKILL.md").write_text(f"# {folder}\n", encoding="utf-8")


def test_resolve_topic_workspace_uses_topic_id_only(monkeypatch, tmp_path: Path):
    original_root = get_settings().dataagent_sandbox_root
    update_settings({"dataagent_sandbox_root": str(tmp_path / "topics")})
    try:
        workspace = resolve_topic_workspace("topic unsafe/id")
        topic_root = resolve_topic_root("topic unsafe/id")
    finally:
        update_settings({"dataagent_sandbox_root": original_root})

    # Workspace is the <topic>/workspace subdir; the topic root is its parent.
    assert topic_root == tmp_path / "topics" / "topic-unsafe-id"
    assert workspace == tmp_path / "topics" / "topic-unsafe-id" / "workspace"


def test_prepare_topic_workspace_copies_enabled_skills(monkeypatch, tmp_path: Path):
    original_root = get_settings().dataagent_sandbox_root
    original_skills = get_settings().skills_output_dir
    original_skills_root = getattr(get_settings(), "skills_root_dir", "")
    project = tmp_path / "project"
    skills_root = project / ".claude" / "skills"
    _write_skill(skills_root, "opendataworks-business-knowledge")
    _write_skill(skills_root, "opendataworks-platform-tools")
    update_settings(
        {
            "dataagent_sandbox_root": str(tmp_path / "topics"),
            "skills_root_dir": str(skills_root),
        }
    )
    try:
        workspace = prepare_topic_workspace(
            "topic_1",
            ["opendataworks-business-knowledge", "opendataworks-platform-tools"],
        )
    finally:
        update_settings(
            {
                "dataagent_sandbox_root": original_root,
                "skills_output_dir": original_skills,
                "skills_root_dir": original_skills_root,
            }
        )

    skill_copy = workspace / ".claude" / "skills" / "opendataworks-business-knowledge"
    platform_copy = workspace / ".claude" / "skills" / "opendataworks-platform-tools"
    assert workspace == tmp_path / "topics" / "topic_1" / "workspace"
    # Real directory copies, not symlinks, so SDK skill discovery sees real files.
    assert skill_copy.is_dir() and not skill_copy.is_symlink()
    assert platform_copy.is_dir() and not platform_copy.is_symlink()
    assert (skill_copy / "SKILL.md").read_text(encoding="utf-8") == "# opendataworks-business-knowledge\n"
    assert (platform_copy / "SKILL.md").read_text(encoding="utf-8") == "# opendataworks-platform-tools\n"


def test_prepare_topic_workspace_keeps_skills_mounted_inside_workspace(monkeypatch, tmp_path: Path):
    original_root = get_settings().dataagent_sandbox_root
    original_skills = get_settings().skills_output_dir
    original_skills_root = getattr(get_settings(), "skills_root_dir", "")
    workspace = tmp_path / "topic-workspace"
    skills_root = workspace / ".claude" / "skills"
    _write_skill(skills_root, "platform-imported-skill")
    stale = skills_root / "stale-skill"
    stale.mkdir(parents=True)
    (stale / "SKILL.md").write_text("# stale\n", encoding="utf-8")
    update_settings(
        {
            "dataagent_sandbox_root": str(tmp_path / "topics"),
            "skills_root_dir": str(skills_root),
        }
    )
    try:
        prepared = prepare_topic_workspace(
            "topic_2",
            ["platform-imported-skill"],
            workspace_dir=workspace,
        )
    finally:
        update_settings(
            {
                "dataagent_sandbox_root": original_root,
                "skills_output_dir": original_skills,
                "skills_root_dir": original_skills_root,
            }
        )

    skill_dir = prepared / ".claude" / "skills" / "platform-imported-skill"
    assert prepared == workspace.resolve()
    assert skill_dir.is_dir()
    assert not skill_dir.is_symlink()
    assert not (prepared / ".claude" / "skills" / "stale-skill").exists()


def test_prepare_topic_workspace_can_use_pre_mounted_workspace(monkeypatch, tmp_path: Path):
    original_root = get_settings().dataagent_sandbox_root
    original_skills = get_settings().skills_output_dir
    original_skills_root = getattr(get_settings(), "skills_root_dir", "")
    project = tmp_path / "project"
    skills_root = project / ".claude" / "skills"
    mounted_workspace = tmp_path / "mounted-workspace"
    _write_skill(skills_root, "opendataworks-business-knowledge")
    update_settings(
        {
            "dataagent_sandbox_root": str(tmp_path / "topics"),
            "skills_root_dir": str(skills_root),
        }
    )
    try:
        workspace = prepare_topic_workspace(
            "topic_1",
            ["opendataworks-business-knowledge"],
            workspace_dir=mounted_workspace,
        )
    finally:
        update_settings(
            {
                "dataagent_sandbox_root": original_root,
                "skills_output_dir": original_skills,
                "skills_root_dir": original_skills_root,
            }
        )

    assert workspace == mounted_workspace.resolve()
    assert workspace != tmp_path / "topics" / "topic_1"
    skill_copy = workspace / ".claude" / "skills" / "opendataworks-business-knowledge"
    assert skill_copy.is_dir() and not skill_copy.is_symlink()
    assert (skill_copy / "SKILL.md").read_text(encoding="utf-8") == "# opendataworks-business-knowledge\n"


def test_prepare_topic_workspace_copies_once_and_refreshes_on_source_change(monkeypatch, tmp_path: Path):
    original_root = get_settings().dataagent_sandbox_root
    original_skills_root = getattr(get_settings(), "skills_root_dir", "")
    project = tmp_path / "project"
    skills_root = project / ".claude" / "skills"
    _write_skill(skills_root, "imported-skill")
    update_settings(
        {
            "dataagent_sandbox_root": str(tmp_path / "topics"),
            "skills_root_dir": str(skills_root),
        }
    )
    try:
        workspace = prepare_topic_workspace("topic_1", ["imported-skill"])
        skill_dir = workspace / ".claude" / "skills" / "imported-skill"
        copied = skill_dir / "SKILL.md"
        assert copied.read_text(encoding="utf-8") == "# imported-skill\n"

        # Unchanged source: a second prepare (e.g. second message in the same
        # topic) must NOT re-copy. Prove it by dropping a sentinel that a re-copy
        # would wipe.
        sentinel = skill_dir / "sentinel.txt"
        sentinel.write_text("keep", encoding="utf-8")
        prepare_topic_workspace("topic_1", ["imported-skill"])
        assert sentinel.exists()
        assert copied.read_text(encoding="utf-8") == "# imported-skill\n"

        # Same-name re-import rewrites the source and bumps mtimes; the next
        # prepare must refresh the copy (sentinel wiped, new content).
        src_md = skills_root / "imported-skill" / "SKILL.md"
        src_md.write_text("# reimported\n", encoding="utf-8")
        future = time.time() + 1000
        os.utime(src_md, (future, future))
        prepare_topic_workspace("topic_1", ["imported-skill"])
        assert copied.read_text(encoding="utf-8") == "# reimported\n"
        assert not sentinel.exists()
    finally:
        update_settings(
            {
                "dataagent_sandbox_root": original_root,
                "skills_root_dir": original_skills_root,
            }
        )


def test_delete_topic_workspace_removes_topic_root_with_workspace_and_home(tmp_path: Path):
    root = tmp_path / "topics"
    topic_root = root / "topic_1"
    sibling = root / "topic_2"
    # New layout: workspace/ and home/ are siblings under the topic root.
    (topic_root / "workspace" / ".claude").mkdir(parents=True)
    (topic_root / "home" / ".claude").mkdir(parents=True)
    (sibling / "workspace").mkdir(parents=True)

    deleted = delete_topic_workspace("topic_1", sandbox_root=root)

    assert deleted is True
    # The whole topic root is removed, so both workspace and home are gone.
    assert not topic_root.exists()
    assert sibling.exists()


def test_cleanup_orphan_topic_workspaces_keeps_active_topics(tmp_path: Path):
    root = tmp_path / "topics"
    (root / "topic_keep").mkdir(parents=True)
    (root / "topic_orphan").mkdir(parents=True)

    removed = cleanup_orphan_topic_workspaces({"topic_keep"}, sandbox_root=root)

    assert removed == ["topic_orphan"]
    assert (root / "topic_keep").exists()
    assert not (root / "topic_orphan").exists()
