"""Task status vocabulary is shared, not restated per consumer.

Both bugs this locks were the same mistake: an engine returned a status the
platform did not recognise, and two consumers each failed silently in their own
way — the SSE loop never closed, and the queue recorded a success as a failure.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core.task_status import (  # noqa: E402
    ACTIVE_TASK_STATUSES,
    TERMINAL_TASK_STATUSES,
    InvalidTerminalTaskStatus,
    from_engine_outcome,
    to_downstream_status,
)


@pytest.mark.parametrize(
    "outcome,expected",
    [("success", "finished"), ("cancelled", "suspended"), ("failed", "error"), ("suspended", "suspended")],
)
def test_engine_outcomes_map_into_the_platform_vocabulary(outcome, expected):
    assert from_engine_outcome(outcome) == expected


def test_every_engine_outcome_maps_to_a_terminal_status():
    """A status outside the terminal set leaves the SSE stream open forever."""
    for outcome in ("success", "cancelled", "failed", "suspended", "something_new"):
        assert from_engine_outcome(outcome) in TERMINAL_TASK_STATUSES


def test_unknown_outcome_becomes_error_rather_than_hanging():
    """An engine reporting something unexpected has still finished."""
    assert from_engine_outcome("who_knows") == "error"
    assert from_engine_outcome("") == "error"


@pytest.mark.parametrize(
    "task_status,expected",
    [("finished", "completed"), ("error", "failed"), ("suspended", "suspended")],
)
def test_downstream_mapping_covers_every_terminal_status(task_status, expected):
    assert to_downstream_status(task_status) == expected


def test_downstream_mapping_rejects_an_unknown_status():
    """The old `else: "failed"` default is what reported Pi successes as failures."""
    with pytest.raises(InvalidTerminalTaskStatus, match="INVALID_TERMINAL_TASK_STATUS"):
        to_downstream_status("success")


def test_active_and_terminal_sets_are_disjoint():
    assert not (ACTIVE_TASK_STATUSES & TERMINAL_TASK_STATUSES)


def test_pi_success_reaches_the_queue_as_completed():
    """End to end across the two mappings — the exact path that was broken."""
    assert to_downstream_status(from_engine_outcome("success")) == "completed"
    assert to_downstream_status(from_engine_outcome("cancelled")) == "suspended"


def test_routes_uses_the_shared_terminal_set():
    """A second literal set in routes.py is how the two drifted apart."""
    import api.routes as routes

    assert routes.TERMINAL_TASK_STATUSES is TERMINAL_TASK_STATUSES
