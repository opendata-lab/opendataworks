from __future__ import annotations

import logging
import os
import re
import shutil
from pathlib import Path

from config import get_settings
from core.skill_discovery import SkillDiscoveryError, resolve_skill_discovery_root_dir

logger = logging.getLogger(__name__)


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


def resolve_topic_root(topic_id: str, *, sandbox_root: str | Path | None = None) -> Path:
    """Per-topic root dir ``<sandbox_root>/<topic_id>``.

    The root holds two separately mounted child subdirs: ``workspace`` (agent
    cwd) and ``home`` (persisted Claude HOME). Deletion and orphan cleanup
    operate at this root level so both are removed together; the container never
    bind-mounts the root directly.
    """
    root = Path(sandbox_root).expanduser().resolve() if sandbox_root else _resolve_sandbox_root(get_settings().dataagent_sandbox_root)
    return root / _safe_topic_id(topic_id)


def resolve_topic_workspace(topic_id: str, *, sandbox_root: str | Path | None = None) -> Path:
    """Agent working directory ``<sandbox_root>/<topic_id>/workspace``.

    This is the shared workspace contract used by the agent cwd, the backend
    topic file APIs (``uploads/``, ``output/``), and the sandbox runner's
    ``/mnt/workspace`` bind source. It is a subdir of the topic root so the
    persisted ``home`` sibling never appears inside the agent's workspace.
    """
    return resolve_topic_root(topic_id, sandbox_root=sandbox_root) / "workspace"


def _tree_max_mtime(root: Path) -> float:
    """Newest file mtime under ``root`` (0.0 if empty/unreadable)."""
    latest = 0.0
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            try:
                mtime = (Path(dirpath) / name).stat().st_mtime
            except OSError:
                continue
            if mtime > latest:
                latest = mtime
    return latest


def _skill_copy_is_current(dest: Path, source: Path) -> bool:
    """True when ``dest`` is an existing real-directory copy of ``source`` that is
    not stale. ``copytree`` preserves file mtimes, so an unchanged source yields
    equal newest-mtimes; a re-imported/edited source is newer and forces a refresh."""
    if not dest.is_dir() or dest.is_symlink():
        return False
    if not (dest / "SKILL.md").is_file():
        return False
    return _tree_max_mtime(dest) >= _tree_max_mtime(source)


def _replace_path_with_copy(dest: Path, source: Path) -> None:
    """Materialize an enabled skill as a real directory copy inside the workspace.

    Copies instead of symlinking because some Claude Code / Agent SDK skill
    discovery does not follow symlinked skill directories. Refreshed on every
    prepare so the workspace copy always matches the current on-disk skill (also
    avoids stale copies after a redeploy or same-name re-import)."""
    if dest.is_symlink() or dest.is_file():
        dest.unlink()
    elif dest.exists():
        shutil.rmtree(dest)
    shutil.copytree(source, dest, symlinks=False)


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
    logger.info(
        "skill.prepare.start topic_id=%s workspace=%s skills_dir=%s discovery_root=%s allow_empty=%s enabled=%s",
        topic_id,
        workspace,
        runtime_skills_dir,
        discovery_root,
        allow_empty,
        enabled,
    )
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
        source_is_dir = source.is_dir()
        skill_md_exists = (source / "SKILL.md").is_file()
        logger.info(
            "skill.prepare.copy topic_id=%s folder=%s source=%s source_is_dir=%s skill_md=%s",
            topic_id,
            folder,
            source,
            source_is_dir,
            skill_md_exists,
        )
        if not source_is_dir:
            raise SkillDiscoveryError(f"enabled skill folder not found: {folder}")
        if not skill_md_exists:
            raise SkillDiscoveryError(f"enabled skill missing SKILL.md: {folder}")

        dest = runtime_skills_dir / folder
        if dest.resolve(strict=False) == source:
            # Already in place at the discovery root path (e.g. the sandbox child
            # where the skill is bind-mounted at <skills>/<folder>); copying would
            # mean copying the directory onto itself, so skip.
            continue
        if _skill_copy_is_current(dest, source):
            # Copied already for this topic and the source skill is unchanged;
            # do not re-copy on every message, only when the source changes
            # (e.g. a same-name re-import bumps file mtimes).
            continue
        _replace_path_with_copy(dest, source)

    linked = _describe_skills_dir(runtime_skills_dir)
    logger.info(
        "skill.prepare.done topic_id=%s skills_dir=%s linked=%s",
        topic_id,
        runtime_skills_dir,
        [_format_skill_entry(entry) for entry in linked],
    )
    broken = [entry for entry in linked if entry["name"] in enabled_set and not entry["skill_md"]]
    if broken:
        logger.warning(
            "skill.prepare.broken topic_id=%s skills_dir=%s entries=%s "
            "(enabled skill entries have no SKILL.md after assembly; "
            "the source skill may be missing or incomplete on disk)",
            topic_id,
            runtime_skills_dir,
            [_format_skill_entry(entry) for entry in broken],
        )
    return workspace


def _describe_skills_dir(skills_dir: Path) -> list[dict]:
    """Describe the prepared skills directory for diagnostics: each entry's link
    target and whether the resolved target still has a SKILL.md."""
    described: list[dict] = []
    try:
        entries = sorted(skills_dir.iterdir(), key=lambda p: p.name)
    except OSError:
        return described
    for entry in entries:
        is_link = entry.is_symlink()
        target = ""
        if is_link:
            try:
                target = os.readlink(entry)
            except OSError:
                target = "<unreadable>"
        resolved = entry.resolve(strict=False)
        described.append(
            {
                "name": entry.name,
                "symlink": is_link,
                "target": target,
                "skill_md": (resolved / "SKILL.md").is_file(),
            }
        )
    return described


def _format_skill_entry(entry: dict) -> str:
    return (
        f"{entry['name']}(symlink={entry['symlink']},"
        f"target={entry['target']},skill_md={entry['skill_md']})"
    )


def delete_topic_workspace(topic_id: str, *, sandbox_root: str | Path | None = None) -> bool:
    # Remove the whole topic root so the workspace and the persisted home
    # subdir are deleted together (no leftover session/home data).
    topic_root = resolve_topic_root(topic_id, sandbox_root=sandbox_root)
    if not topic_root.exists():
        return False
    shutil.rmtree(topic_root)
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
