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

from core.provider_runtime import build_provider_env as _build_provider_env
from core.provider_runtime import normalize_provider_id as _normalize_provider_id
from core.provider_runtime import safe_base_url_for_log as _safe_base_url_for_log
from core.data_scope import encode_scope_header, normalize_data_scope
from core.skill_admin_service import resolve_enabled_skill_runtime, resolve_runtime_provider_selection
from core.skill_discovery import (
    prepare_enabled_skills_project_cwd,
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
_URL_SCHEME_RE = re.compile(r"^[A-Za-z][A-Za-z0-9+.-]*://")


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


def _build_workspace_allowed_roots(project_cwd: str | Path, skill_runtime: dict[str, Any] | None) -> list[Path]:
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


def _validate_bash_workspace_boundary(
    command: str,
    allowed_roots: list[Path],
    runtime_env: dict[str, str] | None,
) -> str | None:
    if _BASH_PARENT_SEGMENT_RE.search(command.replace("\\", "/")):
        return "Bash command uses a parent directory segment; stay inside the current agent workspace."
    try:
        tokens = shlex.split(command)
    except ValueError:
        tokens = command.split()

    python_bin = str((runtime_env or {}).get("DATAAGENT_PYTHON_BIN") or "").strip()
    allowed_executable = Path(python_bin).expanduser().resolve(strict=False) if python_bin else None
    for token in tokens:
        normalized = _normalize_bash_token(token)
        if not normalized or normalized.startswith("$") or _URL_SCHEME_RE.match(normalized):
            continue
        if _path_has_parent_segment(normalized):
            return "Bash command uses a parent directory segment; stay inside the current agent workspace."
        if not normalized.startswith("/"):
            continue
        candidate = Path(normalized).expanduser().resolve(strict=False)
        if allowed_executable and candidate == allowed_executable:
            continue
        if not _path_is_allowed(candidate, allowed_roots):
            return f"Bash command references absolute path outside workspace: {normalized}"
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
    if normalized_tool == "Bash":
        command = str(input_payload.get("command") or "").strip()
        if command:
            return _validate_bash_workspace_boundary(command, allowed_roots, runtime_env)
        return None

    for key, value in _iter_tool_path_inputs(normalized_tool, input_payload):
        if _path_has_parent_segment(value):
            return f"{normalized_tool} {key} uses a parent directory segment; stay inside the current agent workspace."
        candidate = _resolve_workspace_candidate(value, workspace)
        if not _path_is_allowed(candidate, allowed_roots):
            return f"{normalized_tool} {key} is outside workspace or enabled Skill roots: {value}"
    return None


def _build_workspace_boundary_hooks(
    project_cwd: str | Path,
    skill_runtime: dict[str, Any] | None,
    runtime_env: dict[str, str] | None,
) -> dict[str, list[Any]]:
    workspace = Path(project_cwd).expanduser().resolve(strict=False)
    allowed_roots = _build_workspace_allowed_roots(workspace, skill_runtime)

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


def _looks_like_procedural_preamble(text: str) -> bool:
    snippet = str(text or "").strip()
    if not snippet or len(snippet) > 900:
        return False
    markers = (
        "问题类型",
        "我来",
        "让我",
        "先确认",
        "先查看",
        "先读",
        "先按固定阅读顺序",
        "按照固定阅读顺序",
        "需要先确认",
        "查看表结构",
        "字段名",
        "直接执行",
        "现在执行",
        "执行 sql",
        "生成饼图",
        "生成条形图",
        "生成折线图",
        "数据已拿到",
        "根据 playbook",
    )
    lower = snippet.lower()
    return any(marker in snippet or marker in lower for marker in markers)


def _sanitize_user_visible_content(question: str, content: str) -> str:
    text = str(content or "").strip()
    if not text:
        return text

    anchors: list[int] = []
    question_text = str(question or "").strip()
    if question_text:
        question_index = text.find(question_text)
        if question_index > 0:
            anchors.append(question_index)

    for marker in ("\n## ", "## ", "\n### ", "### ", "\n结论：", "结论："):
        index = text.find(marker)
        if index > 0:
            anchors.append(index + 1 if marker.startswith("\n") else index)

    if not anchors:
        return text

    anchor = min(anchors)
    preamble = text[:anchor].strip()
    if not _looks_like_procedural_preamble(preamble):
        return text
    return text[anchor:].lstrip()


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
    requested = str(permission_mode or "inherit").strip() or "inherit"
    if requested == "default":
        return "default"
    if _is_running_as_root():
        # Claude Code rejects bypassPermissions under root/sudo. Fall back to the
        # standard mode and rely on allowed_tools for the read-only + script path.
        return "default"
    if requested == "bypassPermissions":
        return "bypassPermissions"
    return "bypassPermissions"


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

    return {
        PORTAL_MCP_SERVER_NAME: {
            "type": "http",
            # portal-mcp is mounted as a Starlette sub-app; /mcp redirects to
            # /mcp/, and Streamable HTTP clients may not follow POST redirects.
            "url": raw_url.rstrip("/") + "/",
            "headers": headers,
        }
    }


def _build_allowed_tools(
    mcp_servers: dict[str, Any] | None = None,
    allowed_tools: list[str] | tuple[str, ...] | None = None,
) -> list[str]:
    allowed = _dedupe_strings(allowed_tools) if allowed_tools is not None else list(SAFE_AUTO_ALLOWED_TOOLS)
    if mcp_servers and PORTAL_MCP_SERVER_NAME in mcp_servers:
        allowed.extend(
            f"mcp__{PORTAL_MCP_SERVER_NAME}__{tool_name}"
            for tool_name in PORTAL_MCP_TOOL_NAMES
        )

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
