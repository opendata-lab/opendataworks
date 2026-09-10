from __future__ import annotations

import asyncio
import inspect
import json
import logging
import os
import time
import uuid
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Awaitable, Callable

import httpx

from config import get_settings, resolve_runtime_kind, resolve_workspace_scratch_dirs
from core.agent_profile_service import DEFAULT_AGENT_ID, normalize_agent_snapshot, normalize_permission_mode
from core.task_status import from_engine_outcome as task_status_from_engine_outcome
from core.ask_user_question import (
    ASK_USER_QUESTION_TOOL_NAME,
    is_ask_user_question_tool,
    to_sdk_answer_input,
    wait_for_answer,
)
from core.permission_gate import (
    is_exit_plan_mode,
    is_high_risk_tool,
    is_write_tool,
    plan_denies_tool,
    post_plan_mode,
    requires_confirmation,
    strip_card_annotations,
)
from core.permission_wait import wait_for_decision
from core.agent_runtime import (
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
from core.claude_cli import resolve_claude_cli_path
from core.sdk_block_writer import SdkBlockWriter
from core.slash_command_cache import record_agent_slash_commands
from core.task_control import PARKED_TASK_STATUSES, CancelReason, RunnerStoppedError, TaskCancelledError
from core.topic_task_store import get_topic_task_store
from core.topic_workspace import prepare_topic_workspace

logger = logging.getLogger(__name__)

_SDK_TURN_PROGRESS_THRESHOLDS = (1000, 3000, 6000, 10000)
_WORKSPACE_PLANS_DIRECTORY = ".claude/plans"


@dataclass
class TaskExecutionInput:
    """One NL2SQL run's execution contract, assembled by the coordinator/submission layer.

    Timeout fields are resolved per ``execution_mode`` at submission time (see
    ``resolve_task_timeouts``); when a recovered/legacy task carries ``0``/``None``
    the runtime falls back to the mode-specific value (see ``agent_runtime`` and
    ``config.resolve_sql_read_timeout_seconds``).

    Fields:
        task_id / topic_id: identity of this run and its conversation topic.
        question: the user prompt for this run.
        history: prior turns passed to the model as context.
        resume_session_id: SDK session id to resume, or ``None`` for a fresh run.
        provider_id / model: selected runtime provider and model id.
        database_hint: optional default schema/database for SQL tools.
        debug: enable verbose runtime diagnostics.
        timeout_seconds: legacy submission-time budget retained for recovery
            heuristics and compatibility; the SDK message loop is not wrapped in
            this value while confirmation/input waits are parked.
        sql_read_timeout_seconds: per read-query budget exposed to skill SQL tools.
        sql_write_timeout_seconds: per write-statement budget.
        execution_mode: ``interactive`` or ``background``/``auto`` timeout tier.
        agent_snapshot: frozen agent profile (skills, data scope, max_turns, ...).
        permission_mode: write/high-risk confirmation policy for this run.
    """

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
        self._saw_tool_use = False
        self._text_order: list[int] = []
        self._text_by_index: dict[int, str] = {}
        self._block_context: dict[int, dict[str, Any]] = {}
        self._next_message_block_index = 10_000

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
        subtype = str(self.result_subtype or "").strip()
        if self.provider_error_message:
            return subtype if subtype and subtype != "success" else "provider_error"
        if self.result_is_error:
            return subtype if subtype and subtype != "success" else "provider_error"
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

        if msg_type == "SystemMessage":
            # The init message carries the session's authoritative slash-command
            # list (built-ins + skills + custom commands). Cache it per agent so
            # the chat input's slash menu can surface the real commands.
            if str(getattr(msg, "subtype", "") or "") == "init":
                self._record_slash_commands(msg)
            return

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

    def _record_slash_commands(self, msg: Any) -> None:
        data = getattr(msg, "data", None)
        commands = data.get("slash_commands") if isinstance(data, dict) else None
        if commands is None:
            commands = getattr(msg, "slash_commands", None)
        if not isinstance(commands, list):
            return
        snapshot = getattr(self.params, "agent_snapshot", None)
        agent_id = str(snapshot.get("agent_id") or "").strip() if isinstance(snapshot, dict) else ""
        if agent_id:
            record_agent_slash_commands(agent_id, commands)

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
            if block_text and ("text" in lower_type or lower_type in {"textblock", "text"}):
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
            if str(block_payload.get("type") or "") != "text":
                return
            if delta_type == "text_delta":
                self._append_text(block_index, str(delta_payload.get("text") or ""))

    def build_result(self) -> TaskExecutionResult:
        content = self.current_answer_text()
        if self.result_is_error or self.provider_error_message:
            reason = self.preferred_error_message("模型会话异常结束")
            logger.warning(
                "task.result.provider_error task_id=%s topic_id=%s provider=%s model=%s error_code=%s subtype=%s content_len=%s provider_error=%s",
                self.params.task_id,
                self.params.topic_id,
                self.provider_id,
                self.model,
                self.preferred_error_code(),
                self.result_subtype,
                len(content),
                bool(self.provider_error_message),
            )
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
                logger.warning(
                    "task.result.recovered_subtype task_id=%s topic_id=%s provider=%s model=%s subtype=%s reason=%s content_len=%s",
                    self.params.task_id,
                    self.params.topic_id,
                    self.provider_id,
                    self.model,
                    self.result_subtype,
                    reason,
                    len(recovered_content),
                )
                return TaskExecutionResult(
                    task_status="finished",
                    content=recovered_content,
                    usage=self.usage or None,
                    provider_id=self.provider_id,
                    model=self.model,
                    session_id=self.session_id,
                )
            logger.warning(
                "task.result.error_subtype task_id=%s topic_id=%s provider=%s model=%s subtype=%s reason=%s content_len=%s",
                self.params.task_id,
                self.params.topic_id,
                self.provider_id,
                self.model,
                self.result_subtype,
                reason,
                len(content),
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

        # A clean run with no visible answer is still an incomplete run. The
        # no-tool case can be retried once by the caller below; a tool-backed
        # empty closeout is marked explicitly without an automatic retry because
        # replaying could repeat a write.
        if not content and not self._saw_tool_use:
            logger.warning(
                "task.result.empty_completion task_id=%s topic_id=%s provider=%s model=%s saw_stream_event=%s result_subtype=%s session_id_set=%s",
                self.params.task_id,
                self.params.topic_id,
                self.provider_id,
                self.model,
                self._saw_stream_event,
                self.result_subtype,
                bool(self.session_id),
            )
            return self._build_empty_completion_result()
        if not content and self._saw_tool_use:
            logger.warning(
                "task.result.incomplete_answer task_id=%s topic_id=%s provider=%s model=%s saw_stream_event=%s result_subtype=%s session_id_set=%s",
                self.params.task_id,
                self.params.topic_id,
                self.provider_id,
                self.model,
                self._saw_stream_event,
                self.result_subtype,
                bool(self.session_id),
            )
            return self._build_incomplete_answer_result()

        logger.info(
            "task.result.finished task_id=%s topic_id=%s provider=%s model=%s content_len=%s saw_tool_use=%s session_id_set=%s",
            self.params.task_id,
            self.params.topic_id,
            self.provider_id,
            self.model,
            len(content),
            self._saw_tool_use,
            bool(self.session_id),
        )
        return TaskExecutionResult(
            task_status="finished",
            content=content,
            usage=self.usage or None,
            provider_id=self.provider_id,
            model=self.model,
            session_id=self.session_id,
        )

    def _build_incomplete_run_result(
        self,
        *,
        content: str,
        reason: str,
        error_code: str,
        message: str,
        fallback: str,
    ) -> TaskExecutionResult:
        """Terminate a non-error run that produced no trustworthy final answer.

        Shared closeout for the ways a clean (success-subtype) run can still
        dead-end: it ended thinking-only with no answer, or invoked tools but
        never wrote the final response. These must surface as task errors — the
        live stream only carries the raw blocks, so without a terminal error
        record the chat UI cannot distinguish success from an incomplete answer.
        """
        synthetic_blocks: dict[str, dict[str, Any]] = (
            {"tool": {"type": "tool_result", "output": "1"}} if self._saw_tool_use else {}
        )
        recovered = _recover_partial_content(
            question=self.params.question,
            main_text=content,
            blocks=synthetic_blocks,
            reason=reason,
        )
        return TaskExecutionResult(
            task_status="error",
            content=recovered or fallback,
            usage=self.usage or None,
            error={"code": error_code, "message": message, "detail": reason},
            provider_id=self.provider_id,
            model=self.model,
            session_id=self.session_id,
        )

    def _build_incomplete_answer_result(self) -> TaskExecutionResult:
        """Close out a tool-backed run that never produced final visible text."""
        return self._build_incomplete_run_result(
            content="",
            reason="模型调用工具后未生成最终回答",
            error_code="incomplete_answer",
            message="模型本次调用工具后没有给出最终回答，请重试",
            fallback="模型本次调用工具后没有返回最终回答，请重试。",
        )

    def _build_empty_completion_result(self) -> TaskExecutionResult:
        """Close out a clean run that ended with no visible answer and no tool.

        A thinking-only turn that stopped early: no SDK error, no ``tool_use``,
        and no final text. This previously fell back to a silent ``finished``
        with "已完成。", so the chat ended on an empty answer with no way to
        retry. Routing it through the shared error closeout makes the existing
        error card and retry button surface instead.
        """
        return self._build_incomplete_run_result(
            content="",
            reason="模型本次未产出可见回答",
            error_code="empty_completion",
            message="模型本次没有给出回答，请重试",
            fallback="模型本次没有返回任何回答，请重试。",
        )


def _permission_result_types():
    """Return (Allow, Deny) result classes from the SDK, or (None, None).

    Imported lazily because the package is optional in some environments; when
    unavailable the callback falls back to the dict permission protocol.
    """
    try:
        from claude_agent_sdk import PermissionResultAllow, PermissionResultDeny

        return PermissionResultAllow, PermissionResultDeny
    except Exception:
        # SDK 不可用时回退到 dict 权限协议；属可预期的可选依赖缺失
        logger.debug("permission result types unavailable, falling back to dict protocol", exc_info=True)
        return None, None


def _set_mode_permission_update(mode: str):
    """Build a session-scoped ``setMode`` PermissionUpdate, or None if the SDK
    type is unavailable. Returned to the SDK so approving ExitPlanMode switches
    the run out of plan mode in place."""
    try:
        from claude_agent_sdk import PermissionUpdate

        return PermissionUpdate(type="setMode", mode=mode, destination="session")
    except Exception:
        # 旧 SDK 可能没有 PermissionUpdate；裸 allow ExitPlanMode 也能退出 plan，
        # effective_mode 翻转仍保证后续写工具按 post-plan 模式 gating。
        logger.debug("PermissionUpdate unavailable; approving ExitPlanMode without setMode", exc_info=True)
        return None


def _allow_result(tool_input: dict[str, Any], updated_permissions: list | None = None):
    Allow, _ = _permission_result_types()
    if Allow is not None:
        if updated_permissions:
            try:
                return Allow(updated_input=tool_input, updated_permissions=updated_permissions)
            except Exception:
                # updated_permissions 不被支持时退回普通 allow，不阻断放行
                logger.debug("PermissionResultAllow(updated_permissions=...) failed, retrying without it", exc_info=True)
        try:
            return Allow(updated_input=tool_input)
        except Exception:
            # SDK 结果类签名不兼容时降级，记录以便排查 schema 漂移
            logger.debug("PermissionResultAllow(updated_input=...) failed, retrying without input", exc_info=True)
            try:
                return Allow()
            except Exception:
                logger.warning("PermissionResultAllow construction failed, using dict allow fallback", exc_info=True)
    result = {"behavior": "allow", "updatedInput": tool_input}
    if updated_permissions:
        try:
            result["updatedPermissions"] = [
                p.to_dict() if hasattr(p, "to_dict") else p for p in updated_permissions
            ]
        except Exception:
            logger.debug("updated_permissions to_dict fallback failed", exc_info=True)
    return result


def _deny_result(message: str):
    _, Deny = _permission_result_types()
    if Deny is not None:
        try:
            return Deny(message=message)
        except Exception:
            # SDK 结果类签名不兼容时降级到 dict deny，保持拒绝语义不变
            logger.warning("PermissionResultDeny construction failed, using dict deny fallback", exc_info=True)
    return {"behavior": "deny", "message": message}


async def _handle_ask_user_question(
    *,
    tool_input: dict[str, Any],
    context: Any,
    sdk_writer: SdkBlockWriter,
    store: Any,
    task_id: str,
    cancel_reason: Callable[[], Awaitable[Any] | Any] | None,
):
    """Resolve a built-in ``AskUserQuestion`` call against the user.

    Records a question_request block (rendered as a selection card), parks the
    task in ``waiting_input``, waits on MySQL for the user's persisted selection,
    then returns ``PermissionResultAllow`` with the answer mapped onto the tool's
    ``updated_input`` so the same live run resumes with the selection.
    """
    questions = tool_input.get("questions")
    if not isinstance(questions, list) or not questions:
        return _allow_result(tool_input)

    request_id = str(getattr(context, "tool_use_id", "") or "").strip() or uuid.uuid4().hex
    try:
        sdk_writer.append_question_request(request_id=request_id, questions=questions)
        store.set_task_status(task_id, "waiting_input")
    except Exception:
        logger.exception("ask_user: failed to record question request task_id=%s", task_id)
        return _allow_result(tool_input)

    answers = await wait_for_answer(
        task_id,
        request_id,
        cancel_reason=cancel_reason,
    )
    answers = answers or []
    try:
        store.set_task_status(task_id, "running")
    except Exception:
        logger.exception("ask_user: failed to restore running status task_id=%s", task_id)

    return _allow_result(to_sdk_answer_input(questions, answers))


def _build_can_use_tool_callback(
    *,
    sdk_writer: SdkBlockWriter,
    store: Any,
    task_id: str,
    permission_mode: str,
    cancel_reason: Callable[[], Awaitable[Any] | Any] | None,
):
    """Build the SDK can_use_tool callback enforcing session permission policy.

    Read/analysis tools auto-allow. ``AskUserQuestion`` pauses the run to collect
    a multiple-choice answer (recorded as a question_request/answer block and
    returned via updated_input). Write/high-risk tools (per session mode) pause
    the run: a permission_request block is recorded, the task moves to
    waiting_permission, and the run waits for the user's persisted decision before
    resuming. Plan-denied tools are rejected outright.

    Under ``plan`` mode the model presents its plan via ``ExitPlanMode``; that call
    pauses the run for approval just like a write confirmation. On approval the run
    switches to :func:`post_plan_mode` (via an SDK setMode permission update) and
    continues in place, so subsequent write tools are gated by the post-plan policy
    instead of being plan-denied. ``effective_mode`` carries that switch.
    """

    # Mutable so plan approval can switch the in-run policy from plan -> post-plan.
    state = {"mode": permission_mode}

    async def _wait_decision(*, request_id: str, tool_name: str, risk_level: str, title: str, summary: str, payload_preview: dict[str, Any]) -> str:
        """Record a permission/plan request, park the task, and return the decision."""
        try:
            sdk_writer.append_permission_request(
                request_id=request_id,
                tool_name=tool_name,
                risk_level=risk_level,
                title=title,
                summary=summary,
                payload_preview=payload_preview,
            )
            store.set_task_status(task_id, "waiting_permission")
        except Exception:
            logger.exception("can_use_tool: failed to record permission request task_id=%s", task_id)
            return "deny"
        decision = await wait_for_decision(task_id, request_id, cancel_reason=cancel_reason)
        try:
            store.set_task_status(task_id, "running")
        except Exception:
            logger.exception("can_use_tool: failed to restore running status task_id=%s", task_id)
        return decision

    async def can_use_tool(tool_name: str, tool_input: dict[str, Any] | None = None, context: Any = None):
        tool_input = dict(tool_input or {})
        if is_ask_user_question_tool(tool_name):
            return await _handle_ask_user_question(
                tool_input=tool_input,
                context=context,
                sdk_writer=sdk_writer,
                store=store,
                task_id=task_id,
                cancel_reason=cancel_reason,
            )

        # Plan presentation: pause for the user to approve the plan, then leave plan
        # mode in place. The plan text is read from the tool input and persisted as
        # a plan card (risk_level="plan"); no plan file is involved.
        if is_exit_plan_mode(tool_name):
            plan_text = str(tool_input.get("plan") or tool_input.get("summary") or "").strip()
            decision = await _wait_decision(
                request_id=uuid.uuid4().hex,
                tool_name=tool_name,
                risk_level="plan",
                title="请确认执行计划",
                summary=plan_text,
                payload_preview={"plan": plan_text},
            )
            if decision == "allow":
                post_mode = post_plan_mode()
                state["mode"] = post_mode
                update = _set_mode_permission_update(post_mode)
                return _allow_result(tool_input, updated_permissions=[update] if update else None)
            return _deny_result("用户未批准该计划，请继续完善后再申请执行。")

        effective_mode = state["mode"]
        # For write tools the skill may attach card-annotation keys (title/summary)
        # that no downstream tool schema accepts; the card reads them from the raw
        # input, but the forwarded input must drop them or the MCP call is rejected
        # after approval.
        forwarded_input = strip_card_annotations(tool_input) if is_write_tool(tool_name) else tool_input
        if effective_mode == "plan" and plan_denies_tool(tool_name):
            return _deny_result("当前为规划(plan)模式，不允许执行写操作。")
        if not requires_confirmation(tool_name, effective_mode):
            return _allow_result(forwarded_input)

        bare = tool_name.split("__")[-1] if tool_name.startswith("mcp__") else tool_name
        summary = str(tool_input.get("summary") or "").strip()
        title = str(tool_input.get("title") or "").strip() or f"请确认操作:{bare}"
        risk_level = "critical" if is_high_risk_tool(tool_name) else "high"
        decision = await _wait_decision(
            request_id=uuid.uuid4().hex,
            tool_name=tool_name,
            risk_level=risk_level,
            title=title,
            summary=summary,
            payload_preview=tool_input,
        )
        if decision == "allow":
            return _allow_result(forwarded_input)
        return _deny_result("用户拒绝了该操作。")

    return can_use_tool


async def _single_user_prompt_stream(prompt: str):
    yield {
        "type": "user",
        "message": {"role": "user", "content": prompt},
    }


def _is_empty_completion_result(result: TaskExecutionResult) -> bool:
    return (
        result.task_status == "error"
        and isinstance(result.error, dict)
        and str(result.error.get("code") or "") == "empty_completion"
    )


def _build_empty_completion_recovery_prompt(question: str) -> str:
    original_question = _clip_text(str(question or "").strip(), 2000)
    if not original_question:
        original_question = "见本会话上一轮用户问题。"
    return (
        "系统检测到你上一轮已经以 end_turn 结束，但最终只输出了空白文本，"
        "没有调用工具，也没有给用户可见回答。\n"
        "请继续完成用户的原始问题。必须输出可见回答；如果需要查询真实数据，"
        "请继续使用当前已启用的工具。\n"
        "不要解释本条系统检测信息。\n\n"
        f"原始用户问题：\n{original_question}"
    )


async def execute_task_stream(
    params: TaskExecutionInput,
    *,
    emit: Callable[[dict[str, Any]], Awaitable[None] | None],
    is_cancel_requested: Callable[[], Awaitable[Any] | Any] | None = None,
) -> TaskExecutionResult:
    """Execute one NL2SQL run and return the terminal result.

    Dispatches to the sandbox runner when ``dataagent_sandbox_mode`` is set,
    otherwise runs in-process. The current local/child execution path writes SDK
    stream records directly through ``SdkBlockWriter`` into ``da_agent_sdk_record``;
    ``emit`` is for records forwarded by the sandbox-runner protocol and should
    not be read as the single persistence boundary for all SDK records. The
    awaited return value is the final :class:`TaskExecutionResult` (status,
    content, usage, error, session id). ``is_cancel_requested`` is polled and may
    return ``True``/``user_cancel`` or ``runner_stop``.
    """
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


def _normalize_cancel_reason(value: Any) -> CancelReason | None:
    if isinstance(value, str):
        reason = value.strip()
        return reason if reason in {"user_cancel", "runner_stop"} else None  # type: ignore[return-value]
    return "user_cancel" if bool(value) else None


def _task_is_parked(task_id: str) -> bool:
    try:
        task = get_topic_task_store().get_task(task_id)
    except Exception:
        logger.warning("task.cancel_watch: failed to read task status task_id=%s", task_id, exc_info=True)
        return False
    return str((task or {}).get("task_status") or "") in PARKED_TASK_STATUSES


async def _execute_task_stream_via_runner(
    params: TaskExecutionInput,
    *,
    emit: Callable[[dict[str, Any]], Awaitable[None] | None],
    is_cancel_requested: Callable[[], Awaitable[Any] | Any] | None = None,
) -> TaskExecutionResult:
    cfg = get_settings()
    runner_url = str(getattr(cfg, "dataagent_sandbox_runner_url", "") or "").strip().rstrip("/")
    if not runner_url:
        raise RuntimeError("DATAAGENT_SANDBOX_RUNNER_URL is required when DATAAGENT_SANDBOX_MODE is enabled")

    endpoint = f"{runner_url}/internal/sandbox/runs"
    payload = asdict(params)
    cancel_sent = False
    stream_done = False

    async def _cancel_reason() -> CancelReason | None:
        if is_cancel_requested is None:
            return None
        result = is_cancel_requested()
        if inspect.isawaitable(result):
            result = await result
        return _normalize_cancel_reason(result)

    async def _emit(record: dict[str, Any]) -> None:
        result = emit(record)
        if inspect.isawaitable(result):
            await result

    async with httpx.AsyncClient(timeout=None) as client:
        async def _watch_cancel() -> None:
            nonlocal cancel_sent
            while not stream_done:
                reason = await _cancel_reason()
                if not cancel_sent and reason:
                    if reason == "user_cancel" and _task_is_parked(params.task_id):
                        cancel_sent = True
                        await client.post(
                            f"{runner_url}/internal/sandbox/runs/{params.task_id}/cancel",
                            json={"task_id": params.task_id, "reason": reason, "kill": False},
                        )
                        return
                    cancel_sent = True
                    await client.post(
                        f"{runner_url}/internal/sandbox/runs/{params.task_id}/cancel",
                        json={"task_id": params.task_id, "reason": reason},
                    )
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


async def _execute_task_stream_via_pi_runtime(
    params: TaskExecutionInput,
    *,
    provider_id: str,
    model: str,
    system_prompt: str,
    skill_runtime: dict[str, Any],
    project_cwd: Path,
    runtime_env: dict[str, str],
    provider_env: dict[str, str],
    agent_snapshot: dict[str, Any] | None,
    cancel_reason: Callable[[], Awaitable[CancelReason | None]],
) -> TaskExecutionResult:
    """Run one turn on the Node Pi Cell instead of the Claude Agent SDK.

    Imported lazily for the same reason the SDK import is: the module is only
    needed by the engine actually selected, and neither should be a hard import
    cost for a deployment running the other one.
    """
    from core.boundary_policy import build_boundary_policy
    from core.pi_event_writer import PiEventWriter
    from core.pi_runtime import PiRunContext, PiRuntimeUnavailable, execute_pi_run, resolve_cell_command

    cfg = get_settings()
    writer = PiEventWriter(get_topic_task_store(), params.task_id, params.topic_id)

    # Session continuity differs by engine. The SDK resumes an engine-level
    # session and is therefore handed only the new question; Pi has no such
    # session and must replay the transcript, so history is always rebuilt here
    # and params.resume_session_id is intentionally ignored.
    history: list[dict[str, str]] = []
    messages: list[dict[str, str]] = []
    for item in params.history or []:
        content = str(item.get("content") or "").strip()
        if not content:
            continue
        role = "user" if item.get("role") == "user" else "assistant"
        entry = {"role": role, "content": content}
        messages.append(entry)
        history.append(entry)
    question = str(params.question or "").strip()
    messages.append({"role": "user", "content": question})

    raw_mcp_servers = _build_portal_mcp_servers(
        cfg,
        (agent_snapshot or {}).get("mcp_server_ids") if agent_snapshot else None,
        agent_snapshot=agent_snapshot,
    )
    mcp_servers_list = [
        {
            "name": name,
            "url": conf.get("url"),
            "type": conf.get("type", "http"),
            "headers": conf.get("headers", {}),
        }
        for name, conf in (raw_mcp_servers or {}).items()
    ]

    try:
        cell_command = resolve_cell_command(cfg)
    except PiRuntimeUnavailable as exc:
        reason = str(exc)
        writer.append_error(code="pi_runtime_missing", message=reason)
        return TaskExecutionResult(
            task_status="error",
            content=reason,
            error={"code": "pi_runtime_missing", "message": reason},
            provider_id=provider_id,
            model=model,
            session_id="",
        )

    ctx = PiRunContext(
        task_id=params.task_id,
        topic_id=params.topic_id,
        provider_id=provider_id,
        model=model,
        system_prompt=system_prompt,
        messages=messages,
        history=history,
        prompt=question,
        project_cwd=Path(project_cwd),
        boundary_policy=build_boundary_policy(
            project_cwd,
            skill_runtime,
            resolve_workspace_scratch_dirs(cfg),
            runtime_env,
            profile="pi_agent_core",
        ),
        runtime_env=dict(runtime_env),
        provider_env=dict(provider_env),
        skills=[
            {"name": name, "root_path": str(root)}
            for name, root in dict((skill_runtime or {}).get("enabled_roots") or {}).items()
        ],
        mcp_servers=mcp_servers_list,
        total_timeout_seconds=int(params.timeout_seconds or 0) or int(getattr(cfg, "dataagent_run_total_timeout_seconds", 600)),
        idle_timeout_seconds=int(getattr(cfg, "dataagent_run_idle_timeout_seconds", 300)),
        max_inline_result_bytes=int(getattr(cfg, "dataagent_context_max_inline_result_bytes", 16 * 1024)),
        protect_tail_turns=int(getattr(cfg, "dataagent_context_protect_tail_turns", 6)),
        max_context_tokens=int(getattr(cfg, "dataagent_context_max_context_tokens", 64_000)),
        max_turns=_resolve_max_turns(cfg, params.execution_mode, int((agent_snapshot or {}).get("max_turns") or 0)),
    )

    outcome = await execute_pi_run(ctx, writer=writer, cancel_reason=cancel_reason)

    # session_id must be task-scoped, not topic-scoped. It is read back as the
    # next turn's resume_session_id, and a topic-level constant would make the
    # SDK path take its "resume" branch — dropping all history — if the
    # deployment is ever switched back to claude_code.
    session_id = f"pi-{params.topic_id}-{params.task_id}"

    # The engine reports its own outcome vocabulary; the platform has a separate
    # one. Returning 'success'/'cancelled' verbatim put a status in the task row
    # that neither the SSE terminal set nor the downstream mapping recognised,
    # so a finished run left the stream open and was queued as a failure.
    if outcome.terminal_status in {"success", "cancelled"}:
        return TaskExecutionResult(
            task_status=task_status_from_engine_outcome(outcome.terminal_status),
            content=outcome.answer,
            usage=outcome.usage,
            provider_id=provider_id,
            model=model,
            session_id=session_id,
        )
    return TaskExecutionResult(
        task_status="error",
        content=outcome.answer or outcome.error_message,
        usage=outcome.usage,
        error={"code": outcome.error_code or "pi_runtime_error", "message": outcome.error_message},
        provider_id=provider_id,
        model=model,
        session_id=session_id,
    )


async def _execute_task_stream_local(
    params: TaskExecutionInput,
    *,
    emit: Callable[[dict[str, Any]], Awaitable[None] | None],
    is_cancel_requested: Callable[[], Awaitable[Any] | Any] | None = None,
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

    async def _cancel_reason() -> CancelReason | None:
        if is_cancel_requested is None:
            return None
        result = is_cancel_requested()
        if inspect.isawaitable(result):
            result = await result
        return _normalize_cancel_reason(result)

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

    # ---- Data plane fork -------------------------------------------------
    # Everything above is engine-neutral preparation and is shared by both data
    # planes. The engine choice is deployment-level (resolve_runtime_kind) and
    # deliberately *orthogonal* to sandbox mode: sandbox mode already chose the
    # isolation topology further up in execute_task_stream, and this fork picks
    # the engine inside whichever topology that selected. Forking on the two
    # together would let a Pi deployment silently lose container isolation.
    if resolve_runtime_kind(cfg) == "pi_agent_core":
        return await _execute_task_stream_via_pi_runtime(
            params,
            provider_id=provider_id,
            model=model,
            system_prompt=system_prompt,
            skill_runtime=skill_runtime,
            project_cwd=project_cwd,
            runtime_env=runtime_env,
            provider_env=env_payload,
            agent_snapshot=agent_snapshot,
            cancel_reason=_cancel_reason,
        )

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

    # Permission mode is a session-level choice carried on TaskExecutionInput.
    # Older snapshots may still embed permission_mode; honor it only as a fallback.
    requested_permission_mode = params.permission_mode
    if requested_permission_mode is None:
        requested_permission_mode = (agent_snapshot or {}).get("permission_mode")
    # logical_permission_mode is the session choice (plan/default/acceptEdits/
    # bypassPermissions); the SDK permission_mode is decided after tool mounting.
    logical_permission_mode = normalize_permission_mode(requested_permission_mode)
    max_turns = _resolve_max_turns(cfg, params.execution_mode, int((agent_snapshot or {}).get("max_turns") or 0))
    setting_sources = ["project"]
    mcp_servers = _build_portal_mcp_servers(
        cfg,
        (agent_snapshot or {}).get("mcp_server_ids") if agent_snapshot else None,
        agent_snapshot=agent_snapshot,
    )
    allowed_tools = _build_allowed_tools(
        mcp_servers,
        (agent_snapshot or {}).get("allowed_tools") if agent_snapshot else None,
        permission_mode=logical_permission_mode,
    )
    # Let the agent surface multiple-choice questions via the built-in
    # AskUserQuestion tool. It always resolves through can_use_tool, so advertise
    # it and ensure the callback is installed below.
    ask_user_enabled = bool(getattr(cfg, "dataagent_ask_user_question_enabled", True))
    if ask_user_enabled and ASK_USER_QUESTION_TOOL_NAME not in allowed_tools:
        allowed_tools = [*allowed_tools, ASK_USER_QUESTION_TOOL_NAME]

    # The SDK permission mode mirrors the logical session mode 1:1 (only the root
    # bypass fallback deviates), so the in-run can_use_tool gate fires reliably for
    # plan/default/acceptEdits.
    permission_mode = _resolve_sdk_permission_mode(logical_permission_mode)
    # Install the callback whenever it has work to do: guardable write tools to
    # confirm, AskUserQuestion to resolve, or plan mode (to gate ExitPlanMode and
    # plan-deny writes). Non-write tools auto-allow inside it; allowed_tools remains
    # the auto-allow fast-path.
    needs_gating = bool(mcp_servers) and logical_permission_mode in {"default", "acceptEdits", "plan"}
    can_use_tool = None
    if needs_gating or ask_user_enabled or logical_permission_mode == "plan":
        can_use_tool = _build_can_use_tool_callback(
            sdk_writer=sdk_writer,
            store=get_topic_task_store(),
            task_id=params.task_id,
            permission_mode=logical_permission_mode,
            cancel_reason=_cancel_reason,
        )

    options_kwargs = dict(
        system_prompt=system_prompt,
        model=model,
        cwd=str(project_cwd),
        # Claude Code otherwise stores plan-mode drafts under
        # $HOME/.claude/plans, which is intentionally outside the agent
        # workspace. Keep the runtime-owned plan directory relative to cwd so
        # plan writes remain inside the workspace boundary in both local and
        # container execution.
        settings=json.dumps({"plansDirectory": _WORKSPACE_PLANS_DIRECTORY}),
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
    if permission_mode:
        options_kwargs["permission_mode"] = permission_mode
    if can_use_tool is not None:
        options_kwargs["can_use_tool"] = can_use_tool
    cli_path = resolve_claude_cli_path(cfg)
    if cli_path:
        options_kwargs["cli_path"] = cli_path
    def _make_options(resume_session_id: str | None = None):
        current_options = dict(options_kwargs)
        resume_value = str(resume_session_id or "").strip()
        if resume_value:
            current_options["resume"] = resume_value
        return ClaudeAgentOptions(**current_options)

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

    terminal_error_record_written = False

    async def _raise_if_stopped() -> None:
        reason = await _cancel_reason()
        if reason == "user_cancel":
            raise TaskCancelledError("task cancelled")
        if reason == "runner_stop":
            raise RunnerStoppedError("runner stopped")

    async def _run_sdk_turn(
        *,
        turn_prompt: str,
        turn_options: Any,
        phase: str,
    ) -> TaskExecutionResult:
        nonlocal terminal_error_record_written
        sdk_message_count = 0
        sdk_stream_event_count = 0
        next_progress_threshold_index = 0
        turn_started_at = time.monotonic()
        try:
            sdk_prompt = _single_user_prompt_stream(turn_prompt) if can_use_tool is not None else turn_prompt
            async for msg in claude_query(prompt=sdk_prompt, options=turn_options):
                sdk_message_count += 1
                if type(msg).__name__ == "StreamEvent":
                    sdk_stream_event_count += 1
                while (
                    next_progress_threshold_index < len(_SDK_TURN_PROGRESS_THRESHOLDS)
                    and sdk_message_count >= _SDK_TURN_PROGRESS_THRESHOLDS[next_progress_threshold_index]
                ):
                    threshold = _SDK_TURN_PROGRESS_THRESHOLDS[next_progress_threshold_index]
                    next_progress_threshold_index += 1
                    logger.info(
                        "task.sdk_turn.progress task_id=%s topic_id=%s provider=%s model=%s phase=%s sdk_messages=%s stream_events=%s threshold=%s elapsed_ms=%s",
                        params.task_id,
                        params.topic_id,
                        provider_id,
                        model,
                        phase,
                        sdk_message_count,
                        sdk_stream_event_count,
                        threshold,
                        int((time.monotonic() - turn_started_at) * 1000),
                    )
                await _raise_if_stopped()
                accumulator.ingest(msg)
                sdk_writer.ingest(msg)
            await _raise_if_stopped()
        except TaskCancelledError:
            error = {"code": "task_cancelled", "message": "任务已取消"}
            sdk_writer.append_error(**error)
            terminal_error_record_written = True
            return TaskExecutionResult(
                task_status="suspended",
                content=accumulator.current_answer_text(),
                usage=accumulator.usage or None,
                error=error,
                provider_id=provider_id,
                model=model,
                session_id=accumulator.session_id,
            )
        except RunnerStoppedError:
            error = {"code": "runner_stopped", "message": "执行资源已停止"}
            return TaskExecutionResult(
                task_status="suspended",
                content=accumulator.current_answer_text(),
                usage=accumulator.usage or None,
                error=error,
                provider_id=provider_id,
                model=model,
                session_id=accumulator.session_id,
            )
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
                    logger.warning(
                        "task.exception.recovered_timeout task_id=%s topic_id=%s provider=%s model=%s phase=%s reason=%s partial_len=%s recovered_len=%s session_id_set=%s",
                        params.task_id,
                        params.topic_id,
                        provider_id,
                        model,
                        phase,
                        reason,
                        len(partial),
                        len(recovered_content),
                        bool(accumulator.session_id),
                    )
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
            terminal_error_record_written = True
            logger.warning(
                "task.exception.error_result task_id=%s topic_id=%s provider=%s model=%s phase=%s error_code=%s reason_len=%s partial_len=%s session_id_set=%s exception_type=%s",
                params.task_id,
                params.topic_id,
                provider_id,
                model,
                phase,
                error_code,
                len(error_message),
                len(partial),
                bool(accumulator.session_id),
                exc.__class__.__name__,
            )
            return TaskExecutionResult(
                task_status="error",
                content=error_message if error_code != "model_call_failed" else (partial or error_message),
                usage=accumulator.usage or None,
                error=error,
                provider_id=provider_id,
                model=model,
                session_id=accumulator.session_id,
            )

        logger.info(
            "task.sdk_turn.end task_id=%s topic_id=%s provider=%s model=%s phase=%s sdk_messages=%s stream_events=%s elapsed_ms=%s",
            params.task_id,
            params.topic_id,
            provider_id,
            model,
            phase,
            sdk_message_count,
            sdk_stream_event_count,
            int((time.monotonic() - turn_started_at) * 1000),
        )
        return accumulator.build_result()

    result = await _run_sdk_turn(
        turn_prompt=prompt,
        turn_options=_make_options(params.resume_session_id),
        phase="initial",
    )
    if _is_empty_completion_result(result):
        recovery_session_id = str(result.session_id or accumulator.session_id or params.resume_session_id or "").strip()
        logger.warning(
            "task.empty_completion.recover_start task_id=%s topic_id=%s provider=%s model=%s session_id_set=%s",
            params.task_id,
            params.topic_id,
            provider_id,
            model,
            bool(recovery_session_id),
        )
        result = await _run_sdk_turn(
            turn_prompt=_build_empty_completion_recovery_prompt(params.question),
            turn_options=_make_options(recovery_session_id or None),
            phase="empty_completion_recovery",
        )
        logger.info(
            "task.empty_completion.recover_result task_id=%s topic_id=%s provider=%s model=%s task_status=%s error_code=%s content_len=%s session_id_set=%s",
            params.task_id,
            params.topic_id,
            provider_id,
            model,
            result.task_status,
            str((result.error or {}).get("code") or ""),
            len(str(result.content or "")),
            bool(result.session_id),
        )

    if result.task_status == "error" and not terminal_error_record_written:
        sdk_writer.append_error(
            code=str((result.error or {}).get("code") or "model_error"),
            message=str((result.error or {}).get("message") or result.content or "模型会话异常结束"),
            detail=str((result.error or {}).get("detail") or ""),
        )
    logger.info(
        "task.done task_id=%s task_status=%s provider=%s model=%s error_code=%s content_len=%s session_id_set=%s",
        params.task_id,
        result.task_status,
        provider_id,
        model,
        str((result.error or {}).get("code") or ""),
        len(str(result.content or "")),
        bool(result.session_id),
    )
    return result
