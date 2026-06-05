from __future__ import annotations

import os
import re
import shutil
from pathlib import Path

from config import get_settings
from core.skill_discovery import SkillDiscoveryError, resolve_skill_discovery_root_dir


def _backend_root() -> Path:
    return Path(__file__).resolve().parent.parent


def _safe_topic_id(topic_id: str) -> str:
    safe_id = re.sub(r"[^A-Za-z0-9_.-]+", "-", str(topic_id or "").strip()).strip(".-")
    if not safe_id:
        raise ValueError("topic_id is required")
    return safe_id


def sanitize_topic_id(topic_id: str) -> str:
    return _safe_topic_id(topic_id)


def _resolve_sandbox_root(raw: str | None = None) -> Path:
    value = str(raw or "").strip()
    if value:
        path = Path(value).expanduser()
        if path.is_absolute():
            return path.resolve()
        return (_backend_root() / path).resolve()

    home = Path(os.environ.get("HOME") or str(Path.home())).expanduser()
    return (home / "workspaces").resolve()


def resolve_topic_workspace(topic_id: str, *, sandbox_root: str | Path | None = None) -> Path:
    root = Path(sandbox_root).expanduser().resolve() if sandbox_root else _resolve_sandbox_root(get_settings().dataagent_sandbox_root)
    return root / _safe_topic_id(topic_id)


def _replace_path_with_symlink(link_path: Path, target: Path) -> None:
    if link_path.is_symlink():
        current = Path(os.readlink(link_path))
        if current == target:
            return
        link_path.unlink()
    elif link_path.exists():
        if link_path.is_file():
            link_path.unlink()
        else:
            shutil.rmtree(link_path)

    os.symlink(target, link_path, target_is_directory=True)


def prepare_topic_workspace(
    topic_id: str,
    enabled_folders: list[str] | tuple[str, ...],
    *,
    allow_empty: bool = False,
    sandbox_root: str | Path | None = None,
    workspace_dir: str | Path | None = None,
) -> Path:
    workspace = Path(workspace_dir).expanduser().resolve() if workspace_dir else resolve_topic_workspace(topic_id, sandbox_root=sandbox_root)
    runtime_skills_dir = workspace / ".claude" / "skills"
    runtime_skills_dir.mkdir(parents=True, exist_ok=True)

    discovery_root = resolve_skill_discovery_root_dir()
    enabled = [str(folder or "").strip() for folder in enabled_folders if str(folder or "").strip()]
    if not enabled and not allow_empty:
        raise SkillDiscoveryError("no enabled skills configured")

    enabled_set = set(enabled)
    for existing in runtime_skills_dir.iterdir():
        if existing.name in enabled_set:
            continue
        if existing.is_symlink() or existing.is_file():
            existing.unlink()
        else:
            shutil.rmtree(existing)

    for folder in enabled:
        source = (discovery_root / folder).resolve(strict=False)
        if not source.is_dir():
            raise SkillDiscoveryError(f"enabled skill folder not found: {folder}")
        if not (source / "SKILL.md").is_file():
            raise SkillDiscoveryError(f"enabled skill missing SKILL.md: {folder}")

        link_path = (runtime_skills_dir / folder).resolve(strict=False)
        if link_path == source:
            continue
        _replace_path_with_symlink(runtime_skills_dir / folder, source)

    return workspace


def delete_topic_workspace(topic_id: str, *, sandbox_root: str | Path | None = None) -> bool:
    workspace = resolve_topic_workspace(topic_id, sandbox_root=sandbox_root)
    if not workspace.exists():
        return False
    shutil.rmtree(workspace)
    return True


def cleanup_orphan_topic_workspaces(
    active_topic_ids: set[str] | list[str] | tuple[str, ...],
    *,
    sandbox_root: str | Path | None = None,
) -> list[str]:
    root = Path(sandbox_root).expanduser().resolve() if sandbox_root else _resolve_sandbox_root(get_settings().dataagent_sandbox_root)
    if not root.is_dir():
        return []

    active = {_safe_topic_id(str(topic_id)) for topic_id in active_topic_ids if str(topic_id or "").strip()}
    removed: list[str] = []
    for child in root.iterdir():
        if not child.is_dir() or child.name in active:
            continue
        shutil.rmtree(child)
        removed.append(child.name)
    return sorted(removed)
