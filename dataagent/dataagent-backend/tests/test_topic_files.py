from __future__ import annotations

import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

import core.topic_files as topic_files
from core.topic_files import (
    TopicFileError,
    diff_generated_files,
    list_files,
    safe_workspace_file,
    save_upload,
    snapshot_workspace_state,
)


@pytest.fixture(autouse=True)
def sandbox_root(monkeypatch, tmp_path: Path):
    root = tmp_path / "dataagent_runtime"
    monkeypatch.setattr(
        topic_files,
        "resolve_topic_workspace",
        lambda topic_id: root / str(topic_id) / "workspace",
    )
    yield root


def test_save_upload_writes_into_uploads_dir(sandbox_root: Path):
    meta = save_upload("topic_1", "a b!.csv", b"col\n1\n")

    assert meta["rel_path"] == "uploads/a_b_.csv"
    assert meta["kind"] == "input"
    assert meta["size"] == 6
    assert (sandbox_root / "topic_1" / "workspace" / "uploads" / "a_b_.csv").read_bytes() == b"col\n1\n"


def test_save_upload_dedupes_colliding_names(sandbox_root: Path):
    first = save_upload("topic_1", "data.csv", b"a")
    second = save_upload("topic_1", "data.csv", b"b")

    assert first["rel_path"] == "uploads/data.csv"
    assert second["rel_path"] == "uploads/data-1.csv"


def test_save_upload_rejects_blocked_extensions(sandbox_root: Path):
    with pytest.raises(TopicFileError):
        save_upload("topic_1", "evil.sh", b"#!/bin/sh\n")


def test_list_files_only_output_and_uploads_tags_kind(sandbox_root: Path):
    root = sandbox_root / "topic_1" / "workspace"
    (root / ".claude" / "skills" / "x").mkdir(parents=True)
    (root / ".claude" / "skills" / "x" / "SKILL.md").write_text("# x\n", encoding="utf-8")
    (root / "output").mkdir(parents=True)
    (root / "output" / "report.html").write_text("<h1>ok</h1>", encoding="utf-8")
    save_upload("topic_1", "data.csv", b"a,b\n")

    files = list_files("topic_1")
    rels = {item["rel_path"]: item["kind"] for item in files}

    assert rels == {"output/report.html": "output", "uploads/data.csv": "input"}
    assert not any(item["rel_path"].startswith(".claude") for item in files)


def test_list_files_includes_workspace_scratch_files(sandbox_root: Path):
    root = sandbox_root / "topic_1" / "workspace"
    (root / "output").mkdir(parents=True)
    (root / "output" / "result.csv").write_text("a\n1\n", encoding="utf-8")
    # Scratch files written outside output/ and uploads/ must be listed too.
    (root / "scratch.tmp").write_text("noise", encoding="utf-8")
    (root / "exports").mkdir()
    (root / "exports" / "legacy.csv").write_text("x\n", encoding="utf-8")

    rels = {item["rel_path"] for item in list_files("topic_1")}
    assert rels == {"output/result.csv", "scratch.tmp", "exports/legacy.csv"}


def test_list_files_skips_symlinks(sandbox_root: Path, tmp_path: Path):
    root = sandbox_root / "topic_1" / "workspace"
    (root / "output").mkdir(parents=True)
    outside = tmp_path / "secret.txt"
    outside.write_text("secret", encoding="utf-8")
    (root / "output" / "link.txt").symlink_to(outside)
    (root / "output" / "real.txt").write_text("real", encoding="utf-8")

    rels = {item["rel_path"] for item in list_files("topic_1")}
    assert rels == {"output/real.txt"}


def test_safe_workspace_file_resolves_inside_output(sandbox_root: Path):
    root = sandbox_root / "topic_1" / "workspace"
    (root / "output").mkdir(parents=True)
    target = root / "output" / "r.html"
    target.write_text("<p>ok</p>", encoding="utf-8")

    resolved = safe_workspace_file("topic_1", "output/r.html")
    assert resolved == target.resolve()


def test_safe_workspace_file_resolves_scratch_files(sandbox_root: Path):
    root = sandbox_root / "topic_1" / "workspace"
    root.mkdir(parents=True, exist_ok=True)
    target = root / "scratch.tmp"
    target.write_text("ok", encoding="utf-8")

    resolved = safe_workspace_file("topic_1", "scratch.tmp")
    assert resolved == target.resolve()


@pytest.mark.parametrize(
    "bad",
    [
        "../escape.txt",
        "a/../../escape",
        ".claude/skills/x",
    ],
)
def test_safe_workspace_file_rejects_traversal_and_hidden_paths(sandbox_root: Path, bad: str):
    (sandbox_root / "topic_1" / "workspace").mkdir(parents=True)
    with pytest.raises(TopicFileError):
        safe_workspace_file("topic_1", bad)


def test_safe_workspace_file_confines_absolute_looking_paths(sandbox_root: Path):
    root = (sandbox_root / "topic_1" / "workspace")
    root.mkdir(parents=True)
    # A leading slash is stripped and confined under output/, not the host root.
    resolved = safe_workspace_file("topic_1", "/output/passwd.csv")
    assert resolved == (root / "output" / "passwd.csv").resolve()


def test_safe_workspace_file_confines_absolute_host_path(sandbox_root: Path):
    root = (sandbox_root / "topic_1" / "workspace")
    root.mkdir(parents=True)
    # `/etc/passwd` is stripped to `etc/passwd`, resolving under the workspace.
    resolved = safe_workspace_file("topic_1", "/etc/passwd")
    assert resolved == (root / "etc" / "passwd").resolve()


def test_safe_workspace_file_rejects_symlink_escape(sandbox_root: Path, tmp_path: Path):
    root = sandbox_root / "topic_1" / "workspace"
    (root / "output").mkdir(parents=True)
    outside = tmp_path / "outside.txt"
    outside.write_text("x", encoding="utf-8")
    (root / "output" / "link.txt").symlink_to(outside)

    with pytest.raises(TopicFileError):
        safe_workspace_file("topic_1", "output/link.txt")


def test_diff_generated_files_returns_new_and_changed_outputs(sandbox_root: Path):
    root = sandbox_root / "topic_1" / "workspace"
    (root / "output").mkdir(parents=True)
    existing = root / "output" / "old_report.html"
    existing.write_text("<p>v1</p>", encoding="utf-8")
    save_upload("topic_1", "input.csv", b"a\n")

    before = snapshot_workspace_state("topic_1")

    # Run effects: one brand-new deliverable, one rewritten deliverable.
    (root / "output" / "sales.xlsx").write_bytes(b"xlsx-bytes")
    existing.write_text("<p>v2 longer content</p>", encoding="utf-8")

    rels = {item["rel_path"] for item in diff_generated_files("topic_1", before)}
    assert rels == {"output/sales.xlsx", "output/old_report.html"}


def test_diff_generated_files_ignores_uploads_and_unchanged(sandbox_root: Path):
    root = sandbox_root / "topic_1" / "workspace"
    (root / "output").mkdir(parents=True)
    (root / "output" / "stable.csv").write_text("a\n1\n", encoding="utf-8")

    before = snapshot_workspace_state("topic_1")

    # New uploads during the run are user inputs, never run attachments.
    save_upload("topic_1", "mid_run_upload.csv", b"b\n")

    assert diff_generated_files("topic_1", before) == []


def test_diff_generated_files_caps_attachment_count(sandbox_root: Path, monkeypatch):
    root = sandbox_root / "topic_1" / "workspace"
    (root / "output").mkdir(parents=True)
    before = snapshot_workspace_state("topic_1")
    monkeypatch.setattr(topic_files, "MAX_RUN_ATTACHMENTS", 3)
    for index in range(5):
        (root / "output" / f"part_{index}.csv").write_text(f"{index}\n", encoding="utf-8")

    assert len(diff_generated_files("topic_1", before)) == 3
