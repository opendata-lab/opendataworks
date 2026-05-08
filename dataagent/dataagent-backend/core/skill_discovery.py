from __future__ import annotations

"""
Skill discovery helpers for Claude Agent SDK runtime.

This module only owns filesystem discovery and the filtered runtime cwd used by
the SDK. Skill content is loaded by the SDK itself.
"""

import os
import shutil
from pathlib import Path

from config import get_settings


class SkillDiscoveryError(RuntimeError):
    pass


def _backend_root() -> Path:
    return Path(__file__).resolve().parent.parent


def _resolve_root_dir(raw: str) -> Path:
    path = Path(raw or "../.claude/skills/dataagent-nl2sql")
    if path.is_absolute():
        return path
    return (_backend_root() / path).resolve()


def _resolve_runtime_project_cwd(raw: str | None = None) -> Path:
    value = str(raw or "").strip()
    if value:
        path = Path(value).expanduser()
        if path.is_absolute():
            return path
        return (_backend_root() / path).resolve()

    home = Path(os.environ.get("HOME") or str(Path.home())).expanduser()
    return (home / ".dataagent" / "runtime" / "enabled-skills").resolve()


def resolve_builtin_skill_root_dir() -> Path:
    cfg = get_settings()
    return _resolve_root_dir(cfg.skills_output_dir)


def resolve_skills_root_dir() -> Path:
    return resolve_builtin_skill_root_dir()


def resolve_skill_discovery_root_dir() -> Path:
    builtin_root = resolve_builtin_skill_root_dir()
    discovery_root = builtin_root.parent
    if discovery_root.name == "skills" and discovery_root.parent.name == ".claude":
        return discovery_root
    raise SkillDiscoveryError(
        f"builtin skills_output_dir must resolve under '.claude/skills', current={builtin_root}"
    )


def resolve_agent_project_cwd() -> Path:
    discovery_root = resolve_skill_discovery_root_dir()
    return discovery_root.parent.parent


def prepare_enabled_skills_project_cwd(enabled_folders: list[str] | tuple[str, ...]) -> Path:
    discovery_root = resolve_skill_discovery_root_dir()
    cfg = get_settings()
    runtime_root = _resolve_runtime_project_cwd(cfg.dataagent_runtime_project_cwd)
    runtime_skills_dir = runtime_root / ".claude" / "skills"
    runtime_skills_dir.mkdir(parents=True, exist_ok=True)

    enabled = [str(folder or "").strip() for folder in enabled_folders if str(folder or "").strip()]
    if not enabled:
        raise SkillDiscoveryError("no enabled skills configured")
    enabled_set = set(enabled)
    for existing in runtime_skills_dir.iterdir():
        if existing.name not in enabled_set:
            if existing.is_symlink() or existing.is_file():
                existing.unlink()
            else:
                shutil.rmtree(existing)

    for folder in enabled:
        source = (discovery_root / folder).resolve()
        if not source.is_dir():
            raise SkillDiscoveryError(f"enabled skill folder not found: {folder}")
        if not (source / "SKILL.md").is_file():
            raise SkillDiscoveryError(f"enabled skill missing SKILL.md: {folder}")

        target = runtime_skills_dir / folder
        if target.is_symlink() and target.resolve() == source:
            continue
        if target.exists() or target.is_symlink():
            if target.is_symlink() or target.is_file():
                target.unlink()
            else:
                shutil.rmtree(target)
        os.symlink(source, target, target_is_directory=True)
    return runtime_root
