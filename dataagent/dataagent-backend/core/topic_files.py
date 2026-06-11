from __future__ import annotations

"""
Per-topic conversation file helpers.

Uploaded inputs and agent-generated outputs live in the topic workspace
(`/dataagent_runtime/<topic_id>/workspace/`). Only two curated subdirectories are listed and
served: `uploads/` (user-attached inputs) and `output/` (deliverables the agent
is told to write). Everything else — scratch files the agent writes elsewhere in
the workspace, plus the reserved `.claude/` subtree (skills + SDK sessions) — is
never listed or served, so the downloadable surface stays clean. All path
resolution is confined to those directories to prevent traversal/symlink escape.
"""

import mimetypes
import os
import re
from datetime import datetime, timezone
from pathlib import Path

from core.topic_workspace import resolve_topic_workspace

RESERVED_DIRNAME = ".claude"
UPLOADS_DIRNAME = "uploads"
OUTPUT_DIRNAME = "output"
# The only workspace subtrees exposed to listing/download. `uploads/` holds
# user-attached inputs; `output/` holds agent deliverables. Anything outside
# these (scratch files, `.claude/`) is intentionally hidden.
_VISIBLE_DIRNAMES = (UPLOADS_DIRNAME, OUTPUT_DIRNAME)
_BLOCKED_UPLOAD_SUFFIXES = {
    ".exe", ".com", ".msi", ".bat", ".cmd", ".scr", ".dll", ".so", ".dylib", ".sh",
}


class TopicFileError(ValueError):
    """Raised for invalid file names, blocked types, or traversal attempts."""


def _workspace_root(topic_id: str) -> Path:
    return resolve_topic_workspace(topic_id).resolve()


def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts, tz=timezone.utc).isoformat()


def _safe_filename(filename: str) -> str:
    base = os.path.basename(str(filename or "").strip().replace("\\", "/"))
    base = re.sub(r"[^A-Za-z0-9_.\-]+", "_", base).strip("._-")
    return base or "file"


def _file_meta(root: Path, path: Path) -> dict:
    rel = path.relative_to(root).as_posix()
    stat = path.stat()
    return {
        "name": path.name,
        "rel_path": rel,
        "size": int(stat.st_size),
        "modified_at": _iso(stat.st_mtime),
        "content_type": mimetypes.guess_type(path.name)[0] or "application/octet-stream",
        "kind": "input" if rel.startswith(f"{UPLOADS_DIRNAME}/") else "output",
    }


def safe_workspace_file(topic_id: str, rel_path: str) -> Path:
    """Resolve a workspace-relative path, confined to the workspace root and
    excluding hidden files/directories (like `.claude/`). Raises TopicFileError
    on traversal/symlink escape or any hidden path."""
    root = _workspace_root(topic_id)
    raw = str(rel_path or "").strip().lstrip("/")
    if not raw:
        raise TopicFileError("empty path")
    parts = [seg for seg in raw.replace("\\", "/").split("/") if seg]
    if any(seg == ".." for seg in parts):
        raise TopicFileError("parent traversal is not allowed")
    candidate = (root / Path(*parts)).resolve()
    if candidate != root and root not in candidate.parents:
        raise TopicFileError("path escapes the conversation workspace")
    rel = candidate.relative_to(root)
    if any(part.startswith(".") for part in rel.parts):
        raise TopicFileError("access to hidden files or directories is not allowed")
    return candidate


def save_upload(topic_id: str, filename: str, data: bytes) -> dict:
    """Write an uploaded file into `<workspace>/uploads/`, de-duping names."""
    safe_name = _safe_filename(filename)
    if Path(safe_name).suffix.lower() in _BLOCKED_UPLOAD_SUFFIXES:
        raise TopicFileError(f"file type not allowed: {Path(safe_name).suffix}")
    root = _workspace_root(topic_id)
    uploads = root / UPLOADS_DIRNAME
    uploads.mkdir(parents=True, exist_ok=True)

    target = uploads / safe_name
    if target.exists():
        stem, suffix = Path(safe_name).stem, Path(safe_name).suffix
        counter = 1
        while target.exists():
            target = uploads / f"{stem}-{counter}{suffix}"
            counter += 1
    target.write_bytes(data)
    return _file_meta(root, target)


def list_files(topic_id: str) -> list[dict]:
    """List downloadable files in the workspace (excluding .claude and hidden files), newest first."""
    root = _workspace_root(topic_id)
    if not root.is_dir():
        return []
    items: list[dict] = []
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        # Prune hidden directories in-place so os.walk skips them
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for name in filenames:
            if name.startswith("."):
                continue
            path = Path(dirpath) / name
            if path.is_symlink() or not path.is_file():
                continue
            items.append(_file_meta(root, path))
    items.sort(key=lambda item: item["modified_at"], reverse=True)
    return items


# Upper bound on attachments persisted per run; a run that floods the workspace
# should not bloat the message row or the chat UI.
MAX_RUN_ATTACHMENTS = 20


def snapshot_workspace_state(topic_id: str) -> dict[str, tuple[int, str]]:
    """Compact pre-run view of the visible workspace: rel_path -> (size, modified_at)."""
    return {
        item["rel_path"]: (item["size"], item["modified_at"])
        for item in list_files(topic_id)
    }


def diff_generated_files(topic_id: str, before: dict[str, tuple[int, str]]) -> list[dict]:
    """Files the run generated: visible non-upload files that are new or changed
    versus the pre-run snapshot, newest first, capped at MAX_RUN_ATTACHMENTS."""
    generated: list[dict] = []
    for item in list_files(topic_id):
        if item["kind"] != "output":
            continue
        if before.get(item["rel_path"]) == (item["size"], item["modified_at"]):
            continue
        generated.append(item)
    return generated[:MAX_RUN_ATTACHMENTS]
