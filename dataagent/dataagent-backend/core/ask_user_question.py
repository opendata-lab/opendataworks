"""Interactive ``AskUserQuestion`` support for the NL2SQL agent.

``AskUserQuestion`` is a *built-in* SDK tool. It always resolves through the
``can_use_tool`` callback (``checkPermissions`` returns ``ask`` and the tool
``requiresUserInteraction``), and the tool produces its result from the
``answers`` carried on its (updated) input. So the host answers a question by
returning ``PermissionResultAllow(updated_input={questions, answers, ...})``.

This module owns the host side of that round-trip, mirroring the
permission-confirmation flow (``permission_wait`` / ``permission_gate``): record
a ``question_request`` block (rendered as a selection card), park the task in
``waiting_input``, wait on MySQL for the user's answer, then map the answer
onto the SDK ``updated_input`` shape so the same run resumes with the selection.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from core.task_control import RunnerStoppedError, TaskCancelledError

logger = logging.getLogger(__name__)

# The built-in tool name as it appears in tool_use blocks and allowed_tools.
ASK_USER_QUESTION_TOOL_NAME = "AskUserQuestion"


def is_ask_user_question_tool(tool_name: str) -> bool:
    return str(tool_name or "").strip() == ASK_USER_QUESTION_TOOL_NAME


def to_sdk_answer_input(questions: list[dict[str, Any]], answers: list[dict[str, Any]]) -> dict[str, Any]:
    """Map the UI answer payload onto the built-in tool's ``updated_input`` shape.

    The tool echoes ``answers`` (``{questionText: selectedLabel}``) into its
    result, and ``annotations[questionText].notes`` carries free-text. Returning
    only ``questions`` (no answers) yields the tool's "did not answer" result.
    """
    by_question: dict[str, dict[str, Any]] = {}
    for item in answers or []:
        if not isinstance(item, dict):
            continue
        q = str(item.get("question") or "").strip()
        if q:
            by_question[q] = item

    sdk_answers: dict[str, str] = {}
    annotations: dict[str, dict[str, str]] = {}
    for question in questions or []:
        qt = str((question or {}).get("question") or "").strip()
        if not qt:
            continue
        item = by_question.get(qt)
        if not item:
            continue
        selected = item.get("selected")
        if isinstance(selected, str):
            selected = [selected]
        picks = [str(s).strip() for s in (selected or []) if str(s).strip()]
        if picks:
            sdk_answers[qt] = "、".join(picks)
        other = str(item.get("other") or "").strip()
        if other:
            annotations[qt] = {"notes": other}

    updated: dict[str, Any] = {"questions": questions}
    if sdk_answers:
        updated["answers"] = sdk_answers
    if annotations:
        updated["annotations"] = annotations
    return updated


async def wait_for_answer(
    task_id: str,
    request_id: str,
    *,
    poll_interval_seconds: float = 1.0,
    cancel_reason: Any = None,
    deadline: Any = None,
    on_timeout: Any = None,
) -> list[dict[str, Any]] | None:
    """Poll MySQL for the user's answer; return the answers list.

    Past ``deadline`` ``on_timeout`` records the recommended answers once; the
    loop then returns whichever answer was persisted first.
    """
    from core.topic_task_store import get_topic_task_store

    store = get_topic_task_store()
    timed_out = False
    while True:
        reason = await _read_cancel_reason(cancel_reason)
        if reason == "user_cancel":
            raise TaskCancelledError("task cancelled")
        if reason == "runner_stop":
            raise RunnerStoppedError("runner stopped")
        try:
            answers = store.get_resolved_question_answer(task_id=task_id, request_id=request_id)
        except Exception:
            logger.warning(
                "ask_user wait: durable read failed; retrying task_id=%s request_id=%s",
                task_id,
                request_id,
                exc_info=True,
            )
        else:
            if answers is not None:
                return answers
        if not timed_out and on_timeout is not None and deadline is not None and deadline.expired:
            try:
                on_timeout()
                timed_out = True
                continue
            except Exception:
                # Retried next poll: a blip in MySQL must not leave the run waiting forever.
                logger.warning("ask_user wait: timeout policy failed task_id=%s", task_id, exc_info=True)
        await asyncio.sleep(max(0.1, float(poll_interval_seconds)))


async def _read_cancel_reason(cancel_reason: Any) -> str | None:
    if cancel_reason is None:
        return None
    try:
        result = cancel_reason()
        if asyncio.iscoroutine(result):
            result = await result
        reason = str(result or "").strip()
        return reason if reason in {"user_cancel", "runner_stop"} else None
    except Exception:
        logger.warning("ask_user wait: cancel_reason check failed; continuing", exc_info=True)
        return None
