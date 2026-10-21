"""Default inheritance and mode budgets, including the sandbox dispatch boundary."""
from __future__ import annotations

import sys
from dataclasses import asdict
from pathlib import Path

import pytest
from pydantic import ValidationError

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from config import Settings, resolve_run_limits
from core import task_executor, task_submission_service
from core.agent_profile_service import default_agent_payload, opendataworks_agent_payload
from core.task_executor import TaskExecutionInput, TaskExecutionResult


@pytest.mark.parametrize("mode,turns,calls,total,bash", [
    (None, 24, 100, 360, 300),
    ("interactive", 24, 100, 360, 300),
    ("auto", 40, 200, 1800, 900),
    ("background", 40, 200, 1800, 900),
])
def test_no_overrides_selects_bounded_mode_defaults(mode, turns, calls, total, bash):
    cfg = Settings(_env_file=None)
    limits = resolve_run_limits(cfg, mode)
    assert limits == dict(max_turns=turns, max_tool_calls=calls,
                          total_timeout_seconds=total, bash_timeout_seconds=bash,
                          idle_timeout_seconds=300)
    assert default_agent_payload()["max_turns"] == opendataworks_agent_payload()["max_turns"] == 0


def test_global_environment_then_explicit_settings_then_agent_override(monkeypatch):
    monkeypatch.setenv("AGENT_BACKGROUND_MAX_TOOL_CALLS", "250")
    monkeypatch.setenv("AGENT_BACKGROUND_MAX_TURNS", "48")
    cfg = Settings(_env_file=None)
    assert resolve_run_limits(cfg, "auto")["max_tool_calls"] == 250
    assert resolve_run_limits(cfg, "auto", agent_max_turns=0)["max_turns"] == 48
    assert resolve_run_limits(cfg, "auto", agent_max_turns=12)["max_turns"] == 12
    cfg = Settings(_env_file=None, agent_background_max_tool_calls=220)
    assert resolve_run_limits(cfg, "auto")["max_tool_calls"] == 220


@pytest.mark.parametrize("field", [
    "agent_interactive_max_turns", "agent_background_max_tool_calls",
    "agent_interactive_timeout_seconds", "agent_background_bash_timeout_seconds",
    "dataagent_run_idle_timeout_seconds", "agent_sql_write_timeout_seconds",
])
@pytest.mark.parametrize("invalid", [0, -1])
def test_global_budgets_reject_unbounded_or_negative_values(field, invalid):
    with pytest.raises(ValidationError):
        Settings(_env_file=None, **{field: invalid})


@pytest.mark.parametrize("patch", [
    {"agent_background_bash_timeout_seconds": 120},
    {"agent_interactive_timeout_seconds": 300},
])
def test_invalid_timeout_chain_is_rejected(patch):
    with pytest.raises(ValidationError, match="SQL timeouts"):
        Settings(_env_file=None, **patch)


def test_submission_and_execution_share_total_budget_and_preserve_submitted_value(monkeypatch):
    cfg = Settings(_env_file=None, agent_background_timeout_seconds=2400)
    monkeypatch.setattr(task_submission_service, "get_settings", lambda: cfg)
    assert task_submission_service.resolve_task_timeouts("auto")["timeout_seconds"] == 2400
    assert resolve_run_limits(cfg, "auto", total_timeout_seconds=1800)["total_timeout_seconds"] == 1800


@pytest.mark.asyncio
async def test_sandbox_receives_resolved_control_plane_budget(monkeypatch):
    cfg = Settings(_env_file=None, dataagent_sandbox_mode="container",
                   agent_background_max_tool_calls=260, dataagent_run_idle_timeout_seconds=240)
    monkeypatch.setattr(task_executor, "get_settings", lambda: cfg)
    captured = {}

    async def runner(params, **kwargs):
        # The same dataclass serialization/reconstruction used by runner + child.
        received = TaskExecutionInput(**asdict(params))
        captured.update(received.runtime_limits)
        return TaskExecutionResult(task_status="finished", content="ok")

    monkeypatch.setattr(task_executor, "_execute_task_stream_via_runner", runner)
    params = TaskExecutionInput(task_id="t", topic_id="tp", question="hi", history=[],
                                resume_session_id=None, provider_id="p", model="m", database_hint=None,
                                execution_mode="auto", timeout_seconds=1200,
                                agent_snapshot={"max_turns": 18})
    await task_executor.execute_task_stream(params, emit=lambda event: None)
    assert captured == dict(max_turns=18, max_tool_calls=260, total_timeout_seconds=1200,
                            idle_timeout_seconds=240, bash_timeout_seconds=900)
