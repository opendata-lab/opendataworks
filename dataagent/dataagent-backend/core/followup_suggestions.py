from __future__ import annotations

import json
import logging
import os
import re
from collections.abc import Awaitable, Callable
from typing import Any

import anyio

from config import get_settings
from core.provider_runtime import build_provider_env, normalize_provider_id, safe_base_url_for_log
from core.skill_admin_service import resolve_runtime_provider_selection
from core.skill_discovery import resolve_agent_project_cwd
from core.claude_cli import resolve_claude_cli_path

logger = logging.getLogger(__name__)

MAX_SUGGESTIONS = 3
MAX_SUGGESTION_LENGTH = 64
DEFAULT_TIMEOUT_SECONDS = 20
DEFAULT_MAX_TURNS = 10
SUGGESTION_TEXT_KEYS = ("question", "text", "content", "title", "value", "label")
FOLLOWUP_SCHEMA_EXAMPLE = '{"suggestions":["问题1","问题2"]}'

ModelRunner = Callable[..., Awaitable[str]]


def _strip_code_fence(text: str) -> str:
    value = str(text or "").strip()
    if value.startswith("```"):
        value = re.sub(r"^```(?:json)?\s*", "", value, flags=re.IGNORECASE)
        value = re.sub(r"\s*```$", "", value)
    return value.strip()


def _clean_suggestion(value: Any) -> str:
    text = str(value or "").strip()
    text = re.sub(r"^\s*[-*•]\s*", "", text)
    text = re.sub(r"^\s*\d+[.)、]\s*", "", text)
    text = re.sub(r"\s+", " ", text).strip(" \t\r\n\"'`")
    if len(text) > MAX_SUGGESTION_LENGTH:
        text = text[:MAX_SUGGESTION_LENGTH].rstrip()
    return text


def _parse_json_like_value(value: str) -> Any | None:
    text = _strip_code_fence(value)
    if not text or text[0] not in "{[":
        return None
    try:
        return json.loads(text)
    except Exception:
        return None


def _suggestion_text_candidates(value: Any, *, depth: int = 0) -> list[Any]:
    if depth > 4 or value is None:
        return []
    if isinstance(value, dict):
        if "suggestions" in value:
            return _suggestion_text_candidates(value.get("suggestions"), depth=depth + 1)
        for key in SUGGESTION_TEXT_KEYS:
            if key in value and value.get(key) not in (None, ""):
                return _suggestion_text_candidates(value.get(key), depth=depth + 1)
        return []
    if isinstance(value, list):
        candidates: list[Any] = []
        for item in value:
            candidates.extend(_suggestion_text_candidates(item, depth=depth + 1))
        return candidates
    if isinstance(value, str):
        parsed = _parse_json_like_value(value)
        if parsed is not None:
            return _suggestion_text_candidates(parsed, depth=depth + 1)
        return [line for line in value.splitlines() if line.strip()]
    return [value]


def _normalize_suggestions(values: Any, *, previous_question: str) -> list[str]:
    previous = str(previous_question or "").strip()
    previous_key = previous.lower()
    seen: set[str] = set()
    result: list[str] = []
    for value in _suggestion_text_candidates(values):
        text = _clean_suggestion(value)
        if not text:
            continue
        key = text.lower()
        if key == previous_key or key in seen:
            continue
        seen.add(key)
        result.append(text)
        if len(result) >= MAX_SUGGESTIONS:
            break
    return result


def _parse_model_suggestions(raw: str, *, previous_question: str) -> list[str]:
    text = _strip_code_fence(raw)
    try:
        parsed = json.loads(text)
    except Exception:
        parsed = text
    return _normalize_suggestions(parsed, previous_question=previous_question)


def _parse_strict_model_suggestions(raw: str, *, previous_question: str) -> tuple[list[str], str]:
    text = _strip_code_fence(raw)
    try:
        parsed = json.loads(text)
    except Exception:
        return [], "输出不是合法 JSON"

    if not isinstance(parsed, dict):
        return [], "顶层必须是 JSON 对象"

    extra_keys = set(parsed.keys()) - {"suggestions"}
    if extra_keys:
        return [], "顶层只能包含 suggestions 字段"

    values = parsed.get("suggestions")
    if not isinstance(values, list):
        return [], "suggestions 必须是数组"

    if not all(isinstance(value, str) for value in values):
        return [], "suggestions 数组中的每一项必须是字符串"

    suggestions = _normalize_suggestions(values, previous_question=previous_question)
    if not suggestions:
        return [], "suggestions 中没有可用的问题文本"
    return suggestions, ""


def _fallback_suggestions(answer_text: str, *, previous_question: str) -> list[str]:
    text = str(answer_text or "")
    if not text.strip():
        return []
    if re.search(r"\b(sql|select|from|where)\b", text, flags=re.IGNORECASE):
        values = [
            "解释一下这个 SQL 的逻辑",
            "这个查询还能按哪些维度继续分析？",
            "帮我检查这个 SQL 是否有优化空间",
        ]
    elif re.search(r"图表|趋势|chart|折线|柱状|饼图", text, flags=re.IGNORECASE):
        values = [
            "对图表展现的趋势做个深度解读",
            "按业务维度拆解这个趋势",
            "查看异常波动对应的明细",
        ]
    else:
        values = [
            "按核心维度做进一步对比",
            "查看这个结果的明细数据",
            "总结一下可能的业务原因",
        ]
    return _normalize_suggestions(values, previous_question=previous_question)


def _build_prompt(*, previous_question: str, answer_text: str, result_summary: str) -> str:
    sections = [
        "请基于上一轮问答，生成 2-3 条用户最可能继续追问的问题。",
        f"要求：只输出 JSON；格式为 {FOLLOWUP_SCHEMA_EXAMPLE}；不要输出 Markdown、编号或解释。",
        "suggestions 数组中的每一项必须是字符串，禁止对象、嵌套数组或额外字段。",
        "追问必须贴合当前回答中的事实、指标、SQL、图表或异常点，避免重复原问题。",
        f"上一轮用户问题：{previous_question}",
        f"上一轮助手回答：{answer_text}",
    ]
    if str(result_summary or "").strip():
        sections.append(f"结果摘要：{result_summary}")
    return "\n\n".join(sections)


def _build_format_retry_prompt(*, original_prompt: str, raw: str, error: str) -> str:
    return "\n\n".join(
        [
            "上一次输出不符合格式要求。",
            f"格式错误：{error}",
            f"必须只输出合法 JSON，且格式严格为 {FOLLOWUP_SCHEMA_EXAMPLE}。",
            "suggestions 数组中的每一项必须是字符串，禁止对象、嵌套数组、JSON 字符串、Markdown、编号、解释或额外字段。",
            "请基于下面的原始任务重新输出：",
            original_prompt,
            f"上一次错误输出：{str(raw or '').strip()[:1200]}",
        ]
    )


def _extract_sdk_text(message: Any) -> str:
    type_name = type(message).__name__
    if type_name == "ResultMessage":
        return str(getattr(message, "result", "") or "")
    content = getattr(message, "content", None)
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    parts: list[str] = []
    for block in content:
        if isinstance(block, dict):
            value = block.get("text") or block.get("content")
        else:
            value = getattr(block, "text", None)
        if value:
            parts.append(str(value))
    return "\n".join(parts)


async def _default_model_runner(
    *,
    prompt: str,
    provider_id: str,
    model: str,
    timeout_seconds: int,
) -> str:
    try:
        from claude_agent_sdk import ClaudeAgentOptions, query as claude_query
    except ImportError as exc:
        raise RuntimeError(f"claude-agent-sdk 未安装: {exc}") from exc

    cfg = get_settings()
    runtime_target = resolve_runtime_provider_selection(provider_id, model)
    resolved_provider_id = normalize_provider_id(runtime_target.get("provider_id"), runtime_target.get("base_url"))
    resolved_model = str(runtime_target.get("model") or "").strip()
    provider_env = build_provider_env(
        resolved_provider_id,
        api_key=str(runtime_target.get("api_key") or ""),
        auth_token=str(runtime_target.get("auth_token") or ""),
        base_url=str(runtime_target.get("base_url") or ""),
    )
    runtime_env = dict(os.environ)
    runtime_env.update(provider_env)

    options_kwargs = {
        "system_prompt": "你是 OpenDataWorks 智能问数的追问建议生成器。只输出符合要求的 JSON。",
        "model": resolved_model,
        "cwd": str(resolve_agent_project_cwd()),
        "setting_sources": ["project"],
        "max_turns": DEFAULT_MAX_TURNS,
        "allowed_tools": [],
        "mcp_servers": {},
        "include_partial_messages": bool(runtime_target.get("supports_partial_messages", True)),
        "env": runtime_env,
        "stderr": lambda line: logger.error(
            "followup_suggestions.stderr provider=%s model=%s %s",
            resolved_provider_id,
            resolved_model,
            str(line or "").rstrip(),
        ),
    }
    cli_path = resolve_claude_cli_path(cfg)
    if cli_path:
        options_kwargs["cli_path"] = cli_path

    logger.info(
        "followup_suggestions.start provider=%s model=%s base_url=%s",
        resolved_provider_id,
        resolved_model,
        safe_base_url_for_log(str(runtime_target.get("base_url") or "")),
    )

    chunks: list[str] = []
    options = ClaudeAgentOptions(**options_kwargs)
    with anyio.fail_after(max(3, int(timeout_seconds or DEFAULT_TIMEOUT_SECONDS))):
        async for message in claude_query(prompt=prompt, options=options):
            text = _extract_sdk_text(message)
            if text:
                chunks.append(text)
    return "\n".join(chunks).strip()


async def generate_followup_suggestions(
    *,
    previous_question: str,
    answer_text: str,
    result_summary: str = "",
    provider_id: str = "",
    model: str = "",
    timeout_seconds: int = DEFAULT_TIMEOUT_SECONDS,
    model_runner: ModelRunner | None = None,
) -> dict[str, Any]:
    prompt = _build_prompt(
        previous_question=previous_question,
        answer_text=answer_text,
        result_summary=result_summary,
    )
    runner = model_runner or _default_model_runner
    try:
        raw = await runner(
            prompt=prompt,
            provider_id=provider_id,
            model=model,
            timeout_seconds=timeout_seconds,
        )
        suggestions, schema_error = _parse_strict_model_suggestions(raw, previous_question=previous_question)
        if not suggestions and schema_error:
            retry_prompt = _build_format_retry_prompt(original_prompt=prompt, raw=raw, error=schema_error)
            raw = await runner(
                prompt=retry_prompt,
                provider_id=provider_id,
                model=model,
                timeout_seconds=timeout_seconds,
            )
            suggestions, schema_error = _parse_strict_model_suggestions(raw, previous_question=previous_question)
        if not suggestions:
            suggestions = _parse_model_suggestions(raw, previous_question=previous_question)
        if suggestions:
            return {"suggestions": suggestions, "source": "generated"}
    except Exception as exc:
        logger.warning("followup_suggestions.failed error=%s", exc)

    fallback = _fallback_suggestions(answer_text, previous_question=previous_question)
    if fallback:
        return {"suggestions": fallback, "source": "fallback"}
    return {"suggestions": [], "source": "empty"}
