"""Ask the user a question or for plan approval mid-run, for either engine.

Both engines park the run the same way: a request record the cards render, the
task in ``waiting_input`` / ``waiting_permission``, then a durable wait on
MySQL. The writer is the engine's own (``SdkBlockWriter`` or
``PiEventWriter``); both expose ``append_question_request`` and
``append_permission_request`` with the same shape, so the cards cannot tell
which engine asked.
"""
from __future__ import annotations

import logging
from typing import Any

from core.ask_user_question import wait_for_answer
from core.interaction_policy import AUTO_TIMEOUT_SOURCE, InteractionDeadline, recommended_answers
from core.permission_wait import wait_for_decision

logger = logging.getLogger(__name__)

PLAN_TOOL_NAME = "ExitPlanMode"


def _set_status(store: Any, task_id: str, status: str) -> None:
    try:
        store.set_task_status(task_id, status)
    except Exception:
        logger.exception("user_interaction: failed to set status task_id=%s status=%s", task_id, status)


async def ask_questions(
    *,
    writer: Any,
    store: Any,
    task_id: str,
    request_id: str,
    questions: list[Any],
    deadline: InteractionDeadline,
    cancel_reason: Any = None,
) -> list[dict[str, Any]]:
    """Show a question card and return the answers (the user's, or the policy's on timeout)."""
    writer.append_question_request(request_id=request_id, questions=questions, deadline_at=deadline.iso)
    store.set_task_status(task_id, "waiting_input")

    def answer_by_policy() -> None:
        store.append_question_answer_if_waiting(
            task_id=task_id,
            request_id=request_id,
            answers=recommended_answers(questions),
            source=AUTO_TIMEOUT_SOURCE,
        )

    answers = await wait_for_answer(
        task_id,
        request_id,
        cancel_reason=cancel_reason,
        deadline=deadline,
        on_timeout=answer_by_policy,
    )
    _set_status(store, task_id, "running")
    return answers or []


async def request_plan_approval(
    *,
    writer: Any,
    store: Any,
    task_id: str,
    request_id: str,
    plan: str,
    deadline: InteractionDeadline,
    cancel_reason: Any = None,
) -> tuple[str, str]:
    """Show a plan card; return ``(decision, note)`` where note carries the user's revision request."""
    writer.append_permission_request(
        request_id=request_id,
        tool_name=PLAN_TOOL_NAME,
        risk_level="plan",
        title="请确认执行计划",
        summary=plan,
        payload_preview={"plan": plan},
        deadline_at=deadline.iso,
    )
    store.set_task_status(task_id, "waiting_permission")

    def approve_by_policy() -> None:
        store.append_permission_decision_if_waiting(
            task_id=task_id,
            request_id=request_id,
            decision="allow",
            source=AUTO_TIMEOUT_SOURCE,
        )

    decision = await wait_for_decision(
        task_id,
        request_id,
        cancel_reason=cancel_reason,
        deadline=deadline,
        on_timeout=approve_by_policy,
    )
    _set_status(store, task_id, "running")
    note = ""
    if decision != "allow":
        try:
            note = str((store.get_current_permission_interaction(task_id=task_id, request_id=request_id) or {}).get("note") or "")
        except Exception:
            logger.warning("user_interaction: failed to read plan note task_id=%s", task_id, exc_info=True)
    return decision, note.strip()


def plan_rejection_message(note: str) -> str:
    """What the model is told when the user asks to adjust the plan."""
    if note:
        return f"用户未批准该计划，要求先调整：{note}\n请按意见修改计划，再调用 {PLAN_TOOL_NAME} 重新提交。"
    return f"用户未批准该计划。请继续完善后再调用 {PLAN_TOOL_NAME} 提交。"
