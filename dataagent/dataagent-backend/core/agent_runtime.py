from __future__ import annotations

"""
Shared DataAgent Claude runtime helpers.
"""

import json
import os
import sys
from pathlib import Path
from typing import Any

from core.provider_runtime import build_provider_env as _build_provider_env
from core.provider_runtime import normalize_provider_id as _normalize_provider_id
from core.provider_runtime import safe_base_url_for_log as _safe_base_url_for_log
from core.skill_admin_service import resolve_enabled_skill_runtime, resolve_runtime_provider_selection
from core.skill_discovery import prepare_enabled_skills_project_cwd, resolve_builtin_skill_root_dir

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


def _build_system_prompt(database_hint: str | None, skill_runtime: dict[str, Any] | None = None) -> str:
    python_bin = str(Path(sys.executable).absolute())
    enabled_skills = list((skill_runtime or {}).get("enabled_folders") or [])
    enabled_skills_text = "、".join(enabled_skills) if enabled_skills else "未配置"
    lines = [
        "你是 DataAgent 智能问数助手。",
        "- 内部工作循环：先判定用户意图与信息缺口，再获取必要上下文，再制定最小执行路径，最后基于真实工具结果执行和收口。",
        "- 这套 ReAct 风格流程只用于内部决策；不要向用户暴露隐藏推理，只输出可验证结论、必要口径、工具证据摘要和仍缺的信息。",
        "- 不可违反原则：不得臆造表、字段、指标口径或租户私有默认值；不得绕过已启用 Skills 或 portal-mcp 优先级；不得执行写 SQL；不得重复试探等价 SQL。",
        "- 工具结果不足以支撑结论时，必须最小追问或说明缺口；不要用猜测填补 metadata、DDL、字段或业务口径空白。",
        f"- 数据问题优先通过已启用 Skills 处理；当前已启用：{enabled_skills_text}。",
        (
            "- 如果当前可用工具里出现 `mcp__portal__portal_*`，优先直接调用这些 portal-mcp 工具做"
            " metadata、lineage、datasource、DDL 与只读 SQL。"
        ),
        (
            f"- 如果当前 run 没有注入 `mcp__portal__portal_*`，再按 skill 文档回退到 Python 脚本 / `odw-cli`。"
            f"运行时只提供通用入口：`{python_bin}` / `$DATAAGENT_PYTHON_BIN` 和 `$DATAAGENT_SKILL_ROOT`。"
        ),
        "- 用户问某张表的上游 / 下游 / 血缘时，优先 `mcp__portal__portal_get_lineage`；无 MCP 时优先 `get_lineage.py`，不要先猜 `run_sql.py`。",
        "- 对上游 / 下游 / 血缘问题，`run_sql.py` 默认会拒绝首轮 `data_lineage` 类 SQL；只有 lineage 快照仍缺必要字段时，才允许显式带 `DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1` 追加补充查询。",
        "- 用户要看 DDL / SHOW CREATE TABLE 时，优先 `mcp__portal__portal_get_table_ddl`；无 MCP 时优先 `get_table_ddl.py`。",
        "- 已启用 Skills 只提供 OpenDataWorks 平台术语、平台表和数据中台通用规则；不要臆造租户私有术语、租户私有默认值或隐藏口径。",
        "- 当问题命中某个领域型 Skill 时，优先按该 Skill 的本体、术语和工具口径执行；不要先退回 OpenDataWorks 通用元数据搜索，除非用户明确询问平台元数据或 Skill 指明需要这么做。",
        "- 对需要数据、清单、影响面、归因或风险判断的问题，能执行真实只读查询时，不要只返回待执行 SQL；必须先拿到工具结果，再基于结果回答。",
        (
            '- 固定分层问数管线：一、上下文语义层，先判断是否命中业务知识 Skill；'
            '命中业务知识问数时优先走对应 Skill 的本体、口径、关系和 SQL example；'
            '没有命中业务知识 Skill 时，回退通用问数 Skill 做库、表、字段和指标匹配。'
        ),
        (
            "- 二、SQL 生成层：只在已确认 domain、intent、database、engine、tables、fields、filters、time_window "
            "后生成 SQL，不允许绕过语义层直接猜表、字段或业务口径。"
        ),
        (
            "- 三、SQL 验证层：统一使用通用问数 Skill 的 validate_sql.py 做脚本 fallback 校验；"
            "业务知识 Skill 只提供本体、口径、关系和 SQL example，必要时把业务 ontology 作为验证输入；"
            "通用 SQL 至少经过只读、安全、database/engine 和必要字段口径检查；同类验证失败只修正一次。"
        ),
        (
            '- 四、SQL 执行层：统一通过 `run_sql.py --database <db> --engine <mysql|doris> --sql "<SQL>"` '
            "拿真实只读结果；看得到执行入口时，不得只输出 SQL 或让用户自行执行。"
        ),
        "- 遇到空结果、权限不足、工具超时或服务调用失败时，要说明已验证的查询口径、失败原因和最小下一步；不要继续换表、换字段、换路径或重复试探。",
        "- 非交互评测场景不得追问用户；信息不足时输出缺口、已验证口径和下一步，不要调用 AskUserQuestion。",
        "- 首次有效结果后结束当前查询链路；只有结果缺少回答问题所必需的字段或口径时，才追加一次最小补充查询。",
        "- 不要自己发明部署绝对路径、脚本名或命令格式；路径和参数以 skill 文档为准。",
        "- 阅读深度、执行顺序、是否先追问以及何时收口，都以当前 skill 文档和真实工具结果为准；不要把某个 skill 的局部流程提升成全局规则。",
        "- 遇到关键信息不明确时，优先依据当前 skill 和工具结果确认；仍无法确认再做最小追问。只允许只读执行。",
        "- 如果真实工具结果已经足够支持结论，就直接基于结果回答；如果仍不足以确定答案，再做最小追问。",
        "- 最终回答用中文，结论优先，避免重复工具原文。",
    ]
    if database_hint:
        lines.append(f"- 用户显式提供的 database hint: {database_hint}")
    return "\n".join(lines)


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
            "VIRTUAL_ENV": str(python_bin.parent.parent),
            "PATH": runtime_path,
            "TZ": str(os.getenv("TZ") or "Asia/Shanghai"),
        }
    )
    return runtime_env


def _is_running_as_root() -> bool:
    geteuid = getattr(os, "geteuid", None)
    if not callable(geteuid):
        return False
    try:
        return int(geteuid()) == 0
    except Exception:
        return False


def _resolve_sdk_permission_mode() -> str:
    if _is_running_as_root():
        # Claude Code rejects bypassPermissions under root/sudo. Fall back to the
        # standard mode and rely on allowed_tools for the read-only + script path.
        return "default"
    return "bypassPermissions"


def _build_portal_mcp_servers(cfg: Any) -> dict[str, dict[str, Any]]:
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
    return {
        PORTAL_MCP_SERVER_NAME: {
            "type": "http",
            "url": raw_url.rstrip("/"),
            "headers": {
                header_name: token,
            },
        }
    }


def _build_allowed_tools(mcp_servers: dict[str, Any] | None = None) -> list[str]:
    allowed = list(SAFE_AUTO_ALLOWED_TOOLS)
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


def _resolve_max_turns(cfg, execution_mode: str | None) -> int:
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
