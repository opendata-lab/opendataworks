"""Runtime-kind resolution and Pi outcome -> TaskExecutionResult translation."""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from config import Settings, get_settings, resolve_runtime_kind, update_settings  # noqa: E402
from core import task_executor  # noqa: E402
from core.pi_runtime import PiRunOutcome  # noqa: E402
from core.task_executor import TaskExecutionInput  # noqa: E402


def test_runtime_kind_defaults_to_claude_code():
    assert resolve_runtime_kind(Settings()) == "claude_code"


def test_runtime_kind_accepts_pi_agent_core():
    assert resolve_runtime_kind(Settings(dataagent_runtime_kind="pi_agent_core")) == "pi_agent_core"
    assert resolve_runtime_kind(Settings(dataagent_runtime_kind="  PI_AGENT_CORE  ")) == "pi_agent_core"


def test_unknown_runtime_kind_falls_back_instead_of_raising():
    """A typo in one env var must not take the whole backend down."""
    assert resolve_runtime_kind(Settings(dataagent_runtime_kind="pi-agent-core")) == "claude_code"
    assert resolve_runtime_kind(Settings(dataagent_runtime_kind="")) == "claude_code"


class _FakeStore:
    def __init__(self) -> None:
        self.records: list[dict[str, Any]] = []

    def append_sdk_record(self, **kwargs: Any) -> None:
        self.records.append(kwargs)


def _params(**overrides: Any) -> TaskExecutionInput:
    defaults: dict[str, Any] = dict(
        task_id="task-1",
        topic_id="topic-1",
        question="最近 30 天工作流发布次数趋势",
        history=[
            {"role": "user", "content": "上一轮问题"},
            {"role": "assistant", "content": "上一轮回答"},
            {"role": "user", "content": "   "},
        ],
        resume_session_id="sdk-session-should-be-ignored",
        provider_id="anthropic",
        model="test-model",
        database_hint=None,
    )
    defaults.update(overrides)
    return TaskExecutionInput(**defaults)


async def _run_adapter(monkeypatch, tmp_path: Path, outcome: PiRunOutcome, captured: dict[str, Any]):
    store = _FakeStore()
    monkeypatch.setattr(task_executor, "get_topic_task_store", lambda: store)
    monkeypatch.setattr("core.pi_runtime.resolve_cell_command", lambda cfg=None: ["/bin/true"])

    async def _fake_execute(ctx, *, writer, cancel_reason=None, **kwargs):
        captured["ctx"] = ctx
        return outcome

    monkeypatch.setattr("core.pi_runtime.execute_pi_run", _fake_execute)

    async def _no_cancel():
        return None

    return await task_executor._execute_task_stream_via_pi_runtime(
        _params(),
        provider_id="anthropic",
        model="test-model",
        system_prompt="sys",
        skill_runtime={"enabled_roots": {}},
        project_cwd=tmp_path,
        runtime_env={"DATAAGENT_PYTHON_BIN": sys.executable},
        provider_env={"ANTHROPIC_API_KEY": "sk-x"},
        agent_snapshot=None,
        cancel_reason=_no_cancel,
    )


@pytest.mark.asyncio
async def test_success_outcome_becomes_finished_result(monkeypatch, tmp_path: Path):
    """finished, not success: engine words must not reach the task row."""
    captured: dict[str, Any] = {}
    result = await _run_adapter(
        monkeypatch, tmp_path, PiRunOutcome(terminal_status="success", answer="趋势结果"), captured
    )

    assert result.task_status == "finished"
    assert result.content == "趋势结果"
    assert result.provider_id == "anthropic"


@pytest.mark.asyncio
async def test_failure_outcome_becomes_error_result(monkeypatch, tmp_path: Path):
    captured: dict[str, Any] = {}
    result = await _run_adapter(
        monkeypatch,
        tmp_path,
        PiRunOutcome(terminal_status="failed", error_code="PI_RUN_TIMEOUT", error_message="超时"),
        captured,
    )

    assert result.task_status == "error"
    assert result.error == {"code": "PI_RUN_TIMEOUT", "message": "超时"}


@pytest.mark.asyncio
async def test_cancelled_outcome_becomes_suspended_result(monkeypatch, tmp_path: Path):
    """suspended is the platform status; cancelled is the engine outcome."""
    captured: dict[str, Any] = {}
    result = await _run_adapter(
        monkeypatch, tmp_path, PiRunOutcome(terminal_status="cancelled", answer="部分"), captured
    )

    assert result.task_status == "suspended"
    assert result.content == "部分"


@pytest.mark.asyncio
async def test_history_is_replayed_and_resume_session_id_is_ignored(monkeypatch, tmp_path: Path):
    """Pi has no engine-level session, so every turn replays the transcript."""
    captured: dict[str, Any] = {}
    await _run_adapter(monkeypatch, tmp_path, PiRunOutcome(terminal_status="success"), captured)

    messages = captured["ctx"].messages
    assert messages == [
        {"role": "user", "content": "上一轮问题"},
        {"role": "assistant", "content": "上一轮回答"},
        {"role": "user", "content": "最近 30 天工作流发布次数趋势"},
    ]


@pytest.mark.asyncio
async def test_session_id_is_task_scoped(monkeypatch, tmp_path: Path):
    """A topic-scoped constant would make a later claude_code run drop history."""
    captured: dict[str, Any] = {}
    result = await _run_adapter(
        monkeypatch, tmp_path, PiRunOutcome(terminal_status="success"), captured
    )

    assert result.session_id == "pi-topic-1-task-1"
    assert result.session_id != "pi-topic-1"


@pytest.mark.asyncio
async def test_boundary_policy_uses_the_pi_profile(monkeypatch, tmp_path: Path):
    captured: dict[str, Any] = {}
    await _run_adapter(monkeypatch, tmp_path, PiRunOutcome(terminal_status="success"), captured)

    policy = captured["ctx"].boundary_policy
    assert policy["profile"] == "pi_agent_core"
    assert policy["tool_result_root"] is None
    assert str(tmp_path) in policy["allowed_roots"]


@pytest.mark.asyncio
async def test_missing_pi_runtime_reports_error_instead_of_raising(monkeypatch, tmp_path: Path):
    from core.pi_runtime import PiRuntimeUnavailable

    store = _FakeStore()
    monkeypatch.setattr(task_executor, "get_topic_task_store", lambda: store)

    def _unavailable(cfg=None):
        raise PiRuntimeUnavailable("Pi Cell 入口不存在：/nope")

    monkeypatch.setattr("core.pi_runtime.resolve_cell_command", _unavailable)

    async def _no_cancel():
        return None

    result = await task_executor._execute_task_stream_via_pi_runtime(
        _params(),
        provider_id="anthropic",
        model="test-model",
        system_prompt="sys",
        skill_runtime={"enabled_roots": {}},
        project_cwd=tmp_path,
        runtime_env={},
        provider_env={},
        agent_snapshot=None,
        cancel_reason=_no_cancel,
    )

    assert result.task_status == "error"
    assert result.error["code"] == "pi_runtime_missing"
    # Surfaced through the shared error record, same as the SDK path does for
    # a missing claude-agent-sdk.
    assert store.records[-1]["record_type"] == "error"


@pytest.mark.asyncio
async def test_mcp_servers_and_history_forwarded(monkeypatch, tmp_path: Path):
    captured: dict[str, Any] = {}
    cfg = get_settings()
    monkeypatch.setattr(cfg, "dataagent_portal_mcp_enabled", True)
    monkeypatch.setattr(cfg, "dataagent_portal_mcp_base_url", "http://portal-mcp:8801/mcp")
    monkeypatch.setattr(cfg, "dataagent_portal_mcp_token", "test-token")

    store = _FakeStore()
    monkeypatch.setattr(task_executor, "get_topic_task_store", lambda: store)
    monkeypatch.setattr("core.pi_runtime.resolve_cell_command", lambda cfg=None: ["/bin/true"])

    async def _fake_execute(ctx, *, writer, cancel_reason=None, **kwargs):
        captured["ctx"] = ctx
        return PiRunOutcome(terminal_status="success", answer="ok")

    monkeypatch.setattr("core.pi_runtime.execute_pi_run", _fake_execute)

    async def _no_cancel():
        return None

    params = _params()
    params.history = [
        {"role": "user", "content": "hello"},
        {"role": "assistant", "content": "hi"},
    ]
    params.question = "what tables exist?"

    agent_snapshot = {
        "mcp_server_ids": ["portal"],
        "data_scope": {"allowed_scopes": []},
    }

    result = await task_executor._execute_task_stream_via_pi_runtime(
        params,
        provider_id="anthropic",
        model="test-model",
        system_prompt="sys",
        skill_runtime={"enabled_roots": {"test-skill": "/skills/test"}},
        project_cwd=tmp_path,
        runtime_env={},
        provider_env={},
        agent_snapshot=agent_snapshot,
        cancel_reason=_no_cancel,
    )

    assert result.task_status == "finished"
    ctx = captured["ctx"]
    assert ctx.prompt == "what tables exist?"
    assert len(ctx.history) == 2
    assert ctx.history[0]["content"] == "hello"
    assert len(ctx.mcp_servers) == 1
    assert ctx.mcp_servers[0]["name"] == "portal"
    assert ctx.mcp_servers[0]["url"] == "http://portal-mcp:8801/mcp/"
    assert ctx.mcp_servers[0]["headers"]["X-Portal-MCP-Token"] == "test-token"
    assert len(ctx.skills) == 1
    assert ctx.skills[0]["name"] == "test-skill"

    # Verify serialization in cell.init payload
    init_payload = ctx.to_init_payload()
    assert init_payload["prompt"] == "what tables exist?"
    assert len(init_payload["history"]) == 2
    assert len(init_payload["mcp_servers"]) == 1


GOVERNANCE_SETTING_KEYS = (
    "dataagent_context_max_inline_result_bytes",
    "dataagent_context_protect_tail_turns",
    "dataagent_context_max_context_tokens",
)


@pytest.mark.asyncio
async def test_governance_settings_flow_from_settings_into_cell_init(monkeypatch, tmp_path):
    """Settings -> PiRunContext -> cell.init must carry context governance.

    The Cell reads these from ``governance_settings`` and silently falls back to
    its own hardcoded defaults when the section is absent, so a missing link
    here turns every DATAAGENT_CONTEXT_* setting into a no-op with no error.
    The key names are a wire contract with src/protocol/frames.ts.
    """
    captured: dict[str, Any] = {}
    originals = {key: getattr(get_settings(), key) for key in GOVERNANCE_SETTING_KEYS}
    update_settings(
        {
            "dataagent_context_max_inline_result_bytes": 4096,
            "dataagent_context_protect_tail_turns": 3,
            "dataagent_context_max_context_tokens": 12_345,
        }
    )
    try:
        await _run_adapter(
            monkeypatch,
            tmp_path,
            PiRunOutcome(terminal_status="success", last_sequence=1),
            captured,
        )
    finally:
        update_settings(originals)

    governance = captured["ctx"].to_init_payload()["governance_settings"]
    assert governance["max_inline_result_bytes"] == 4096
    assert governance["protect_tail_turns"] == 3
    assert governance["max_context_tokens"] == 12_345
    # Watermarks and cache retention ride in the same section, so the Cell has a
    # single source for every governance decision.
    assert governance["prune_high_watermark_ratio"] == 0.90
    assert governance["prune_target_ratio"] == 0.70
    assert governance["cache_retention"] in {"short", "long", "off"}



@pytest.mark.asyncio
async def test_pi_terminal_outcomes_use_platform_task_statuses(monkeypatch, tmp_path: Path):
    """Pi must not return its own vocabulary to the platform.

    'success' and 'cancelled' are engine words. Returning them verbatim put a
    status in the task row that the SSE terminal set did not recognise, so a
    finished run left the stream open and the UI spinning, while finish_task's
    downstream mapping recorded the same run as a failure.
    """
    from core.task_status import TERMINAL_TASK_STATUSES

    for outcome_status, expected in (("success", "finished"), ("cancelled", "suspended")):
        captured: dict[str, Any] = {}
        result = await _run_adapter(
            monkeypatch, tmp_path, PiRunOutcome(terminal_status=outcome_status, answer="ok"), captured
        )
        assert result.task_status == expected, outcome_status
        assert result.task_status in TERMINAL_TASK_STATUSES, outcome_status
