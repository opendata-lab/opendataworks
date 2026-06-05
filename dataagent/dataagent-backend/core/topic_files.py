from __future__ import annotations

"""
Per-topic conversation file helpers.

Uploaded inputs and agent-generated outputs live in the topic workspace
(`/workspaces/<topic_id>/`). The reserved `.claude/` subtree (skills + SDK
sessions) is never listed or served. All path resolution is confined to the
workspace root to prevent traversal/symlink escape.
"""

import mimetypes
import os
import re
from datetime import datetime, timezone
from pathlib import Path

from core.topic_workspace import resolve_topic_workspace

RESERVED_DIRNAME = ".claude"
UPLOADS_DIRNAME = "uploads"
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
    """Resolve a workspace-relative path, confined to the workspace and outside
    `.claude/`. Raises TopicFileError on traversal/symlink escape."""
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
    if rel.parts and rel.parts[0] == RESERVED_DIRNAME:
        raise TopicFileError("the .claude directory is not accessible")
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
    """List workspace files (excluding `.claude/` and symlinks), newest first."""
    root = _workspace_root(topic_id)
    if not root.is_dir():
        return []
    items: list[dict] = []
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        if Path(dirpath) == root and RESERVED_DIRNAME in dirnames:
            dirnames.remove(RESERVED_DIRNAME)
        for name in filenames:
            path = Path(dirpath) / name
            if path.is_symlink() or not path.is_file():
                continue
            items.append(_file_meta(root, path))
    items.sort(key=lambda item: item["modified_at"], reverse=True)
    return items
