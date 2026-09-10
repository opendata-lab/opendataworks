"""Task status vocabulary, defined once.

The platform stores task lifecycle in ``da_agent_task.task_status`` and both the
SSE endpoint and the downstream queue branch on it. Those two consumers used to
carry their own literal sets, so an engine returning a status neither of them
recognised failed silently in two different ways at once: the stream never
closed, and the queue classified the run as failed.

Engines describe their own outcome (``success``/``cancelled``); the platform
vocabulary is separate on purpose. ``from_engine_outcome`` is the one place that
bridges them, so a new engine cannot invent a status by accident.
"""

from __future__ import annotations

# A task in one of these is still expected to produce more events.
ACTIVE_TASK_STATUSES = frozenset(
    {"waiting", "running", "waiting_input", "waiting_permission"}
)

# A task in one of these will produce nothing further. The SSE loop closes on
# them, so a terminal status missing from this set leaves the client hanging.
TERMINAL_TASK_STATUSES = frozenset({"finished", "error", "suspended"})

# Engine-level outcome -> platform task status.
_ENGINE_OUTCOME_TO_TASK_STATUS = {
    "success": "finished",
    "failed": "error",
    "error": "error",
    "cancelled": "suspended",
    "suspended": "suspended",
}

# Platform task status -> downstream queue/schedule status.
_TASK_STATUS_TO_DOWNSTREAM = {
    "finished": "completed",
    "error": "failed",
    "suspended": "suspended",
}


class InvalidTerminalTaskStatus(ValueError):
    """A terminal status outside TERMINAL_TASK_STATUSES reached persistence."""


def from_engine_outcome(terminal_status: str) -> str:
    """Translate an engine outcome into the platform task status.

    Unknown outcomes map to ``error`` rather than raising: an engine reporting
    something unexpected has already finished, and failing here would strand the
    task in ``running`` — the exact hang this module exists to prevent.
    """
    return _ENGINE_OUTCOME_TO_TASK_STATUS.get(str(terminal_status or "").strip(), "error")


def to_downstream_status(task_status: str) -> str:
    """Translate a terminal task status for queue/schedule consumers.

    Raises rather than guessing. The previous ``else: "failed"`` fallback is how
    every successful Pi run was reported as a failure downstream: 'success' was
    not 'finished', so it fell through.
    """
    status = str(task_status or "").strip()
    try:
        return _TASK_STATUS_TO_DOWNSTREAM[status]
    except KeyError as exc:
        raise InvalidTerminalTaskStatus(
            f"INVALID_TERMINAL_TASK_STATUS: {status!r} is not one of "
            f"{sorted(TERMINAL_TASK_STATUSES)}"
        ) from exc
