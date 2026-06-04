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
    path = Path(raw or "../.claude/skills/opendataworks-business-knowledge")
    if path.is_absolute():
        return path
    return (_backend_root() / path).resolve()


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
