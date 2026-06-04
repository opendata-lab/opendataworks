from __future__ import annotations

import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from config import get_settings, update_settings
from core.topic_files import TopicFileError, list_files, safe_workspace_file, save_upload


@pytest.fixture(autouse=True)
def sandbox_root(tmp_path: Path):
    original = get_settings().dataagent_sandbox_root
    update_settings({"dataagent_sandbox_root": str(tmp_path / "workspaces")})
    try:
        yield tmp_path / "workspaces"
    finally:
        update_settings({"dataagent_sandbox_root": original})


def test_save_upload_writes_into_uploads_dir(sandbox_root: Path):
    meta = save_upload("topic_1", "a b!.csv", b"col\n1\n")

    assert meta["rel_path"] == "uploads/a_b_.csv"
    assert meta["kind"] == "input"
    assert meta["size"] == 6
    assert (sandbox_root / "topic_1" / "uploads" / "a_b_.csv").read_bytes() == b"col\n1\n"


def test_save_upload_dedupes_colliding_names(sandbox_root: Path):
    first = save_upload("topic_1", "data.csv", b"a")
    second = save_upload("topic_1", "data.csv", b"b")

    assert first["rel_path"] == "uploads/data.csv"
    assert second["rel_path"] == "uploads/data-1.csv"


def test_save_upload_rejects_blocked_extensions(sandbox_root: Path):
    with pytest.raises(TopicFileError):
        save_upload("topic_1", "evil.sh", b"#!/bin/sh\n")


def test_list_files_excludes_claude_and_tags_kind(sandbox_root: Path):
    root = sandbox_root / "topic_1"
    (root / ".claude" / "skills" / "x").mkdir(parents=True)
    (root / ".claude" / "skills" / "x" / "SKILL.md").write_text("# x\n", encoding="utf-8")
    (root / "report.html").write_text("<h1>ok</h1>", encoding="utf-8")
    save_upload("topic_1", "data.csv", b"a,b\n")

    files = list_files("topic_1")
    rels = {item["rel_path"]: item["kind"] for item in files}

    assert rels == {"report.html": "output", "uploads/data.csv": "input"}
    assert not any(item["rel_path"].startswith(".claude") for item in files)


def test_list_files_skips_symlinks(sandbox_root: Path, tmp_path: Path):
    root = sandbox_root / "topic_1"
    root.mkdir(parents=True)
    outside = tmp_path / "secret.txt"
    outside.write_text("secret", encoding="utf-8")
    (root / "link.txt").symlink_to(outside)
    (root / "real.txt").write_text("real", encoding="utf-8")

    rels = {item["rel_path"] for item in list_files("topic_1")}
    assert rels == {"real.txt"}


def test_safe_workspace_file_resolves_inside_workspace(sandbox_root: Path):
    root = sandbox_root / "topic_1"
    (root / "outputs").mkdir(parents=True)
    target = root / "outputs" / "r.html"
    target.write_text("<p>ok</p>", encoding="utf-8")

    resolved = safe_workspace_file("topic_1", "outputs/r.html")
    assert resolved == target.resolve()


@pytest.mark.parametrize("bad", ["../escape.txt", "a/../../escape", ".claude/skills/x"])
def test_safe_workspace_file_rejects_traversal_and_reserved(sandbox_root: Path, bad: str):
    (sandbox_root / "topic_1").mkdir(parents=True)
    with pytest.raises(TopicFileError):
        safe_workspace_file("topic_1", bad)


def test_safe_workspace_file_confines_absolute_looking_paths(sandbox_root: Path):
    root = (sandbox_root / "topic_1")
    root.mkdir(parents=True)
    # A leading slash is stripped and confined under the workspace, not the host root.
    resolved = safe_workspace_file("topic_1", "/etc/passwd")
    assert resolved == (root / "etc" / "passwd").resolve()


def test_safe_workspace_file_rejects_symlink_escape(sandbox_root: Path, tmp_path: Path):
    root = sandbox_root / "topic_1"
    root.mkdir(parents=True)
    outside = tmp_path / "outside.txt"
    outside.write_text("x", encoding="utf-8")
    (root / "link.txt").symlink_to(outside)

    with pytest.raises(TopicFileError):
        safe_workspace_file("topic_1", "link.txt")
