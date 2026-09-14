"""End-to-end: the Python adapter driving the *real* built Node Pi Cell.

Everything else in the suite tests one side of the stdio protocol. This tests
both against each other, which is the only way to catch the two sides agreeing
with themselves and disagreeing with each other — the failure that a wrapper
mismatch (payload={"event": ...} vs. the bare event) produces, where each side's
own tests stay green while no event ever crosses.

Skipped when dist/ has not been built, so a Python-only checkout still runs
clean; CI builds the package and therefore runs it for real.
"""

from __future__ import annotations

import shutil
import sys
from pathlib import Path
from typing import Any

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core.boundary_policy import build_boundary_policy  # noqa: E402
from core.pi_runtime import PiRunContext, execute_pi_run  # noqa: E402

REPO_DATAAGENT = Path(__file__).resolve().parents[2]
CELL_ENTRYPOINT = REPO_DATAAGENT / "dataagent-runtime-pi" / "dist" / "src" / "main.js"

pytestmark = pytest.mark.skipif(
    not CELL_ENTRYPOINT.exists() or shutil.which("node") is None,
    reason="Pi Cell not built (run npm ci && npm run build in dataagent-runtime-pi) or node unavailable",
)


class _RecordingWriter:
    def __init__(self) -> None:
        self.events: list[dict[str, Any]] = []

    def ingest(self, event: dict[str, Any]) -> None:
        self.events.append(event)


def _context(tmp_path: Path, provider_id: str, model_id: str) -> PiRunContext:
    return PiRunContext(
        task_id="task-e2e-1",
        topic_id="topic-e2e",
        provider_id=provider_id,
        api_format="/v1/messages",
        model=model_id,
        system_prompt="你是 OpenDataWorks 的数据助手。",
        messages=[{"role": "user", "content": "你好"}],
        project_cwd=tmp_path,
        boundary_policy=build_boundary_policy(
            tmp_path, {"enabled_roots": {}}, [str(tmp_path)], {}, profile="pi_agent_core"
        ),
        runtime_env={"DATAAGENT_PYTHON_BIN": sys.executable},
        total_timeout_seconds=60,
        idle_timeout_seconds=30,
    )


@pytest.mark.asyncio
async def test_real_cell_completes_the_protocol_round_trip(tmp_path: Path):
    """cell.init in, neutral events out, exactly one terminal event.

    Uses an unsupported provider deliberately: it exercises the whole frame
    round-trip against the real Cell without needing model credentials, and
    proves that a provider the Cell cannot serve still terminates the run
    instead of hanging it.
    """
    writer = _RecordingWriter()
    outcome = await execute_pi_run(_context(tmp_path, "definitely-not-a-provider", "x"), writer=writer)

    assert outcome.terminal_status == "failed"
    assert writer.events, "the real Cell produced no events at all — the protocol did not cross"

    # The Cell must have parsed cell.init: it reached run.started before failing.
    assert writer.events[0]["type"] == "run.started"

    terminal_types = {"run.completed", "run.failed", "run.cancelled", "run.suspended"}
    terminals = [e for e in writer.events if e["type"] in terminal_types]
    assert len(terminals) == 1, f"expected exactly one terminal event, got {[e['type'] for e in terminals]}"
    assert terminals[0] is writer.events[-1]
    assert "provider" in str(terminals[0]["payload"].get("message", "")).lower()


@pytest.mark.asyncio
async def test_real_cell_events_are_unwrapped_and_contiguous(tmp_path: Path):
    """The exact shape agreement the two sides must have."""
    writer = _RecordingWriter()
    await execute_pi_run(_context(tmp_path, "definitely-not-a-provider", "x"), writer=writer)

    for index, event in enumerate(writer.events, start=1):
        # Bare event, not {"event": {...}}.
        assert "event" not in event, "run.event payload must be the event itself, not a wrapper"
        assert set(event) >= {"event_id", "run_id", "task_id", "sequence", "type", "payload"}
        assert event["sequence"] == index, "sequences must be contiguous from 1"
        # Dotted names only: the Python AgentEventType enum is closed and would
        # reject a snake_case variant, dropping the event silently.
        assert "." in event["type"] and "_" not in event["type"]


@pytest.mark.asyncio
async def test_real_cell_receives_the_boundary_policy(tmp_path: Path):
    """A malformed policy would make the Cell fail differently than a bad provider.

    Reaching run.started proves cell.init parsed cleanly, boundary policy
    included, before provider resolution failed it.
    """
    writer = _RecordingWriter()
    ctx = _context(tmp_path, "definitely-not-a-provider", "x")
    assert ctx.boundary_policy["profile"] == "pi_agent_core"
    assert str(tmp_path) in ctx.boundary_policy["allowed_roots"]

    await execute_pi_run(ctx, writer=writer)

    assert writer.events[0]["type"] == "run.started"
    assert writer.events[0]["payload"]["topic_id"] == "topic-e2e"
