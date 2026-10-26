"""Wait for a user permission decision during a run.

The API endpoint persists decisions in MySQL. This module polls that durable
record so the in-process executor and sandbox child use the same authoritative
path without Redis delivery timeouts.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from core.task_control import RunnerStoppedError, TaskCancelledError
from core.topic_task_store import get_topic_task_store

logger = logging.getLogger(__name__)


async def wait_for_decision(
    task_id: str,
    request_id: str,
    *,
    poll_interval_seconds: float = 1.0,
    cancel_reason: Any = None,
    deadline: Any = None,
    on_timeout: Any = None,
) -> str:
    """Poll MySQL for the user's decision; return callback verb ``allow``/``deny``.

    Past ``deadline`` (an ``InteractionDeadline``) ``on_timeout`` records the
    policy's decision once; polling continues, so a user reply that won the race
    is what gets returned.
    """
    store = get_topic_task_store()
    timed_out = False
    while True:
        reason = await _read_cancel_reason(cancel_reason)
        if reason == "user_cancel":
            raise TaskCancelledError("task cancelled")
        if reason == "runner_stop":
            raise RunnerStoppedError("runner stopped")
        try:
            decision = store.get_resolved_permission_decision(task_id=task_id, request_id=request_id)
        except Exception:
            logger.warning(
                "permission_wait: durable read failed; retrying task_id=%s request_id=%s",
                task_id,
                request_id,
                exc_info=True,
            )
        else:
            if decision:
                callback_decision = _to_callback_decision(decision)
                if callback_decision:
                    return callback_decision
                return "deny"
        if not timed_out and on_timeout is not None and deadline is not None and deadline.expired:
            try:
                on_timeout()
                timed_out = True
                continue
            except Exception:
                # Retried next poll: a blip in MySQL must not leave the run waiting forever.
                logger.warning("permission_wait: timeout policy failed task_id=%s", task_id, exc_info=True)
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
        logger.warning("permission_wait: cancel_reason check failed; continuing", exc_info=True)
        return None


def _to_callback_decision(decision: Any) -> str | None:
    value = str(decision or "").strip().lower()
    if value == "allow":
        return "allow"
    if value in {"deny", "timeout"}:
        return "deny"
    return None
