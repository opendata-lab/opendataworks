"""Cross-process contract tests for the Pi data plane adapter.

These drive a *real child process* over the *real stdio protocol*. That matters:
the failure mode this guards against is the two sides of a framed protocol
disagreeing on payload shape while each side's own unit tests stay green. A test
that mocks the channel away cannot see it.

The stub Cell is Python rather than Node so the suite has no toolchain
dependency; the Node implementation is held to the same frames by its own tests.
"""

from __future__ import annotations

import asyncio
import json
import sys
import textwrap
from pathlib import Path
from typing import Any

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core.pi_runtime import (  # noqa: E402
    PiRunContext,
    PiRuntimeUnavailable,
    execute_pi_run,
    resolve_cell_command,
)


class _RecordingWriter:
    def __init__(self) -> None:
        self.events: list[dict[str, Any]] = []

    def ingest(self, event: dict[str, Any]) -> None:
        self.events.append(event)


def _context(tmp_path: Path, **overrides: Any) -> PiRunContext:
    defaults: dict[str, Any] = dict(
        task_id="task-1",
        topic_id="topic-1",
        provider_id="anthropic",
        api_format="/v1/messages",
        model="test-model",
        system_prompt="sys",
        messages=[{"role": "user", "content": "hi"}],
        project_cwd=tmp_path,
        boundary_policy={"policy_version": 1, "allowed_roots": [str(tmp_path)]},
        total_timeout_seconds=10,
        idle_timeout_seconds=5,
    )
    defaults.update(overrides)
    return PiRunContext(**defaults)


def _stub_cell(body: str) -> list[str]:
    """A child process speaking the Cell side of the protocol."""
    script = textwrap.dedent(
        """
        import json, sys, time

        def send(frame_type, payload=None):
            sys.stdout.write(json.dumps(
                {"protocol_version": 1, "type": frame_type, "payload": payload or {}}
            ) + "\\n")
            sys.stdout.flush()

        def event(sequence, event_type, payload=None):
            send("run.event", {
                "event_id": "ev-%s" % sequence,
                "run_id": "task-1",
                "task_id": "task-1",
                "task_attempt_id": "attempt-1",
                "sequence": sequence,
                "type": event_type,
                "payload": payload or {},
            })

        def read():
            line = sys.stdin.readline()
            if not line:
                return None
            return json.loads(line)

        """
    ) + textwrap.dedent(body)
    return [sys.executable, "-c", script]


@pytest.mark.asyncio
async def test_happy_path_streams_events_and_reports_success(tmp_path: Path):
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        init = read()
        assert init["type"] == "cell.init", init
        # The init payload is the run spec itself, not a wrapper around it.
        assert init["payload"]["model"]["model_id"] == "test-model"
        assert init["payload"]["model"]["api_format"] == "/v1/messages"
        assert init["payload"]["system_prompt"] == "sys"
        assert init["payload"]["limits"]["total_timeout_seconds"] == 10
        send("cell.ready", {"manifest": {"runtime_kind": "pi_agent_core"}})
        event(1, "run.started")
        event(2, "turn.started", {"turn_id": "turn-1"})
        event(3, "content.delta", {"content_id": "c-0", "kind": "answer", "delta": "he"})
        event(4, "content.delta", {"content_id": "c-0", "kind": "reasoning", "delta": "IGNORED"})
        event(5, "content.delta", {"content_id": "c-0", "kind": "answer", "delta": "llo"})
        event(6, "run.completed", {"terminal_status": "success"})
        send("run.settled", {"terminal_status": "success"})
        """
    )

    outcome = await execute_pi_run(_context(tmp_path), writer=writer, cell_command=cell)

    assert outcome.terminal_status == "success"
    # Reasoning deltas must not leak into the user-visible answer.
    assert outcome.answer == "hello"
    assert outcome.last_sequence == 6
    assert [e["type"] for e in writer.events] == [
        "run.started",
        "turn.started",
        "content.delta",
        "content.delta",
        "content.delta",
        "run.completed",
    ]


@pytest.mark.asyncio
async def test_run_event_payload_is_the_event_itself(tmp_path: Path):
    """Pins the exact shape both sides must agree on.

    A wrapper here (payload={"event": {...}}) is precisely the asymmetry that
    silently drops every event, so assert the unwrapped shape explicitly.
    """
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        read()
        event(1, "tool.started", {"tool_call_id": "t1", "tool_name": "run_sql", "input": {"sql": "select 1"}})
        event(2, "tool.completed", {"tool_call_id": "t1", "output": "rows: 1", "is_error": False})
        event(3, "run.completed", {"terminal_status": "success", "answer": "ok"})
        """
    )

    outcome = await execute_pi_run(_context(tmp_path), writer=writer, cell_command=cell)

    assert outcome.terminal_status == "success"
    assert outcome.answer == "ok"
    started = writer.events[0]
    assert started["type"] == "tool.started"
    assert started["payload"]["tool_name"] == "run_sql"
    assert "event" not in started["payload"]
    completed = writer.events[1]
    assert completed["payload"]["output"] == "rows: 1"
    assert completed["payload"]["is_error"] is False


@pytest.mark.asyncio
async def test_blank_and_non_json_lines_do_not_tear_down_the_channel(tmp_path: Path):
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        read()
        sys.stdout.write("\\n"); sys.stdout.flush()
        sys.stdout.write("this is not json\\n"); sys.stdout.flush()
        sys.stdout.write("   \\n"); sys.stdout.flush()
        sys.stdout.write("[1,2,3]\\n"); sys.stdout.flush()
        event(1, "content.delta", {"kind": "answer", "delta": "survived"})
        event(2, "run.completed", {"terminal_status": "success"})
        """
    )

    outcome = await execute_pi_run(_context(tmp_path), writer=writer, cell_command=cell)

    assert outcome.terminal_status == "success"
    assert outcome.answer == "survived"


@pytest.mark.asyncio
async def test_child_exit_without_terminal_event_yields_cell_loss(tmp_path: Path):
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        read()
        event(1, "content.delta", {"kind": "answer", "delta": "partial"})
        sys.exit(3)
        """
    )

    outcome = await execute_pi_run(_context(tmp_path), writer=writer, cell_command=cell)

    assert outcome.terminal_status == "failed"
    assert outcome.error_code == "CELL_LOSS"
    assert outcome.answer == "partial"
    # The record stream must still be terminated, or history replay shows a
    # turn that never ends.
    assert writer.events[-1]["type"] == "run.failed"
    assert writer.events[-1]["payload"]["error_code"] == "CELL_LOSS"


@pytest.mark.asyncio
async def test_total_timeout_terminates_a_wedged_cell(tmp_path: Path):
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        read()
        time.sleep(60)
        """
    )

    outcome = await execute_pi_run(
        _context(tmp_path, total_timeout_seconds=1, idle_timeout_seconds=0),
        writer=writer,
        cell_command=cell,
    )

    assert outcome.terminal_status == "failed"
    assert outcome.error_code == "PI_RUN_TIMEOUT"
    assert writer.events[-1]["type"] == "run.failed"


@pytest.mark.asyncio
async def test_idle_timeout_fires_while_the_cell_is_silent(tmp_path: Path):
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        read()
        event(1, "run.started")
        time.sleep(60)
        """
    )

    outcome = await execute_pi_run(
        _context(tmp_path, total_timeout_seconds=60, idle_timeout_seconds=1),
        writer=writer,
        cell_command=cell,
    )

    assert outcome.terminal_status == "failed"
    assert outcome.error_code == "PI_RUN_IDLE_TIMEOUT"


@pytest.mark.asyncio
async def test_cancel_flag_sends_run_cancel_frame(tmp_path: Path):
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        read()
        event(1, "run.started")
        frame = read()
        assert frame["type"] == "run.cancel", frame
        event(2, "run.cancelled", {"reason": frame["payload"].get("reason")})
        """
    )

    async def _cancelled() -> str:
        return "user_cancel"

    outcome = await execute_pi_run(
        _context(tmp_path), writer=writer, cell_command=cell, cancel_reason=_cancelled
    )

    assert outcome.terminal_status == "cancelled"
    assert writer.events[-1]["type"] == "run.cancelled"
    assert writer.events[-1]["payload"]["reason"] == "user_cancel"


@pytest.mark.asyncio
async def test_child_is_always_reaped(tmp_path: Path):
    """Even a Cell that settles but refuses to exit must not survive the run."""
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        read()
        event(1, "run.completed", {"terminal_status": "success"})
        time.sleep(120)
        """
    )

    before = asyncio.get_running_loop().time()
    outcome = await execute_pi_run(_context(tmp_path), writer=writer, cell_command=cell)
    elapsed = asyncio.get_running_loop().time() - before

    assert outcome.terminal_status == "success"
    # Terminate + grace, not the child's 120s sleep.
    assert elapsed < 15


@pytest.mark.asyncio
async def test_provider_credentials_travel_by_env_not_by_stdio(tmp_path: Path):
    """Secrets must never appear in a frame the child could log or echo."""
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        import os
        init = read()
        raw = json.dumps(init)
        assert "sk-secret-value" not in raw, "credential leaked into cell.init payload"
        assert os.environ.get("ANTHROPIC_API_KEY") == "sk-secret-value"
        event(1, "run.completed", {"terminal_status": "success", "answer": "ok"})
        """
    )

    ctx = _context(tmp_path, provider_env={"ANTHROPIC_API_KEY": "sk-secret-value"})
    outcome = await execute_pi_run(ctx, writer=writer, cell_command=cell)

    assert outcome.terminal_status == "success", outcome.error_message


@pytest.mark.asyncio
async def test_runtime_env_reaches_the_child(tmp_path: Path):
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        import os
        read()
        assert os.environ.get("DATAAGENT_PYTHON_BIN") == "/usr/bin/python3"
        event(1, "run.completed", {"terminal_status": "success"})
        """
    )

    ctx = _context(tmp_path, runtime_env={"DATAAGENT_PYTHON_BIN": "/usr/bin/python3"})
    outcome = await execute_pi_run(ctx, writer=writer, cell_command=cell)

    assert outcome.terminal_status == "success"


def test_missing_cell_entrypoint_reports_actionable_error(tmp_path: Path, monkeypatch):
    monkeypatch.setenv("DATAAGENT_RUNTIME_PI_DIR", str(tmp_path / "nonexistent"))
    monkeypatch.setenv("DATAAGENT_NODE_BIN", sys.executable)

    with pytest.raises(PiRuntimeUnavailable, match="Pi Cell 入口不存在"):
        resolve_cell_command()


@pytest.mark.asyncio
async def test_cancel_is_bounded_when_the_cell_ignores_it(tmp_path: Path):
    """A cancel the Cell never honours must not wait out the run timeout.

    Without a cancel-specific deadline the loop keeps polling until
    total_timeout_seconds, so a user cancelling a 360s run at t=10s waits the
    remaining 350s -- and then sees it reported as a timeout failure rather
    than the cancellation it was.
    """
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        read()
        event(1, "run.started")
        # Receive the cancel frame and deliberately do nothing about it.
        read()
        time.sleep(60)
        """
    )

    async def _cancelled() -> str:
        return "user_cancel"

    started = asyncio.get_running_loop().time()
    outcome = await execute_pi_run(
        _context(tmp_path, total_timeout_seconds=60, idle_timeout_seconds=0),
        writer=writer,
        cell_command=cell,
        cancel_reason=_cancelled,
    )
    elapsed = asyncio.get_running_loop().time() - started

    assert outcome.terminal_status == "cancelled", "an ignored cancel is still a cancellation"
    assert elapsed < 20, f"cancel took {elapsed:.1f}s; it must be bounded by the grace period"


@pytest.mark.asyncio
async def test_idle_timeout_does_not_mislabel_a_pending_cancel(tmp_path: Path):
    """After a cancel is sent, silence is expected -- not an idle failure."""
    writer = _RecordingWriter()
    cell = _stub_cell(
        """
        read()
        event(1, "run.started")
        read()
        time.sleep(60)
        """
    )

    async def _cancelled() -> str:
        return "user_cancel"

    outcome = await execute_pi_run(
        _context(tmp_path, total_timeout_seconds=60, idle_timeout_seconds=1),
        writer=writer,
        cell_command=cell,
        cancel_reason=_cancelled,
    )

    assert outcome.terminal_status == "cancelled"
    assert outcome.error_code != "PI_RUN_IDLE_TIMEOUT"
