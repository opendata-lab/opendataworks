from __future__ import annotations

"""
Skill discovery helpers for Claude Agent SDK runtime.

This module only owns filesystem discovery of the skills root used by the SDK.
Skill content is loaded by the SDK itself.
"""

from pathlib import Path

from config import get_settings


class SkillDiscoveryError(RuntimeError):
    pass


def _backend_root() -> Path:
    return Path(__file__).resolve().parent.parent


def _resolve_root_dir(raw: str) -> Path:
    value = str(raw or "").strip()
    if not value:
        raise SkillDiscoveryError("SKILLS_ROOT_DIR is required")
    path = Path(value)
    if path.is_absolute():
        return path.expanduser().resolve(strict=False)
    return (_backend_root() / path).resolve()


def resolve_builtin_skill_root_dir() -> Path:
    return resolve_skills_root_dir()


def resolve_skills_root_dir() -> Path:
    cfg = get_settings()
    root = _resolve_root_dir(getattr(cfg, "skills_root_dir", ""))
    if root.name != "skills" or root.parent.name != ".claude":
        raise SkillDiscoveryError(f"SKILLS_ROOT_DIR must resolve to a '.claude/skills' directory, current={root}")
    if not root.is_dir():
        raise SkillDiscoveryError(f"SKILLS_ROOT_DIR directory not found: {root}")
    return root


def resolve_skill_discovery_root_dir() -> Path:
    return resolve_skills_root_dir()


def resolve_agent_project_cwd() -> Path:
    discovery_root = resolve_skill_discovery_root_dir()
    return discovery_root.parent.parent
