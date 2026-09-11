from __future__ import annotations

"""
Shared DataAgent Claude runtime helpers.
"""

import json
import os
import re
import shlex
import sys
from pathlib import Path
from types import SimpleNamespace
from typing import Any

from config import get_settings, resolve_sql_read_timeout_seconds, resolve_workspace_scratch_dirs
from core.provider_runtime import build_provider_env as _build_provider_env
from core.provider_runtime import normalize_provider_id as _normalize_provider_id
from core.provider_runtime import safe_base_url_for_log as _safe_base_url_for_log
from core.agent_profile_service import normalize_permission_mode
from core.permission_gate import WRITE_TOOL_NAMES, plan_denies_tool, requires_confirmation
from core.data_scope import encode_scope_header, normalize_data_scope
from core.skill_admin_service import resolve_enabled_skill_runtime, resolve_runtime_provider_selection
from core.skill_discovery import (
    resolve_builtin_skill_root_dir,
    resolve_skill_discovery_root_dir,
)

SAFE_AUTO_ALLOWED_TOOLS = ["Skill", "Bash", "Read", "LS", "Glob", "Grep"]
PORTAL_MCP_SERVER_NAME = "portal"
PORTAL_MCP_TOOL_NAMES = [
    "portal_search_tables",
    "portal_get_lineage",
    "portal_resolve_datasource",
    "portal_export_metadata",
    "portal_get_table_ddl",
    "portal_query_readonly",
]
PLATFORM_TOOLS_SKILL_FOLDER = "opendataworks-platform-tools"
# Shipped inside both the backend and the sandbox-runner image (each COPYs the whole
# dataagent-backend tree), so the same absolute path resolves in either container.
SYSTEM_PROMPT_PATH = Path(__file__).resolve().parent.parent / "prompts" / "data_agent_system_prompt.md"
_FILE_BOUNDARY_PATH_KEYS = {
    "Read": ("file_path", "path"),
    "LS": ("path",),
    "Glob": ("path", "pattern"),
    "Grep": ("path", "glob"),
    "Write": ("file_path",),
    "Edit": ("file_path",),
    "MultiEdit": ("file_path",),
    "NotebookEdit": ("notebook_path",),
}
_BASH_PARENT_SEGMENT_RE = re.compile(r"(^|[\s;&|()])\.\.(?=$|[/\s;&|()])")
# Not a filesystem location but a discard sink: writes store nothing and reads hit
# EOF immediately. `> /dev/null 2>&1` is ubiquitous in shell commands, so denying
# it as "outside workspace" is pure friction with no isolation value. This is not
# part of the configurable scratch allow-list (``dataagent_workspace_scratch_dirs``)
# because it is a fixed POSIX property, not a deployment choice. Matched exactly:
# the rest of /dev — and any path under /dev/null — stays outside the boundary.
_DISCARD_SINK_PATHS: frozenset[Path] = frozenset({Path("/dev/null")})
_URL_SCHEME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*://")
# Claude Code offloads oversized tool results as either a ".txt" (string result)
# or ".json" (structured result) file under the per-project tool-results dir.
_OFFLOADED_TOOL_RESULT_SUFFIXES = frozenset({".txt", ".json"})
# Characters the punctuation-aware shell tokenizer emits as standalone operator
# runs (redirects, pipes, separators, subshells). An operator token is a run made
# up *only* of these; a quoted argument that merely contains one (e.g. "a>b") is a
# word, not an operator.
_BASH_OPERATOR_CHARS = frozenset("();<>|&")
_BASH_ASSIGNMENT_RE = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*=")
# A word shaped like ``<name>=<value>``: an env assignment (``FOO=/x``), a
# short/long option with an inline value (``-o=/x``, ``--file=/x``), or a tool's
# own key syntax (``dd if=/x``). The name part is deliberately strict — letters,
# digits, ``_``/``-``, at most two leading dashes — so words that merely contain
# "=" after other punctuation (``sed 's/a=/b/'``) are not read as assignments.
_BASH_OPTION_ASSIGNMENT_RE = re.compile(r"^-{0,2}[A-Za-z_][A-Za-z0-9_-]*=")
# Separators splitting an assignment value into individual paths: "=" for nested
# keys (``--define=key=/x``) and ":" for joined path lists (``PYTHONPATH=/a:/b``),
# so each element is checked on its own instead of the whole value being rejected.
_BASH_ASSIGNMENT_VALUE_SPLIT_RE = re.compile(r"[=:]")
# The offloaded tool-result file lives outside the workspace, so the only Bash use
# the boundary hook permits is *viewing* it. These command words behave as simple
# read filters for path arguments, so granting the tool-result exception to their
# non-redirect arguments stays read-only. Pagers such as less/more are excluded
# because environment hooks such as LESSOPEN can execute arbitrary preprocessors.
# Mutating/executing commands (rm, mv, cp, tee, dd, sed -i, python, ...) and
# redirect targets are intentionally excluded and fall through to denial.
_BASH_READONLY_COMMANDS = frozenset(
    {
        "cat",
        "tac",
        "head",
        "tail",
        "nl",
        "wc",
        "cut",
        "grep",
        "egrep",
        "fgrep",
        "od",
        "strings",
        "file",
        "stat",
    }
)


def _build_prompt(history: list[dict[str, str]], question: str) -> str:
    lines: list[str] = []
    for item in history:
        role = "用户" if item.get("role") == "user" else "助手"
        content = str(item.get("content") or "").strip()
        if not content:
            continue
        lines.append(f"[{role}]: {content}")
    lines.append(f"[用户]: {question}")
    return "\n\n".join(lines)


def _load_system_prompt_template() -> str:
    return SYSTEM_PROMPT_PATH.read_text(encoding="utf-8").strip()


def _dedupe_strings(values: Any) -> list[str]:
    if values is None:
        return []
    if isinstance(values, str):
        values = [values]
    result: list[str] = []
    seen: set[str] = set()
    iterable = values if isinstance(values, (list, tuple, set)) else []
    for value in iterable:
        text = str(value or "").strip()
        if not text or text in seen:
            continue
        result.append(text)
        seen.add(text)
    return result


def _path_has_parent_segment(value: Any) -> bool:
    text = str(value or "").strip()
    if not text:
        return False
    return any(part == ".." for part in text.replace("\\", "/").split("/"))


def _resolve_workspace_candidate(raw: Any, workspace: Path) -> Path:
    text = os.path.expandvars(str(raw or "").strip())
    path = Path(text).expanduser()
    if not path.is_absolute():
        path = workspace / path
    return path.resolve(strict=False)


def _path_is_under(path: Path, root: Path) -> bool:
    return path == root or path.is_relative_to(root)


def _path_is_allowed(path: Path, allowed_roots: list[Path]) -> bool:
    return any(_path_is_under(path, root) for root in allowed_roots)


def _is_discard_sink(path: Path) -> bool:
    """Whether ``path`` is a no-op sink the boundary never needs to guard."""
    return path in _DISCARD_SINK_PATHS


def _resolve_claude_project_data_dir(workspace: Path, runtime_env: dict[str, str] | None) -> Path | None:
    """Per-project Claude data dir for the current run.

    Mirrors the Claude Code CLI layout ``<config_dir>/projects/<encoded_cwd>``,
    where ``config_dir`` is ``$CLAUDE_CONFIG_DIR`` or ``$HOME/.claude`` and
    ``encoded_cwd`` is ``realpath(cwd)`` with every non-alphanumeric char replaced
    by ``-``. This dir holds session ``.jsonl`` transcripts, ``subagents/`` logs,
    and offloaded (too-large) tool results. It is intentionally *not* added to the
    workspace allow-list: only the offloaded tool-result files under it are
    readable (see ``_is_offloaded_tool_result_path``); transcripts and other
    session state stay outside the boundary.
    """
    env = runtime_env or {}
    config_dir = str(env.get("CLAUDE_CONFIG_DIR") or "").strip()
    if config_dir:
        base = Path(config_dir).expanduser()
    else:
        home = str(env.get("HOME") or "").strip()
        if not home:
            return None
        base = Path(home).expanduser() / ".claude"
    encoded_cwd = re.sub(r"[^a-zA-Z0-9]", "-", str(workspace))
    return (base / "projects" / encoded_cwd).resolve(strict=False)


def _is_offloaded_tool_result_path(candidate: Path, tool_result_root: Path | None) -> bool:
    """True when ``candidate`` is exactly an offloaded tool-result file the CLI
    wrote a "Full output saved to" pointer for.

    The CLI offloads oversized tool results to
    ``<project_data_dir>/<session_id>/tool-results/<tool_use_id>.{txt,json}`` and
    rewrites the inline result with that file path. The CLI does not dictate which
    tool the agent must use to view it, so both ``Read`` and a read-only ``Bash``
    view of the file get this allowance (see ``_validate_bash_workspace_boundary``
    for the read-only-command gate). Only that exact shape is allowed: a
    ``.txt``/``.json`` file whose parent dir is ``tool-results`` directly under a
    single session segment. Session ``.jsonl`` transcripts, ``subagents/`` logs,
    meta files, and the project root itself stay denied so the agent cannot
    browse cross-run session state.
    """
    if tool_result_root is None:
        return False
    try:
        rel = candidate.relative_to(tool_result_root)
    except ValueError:
        return False
    parts = rel.parts
    return (
        len(parts) == 3
        and parts[1] == "tool-results"
        and candidate.suffix.lower() in _OFFLOADED_TOOL_RESULT_SUFFIXES
    )


def _build_workspace_allowed_roots(
    project_cwd: str | Path,
    skill_runtime: dict[str, Any] | None,
    scratch_dirs: list[str] | None = None,
) -> list[Path]:
    """Roots the boundary hook accepts for both reads and writes.

    ``scratch_dirs`` are extra absolute directories outside the workspace that the
    deployment declares writable (see ``resolve_workspace_scratch_dirs``); the
    caller resolves them from config so this stays a pure function of its inputs.
    """
    roots = [Path(project_cwd).expanduser().resolve(strict=False)]
    enabled_folders = set(_dedupe_strings((skill_runtime or {}).get("enabled_folders")))
    enabled_roots = dict((skill_runtime or {}).get("enabled_roots") or {})
    for root in enabled_roots.values():
        if str(root or "").strip():
            roots.append(Path(str(root)).expanduser().resolve(strict=False))

    primary_root = str((skill_runtime or {}).get("primary_root") or "").strip()
    if enabled_folders and primary_root:
        roots.append(Path(primary_root).expanduser().resolve(strict=False))

    if PLATFORM_TOOLS_SKILL_FOLDER in enabled_folders and primary_root:
        sibling_platform_root = Path(primary_root).expanduser().resolve(strict=False).parent / PLATFORM_TOOLS_SKILL_FOLDER
        if sibling_platform_root.exists():
            roots.append(sibling_platform_root.resolve(strict=False))

    for scratch_dir in _dedupe_strings(scratch_dirs):
        roots.append(Path(scratch_dir).expanduser().resolve(strict=False))

    deduped: list[Path] = []
    seen: set[str] = set()
    for root in roots:
        key = str(root)
        if key in seen:
            continue
        deduped.append(root)
        seen.add(key)
    return deduped


def _iter_tool_path_inputs(tool_name: str, tool_input: dict[str, Any]) -> list[tuple[str, str]]:
    keys = _FILE_BOUNDARY_PATH_KEYS.get(tool_name, ())
    results: list[tuple[str, str]] = []
    for key in keys:
        value = tool_input.get(key)
        if value is None:
            continue
        if isinstance(value, (list, tuple, set)):
            results.extend((key, str(item)) for item in value if str(item or "").strip())
        else:
            text = str(value or "").strip()
            if text:
                results.append((key, text))
    return results


def _normalize_bash_token(token: str) -> str:
    return str(token or "").strip().strip("'\"").rstrip(",;")


def _tokenize_bash_command(command: str) -> list[str]:
    """Split a Bash command into words *and* standalone shell operators.

    Unlike ``shlex.split``, punctuation-aware tokenizing separates redirect and
    control operators from adjacent paths even without whitespace, so
    ``cat a>/tmp/out`` yields ``["cat", "a", ">", "/tmp/out"]`` and the redirect
    target is validated instead of hidden inside a glued ``a>/tmp/out`` token.
    """
    lexer = shlex.shlex(command, posix=True, punctuation_chars=True)
    lexer.whitespace_split = True
    try:
        return list(lexer)
    except ValueError:
        return command.split()


def _bash_command_has_linebreak(command: str) -> bool:
    return "\n" in command or "\r" in command


def _bash_command_has_process_substitution(tokens: list[str]) -> bool:
    return any(token in {"<(", ">("} for token in tokens)


def _bash_references_offloaded_tool_result(tokens: list[str], tool_result_root: Path | None) -> bool:
    if tool_result_root is None:
        return False
    for token in tokens:
        normalized = _normalize_bash_token(token)
        if not normalized.startswith("/"):
            continue
        candidate = Path(normalized).expanduser().resolve(strict=False)
        if _is_offloaded_tool_result_path(candidate, tool_result_root):
            return True
    return False


def _bash_token_path_candidates(normalized: str) -> list[str]:
    """Absolute paths carried by one Bash word.

    A word that is itself an absolute path is its own candidate. Paths also hide
    inside ``key=value`` words — ``dd if=/etc/shadow``, ``tar --file=/etc/passwd``,
    ``FOO=/etc/shadow cat $FOO`` — which would otherwise skip the boundary check
    entirely because the word does not start with "/". Checking the assignment
    itself is what catches the variable-indirection case: the path literal is
    visible here even though ``$FOO`` at the use site is not resolvable.

    Only ``/``-rooted segments are returned; relative values (``LANG=en_US.UTF-8``,
    ``--pretty=format:%H``) carry nothing for the boundary to check.
    """
    if normalized.startswith("/"):
        return [normalized]
    if not _BASH_OPTION_ASSIGNMENT_RE.match(normalized):
        return []
    value = normalized.split("=", 1)[1]
    return [
        segment
        for segment in _BASH_ASSIGNMENT_VALUE_SPLIT_RE.split(value)
        if segment.startswith("/")
    ]


def _classify_bash_operator(token: str) -> str | None:
    """Return ``"redirect"``/``"separator"`` when ``token`` is a shell operator.

    An operator token is a run made up *only* of ``_BASH_OPERATOR_CHARS``; a
    quoted argument that merely contains one of those characters (e.g. ``a>b``)
    keeps its other characters and is treated as an ordinary word.
    """
    if not token or any(ch not in _BASH_OPERATOR_CHARS for ch in token):
        return None
    return "redirect" if any(ch in "<>" for ch in token) else "separator"


def _validate_bash_workspace_boundary(
    command: str,
    allowed_roots: list[Path],
    runtime_env: dict[str, str] | None,
    tool_result_root: Path | None = None,
) -> str | None:
    if _BASH_PARENT_SEGMENT_RE.search(command.replace("\\", "/")):
        return "Bash command uses a parent directory segment; stay inside the current agent workspace."
    tokens = _tokenize_bash_command(command)

    if _bash_references_offloaded_tool_result(tokens, tool_result_root) and (
        _bash_command_has_linebreak(command) or _bash_command_has_process_substitution(tokens)
    ):
        return (
            "Bash command uses unsupported shell syntax with an offloaded tool result; "
            "use Read or a simple single-line read-only command."
        )

    python_bin = str((runtime_env or {}).get("DATAAGENT_PYTHON_BIN") or "").strip()
    allowed_executable = Path(python_bin).expanduser().resolve(strict=False) if python_bin else None

    # Track the command word of the current pipeline segment and whether the
    # current token sits in a redirect target position, so the offloaded
    # tool-result exception can stay read-only: it is granted only to a
    # non-redirect argument of a known read-only viewer command.
    segment_command: str | None = None
    prev_was_redirect = False
    for token in tokens:
        operator = _classify_bash_operator(token)
        if operator == "redirect":
            prev_was_redirect = True
            continue
        if operator == "separator":
            segment_command = None
            prev_was_redirect = False
            continue

        is_redirect_target = prev_was_redirect
        prev_was_redirect = False
        normalized = _normalize_bash_token(token)

        # The first non-assignment word of a segment is its command word.
        if segment_command is None and not is_redirect_target and normalized:
            if not _BASH_ASSIGNMENT_RE.match(normalized):
                segment_command = Path(normalized).name

        if not normalized or normalized.startswith("$") or _URL_SCHEME_RE.match(normalized):
            continue
        if _path_has_parent_segment(normalized):
            return "Bash command uses a parent directory segment; stay inside the current agent workspace."
        for value in _bash_token_path_candidates(normalized):
            candidate = Path(value).expanduser().resolve(strict=False)
            if allowed_executable and candidate == allowed_executable:
                continue
            if _is_discard_sink(candidate):
                continue
            if _path_is_allowed(candidate, allowed_roots):
                continue
            if (
                not is_redirect_target
                and segment_command in _BASH_READONLY_COMMANDS
                and _is_offloaded_tool_result_path(candidate, tool_result_root)
            ):
                continue
            return f"Bash command references absolute path outside workspace: {value}"
    return None


def _validate_workspace_tool_boundary(
    tool_name: str,
    tool_input: dict[str, Any] | None,
    project_cwd: str | Path,
    allowed_roots: list[Path],
    runtime_env: dict[str, str] | None,
) -> str | None:
    normalized_tool = str(tool_name or "").strip()
    input_payload = tool_input or {}
    workspace = Path(project_cwd).expanduser().resolve(strict=False)

    # The only path outside the workspace the agent may touch is an offloaded
    # tool-result file, via Read or a Bash command that merely references it.
    # Resolve the per-project tool-result root so the surrounding session
    # transcripts stay denied; other tools never get this exception.
    tool_result_root = (
        _resolve_claude_project_data_dir(workspace, runtime_env)
        if normalized_tool in ("Read", "Bash")
        else None
    )

    if normalized_tool == "Bash":
        command = str(input_payload.get("command") or "").strip()
        if command:
            return _validate_bash_workspace_boundary(command, allowed_roots, runtime_env, tool_result_root)
        return None

    for key, value in _iter_tool_path_inputs(normalized_tool, input_payload):
        if _path_has_parent_segment(value):
            return f"{normalized_tool} {key} uses a parent directory segment; stay inside the current agent workspace."
        candidate = _resolve_workspace_candidate(value, workspace)
        if _path_is_allowed(candidate, allowed_roots):
            continue
        if _is_discard_sink(candidate):
            continue
        if _is_offloaded_tool_result_path(candidate, tool_result_root):
            continue
        return f"{normalized_tool} {key} is outside workspace or enabled Skill roots: {value}"
    return None


def _build_workspace_boundary_hooks(
    project_cwd: str | Path,
    skill_runtime: dict[str, Any] | None,
    runtime_env: dict[str, str] | None,
) -> dict[str, list[Any]]:
    workspace = Path(project_cwd).expanduser().resolve(strict=False)
    allowed_roots = _build_workspace_allowed_roots(
        workspace, skill_runtime, resolve_workspace_scratch_dirs(get_settings())
    )

    async def _pre_tool_use(input_data: dict[str, Any], tool_use_id: str | None, context: dict[str, Any]) -> dict[str, Any]:
        tool_name = str((input_data or {}).get("tool_name") or "")
        tool_input = (input_data or {}).get("tool_input") or {}
        reason = _validate_workspace_tool_boundary(tool_name, tool_input, workspace, allowed_roots, runtime_env)
        if not reason:
            return {"continue_": True, "suppressOutput": True}
        return {
            "decision": "block",
            "reason": reason,
            "hookSpecificOutput": {
                "hookEventName": "PreToolUse",
                "permissionDecision": "deny",
                "permissionDecisionReason": reason,
            },
        }

    return {"PreToolUse": [SimpleNamespace(matcher=None, hooks=[_pre_tool_use])]}


def _build_system_prompt(
    database_hint: str | None,
    skill_runtime: dict[str, Any] | None = None,
    agent_snapshot: dict[str, Any] | None = None,
) -> str:
    enabled_skills = list((skill_runtime or {}).get("enabled_folders") or [])
    enabled_skills_text = "、".join(enabled_skills) if enabled_skills else "未配置"
    lines = [_load_system_prompt_template(), "", "# 运行时上下文", f"- 已启用 Skills：当前已启用：{enabled_skills_text}。"]
    # Same config source as the boundary hook, so the prompt never advertises a
    # scratch dir the hook would then deny (or hide one it would allow).
    scratch_dirs = resolve_workspace_scratch_dirs(get_settings())
    if scratch_dirs:
        lines.append(
            f"- 可写临时目录：{'、'.join(scratch_dirs)}。仅存放中间过程文件，"
            "运行结束即可能失效；最终交付文件仍必须写入工作区 `output/`。"
        )
    else:
        lines.append("- 可写临时目录：无。所有文件读写都必须在当前会话工作区内完成。")
    custom_prompt = str((agent_snapshot or {}).get("system_prompt") or "").strip()
    if custom_prompt:
        lines.extend(["", "# 智能体系统提示词", custom_prompt])
    data_scope = normalize_data_scope((agent_snapshot or {}).get("data_scope") or {})
    scope_items = data_scope.get("allowed_scopes", [])
    if scope_items:
        lines.extend(["", "# 已授权数据范围"])
        for item in scope_items:
            cluster_text = "null" if item.get("cluster_id") is None else str(item.get("cluster_id"))
            lines.append(
                f"- cluster_id={cluster_text}, source_type={item.get('source_type') or ''}, database={item.get('database') or ''}"
            )
    else:
        lines.extend(["", "# 已授权数据范围", "- 无。未配置数据范围时禁止访问任何元数据或查询任何数据。"])
    if database_hint:
        lines.append(f"- 用户显式提供的 database hint: {database_hint}")
    return "\n".join(lines)


def resolve_agent_skill_runtime(
    agent_snapshot: dict[str, Any] | None,
    fallback_runtime: dict[str, Any],
) -> dict[str, Any]:
    selected = _dedupe_strings((agent_snapshot or {}).get("skill_folders"))
    if not selected:
        if agent_snapshot:
            return {
                "primary_folder": "",
                "primary_root": str(resolve_builtin_skill_root_dir()),
                "enabled_folders": [],
                "enabled_roots": {},
            }
        return fallback_runtime
    discovery_root = resolve_skill_discovery_root_dir()
    roots = {folder: str((discovery_root / folder).resolve()) for folder in selected}
    primary_folder = selected[0]
    return {
        "primary_folder": primary_folder,
        "primary_root": roots.get(primary_folder, str(resolve_builtin_skill_root_dir())),
        "enabled_folders": selected,
        "enabled_roots": roots,
    }


def _sanitize_user_visible_content(question: str, content: str) -> str:
    return str(content or "").strip()


def _extract_block(block: Any) -> tuple[str, str, dict[str, Any]]:
    if isinstance(block, dict):
        block_type = str(block.get("type") or "unknown")
        text = _extract_text_from_payload(block)
        return block_type, text, block

    block_type = str(getattr(block, "type", type(block).__name__) or "unknown")
    payload: dict[str, Any] = {}
    for key in ("id", "name", "input", "tool_id", "tool_use_id", "text", "thinking", "content", "result"):
        value = getattr(block, key, None)
        if value is not None:
            payload[key] = value

    text = _extract_text_from_payload(payload)
    if not text:
        maybe_text = getattr(block, "text", None)
        if isinstance(maybe_text, str):
            text = maybe_text
    return block_type, text, payload


def _extract_text_from_payload(payload: dict[str, Any]) -> str:
    for key in ("text", "thinking", "content", "result"):
        value = payload.get(key)
        if isinstance(value, str) and value.strip():
            return value
        if isinstance(value, list):
            parts: list[str] = []
            for item in value:
                if isinstance(item, str):
                    parts.append(item)
                elif isinstance(item, dict) and isinstance(item.get("text"), str):
                    parts.append(str(item.get("text")))
            if parts:
                return "\n".join(parts)
    return ""


def _append_delta(current: str, incoming: str) -> tuple[str, str]:
    new = str(incoming or "")
    if not new:
        return current, ""
    if not current:
        return new, new
    if new == current:
        return current, ""
    if new.startswith(current):
        return new, new[len(current):]
    if current.endswith(new):
        return current, ""
    return current + new, new


def _build_runtime_env(
    cfg,
    provider_env: dict[str, str],
    params: Any | None = None,
    skill_runtime: dict[str, Any] | None = None,
) -> dict[str, str]:
    """Build the environment dict handed to the skill-driven agent subprocess.

    Inherits the current process env, overlays ``provider_env`` (provider/model
    credentials and base URL), and exposes the skill-agnostic invocation contract
    (``DATAAGENT_PYTHON_BIN``/``DATAAGENT_SKILL_ROOT``) plus per-run knobs derived
    from ``cfg`` and ``params`` (query limit, SQL read timeout, enabled skills,
    data scope). No direct DB connection settings are exposed here — those stay at
    the deploy/skill layer. See AGENTS.md "Intelligent Query module rules".
    """
    python_bin = Path(sys.executable).absolute()
    python_dir = str(python_bin.parent)
    skills_root = Path(str((skill_runtime or {}).get("primary_root") or resolve_builtin_skill_root_dir())).resolve()
    enabled_folders = [str(item) for item in ((skill_runtime or {}).get("enabled_folders") or [])]
    enabled_roots = dict((skill_runtime or {}).get("enabled_roots") or {})
    platform_skill_root = str(enabled_roots.get(PLATFORM_TOOLS_SKILL_FOLDER) or "").strip()
    if not platform_skill_root:
        sibling_platform_root = skills_root.parent / PLATFORM_TOOLS_SKILL_FOLDER
        if sibling_platform_root.is_dir():
            platform_skill_root = str(sibling_platform_root)
    existing_path = str(os.getenv("PATH") or "").strip()
    runtime_path = python_dir if not existing_path else f"{python_dir}:{existing_path}"
    # Preserve the current process environment so skill-private env vars can be
    # wired entirely at deploy/skill layer without being re-modeled here.
    runtime_env = dict(os.environ)
    runtime_env.update(provider_env)
    sql_read_timeout = int(getattr(params, "sql_read_timeout_seconds", 0) or 0)
    if sql_read_timeout <= 0:
        # 兼容历史/恢复任务未持久化该值的情况，按执行模式回落到统一档位，
        # 避免向技能传递 0 导致使用不一致的短默认值
        sql_read_timeout = resolve_sql_read_timeout_seconds(cfg, getattr(params, "execution_mode", None))
    original_question = str(getattr(params, "question", "") or "").strip()
    runtime_env.update(
        {
            "DATAAGENT_QUERY_LIMIT": str(int(cfg.query_result_limit or 1000)),
            "DATAAGENT_RESULT_PREVIEW_ROWS": str(min(20, int(cfg.query_result_limit or 1000))),
            "DATAAGENT_SQL_READ_TIMEOUT_SECONDS": str(sql_read_timeout),
            "DATAAGENT_ORIGINAL_QUESTION": original_question,
            "DATAAGENT_PYTHON_BIN": str(python_bin),
            "DATAAGENT_SKILL_ROOT": str(skills_root),
            "DATAAGENT_ENABLED_SKILLS": ",".join(enabled_folders),
            "DATAAGENT_ENABLED_SKILL_ROOTS": json.dumps(enabled_roots, ensure_ascii=False),
            "DATAAGENT_DATA_SCOPE_JSON": json.dumps(
                normalize_data_scope((getattr(params, "agent_snapshot", None) or {}).get("data_scope") or {}),
                ensure_ascii=False,
                sort_keys=True,
            ),
            "VIRTUAL_ENV": str(python_bin.parent.parent),
            "PATH": runtime_path,
            "TZ": str(os.getenv("TZ") or "Asia/Shanghai"),
        }
    )
    if platform_skill_root:
        runtime_env["DATAAGENT_PLATFORM_SKILL_ROOT"] = str(Path(platform_skill_root).resolve())
    agent_env = getattr(params, "agent_env_vars", None)
    if not agent_env and getattr(params, "agent_snapshot", None):
        agent_env = dict((getattr(params, "agent_snapshot", None) or {}).get("env_vars") or {})
    if isinstance(agent_env, dict):
        runtime_env.update({str(key): str(value) for key, value in agent_env.items()})
    # Keep the portal-selected MCP timeout deterministic. MCP_TOOL_TIMEOUT is
    # process-wide (other MCP servers in this CLI would inherit it); Claude Code
    # 2.1.142+ uses it for both the overall call and the HTTP/SSE per-request fetch
    # ceiling. Apply it after profile env so an agent cannot silently restore the
    # old 60s ceiling. Values above 300s also require raising and verifying
    # CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT; see the Streamable HTTP design.
    portal_mcp_timeout_seconds = max(
        1,
        int(getattr(cfg, "dataagent_portal_mcp_tool_timeout_seconds", 0) or 180),
    )
    runtime_env["MCP_TOOL_TIMEOUT"] = str(portal_mcp_timeout_seconds * 1000)
    return runtime_env


def _is_running_as_root() -> bool:
    geteuid = getattr(os, "geteuid", None)
    if not callable(geteuid):
        return False
    try:
        return int(geteuid()) == 0
    except Exception:
        return False


def _resolve_sdk_permission_mode(permission_mode: str | None = None) -> str:
    # The SDK permission mode mirrors the logical session mode 1:1 so that the
    # in-run gate (can_use_tool) actually fires for plan/default/acceptEdits.
    # The only deviation is the root fallback below.
    mode = normalize_permission_mode(permission_mode)
    if mode == "bypassPermissions" and _is_running_as_root():
        # Claude Code rejects bypassPermissions under root/sudo, and the sandbox
        # runner runs as uid 0 by default (it needs the mounted docker socket to
        # spawn per-session containers). Degrade only this one mode to the
        # standard gated mode; plan/default/acceptEdits are unaffected.
        return "default"
    return mode


def _build_portal_mcp_servers(
    cfg: Any,
    mcp_server_ids: list[str] | tuple[str, ...] | None = None,
    agent_snapshot: dict[str, Any] | None = None,
) -> dict[str, dict[str, Any]]:
    selected = _dedupe_strings(mcp_server_ids)
    if mcp_server_ids is not None and PORTAL_MCP_SERVER_NAME not in selected:
        return {}
    enabled = bool(getattr(cfg, "dataagent_portal_mcp_enabled", True))
    if not enabled:
        return {}

    raw_url = str(getattr(cfg, "dataagent_portal_mcp_base_url", "") or "").strip()
    token = str(getattr(cfg, "dataagent_portal_mcp_token", "") or "").strip()
    if not raw_url or not token:
        return {}

    header_name = (
        str(getattr(cfg, "dataagent_portal_mcp_token_header_name", "") or "").strip()
        or "X-Portal-MCP-Token"
    )
    headers = {
        header_name: token,
    }
    if agent_snapshot is not None:
        headers["X-Agent-Data-Scope"] = encode_scope_header((agent_snapshot or {}).get("data_scope") or {})

    # portal-mcp is mounted as a Starlette sub-app; /mcp redirects to /mcp/, and
    # Streamable HTTP clients may not follow POST redirects.
    url = raw_url.rstrip("/") + "/"
    # portal-mcp is a remote service, so use the protocol's native Streamable HTTP
    # transport. Claude Code 2.1.142+ honors MCP_TOOL_TIMEOUT for the HTTP request
    # ceiling. The server is stateless, so subsequent calls do not depend on a
    # logical MCP session surviving; an interrupted in-flight call still fails
    # instead of being retried implicitly. Keep one transport path instead of
    # duplicating JSON-RPC/SSE semantics in a local stdio bridge. See the
    # Streamable HTTP design document.
    return {
        PORTAL_MCP_SERVER_NAME: {
            "type": "http",
            "url": url,
            "headers": headers,
        }
    }


def _build_allowed_tools(
    mcp_servers: dict[str, Any] | None = None,
    allowed_tools: list[str] | tuple[str, ...] | None = None,
    permission_mode: str | None = None,
) -> list[str]:
    allowed = _dedupe_strings(allowed_tools) if allowed_tools is not None else list(SAFE_AUTO_ALLOWED_TOOLS)
    mode = normalize_permission_mode(permission_mode)
    if mcp_servers and PORTAL_MCP_SERVER_NAME in mcp_servers:
        tool_names = list(PORTAL_MCP_TOOL_NAMES) + sorted(WRITE_TOOL_NAMES)
        for tool_name in tool_names:
            qualified = f"mcp__{PORTAL_MCP_SERVER_NAME}__{tool_name}"
            # allowed_tools is the auto-allow fast-path. Tools that would need
            # confirmation (or are plan-denied) in the current mode are left out so
            # they route through can_use_tool instead of auto-running. Now that the
            # SDK permission mode mirrors the logical mode, that callback fires
            # reliably for plan/default/acceptEdits; under bypassPermissions nothing
            # requires confirmation, so all write tools stay auto-allowed.
            if (mode == "plan" and plan_denies_tool(qualified)) or requires_confirmation(qualified, mode):
                continue
            allowed.append(qualified)

    deduped: list[str] = []
    seen: set[str] = set()
    for item in allowed:
        name = str(item or "").strip()
        if not name or name in seen:
            continue
        deduped.append(name)
        seen.add(name)
    return deduped


def _default_model_for_provider(provider_id: str) -> str:
    if provider_id == "openrouter":
        return "anthropic/claude-sonnet-4.5"
    if provider_id == "anyrouter":
        return "claude-opus-4-6"
    return "claude-sonnet-4-20250514"


def _result_subtype_to_reason(subtype: str, detail: str) -> str:
    st = str(subtype or "").strip()
    if st == "error_max_turns":
        return "模型在最大轮次限制内未完成输出"
    if st.startswith("error"):
        return "模型会话异常结束"
    if detail:
        return detail
    return "模型会话未正常结束"


def _resolve_max_turns(cfg, execution_mode: str | None, agent_max_turns: int | None = None) -> int:
    if int(agent_max_turns or 0) > 0:
        return max(1, int(agent_max_turns or 0))
    mode = str(execution_mode or "").strip().lower()
    if mode in {"background", "auto"}:
        return max(1, int(getattr(cfg, "agent_background_max_turns", 0) or getattr(cfg, "agent_max_turns", 0) or 40))
    return max(1, int(getattr(cfg, "agent_interactive_max_turns", 0) or getattr(cfg, "agent_max_turns", 0) or 24))


def _has_visible_value(value: Any) -> bool:
    if value is None:
        return False
    if isinstance(value, str):
        return bool(value.strip())
    if isinstance(value, (list, tuple, set, dict)):
        return bool(value)
    return True


def _block_has_tool_output(block: dict[str, Any]) -> bool:
    block_type = str(block.get("type") or "").strip()
    if block_type not in {"tool", "tool_result", "tool_use"}:
        return False
    if _has_visible_value(block.get("output")):
        return True
    payload = block.get("payload")
    if isinstance(payload, dict):
        for key in ("output", "content", "result", "stdout", "partial_json"):
            if _has_visible_value(payload.get(key)):
                return True
    return False


def _partial_completion_note(reason: str) -> str:
    text = str(reason or "").strip()
    if "最大轮次" in text:
        return "注：本次推理达到轮次上限，已返回当前可用结果。"
    if "超时" in text:
        return "注：本次执行耗时较长，已返回当前可用结果。"
    return "注：本次执行未完整结束，已返回当前可用结果。"


def _recover_partial_content(
    *,
    question: str,
    main_text: str,
    blocks: dict[str, dict[str, Any]],
    reason: str,
) -> str:
    sanitized = _sanitize_user_visible_content(question, str(main_text or "").strip())
    if sanitized:
        note = _partial_completion_note(reason)
        if note and note not in sanitized:
            return f"{sanitized}\n\n{note}"
        return sanitized
    if any(_block_has_tool_output(block) for block in blocks.values()):
        return f"{_partial_completion_note(reason)} 请查看上方思考过程中的工具输出。"
    return ""


def _is_recoverable_timeout_reason(reason: str) -> bool:
    return "超时" in str(reason or "")


def _collect_exception_parts(error: Exception) -> list[str]:
    parts: list[str] = []
    seen: set[str] = set()
    current: BaseException | None = error
    depth = 0
    while current is not None and depth < 8:
        depth += 1
        text = str(current or "").strip() or current.__class__.__name__
        if text not in seen:
            seen.add(text)
            parts.append(text)
        current = current.__cause__ or current.__context__
    return parts


def _format_exception_reason(error: Exception) -> str:
    parts = _collect_exception_parts(error)
    if not parts:
        return error.__class__.__name__

    lowered = [p.lower() for p in parts]
    if any(("timeout" in x) or ("timed out" in x) or ("wouldblock" in x) for x in lowered):
        return "请求超时，模型服务在限定时间内未返回"
    if any("cancel" in x for x in lowered):
        return "请求被取消"
    if any(("ssl" in x) or ("certificate" in x) or ("handshake" in x) for x in lowered):
        return "模型网关 TLS 握手失败或证书无效"
    if any(("cloudflare" in x and "1001" in x) or ("error code: 1001" in x) for x in lowered):
        return "模型网关域名未解析（Cloudflare 1001）"

    return parts[0]


def _safe_stringify(value: Any) -> str:
    if isinstance(value, str):
        return value
    try:
        return json.dumps(value, ensure_ascii=False)
    except Exception:
        return str(value)


def _clip_text(text: str, max_chars: int) -> str:
    raw = str(text or "")
    if len(raw) <= max_chars:
        return raw
    return raw[:max_chars] + f"...(truncated,total={len(raw)})"


def _safe_base_url(raw_url: str | None) -> str:
    return _safe_base_url_for_log(raw_url)
