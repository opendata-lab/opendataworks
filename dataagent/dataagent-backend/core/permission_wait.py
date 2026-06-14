"""Wait for a user permission decision during a run.

Used by the ``can_use_tool`` confirmation callback. Self-connects to Redis from
settings so it works both in the in-process executor and in the sandbox runner
subprocess (neither needs to thread a callback through). The decision is written
by the API decision endpoint via the coordinator under the shared key
:func:`core.permission_gate.permission_decision_redis_key`.
"""
from __future__ import annotations

import asyncio
import logging
from typing import Any

from config import get_settings
from core.permission_gate import permission_decision_redis_key

logger = logging.getLogger(__name__)


async def wait_for_decision(
    task_id: str,
    request_id: str,
    *,
    timeout_seconds: int,
    poll_interval_seconds: float = 1.0,
    is_cancel_requested: Any = None,
) -> str:
    """Poll Redis for the user's decision; return ``allow`` / ``deny`` / ``timeout``.

    A cancellation observed while waiting resolves to ``deny`` so the run can
    unwind promptly. If Redis is unavailable the wait fails closed (``deny``).
    """
    cfg = get_settings()
    key = permission_decision_redis_key(task_id, request_id)
    try:
        import redis.asyncio as redis

        client = redis.Redis(
            host=cfg.redis_host,
            port=int(cfg.redis_port or 6379),
            password=cfg.redis_password or None,
            db=int(cfg.redis_db or 0),
            decode_responses=True,
        )
    except Exception:
        logger.exception("permission_wait: redis unavailable; denying request_id=%s", request_id)
        return "deny"

    deadline = asyncio.get_event_loop().time() + max(1, int(timeout_seconds or 600))
    try:
        while True:
            try:
                value = await client.get(key)
            except Exception:
                logger.exception("permission_wait: redis read failed; denying request_id=%s", request_id)
                return "deny"
            if value:
                decision = str(value).strip().lower()
                return decision if decision in {"allow", "deny"} else "deny"
            if is_cancel_requested is not None:
                try:
                    cancelled = is_cancel_requested()
                    if asyncio.iscoroutine(cancelled):
                        cancelled = await cancelled
                    if cancelled:
                        return "deny"
                except Exception:
                    pass
            if asyncio.get_event_loop().time() >= deadline:
                return "timeout"
            await asyncio.sleep(max(0.1, float(poll_interval_seconds)))
    finally:
        try:
            await client.aclose()
        except Exception:
            pass
