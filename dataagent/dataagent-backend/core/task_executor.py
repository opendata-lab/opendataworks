from __future__ import annotations

import asyncio
import inspect
import json
import logging
import os
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Awaitable, Callable

import anyio
import httpx

from config import get_settings
from core.agent_profile_service import DEFAULT_AGENT_ID, normalize_agent_snapshot
from core.agent_runtime import (
    _build_allowed_tools,
    _build_portal_mcp_servers,
    _build_prompt,
    _build_provider_env,
    _build_runtime_env,
    _build_system_prompt,
    _build_workspace_boundary_hooks,
    _clip_text,
    _contains_pseudo_tool_call,
    _default_model_for_provider,
    _extract_block,
    _format_exception_reason,
    _is_recoverable_timeout_reason,
    _normalize_provider_id,
    _recover_partial_content,
    _resolve_max_turns,
    _resolve_sdk_permission_mode,
    _result_subtype_to_reason,
    _safe_base_url,
    _safe_stringify,
    _strip_pseudo_tool_call_tags,
    resolve_agent_skill_runtime,
    resolve_enabled_skill_runtime,
    resolve_runtime_provider_selection,
)
from core.claude_cli import resolve_claude_cli_path
from core.sdk_block_writer import SdkBlockWriter
from core.topic_task_store import get_topic_task_store
from core.topic_workspace import prepare_topic_workspace

logger = logging.getLogger(__name__)


@dataclass
class TaskExecutionInput:
    task_id: str
    topic_id: str
    question: str
    history: list[dict[str, str]]
    resume_session_id: str | None
    provider_id: str
    model: str
    database_hint: str | None
    debug: bool = False
    timeout_seconds: int | None = None
    sql_read_timeout_seconds: int | None = None
    sql_write_timeout_seconds: int | None = None
    execution_mode: str = "background"
    agent_snapshot: dict[str, Any] | None = None
    permission_mode: str | None = None


@dataclass
class TaskExecutionResult:
    task_status: str
    content: str
    usage: dict[str, Any] | None = None
    error: dict[str, Any] | None = None
    provider_id: str = ""
    model: str = ""
    session_id: str = ""


class SdkResultAccumulator:
    """Derive final task metadata from native Claude SDK messages.

    Chat V2 renders live and historical blocks from da_agent_sdk_record. This
    accumulator only keeps the compact final assistant message fields used by
    topic history, follow-up suggestions, and task status.
    """

    def __init__(self, params: TaskExecutionInput, *, provider_id: str, model: str):
        self.params = params
        self.provider_id = provider_id
        self.model = model
        self.usage: dict[str, Any] = {}
        self.session_id = ""
        self.result_subtype = ""
        self.result_error = ""
        self.result_is_error = False
        self.provider_error_message = ""
        self._saw_stream_event = False
        self._saw_pseudo_tool_call = False
        self._saw_tool_use = False
        self._text_order: list[int] = []
        self._text_by_index: dict[int, str] = {}
        self._block_context: dict[int, dict[str, Any]] = {}
        self._next_message_block_index = 10_000

    def _note_pseudo_tool_call(self, text: str) -> None:
        if not self._saw_pseudo_tool_call and _contains_pseudo_tool_call(text):
            self._saw_pseudo_tool_call = True

    def _remember_provider_error(self, message: Any) -> None:
        text = str(message or "").strip()
        if text:
            self.provider_error_message = _clip_text(text, 4000)

    def preferred_error_message(self, fallback: str) -> str:
        if self.provider_error_message:
            return self.provider_error_message
        if self.result_is_error and self.result_error:
            return self.result_error
        return str(fallback or "").strip()

    def preferred_error_code(self) -> str:
        if self.provider_error_message or self.result_is_error:
            return str(self.result_subtype or "provider_error")
        return "model_call_failed"

    def _append_text(self, block_index: int, piece: str) -> None:
        text = str(piece or "")
        if not text:
            return
        if block_index not in self._text_by_index:
            self._text_order.append(block_index)
            self._text_by_index[block_index] = ""
        self._text_by_index[block_index] = f"{self._text_by_index[block_index]}{text}"

    def _append_message_text(self, text: str) -> None:
        self._next_message_block_index += 1
        self._append_text(self._next_message_block_index, text)

    def current_answer_text(self) -> str:
        parts = [str(self._text_by_index.get(index) or "").strip() for index in self._text_order]
        return "\n\n".join(part for part in parts if part).strip()

    def ingest(self, msg: Any) -> None:
        msg_type = type(msg).__name__
        session_id = str(getattr(msg, "session_id", "") or "").strip()
        if session_id:
            self.session_id = session_id

        if msg_type == "ResultMessage":
            self.result_subtype = str(getattr(msg, "subtype", "") or "")
            self.result_is_error = bool(getattr(msg, "is_error", False))
            result_raw = getattr(msg, "result", None)
            if result_raw is not None:
                self.result_error = _clip_text(_safe_stringify(result_raw), 2000)
                if not self.current_answer_text() and not self.result_is_error and isinstance(result_raw, str):
                    self._append_message_text(result_raw)
            if self.result_is_error and self.result_error:
                self._remember_provider_error(self.result_error)
            return

        if msg_type == "StreamEvent":
            self._saw_stream_event = True
            raw_event = getattr(msg, "event", None)
            if isinstance(raw_event, dict):
                self._ingest_stream_event(raw_event)
            return

        content = getattr(msg, "content", None)
        if msg_type == "AssistantMessage":
            assistant_error = str(getattr(msg, "error", "") or "").strip()
            if assistant_error:
                self._remember_provider_error(assistant_error)
            # In partial-streaming mode the SDK already accumulated every text
            # block from StreamEvent deltas. The trailing AssistantMessage repeats
            # the same content, so projecting it again would duplicate the final
            # answer text. Only ingest whole-message content when no partial
            # StreamEvent was observed (supports_partial_messages=false providers).
            if self._saw_stream_event:
                return
            self._ingest_assistant_content(content)

    def _ingest_assistant_content(self, content: Any) -> None:
        if isinstance(content, str):
            self._append_message_text(content)
            return
        if not isinstance(content, list):
            return
        for block in content:
            block_type, block_text, _payload = _extract_block(block)
            lower_type = block_type.lower()
            if "tool_use" in lower_type:
                self._saw_tool_use = True
            if block_text:
                self._note_pseudo_tool_call(block_text)
                if "text" in lower_type or lower_type in {"textblock", "text"}:
                    self._append_message_text(block_text)

    def _ingest_stream_event(self, raw_event: dict[str, Any]) -> None:
        event_type = str(raw_event.get("type") or "").strip()
        if not event_type:
            return

        if event_type == "message_start":
            message_payload = raw_event.get("message")
            if isinstance(message_payload, dict):
                if isinstance(message_payload.get("usage"), dict):
                    self.usage = {**self.usage, **dict(message_payload.get("usage") or {})}
            return

        if event_type == "message_delta":
            delta_payload = raw_event.get("delta") if isinstance(raw_event.get("delta"), dict) else {}
            for usage in (raw_event.get("usage"), delta_payload.get("usage")):
                if isinstance(usage, dict):
                    self.usage = {**self.usage, **dict(usage or {})}
            return

        if event_type == "content_block_start":
            block_index = int(raw_event.get("index") or 0)
            block_payload = raw_event.get("content_block") if isinstance(raw_event.get("content_block"), dict) else {}
            block_type = str(block_payload.get("type") or "").strip().lower()
            self._block_context[block_index] = {"type": block_type}
            if "tool_use" in block_type:
                self._saw_tool_use = True
            if block_type == "text" and block_payload.get("text"):
                self._append_text(block_index, str(block_payload.get("text") or ""))
            return

        if event_type == "content_block_delta":
            block_index = int(raw_event.get("index") or 0)
            block_payload = self._block_context.get(block_index) or {}
            delta_payload = raw_event.get("delta") if isinstance(raw_event.get("delta"), dict) else {}
            delta_type = str(delta_payload.get("type") or "")
            # Detect leaked pseudo tool-call tags in any block, including thinking,
            # so a drifted run is not silently reported as a clean answer. Thinking
            # deltas carry text under "thinking"; text deltas under "text". Thinking
            # text is still kept out of the visible answer below.
            if delta_type == "thinking_delta":
                self._note_pseudo_tool_call(str(delta_payload.get("thinking") or ""))
            elif delta_type == "text_delta":
                self._note_pseudo_tool_call(str(delta_payload.get("text") or ""))
            if str(block_payload.get("type") or "") != "text":
                return
            if delta_type == "text_delta":
                self._append_text(block_index, str(delta_payload.get("text") or ""))

    def build_result(self) -> TaskExecutionResult:
        content = self.current_answer_text()
        if self.result_is_error or self.provider_error_message:
            reason = self.preferred_error_message("模型会话异常结束")
            return TaskExecutionResult(
                task_status="error",
                content=reason,
                usage=self.usage or None,
                error={"code": self.preferred_error_code(), "message": reason, "detail": self.result_error},
                provider_id=self.provider_id,
                model=self.model,
                session_id=self.session_id,
            )

        if self.result_subtype.startswith("error"):
            reason = _result_subtype_to_reason(self.result_subtype, self.result_error)
            recovered_content = _recover_partial_content(
                question=self.params.question,
                main_text=content,
                blocks={},
                reason=reason,
            )
            if recovered_content:
                return TaskExecutionResult(
                    task_status="finished",
                    content=recovered_content,
                    usage=self.usage or None,
                    provider_id=self.provider_id,
                    model=self.model,
                    session_id=self.session_id,
                )
            return TaskExecutionResult(
                task_status="error",
                content=content or reason,
                usage=self.usage or None,
                error={"code": self.result_subtype or "model_error", "message": reason, "detail": self.result_error},
                provider_id=self.provider_id,
                model=self.model,
                session_id=self.session_id,
            )

        if self._saw_pseudo_tool_call:
            return self._build_format_drift_result(content)

        return TaskExecutionResult(
            task_status="finished",
            content=content or "已完成。",
            usage=self.usage or None,
            provider_id=self.provider_id,
            model=self.model,
            session_id=self.session_id,
        )

    def _build_format_drift_result(self, content: str) -> TaskExecutionResult:
        """Close out a run that leaked pseudo tool-call tags instead of a real call.

        The model turn ended without an SDK error, but it emitted XML-style
        tool-call markup as text and never produced a trustworthy final answer.
        A drifted run must always terminate as a task error: the live stream only
        carries the raw blocks (leaked tags included), so without a terminal
        error record the chat UI ends the conversation silently with no way to
        notice the failure or retry. Salvaged clean text is kept in the content
        for history; the error itself carries the user-facing retry message.
        """
        reason = "模型工具调用格式异常未正常收口"
        cleaned = _strip_pseudo_tool_call_tags(content).strip()
        synthetic_blocks: dict[str, dict[str, Any]] = (
            {"tool": {"type": "tool_result", "output": "1"}} if self._saw_tool_use else {}
        )
        recovered = _recover_partial_content(
            question=self.params.question,
            main_text=cleaned,
            blocks=synthetic_blocks,
            reason=reason,
        )
        message = "模型输出了伪工具调用标签，本次回答未正常完成，请重试"
        return TaskExecutionResult(
            task_status="error",
            content=recovered or "模型本次回答因工具调用格式异常未能正常生成，请重试。",
            usage=self.usage or None,
            error={"code": "tool_call_format_drift", "message": message, "detail": reason},
            provider_id=self.provider_id,
            model=self.model,
            session_id=self.session_id,
        )


async def execute_task_stream(
    params: TaskExecutionInput,
    *,
    emit: Callable[[dict[str, Any]], Awaitable[None] | None],
    is_cancel_requested: Callable[[], Awaitable[bool] | bool] | None = None,
) -> TaskExecutionResult:
    cfg = get_settings()
    if _should_use_sandbox_runner(cfg):
        return await _execute_task_stream_via_runner(
            params,
            emit=emit,
            is_cancel_requested=is_cancel_requested,
        )

    return await _execute_task_stream_local(
        params,
        emit=emit,
        is_cancel_requested=is_cancel_requested,
    )


def _should_use_sandbox_runner(cfg: Any) -> bool:
    return bool(str(getattr(cfg, "dataagent_sandbox_mode", "") or "").strip())


async def _execute_task_stream_via_runner(
    params: TaskExecutionInput,
    *,
    emit: Callable[[dict[str, Any]], Awaitable[None] | None],
    is_cancel_requested: Callable[[], Awaitable[bool] | bool] | None = None,
) -> TaskExecutionResult:
    cfg = get_settings()
    runner_url = str(getattr(cfg, "dataagent_sandbox_runner_url", "") or "").strip().rstrip("/")
    if not runner_url:
        raise RuntimeError("DATAAGENT_SANDBOX_RUNNER_URL is required when DATAAGENT_SANDBOX_MODE is enabled")

    endpoint = f"{runner_url}/internal/sandbox/runs"
    payload = asdict(params)
    cancel_sent = False
    stream_done = False

    async def _cancelled() -> bool:
        if is_cancel_requested is None:
            return False
        result = is_cancel_requested()
        if inspect.isawaitable(result):
            return bool(await result)
        return bool(result)

    async def _emit(record: dict[str, Any]) -> None:
        result = emit(record)
        if inspect.isawaitable(result):
            await result

    async with httpx.AsyncClient(timeout=None) as client:
        async def _watch_cancel() -> None:
            nonlocal cancel_sent
            while not stream_done:
                if not cancel_sent and await _cancelled():
                    cancel_sent = True
                    await client.post(f"{runner_url}/internal/sandbox/runs/{params.task_id}/cancel", json={"task_id": params.task_id})
                    return
                await asyncio.sleep(0.25)

        cancel_task = asyncio.create_task(_watch_cancel())
        try:
            async with client.stream("POST", endpoint, json=payload) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if not str(line or "").strip():
                        continue
                    message = json.loads(line)
                    message_type = str(message.get("type") or "")
                    if message_type == "record":
                        record = message.get("record") or {}
                        if isinstance(record, dict):
                            await _emit(record)
                        continue
                    if message_type == "result":
                        result_payload = message.get("result") or {}
                        if not isinstance(result_payload, dict):
                            break
                        return TaskExecutionResult(
                            task_status=str(result_payload.get("task_status") or "error"),
                            content=str(result_payload.get("content") or ""),
                            usage=result_payload.get("usage") if isinstance(result_payload.get("usage"), dict) else None,
                            error=result_payload.get("error") if isinstance(result_payload.get("error"), dict) else None,
                            provider_id=str(result_payload.get("provider_id") or ""),
                            model=str(result_payload.get("model") or ""),
                            session_id=str(result_payload.get("session_id") or ""),
                        )
        finally:
            stream_done = True
            cancel_task.cancel()
            try:
                await cancel_task
            except asyncio.CancelledError:
                pass

    return TaskExecutionResult(
        task_status="error",
        content="sandbox runner stream ended without a result",
        error={"code": "sandbox_runner_no_result", "message": "sandbox runner stream ended without a result"},
        provider_id=params.provider_id,
        model=params.model,
    )


async def _execute_task_stream_local(
    params: TaskExecutionInput,
    *,
    emit: Callable[[dict[str, Any]], Awaitable[None] | None],
    is_cancel_requested: Callable[[], Awaitable[bool] | bool] | None = None,
    prepared_workspace_dir: str | Path | None = None,
) -> TaskExecutionResult:
    cfg = get_settings()
    runtime_target = resolve_runtime_provider_selection(params.provider_id, params.model)
    provider_id = _normalize_provider_id(runtime_target.get("provider_id"), runtime_target.get("base_url"))
    supports_partial_messages = bool(
        runtime_target.get("supports_partial_messages", provider_id != "anthropic_compatible")
    )
    model = str(runtime_target.get("model") or cfg.claude_model or "").strip()
    if not model:
        model = _default_model_for_provider(provider_id)

    accumulator = SdkResultAccumulator(params, provider_id=provider_id, model=model)
    sdk_writer = SdkBlockWriter(get_topic_task_store(), params.task_id, params.topic_id)

    prompt = str(params.question or "").strip() if params.resume_session_id else _build_prompt(params.history, params.question)
    agent_snapshot = normalize_agent_snapshot(params.agent_snapshot) if params.agent_snapshot else None
    skill_runtime = resolve_agent_skill_runtime(agent_snapshot, resolve_enabled_skill_runtime())
    logger.info(
        "skill.resolve task_id=%s topic_id=%s source=%s agent_skill_folders=%s enabled_folders=%s enabled_roots=%s",
        params.task_id,
        params.topic_id,
        "agent" if agent_snapshot else "global_fallback",
        (agent_snapshot or {}).get("skill_folders") if agent_snapshot else None,
        skill_runtime.get("enabled_folders"),
        skill_runtime.get("enabled_roots"),
    )
    system_prompt = _build_system_prompt(params.database_hint, skill_runtime, agent_snapshot)

    try:
        from claude_agent_sdk import ClaudeAgentOptions, query as claude_query
    except ImportError as exc:
        reason = "claude-agent-sdk 未安装"
        sdk_writer.append_error(code="sdk_not_installed", message=reason, detail=str(exc))
        return TaskExecutionResult(
            task_status="error",
            content=reason,
            error={"code": "sdk_not_installed", "message": reason, "detail": str(exc)},
            provider_id=provider_id,
            model=model,
            session_id=accumulator.session_id,
        )

    env_payload = _build_provider_env(
        provider_id,
        api_key=str(runtime_target.get("api_key") or ""),
        auth_token=str(runtime_target.get("auth_token") or ""),
        base_url=str(runtime_target.get("base_url") or ""),
    )
    runtime_env = _build_runtime_env(cfg, env_payload, params, skill_runtime)
    for key, value in runtime_env.items():
        os.environ[key] = value

    enabled_folders = skill_runtime.get("enabled_folders") or []
    workspace_dir = str(prepared_workspace_dir or "").strip()
    project_cwd = prepare_topic_workspace(
        params.topic_id,
        enabled_folders,
        allow_empty=bool(agent_snapshot) or not enabled_folders,
        workspace_dir=workspace_dir or None,
    )
    workspace_env = {
        "PWD": str(project_cwd),
    }
    runtime_env.pop("DATAAGENT_WORKSPACE_DIR", None)
    runtime_env.pop("DATAAGENT_WORKSPACE_PREPARED", None)
    runtime_env.update(workspace_env)
    for key, value in workspace_env.items():
        os.environ[key] = value
    # Permission mode is a session-level choice carried on TaskExecutionInput.
    # Older snapshots may still embed permission_mode; honor it only as a fallback.
    requested_permission_mode = params.permission_mode
    if requested_permission_mode is None:
        requested_permission_mode = (agent_snapshot or {}).get("permission_mode")
    permission_mode = _resolve_sdk_permission_mode(requested_permission_mode)
    max_turns = _resolve_max_turns(cfg, params.execution_mode, int((agent_snapshot or {}).get("max_turns") or 0))
    setting_sources = ["project"]
    mcp_servers = _build_portal_mcp_servers(
        cfg,
        (agent_snapshot or {}).get("mcp_server_ids") if agent_snapshot else None,
        agent_snapshot=agent_snapshot,
    )
    allowed_tools = _build_allowed_tools(mcp_servers, (agent_snapshot or {}).get("allowed_tools") if agent_snapshot else None)
    options_kwargs = dict(
        system_prompt=system_prompt,
        model=model,
        cwd=str(project_cwd),
        setting_sources=setting_sources,
        max_turns=max_turns,
        allowed_tools=allowed_tools,
        skills=list(enabled_folders),
        mcp_servers=mcp_servers,
        include_partial_messages=supports_partial_messages,
        max_buffer_size=max(1024 * 1024, int(cfg.agent_max_buffer_size_bytes)),
        env=runtime_env,
        hooks=_build_workspace_boundary_hooks(project_cwd, skill_runtime, runtime_env),
        stderr=lambda line: logger.error(
            "sdk.stderr task_id=%s provider=%s model=%s %s",
            params.task_id,
            provider_id,
            model,
            str(line or "").rstrip(),
        ),
    )
    if params.resume_session_id:
        options_kwargs["resume"] = params.resume_session_id
    if permission_mode:
        options_kwargs["permission_mode"] = permission_mode
    cli_path = resolve_claude_cli_path(cfg)
    if cli_path:
        options_kwargs["cli_path"] = cli_path
    options = ClaudeAgentOptions(**options_kwargs)
    timeout_seconds = max(10, int(params.timeout_seconds or cfg.agent_timeout_seconds))

    logger.info(
        "task.start task_id=%s topic_id=%s provider=%s model=%s cwd=%s setting_sources=%s allowed_tools=%s mcp_servers=%s max_turns=%s partial=%s base_url=%s env_base_url=%s auth_token_set=%s api_key_set=%s",
        params.task_id,
        params.topic_id,
        provider_id,
        model,
        project_cwd,
        ",".join(setting_sources),
        ",".join(allowed_tools),
        ",".join(sorted(mcp_servers.keys())) if mcp_servers else "(none)",
        max_turns,
        supports_partial_messages,
        _safe_base_url(runtime_target.get("base_url")),
        _safe_base_url(env_payload.get("ANTHROPIC_BASE_URL")),
        bool(str(runtime_target.get("auth_token") or "").strip()),
        bool(str(runtime_target.get("api_key") or "").strip()),
    )

    async def _cancelled() -> bool:
        if is_cancel_requested is None:
            return False
        result = is_cancel_requested()
        if inspect.isawaitable(result):
            return bool(await result)
        return bool(result)

    try:
        with anyio.fail_after(timeout_seconds):
            async for msg in claude_query(prompt=prompt, options=options):
                if await _cancelled():
                    error = {"code": "task_cancelled", "message": "任务已取消"}
                    sdk_writer.append_error(**error)
                    return TaskExecutionResult(
                        task_status="suspended",
                        content=accumulator.current_answer_text(),
                        usage=accumulator.usage or None,
                        error=error,
                        provider_id=provider_id,
                        model=model,
                        session_id=accumulator.session_id,
                    )
                accumulator.ingest(msg)
                sdk_writer.ingest(msg)
    except Exception as exc:
        reason = _format_exception_reason(exc)
        partial = accumulator.current_answer_text()
        if _is_recoverable_timeout_reason(reason):
            recovered_content = _recover_partial_content(
                question=params.question,
                main_text=partial,
                blocks={},
                reason=reason,
            )
            if recovered_content:
                sdk_writer.append_done(is_error=False, subtype="recovered_timeout")
                return TaskExecutionResult(
                    task_status="finished",
                    content=recovered_content,
                    usage=accumulator.usage or None,
                    provider_id=provider_id,
                    model=model,
                    session_id=accumulator.session_id,
                )

        error_message = accumulator.preferred_error_message(reason)
        error_code = accumulator.preferred_error_code()
        error = {
            "code": error_code,
            "message": error_message,
            "exception_type": exc.__class__.__name__,
        }
        sdk_writer.append_error(**error)
        return TaskExecutionResult(
            task_status="error",
            content=error_message if error_code != "model_call_failed" else (partial or error_message),
            usage=accumulator.usage or None,
            error=error,
            provider_id=provider_id,
            model=model,
            session_id=accumulator.session_id,
        )

    result = accumulator.build_result()
    if result.task_status == "error":
        sdk_writer.append_error(
            code=str((result.error or {}).get("code") or "model_error"),
            message=str((result.error or {}).get("message") or result.content or "模型会话异常结束"),
            detail=str((result.error or {}).get("detail") or ""),
        )
    logger.info(
        "task.done task_id=%s task_status=%s provider=%s model=%s",
        params.task_id,
        result.task_status,
        provider_id,
        model,
    )
    return result
