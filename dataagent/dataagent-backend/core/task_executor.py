from __future__ import annotations

import logging
import os
import asyncio
import inspect
import json
import time
import uuid
from dataclasses import asdict, dataclass, field
from typing import Any, AsyncIterator, Awaitable, Callable

import anyio
import httpx

from config import get_settings
from core.claude_cli import resolve_claude_cli_path
from core.agent_runtime import (
    _append_delta,
    _build_allowed_tools,
    _build_portal_mcp_servers,
    _build_prompt,
    _build_provider_env,
    _build_runtime_env,
    _build_system_prompt,
    _build_workspace_boundary_hooks,
    _clip_text,
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
    resolve_agent_skill_runtime,
    resolve_enabled_skill_runtime,
    resolve_runtime_provider_selection,
)
from core.agent_profile_service import DEFAULT_AGENT_ID, normalize_agent_snapshot
from core.sdk_block_writer import SdkBlockWriter
from core.topic_task_store import get_topic_task_store
from core.topic_workspace import prepare_topic_workspace

logger = logging.getLogger(__name__)

CHUNK_FLUSH_INTERVAL_SECONDS = 0.35
CHUNK_FLUSH_MIN_CHARS = 80
TERMINAL_TASK_STATUSES = {"finished", "error", "suspended"}


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


@dataclass
class TaskExecutionResult:
    task_status: str
    content: str
    usage: dict[str, Any] | None = None
    error: dict[str, Any] | None = None
    provider_id: str = ""
    model: str = ""
    session_id: str = ""


@dataclass
class _PhaseState:
    kind: str
    correlation_id: str
    parent_correlation_id: str | None = None
    full_text: str = ""
    pending_text: str = ""
    chunk_started: bool = False
    last_flush_at: float = field(default_factory=time.monotonic)


@dataclass
class _ProvisionalReducerState:
    pending_answer_text: str = ""
    pending_answer_boundary: int = 0
    active_reasoning_correlation_id: str | None = None
    last_seen_tool_boundary: int = 0
    turn_finished: bool = False


class ClaudeToMagicAdapter:
    def __init__(self, params: TaskExecutionInput, *, provider_id: str, model: str):
        self.params = params
        self.provider_id = provider_id
        self.model = model
        self.request_id = f"task-{params.task_id}"
        self.chunk_id = 0
        self.current_phase: _PhaseState | None = None
        self.before_think_emitted = False
        self.pending_after_think = False
        self.answer_phase_order: list[str] = []
        self.answer_phase_text: dict[str, str] = {}
        self.tool_state: dict[str, dict[str, Any]] = {}
        self.latest_tool_id: str | None = None
        self.block_context: dict[int, dict[str, Any]] = {}
        self.usage: dict[str, Any] = {}
        self.stop_reason = ""
        self.stop_sequence = ""
        self.result_subtype = ""
        self.result_error = ""
        self.result_is_error = False
        self.provider_error_message = ""
        self.session_id = ""
        self.saw_partial_stream = False
        self.reducer = _ProvisionalReducerState()

    def _new_correlation_id(self, prefix: str) -> str:
        return f"{prefix}_{uuid.uuid4().hex[:18]}"

    def _event(
        self,
        event_type: str,
        *,
        content_type: str | None = None,
        correlation_id: str | None = None,
        parent_correlation_id: str | None = None,
        data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        return {
            "record_type": "event",
            "event_type": event_type,
            "content_type": content_type,
            "correlation_id": correlation_id,
            "parent_correlation_id": parent_correlation_id,
            "data": data or {},
        }

    def _chunk(
        self,
        *,
        content: str | None,
        status: str,
        correlation_id: str,
        parent_correlation_id: str | None,
        content_type: str,
        finish_reason: str | None = None,
    ) -> dict[str, Any]:
        self.chunk_id += 1
        return {
            "record_type": "chunk",
            "request_id": self.request_id,
            "chunk_id": self.chunk_id,
            "content": content,
            "delta": {
                "status": status,
                "finish_reason": finish_reason,
            },
            "metadata": {
                "correlation_id": correlation_id,
                "parent_correlation_id": parent_correlation_id,
                "model_id": self.model,
                "content_type": content_type,
            },
        }

    def _should_flush_pending(self, piece: str) -> bool:
        if not self.current_phase or not self.current_phase.pending_text:
            return False
        if len(self.current_phase.pending_text) >= CHUNK_FLUSH_MIN_CHARS:
            return True
        if any(mark in piece for mark in ("\n", "。", "！", "？", ".", "!", "?", ";", "；")):
            return True
        return (time.monotonic() - self.current_phase.last_flush_at) >= CHUNK_FLUSH_INTERVAL_SECONDS

    def _flush_phase(self, *, force: bool = False) -> list[dict[str, Any]]:
        if not self.current_phase or not self.current_phase.pending_text:
            return []
        if not force and not self._should_flush_pending(self.current_phase.pending_text[-1:]):
            return []
        status = "START" if not self.current_phase.chunk_started else "STREAMING"
        payload = self._chunk(
            content=self.current_phase.pending_text,
            status=status,
            correlation_id=self.current_phase.correlation_id,
            parent_correlation_id=self.current_phase.parent_correlation_id,
            content_type=self.current_phase.kind,
        )
        self.current_phase.chunk_started = True
        self.current_phase.pending_text = ""
        self.current_phase.last_flush_at = time.monotonic()
        return [payload]

    def _append_phase_text(self, kind: str, piece: str) -> list[dict[str, Any]]:
        text = str(piece or "")
        if not text:
            return []

        records: list[dict[str, Any]] = []
        if self.current_phase and self.current_phase.kind != kind:
            records.extend(self._close_phase())

        if self.current_phase is None:
            correlation_id = self._new_correlation_id(kind)
            if kind == "reasoning":
                if not self.before_think_emitted:
                    records.append(
                        self._event(
                            "BEFORE_AGENT_THINK",
                            content_type="reasoning",
                            correlation_id=correlation_id,
                            data={"status": "running"},
                        )
                    )
                    self.before_think_emitted = True
                self.pending_after_think = True
            elif self.pending_after_think:
                records.append(
                    self._event(
                        "AFTER_AGENT_THINK",
                        content_type="reasoning",
                        data={"status": "running"},
                    )
                )
                self.pending_after_think = False
                self.reducer.active_reasoning_correlation_id = None

            self.current_phase = _PhaseState(kind=kind, correlation_id=correlation_id)
            if kind == "reasoning":
                self.reducer.active_reasoning_correlation_id = correlation_id
            records.append(
                self._event(
                    "BEFORE_AGENT_REPLY",
                    content_type=kind,
                    correlation_id=correlation_id,
                    data={"status": "running"},
                )
            )

        self.current_phase.full_text = f"{self.current_phase.full_text}{text}"
        self.current_phase.pending_text = f"{self.current_phase.pending_text}{text}"
        records.extend(self._flush_phase())
        return records

    def _close_phase(self, *, final_status: str = "running", finish_reason: str | None = None) -> list[dict[str, Any]]:
        if self.current_phase is None:
            return []

        phase = self.current_phase
        records = self._flush_phase(force=True)
        if phase.chunk_started or phase.full_text:
            records.append(
                self._chunk(
                    content=phase.full_text,
                    status="END",
                    correlation_id=phase.correlation_id,
                    parent_correlation_id=phase.parent_correlation_id,
                    content_type=phase.kind,
                    finish_reason=finish_reason,
                )
            )

        data: dict[str, Any] = {"status": final_status}
        if final_status in TERMINAL_TASK_STATUSES and self.usage:
            data["token_usage"] = dict(self.usage)
        records.append(
            self._event(
                "AFTER_AGENT_REPLY",
                content_type=phase.kind,
                correlation_id=phase.correlation_id,
                parent_correlation_id=phase.parent_correlation_id,
                data=data,
            )
        )

        if phase.kind == "content":
            if phase.correlation_id not in self.answer_phase_order:
                self.answer_phase_order.append(phase.correlation_id)
            self.answer_phase_text[phase.correlation_id] = phase.full_text

        self.current_phase = None
        return records

    def _current_answer_text(self) -> str:
        parts: list[str] = []
        for correlation_id in self.answer_phase_order:
            parts.append(self.answer_phase_text.get(correlation_id, ""))
        if self.current_phase and self.current_phase.kind == "content":
            parts.append(self.current_phase.full_text)
        return "".join(parts)

    def _buffer_pending_answer_text(self, piece: str) -> None:
        text = str(piece or "")
        if not text:
            return
        if not self.reducer.pending_answer_text:
            self.reducer.pending_answer_boundary = self.reducer.last_seen_tool_boundary
        self.reducer.pending_answer_text = f"{self.reducer.pending_answer_text}{text}"

    def _flush_pending_answer_text(self, kind: str) -> list[dict[str, Any]]:
        text = str(self.reducer.pending_answer_text or "")
        self.reducer.pending_answer_text = ""
        if not text:
            return []
        if kind == "content" and self.reducer.pending_answer_boundary != self.reducer.last_seen_tool_boundary:
            kind = "reasoning"
        self.reducer.pending_answer_boundary = self.reducer.last_seen_tool_boundary
        return self._append_phase_text(kind, text)

    def _promote_pending_answer_to_reasoning(self) -> list[dict[str, Any]]:
        return self._flush_pending_answer_text("reasoning")

    def _commit_pending_answer_to_content(self) -> list[dict[str, Any]]:
        if not self.reducer.turn_finished:
            return []
        return self._flush_pending_answer_text("content")

    def _discard_pending_answer_text(self) -> None:
        self.reducer.pending_answer_text = ""
        self.reducer.pending_answer_boundary = self.reducer.last_seen_tool_boundary

    def _mark_tool_boundary(self) -> None:
        self.reducer.last_seen_tool_boundary += 1

    def _tool_payload(
        self,
        *,
        tool_id: str,
        name: str,
        status: str,
        input_value: Any = None,
        output_value: Any = None,
    ) -> dict[str, Any]:
        payload = {
            "id": tool_id,
            "name": name or "Tool",
            "status": status,
        }
        if input_value is not None:
            payload["input"] = input_value
        if output_value is not None:
            payload["output"] = output_value
        return payload

    def _start_tool(self, block_payload: dict[str, Any]) -> list[dict[str, Any]]:
        tool_id = str(block_payload.get("id") or f"tool_{uuid.uuid4().hex[:12]}")
        tool_name = str(block_payload.get("name") or "Tool")
        tool_input = block_payload.get("input")

        records = self._promote_pending_answer_to_reasoning()
        self._mark_tool_boundary()
        if self.current_phase:
            records.extend(self._close_phase())

        parent_correlation_id = (
            self.current_phase.correlation_id
            if self.current_phase
            else self.reducer.active_reasoning_correlation_id
        )
        self.tool_state[tool_id] = {
            "id": tool_id,
            "name": tool_name,
            "input": tool_input,
            "output": None,
            "parent_correlation_id": parent_correlation_id,
        }
        self.latest_tool_id = tool_id

        records.append(
            self._event(
                "PENDING_TOOL_CALL",
                correlation_id=tool_id,
                parent_correlation_id=parent_correlation_id,
                data={
                    "status": "running",
                    "tool": self._tool_payload(
                        tool_id=tool_id,
                        name=tool_name,
                        status="pending",
                        input_value=tool_input,
                    ),
                },
            )
        )
        records.append(
            self._event(
                "BEFORE_TOOL_CALL",
                correlation_id=tool_id,
                parent_correlation_id=parent_correlation_id,
                data={
                    "status": "running",
                    "tool": self._tool_payload(
                        tool_id=tool_id,
                        name=tool_name,
                        status="running",
                        input_value=tool_input,
                    ),
                },
            )
        )
        return records

    def _append_tool_input(self, block_payload: dict[str, Any], partial_json: str) -> None:
        tool_id = str(block_payload.get("id") or self.latest_tool_id or "")
        if not tool_id:
            return
        state = self.tool_state.setdefault(
            tool_id,
            {
                "id": tool_id,
                "name": str(block_payload.get("name") or "Tool"),
                "input": "",
                "output": None,
                "parent_correlation_id": self.current_phase.correlation_id if self.current_phase else None,
            },
        )
        current = str(state.get("input") or "")
        merged, _ = _append_delta(current, partial_json)
        state["input"] = merged

    def _complete_tool(self, *, tool_id: str | None, name: str | None, output: Any) -> list[dict[str, Any]]:
        resolved_id = str(tool_id or self.latest_tool_id or "")
        if not resolved_id:
            return []

        state = self.tool_state.setdefault(
            resolved_id,
            {
                "id": resolved_id,
                "name": str(name or "Tool"),
                "input": None,
                "output": None,
                "parent_correlation_id": self.current_phase.correlation_id if self.current_phase else None,
            },
        )
        if name:
            state["name"] = str(name)

        if isinstance(state.get("output"), str) and isinstance(output, str):
            merged, _ = _append_delta(str(state.get("output") or ""), output)
            state["output"] = merged
        elif output is not None:
            state["output"] = output

        return [
            self._event(
                "AFTER_TOOL_CALL",
                correlation_id=resolved_id,
                parent_correlation_id=state.get("parent_correlation_id"),
                data={
                    "status": "running",
                    "tool": self._tool_payload(
                        tool_id=resolved_id,
                        name=str(state.get("name") or "Tool"),
                        status="success",
                        input_value=state.get("input"),
                        output_value=state.get("output"),
                    ),
                },
            )
        ]

    def _is_internal_skill_bootstrap(self, text: str) -> bool:
        return str(text or "").lstrip().startswith("Base directory for this skill:")

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

    def ingest_stream_event(self, raw_event: dict[str, Any]) -> list[dict[str, Any]]:
        self.saw_partial_stream = True
        event_type = str(raw_event.get("type") or "").strip()
        if not event_type:
            return []

        records: list[dict[str, Any]] = []

        if event_type == "message_start":
            message_payload = raw_event.get("message")
            if isinstance(message_payload, dict):
                if message_payload.get("id"):
                    self.request_id = str(message_payload.get("id"))
                if isinstance(message_payload.get("usage"), dict):
                    self.usage = {**self.usage, **dict(message_payload.get("usage") or {})}
            return records

        if event_type == "message_delta":
            delta_payload = raw_event.get("delta")
            if isinstance(delta_payload, dict):
                if delta_payload.get("stop_reason") is not None:
                    self.stop_reason = str(delta_payload.get("stop_reason") or "")
                if delta_payload.get("stop_sequence") is not None:
                    self.stop_sequence = str(delta_payload.get("stop_sequence") or "")
                if isinstance(delta_payload.get("usage"), dict):
                    self.usage = {**self.usage, **dict(delta_payload.get("usage") or {})}
            return records

        if event_type == "content_block_start":
            block_index = raw_event.get("index")
            block_payload = raw_event.get("content_block") if isinstance(raw_event.get("content_block"), dict) else {}
            block_type = str(block_payload.get("type") or "").strip().lower()
            self.block_context[int(block_index or 0)] = {"type": block_type}

            if block_type == "thinking":
                self.block_context[int(block_index or 0)]["phase_kind"] = "reasoning"
                if block_payload.get("thinking"):
                    records.extend(self._append_phase_text("reasoning", str(block_payload.get("thinking") or "")))
                return records

            if block_type == "text":
                self.block_context[int(block_index or 0)]["phase_kind"] = "content"
                if block_payload.get("text"):
                    self._buffer_pending_answer_text(str(block_payload.get("text") or ""))
                return records

            if block_type in {"tool_use", "server_tool_use"}:
                self.block_context[int(block_index or 0)]["tool_id"] = str(block_payload.get("id") or "")
                records.extend(self._start_tool(block_payload))
                return records

            if block_type == "tool_result":
                self.block_context[int(block_index or 0)]["tool_id"] = str(
                    block_payload.get("tool_use_id") or block_payload.get("tool_id") or ""
                )
                if block_payload.get("content") is not None:
                    records.extend(
                        self._complete_tool(
                            tool_id=str(block_payload.get("tool_use_id") or block_payload.get("tool_id") or ""),
                            name=str(block_payload.get("name") or "Tool"),
                            output=block_payload.get("content"),
                        )
                    )
                return records

            return records

        if event_type == "content_block_delta":
            block_index = int(raw_event.get("index") or 0)
            delta_payload = raw_event.get("delta") if isinstance(raw_event.get("delta"), dict) else {}
            delta_type = str(delta_payload.get("type") or "").strip()
            if delta_type == "thinking_delta":
                records.extend(self._append_phase_text("reasoning", str(delta_payload.get("thinking") or "")))
                return records
            if delta_type == "text_delta":
                self._buffer_pending_answer_text(str(delta_payload.get("text") or ""))
                return records
            if delta_type == "input_json_delta":
                block_payload = self.block_context.get(block_index) or {}
                self._append_tool_input(block_payload, str(delta_payload.get("partial_json") or ""))
            return []

        if event_type == "content_block_stop":
            block_index = int(raw_event.get("index") or 0)
            block_payload = self.block_context.get(block_index) or {}
            phase_kind = str(block_payload.get("phase_kind") or "")
            if phase_kind and self.current_phase and self.current_phase.kind == phase_kind:
                return self._close_phase()
            return []

        if event_type == "message_stop":
            return []

        return []

    def ingest_sdk_message(self, msg: Any) -> list[dict[str, Any]]:
        msg_type = type(msg).__name__
        records: list[dict[str, Any]] = []
        content = getattr(msg, "content", None)
        session_id = str(getattr(msg, "session_id", "") or "").strip()
        if session_id:
            self.session_id = session_id

        if msg_type == "ResultMessage":
            self.result_subtype = str(getattr(msg, "subtype", "") or "")
            self.result_is_error = bool(getattr(msg, "is_error", False))
            result_raw = getattr(msg, "result", None)
            if result_raw is not None:
                self.result_error = _clip_text(_safe_stringify(result_raw), 2000)
            if self.result_is_error and self.result_error:
                self._remember_provider_error(self.result_error)
                self._discard_pending_answer_text()
            return records

        if msg_type == "StreamEvent":
            raw_event = getattr(msg, "event", None)
            if isinstance(raw_event, dict):
                return self.ingest_stream_event(raw_event)
            return records

        if isinstance(content, list):
            text_parts: list[str] = []
            assistant_error = str(getattr(msg, "error", "") or "").strip() if msg_type == "AssistantMessage" else ""
            if assistant_error:
                self._remember_provider_error(assistant_error)
            for block in content:
                block_type, block_text, block_payload = _extract_block(block)
                lower_type = block_type.lower()
                if "toolresult" in lower_type or lower_type in {"tool_result", "toolresultblock"}:
                    records.extend(
                        self._complete_tool(
                            tool_id=str(
                                block_payload.get("tool_use_id")
                                or block_payload.get("tool_id")
                                or block_payload.get("id")
                                or ""
                            ),
                            name=str(block_payload.get("name") or "Tool"),
                            output=block_payload.get("content"),
                        )
                    )
                    continue
                if "tooluse" in lower_type or lower_type in {"tool_use", "tooluseblock"}:
                    records.extend(self._start_tool(block_payload))
                    continue
                if msg_type == "AssistantMessage" and not self.saw_partial_stream and block_text:
                    if "thinking" in lower_type:
                        records.extend(self._append_phase_text("reasoning", block_text))
                    else:
                        self._buffer_pending_answer_text(block_text)
                    continue
                if msg_type == "UserMessage" and block_text:
                    text_parts.append(block_text)

            if text_parts and self.latest_tool_id:
                text = "\n".join(text_parts).strip()
                if text and not self._is_internal_skill_bootstrap(text):
                    records.extend(
                        self._complete_tool(
                            tool_id=self.latest_tool_id,
                            name=str((self.tool_state.get(self.latest_tool_id) or {}).get("name") or "Tool"),
                            output=text,
                        )
                    )
            return records

        if isinstance(content, str) and content.strip():
            if msg_type == "AssistantMessage" and not self.saw_partial_stream:
                self._buffer_pending_answer_text(content)
                return records
            if self.latest_tool_id and not self._is_internal_skill_bootstrap(content):
                records.extend(
                    self._complete_tool(
                        tool_id=self.latest_tool_id,
                        name=str((self.tool_state.get(self.latest_tool_id) or {}).get("name") or "Tool"),
                        output=content,
                    )
                )
        return records

    def finalize_records(self, *, final_status: str, commit_pending_answer: bool) -> list[dict[str, Any]]:
        records: list[dict[str, Any]] = []
        self.reducer.turn_finished = final_status == "finished"
        if commit_pending_answer:
            records.extend(self._commit_pending_answer_to_content())
        else:
            self._discard_pending_answer_text()
        if self.current_phase:
            records.extend(self._close_phase(final_status=final_status, finish_reason=self.stop_reason or None))
        if self.pending_after_think:
            records.append(
                self._event(
                    "AFTER_AGENT_THINK",
                    content_type="reasoning",
                    data={"status": final_status},
                )
            )
            self.pending_after_think = False
            self.reducer.active_reasoning_correlation_id = None
        return records

    def build_result(self) -> TaskExecutionResult:
        content = self._current_answer_text().strip()
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

        return TaskExecutionResult(
            task_status="finished",
            content=content or "已完成。",
            usage=self.usage or None,
            provider_id=self.provider_id,
            model=self.model,
            session_id=self.session_id,
        )


async def _emit_records(
    emit: Callable[[dict[str, Any]], Awaitable[None] | None],
    records: list[dict[str, Any]],
) -> None:
    for record in records:
        result = emit(record)
        if inspect.isawaitable(result):
            await result


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

    adapter = ClaudeToMagicAdapter(params, provider_id=provider_id, model=model)
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

    if params.debug:
        await _emit_records(
            emit,
            [
                {
                    "record_type": "event",
                    "event_type": "DEBUG",
                    "data": {
                        "status": "running",
                        "provider_id": provider_id,
                        "model": model,
                        "prompt_preview": _clip_text(prompt, 4000),
                        "system_prompt_preview": _clip_text(system_prompt, 1200),
                        "agent_id": str((agent_snapshot or {}).get("agent_id") or DEFAULT_AGENT_ID),
                    },
                }
            ],
        )

    try:
        from claude_agent_sdk import ClaudeAgentOptions, query as claude_query
    except ImportError as exc:
        reason = "claude-agent-sdk 未安装"
        await _emit_records(
            emit,
            [
                {
                    "record_type": "event",
                    "event_type": "ERROR",
                    "data": {
                        "status": "error",
                        "error": {
                            "code": "sdk_not_installed",
                            "message": reason,
                            "detail": str(exc),
                        },
                    },
                }
            ],
        )
        return TaskExecutionResult(
            task_status="error",
            content=reason,
            error={"code": "sdk_not_installed", "message": reason, "detail": str(exc)},
            provider_id=provider_id,
            model=model,
            session_id=adapter.session_id,
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
    prepared_workspace_dir = ""
    if str(os.environ.get("DATAAGENT_WORKSPACE_PREPARED") or "").strip() == "1":
        prepared_workspace_dir = str(os.environ.get("DATAAGENT_WORKSPACE_DIR") or "").strip()
    project_cwd = prepare_topic_workspace(
        params.topic_id,
        enabled_folders,
        allow_empty=bool(agent_snapshot) or not enabled_folders,
        workspace_dir=prepared_workspace_dir or None,
    )
    workspace_env = {
        "HOME": str(project_cwd),
        "PWD": str(project_cwd),
        "DATAAGENT_WORKSPACE_DIR": str(project_cwd),
    }
    runtime_env.update(workspace_env)
    for key, value in workspace_env.items():
        os.environ[key] = value
    permission_mode = _resolve_sdk_permission_mode(str((agent_snapshot or {}).get("permission_mode") or "inherit"))
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
        if isinstance(result, Awaitable):
            return bool(await result)
        return bool(result)

    try:
        with anyio.fail_after(timeout_seconds):
            async for msg in claude_query(prompt=prompt, options=options):
                if await _cancelled():
                    await _emit_records(
                        emit,
                        adapter.finalize_records(final_status="suspended", commit_pending_answer=False)
                        + [
                            {
                                "record_type": "event",
                                "event_type": "AGENT_SUSPENDED",
                                "data": {
                                    "status": "suspended",
                                    "error": {
                                        "code": "task_cancelled",
                                        "message": "任务已取消",
                                    },
                                },
                            }
                        ],
                    )
                    return TaskExecutionResult(
                        task_status="suspended",
                        content=adapter._current_answer_text().strip(),
                        usage=adapter.usage or None,
                        error={"code": "task_cancelled", "message": "任务已取消"},
                        provider_id=provider_id,
                        model=model,
                        session_id=adapter.session_id,
                    )
                await _emit_records(emit, adapter.ingest_sdk_message(msg))
                sdk_writer.ingest(msg)
    except Exception as exc:
        reason = _format_exception_reason(exc)
        partial = adapter._current_answer_text().strip()
        if _is_recoverable_timeout_reason(reason):
            recovered_content = _recover_partial_content(
                question=params.question,
                main_text=partial,
                blocks={},
                reason=reason,
            )
            if recovered_content:
                await _emit_records(emit, adapter.finalize_records(final_status="finished", commit_pending_answer=False))
                return TaskExecutionResult(
                    task_status="finished",
                    content=recovered_content,
                    usage=adapter.usage or None,
                    provider_id=provider_id,
                    model=model,
                    session_id=adapter.session_id,
                )

        error_message = adapter.preferred_error_message(reason)
        error_code = adapter.preferred_error_code()
        await _emit_records(
            emit,
            adapter.finalize_records(final_status="error", commit_pending_answer=False)
            + [
                {
                    "record_type": "event",
                    "event_type": "ERROR",
                    "data": {
                        "status": "error",
                        "error": {
                            "code": error_code,
                            "message": error_message,
                            "exception_type": exc.__class__.__name__,
                        },
                    },
                }
            ],
        )
        return TaskExecutionResult(
            task_status="error",
            content=error_message if error_code != "model_call_failed" else (partial or error_message),
            usage=adapter.usage or None,
            error={
                "code": error_code,
                "message": error_message,
                "exception_type": exc.__class__.__name__,
            },
            provider_id=provider_id,
            model=model,
            session_id=adapter.session_id,
        )

    await _emit_records(emit, adapter.finalize_records(final_status="finished", commit_pending_answer=True))
    result = adapter.build_result()
    logger.info(
        "task.done task_id=%s task_status=%s provider=%s model=%s",
        params.task_id,
        result.task_status,
        provider_id,
        model,
    )
    return result
