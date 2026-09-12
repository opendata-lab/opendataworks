from __future__ import annotations

import asyncio
import json
import logging
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from config import get_settings, update_settings
from core import task_executor


class _RecordingWriter:
    def __init__(self):
        self.requests = []
        self.decisions = []
        self.question_requests = []
        self.question_answers = []

    def append_permission_request(self, **kwargs):
        self.requests.append(kwargs)

    def append_permission_decision(self, **kwargs):
        self.decisions.append(kwargs)

    def append_question_request(self, **kwargs):
        self.question_requests.append(kwargs)

    def append_question_answer(self, **kwargs):
        self.question_answers.append(kwargs)


class _RecordingStore:
    def __init__(self):
        self.statuses = []
        self.question_answer_records = []

    def set_task_status(self, task_id, status):
        self.statuses.append(status)

    def append_question_answer_record(self, **kwargs):
        self.question_answer_records.append(kwargs)


class _SdkRecordStore:
    def __init__(self):
        self.records = []

    def append_sdk_record(self, **kwargs):
        self.records.append(kwargs)


@pytest.fixture(autouse=True)
def _pin_sdk_engine():
    """These tests drive the claude_code engine, so say so instead of inheriting.

    They passed for as long as claude_code was the default. When production's
    engine became the default they all routed to Pi, which is not what any of
    them is about — a whole file silently testing the wrong path is the same
    failure the default change was made to prevent.
    """
    original = get_settings().dataagent_runtime_kind
    update_settings({"dataagent_runtime_kind": "claude_code"})
    try:
        yield
    finally:
        update_settings({"dataagent_runtime_kind": original})


def _build_gate(monkeypatch, decision, *, mode="default"):
    writer = _RecordingWriter()
    store = _RecordingStore()

    async def fake_wait(task_id, request_id, *, cancel_reason=None, **kw):
        return decision

    monkeypatch.setattr(task_executor, "wait_for_decision", fake_wait)
    cb = task_executor._build_can_use_tool_callback(
        sdk_writer=writer,
        store=store,
        task_id="task-1",
        permission_mode=mode,
        cancel_reason=None,
    )
    return cb, writer, store


def _permission_behavior(result):
    if isinstance(result, dict):
        return result.get("behavior")
    name = type(result).__name__.lower()
    if "allow" in name:
        return "allow"
    if "deny" in name:
        return "deny"
    return getattr(result, "behavior", None)


def _permission_updated_input(result):
    if isinstance(result, dict):
        return result.get("updatedInput")
    return getattr(result, "updated_input", None)


def test_can_use_tool_allows_read_tools_without_confirmation(monkeypatch):
    cb, writer, store = _build_gate(monkeypatch, "deny")
    result = asyncio.run(cb("mcp__portal__portal_search_tables", {}))
    assert _permission_behavior(result) == "allow"
    assert writer.requests == []
    assert store.statuses == []


def test_can_use_tool_confirms_write_tool_allowed(monkeypatch):
    cb, writer, store = _build_gate(monkeypatch, "allow")
    result = asyncio.run(cb("mcp__portal__portal_create_task", {"summary": "建表任务"}))
    assert _permission_behavior(result) == "allow"
    assert len(writer.requests) == 1
    assert writer.requests[0]["tool_name"] == "mcp__portal__portal_create_task"
    assert writer.decisions == []
    assert store.statuses == ["waiting_permission", "running"]


def test_can_use_tool_confirms_write_tool_denied(monkeypatch):
    cb, writer, store = _build_gate(monkeypatch, "deny")
    result = asyncio.run(cb("mcp__portal__portal_publish_workflow", {"summary": "发布", "operation": "deploy"}))
    assert _permission_behavior(result) == "deny"
    assert writer.requests[0]["risk_level"] == "critical"
    assert writer.decisions == []
    assert store.statuses == ["waiting_permission", "running"]


def test_can_use_tool_strips_card_annotations_from_forwarded_input(monkeypatch):
    cb, writer, store = _build_gate(monkeypatch, "allow")
    result = asyncio.run(
        cb(
            "mcp__portal__portal_publish_workflow",
            {
                "workflow_id": 7,
                "operation": "deploy",
                "preview_token": "tok",
                "title": "发布工作流 #7",
                "summary": "新增一张表",
            },
        )
    )
    assert _permission_behavior(result) == "allow"
    # Card-annotation keys must not leak into the forwarded MCP call (extra="forbid").
    assert _permission_updated_input(result) == {
        "workflow_id": 7,
        "operation": "deploy",
        "preview_token": "tok",
    }
    # The card still receives the annotations for display.
    assert writer.requests[0]["title"] == "发布工作流 #7"
    assert writer.requests[0]["summary"] == "新增一张表"


def test_can_use_tool_auto_allow_strips_card_annotations(monkeypatch):
    cb, writer, store = _build_gate(monkeypatch, "allow", mode="acceptEdits")
    # Draft write tool auto-allows under acceptEdits but still must not forward annotations.
    result = asyncio.run(
        cb("mcp__portal__portal_create_task", {"task": {"name": "t"}, "title": "建表", "summary": "x"})
    )
    assert _permission_behavior(result) == "allow"
    assert writer.requests == []
    assert _permission_updated_input(result) == {"task": {"name": "t"}}


def test_can_use_tool_denies_after_persisted_denial(monkeypatch):
    cb, writer, store = _build_gate(monkeypatch, "deny")
    result = asyncio.run(cb("mcp__portal__portal_create_task", {}))
    assert _permission_behavior(result) == "deny"
    assert writer.decisions == []


def test_can_use_tool_exit_plan_mode_approved_switches_mode(monkeypatch):
    # Under plan mode, ExitPlanMode pauses the run for approval. On allow it records
    # a plan card, returns a setMode permission update (or dict fallback), and flips
    # the in-run effective mode so subsequent draft writes auto-allow.
    cb, writer, store = _build_gate(monkeypatch, "allow", mode="plan")
    result = asyncio.run(cb("ExitPlanMode", {"plan": "1. 建表\n2. 发布"}))
    assert _permission_behavior(result) == "allow"
    assert len(writer.requests) == 1
    req = writer.requests[0]
    assert req["risk_level"] == "plan"
    assert req["tool_name"] == "ExitPlanMode"
    assert req["summary"] == "1. 建表\n2. 发布"
    assert store.statuses == ["waiting_permission", "running"]

    # After approval the same callback gates a draft write per acceptEdits: auto-allow
    # without a new confirmation request, instead of plan-denying it.
    result2 = asyncio.run(cb("mcp__portal__portal_create_task", {"task": {"name": "t"}}))
    assert _permission_behavior(result2) == "allow"
    assert len(writer.requests) == 1  # no extra confirmation recorded


def test_can_use_tool_exit_plan_mode_denied(monkeypatch):
    cb, writer, store = _build_gate(monkeypatch, "deny", mode="plan")
    result = asyncio.run(cb("ExitPlanMode", {"plan": "草案"}))
    assert _permission_behavior(result) == "deny"
    assert writer.requests[0]["risk_level"] == "plan"
    assert store.statuses == ["waiting_permission", "running"]

    # Still in plan mode: a write tool is plan-denied outright (no confirmation).
    result2 = asyncio.run(cb("mcp__portal__portal_create_task", {}))
    assert _permission_behavior(result2) == "deny"
    assert len(writer.requests) == 1


def test_can_use_tool_plan_mode_denies_write_before_approval(monkeypatch):
    cb, writer, store = _build_gate(monkeypatch, "allow", mode="plan")
    result = asyncio.run(cb("mcp__portal__portal_create_task", {"summary": "建表"}))
    assert _permission_behavior(result) == "deny"
    assert writer.requests == []
    assert store.statuses == []


def test_can_use_tool_plan_mode_denies_builtin_file_writes(monkeypatch):
    # Built-in Write/Edit/MultiEdit/NotebookEdit must be denied under plan, not
    # auto-allowed via requires_confirmation==False.
    cb, writer, store = _build_gate(monkeypatch, "allow", mode="plan")
    for tool in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
        result = asyncio.run(cb(tool, {"file_path": "/ws/x", "content": "y"}))
        assert _permission_behavior(result) == "deny", tool
    assert writer.requests == []
    assert store.statuses == []


def test_can_use_tool_builtin_write_auto_allows_after_plan_approval(monkeypatch):
    # After plan approval the run is acceptEdits, where built-in file edits auto-run.
    cb, writer, store = _build_gate(monkeypatch, "allow", mode="plan")
    asyncio.run(cb("ExitPlanMode", {"plan": "步骤"}))
    result = asyncio.run(cb("Write", {"file_path": "/ws/x", "content": "y"}))
    assert _permission_behavior(result) == "allow"


def _build_ask_gate(monkeypatch, answers, *, mode="default"):
    writer = _RecordingWriter()
    store = _RecordingStore()

    async def fake_answer(task_id, request_id, *, cancel_reason=None, **kw):
        return answers

    monkeypatch.setattr(task_executor, "wait_for_answer", fake_answer)
    cb = task_executor._build_can_use_tool_callback(
        sdk_writer=writer,
        store=store,
        task_id="task-1",
        permission_mode=mode,
        cancel_reason=None,
    )
    return cb, writer, store


def _updated_input(result):
    if isinstance(result, dict):
        return result.get("updatedInput") or result.get("updated_input")
    return getattr(result, "updated_input", None)


@pytest.mark.parametrize("mode", ["default", "plan", "bypassPermissions", "acceptEdits"])
def test_can_use_tool_ask_user_question_answers_in_any_mode(monkeypatch, mode):
    # AskUserQuestion is intercepted regardless of session mode (matching the
    # official behavior of asking even under plan / bypassPermissions). The user's
    # selection is mapped onto updated_input.answers so the built-in tool resumes.
    answers = [{"question": "按哪个维度统计?", "selected": ["按天"], "other": "含节假日"}]
    cb, writer, store = _build_ask_gate(monkeypatch, answers, mode=mode)
    questions = [{"question": "按哪个维度统计?", "header": "维度", "options": [{"label": "按天"}]}]

    result = asyncio.run(cb("AskUserQuestion", {"questions": questions}, SimpleNamespace(tool_use_id="tu-9")))

    assert _permission_behavior(result) == "allow"
    updated = _updated_input(result)
    assert updated["answers"] == {"按哪个维度统计?": "按天"}
    assert updated["annotations"] == {"按哪个维度统计?": {"notes": "含节假日"}}
    assert writer.question_requests[0]["request_id"] == "tu-9"
    assert store.question_answer_records == []
    assert store.statuses == ["waiting_input", "running"]


def test_can_use_tool_ask_user_question_no_answer_returns_questions_only(monkeypatch):
    # Timeout / cancellation yields no answers; returning only questions lets the
    # built-in tool produce its own "did not answer" result and continue.
    cb, writer, store = _build_ask_gate(monkeypatch, [])
    questions = [{"question": "按哪个维度统计?", "header": "维度", "options": [{"label": "按天"}]}]

    result = asyncio.run(cb("AskUserQuestion", {"questions": questions}, SimpleNamespace(tool_use_id="tu-1")))

    assert _permission_behavior(result) == "allow"
    assert _updated_input(result) == {"questions": questions}
    assert store.statuses == ["waiting_input", "running"]


def test_can_use_tool_plan_denies_writes_without_request(monkeypatch):
    cb, writer, store = _build_gate(monkeypatch, "allow", mode="plan")
    result = asyncio.run(cb("mcp__portal__portal_create_task", {}))
    assert _permission_behavior(result) == "deny"
    assert writer.requests == []


class ClaudeAgentOptions:
    last_kwargs = None

    def __init__(self, **kwargs):
        ClaudeAgentOptions.last_kwargs = kwargs
        self.kwargs = kwargs


class QueryCapture:
    last_prompt = None
    last_options = None
    calls = []


class StreamEvent:
    def __init__(self, event, *, session_id=""):
        self.event = event
        self.session_id = session_id


class UserMessage:
    def __init__(self, content):
        self.content = content


class AssistantMessage:
    def __init__(self, content, *, error=""):
        self.content = content
        self.error = error


class ResultMessage:
    def __init__(self, subtype="success", result=None, *, is_error=False, session_id=""):
        self.subtype = subtype
        self.result = result
        self.is_error = is_error
        self.session_id = session_id


class ThinkingBlock:
    type = "thinking"

    def __init__(self, thinking):
        self.thinking = thinking


class TextBlock:
    type = "text"

    def __init__(self, text):
        self.text = text


class ToolUseBlock:
    type = "tool_use"

    def __init__(self, *, id, name, input):
        self.id = id
        self.name = name
        self.input = input


class ToolResultBlock:
    type = "tool_result"

    def __init__(self, *, tool_use_id, name, content):
        self.tool_use_id = tool_use_id
        self.name = name
        self.content = content


@pytest.fixture(autouse=True)
def _configured_skills_root(tmp_path: Path):
    original = getattr(get_settings(), "skills_root_dir", "")
    skills_root = tmp_path / ".claude" / "skills"
    skills_root.mkdir(parents=True, exist_ok=True)
    update_settings({"skills_root_dir": str(skills_root)})
    try:
        yield
    finally:
        update_settings({"skills_root_dir": original})


def _install_fake_sdk(monkeypatch, messages, *, final_exception=None):
    QueryCapture.last_prompt = None
    QueryCapture.last_options = None
    QueryCapture.calls = []

    async def fake_query(*, prompt, options):
        QueryCapture.last_prompt = prompt
        QueryCapture.last_options = options
        QueryCapture.calls.append({"prompt": prompt, "options": options})
        for message in messages:
            yield message
        if final_exception is not None:
            raise final_exception

    monkeypatch.setitem(
        sys.modules,
        "claude_agent_sdk",
        SimpleNamespace(ClaudeAgentOptions=ClaudeAgentOptions, query=fake_query),
    )


def _install_fake_sdk_runs(monkeypatch, runs):
    QueryCapture.last_prompt = None
    QueryCapture.last_options = None
    QueryCapture.calls = []

    async def fake_query(*, prompt, options):
        QueryCapture.last_prompt = prompt
        QueryCapture.last_options = options
        QueryCapture.calls.append({"prompt": prompt, "options": options})
        index = len(QueryCapture.calls) - 1
        messages = runs[index] if index < len(runs) else runs[-1]
        for message in messages:
            yield message

    monkeypatch.setitem(
        sys.modules,
        "claude_agent_sdk",
        SimpleNamespace(ClaudeAgentOptions=ClaudeAgentOptions, query=fake_query),
    )


async def _collect_async_prompt(prompt):
    return [item async for item in prompt]


def _prompt_text(prompt):
    """Prompt text regardless of plain-string or streamed (can_use_tool) form.

    With AskUserQuestion enabled the SDK runs in streaming-input mode, so the
    prompt is an async generator of user-message dicts rather than a raw string.
    """
    if isinstance(prompt, str):
        return prompt
    items = asyncio.run(_collect_async_prompt(prompt))
    return "".join(str((item.get("message") or {}).get("content") or "") for item in items)


def _build_input(*, history=None, resume_session_id=None, agent_snapshot=None, permission_mode=None):
    return task_executor.TaskExecutionInput(
        task_id="task-1",
        topic_id="topic-1",
        question="最近 30 天工作流发布次数趋势",
        history=history or [],
        resume_session_id=resume_session_id,
        provider_id="openrouter",
        model="anthropic/claude-sonnet-4.5",
        database_hint=None,
        debug=False,
        timeout_seconds=60,
        sql_read_timeout_seconds=30,
        sql_write_timeout_seconds=30,
        agent_snapshot=agent_snapshot,
        permission_mode=permission_mode,
    )


def _patch_skill_runtime(monkeypatch, tmp_path: Path) -> dict[str, list[str]]:
    enabled_folders = ["opendataworks-business-knowledge", "marketing-insights"]
    captured: dict[str, list[str]] = {}

    monkeypatch.setattr(
        task_executor,
        "resolve_enabled_skill_runtime",
        lambda: {
            "primary_root": str(tmp_path / "opendataworks-business-knowledge"),
            "enabled_folders": enabled_folders,
            "enabled_roots": {folder: str(tmp_path / folder) for folder in enabled_folders},
        },
    )

    def fake_prepare_topic_workspace(topic_id, folders, **kwargs):
        captured["topic_id"] = topic_id
        captured["folders"] = list(folders)
        captured["kwargs"] = dict(kwargs)
        return tmp_path

    monkeypatch.setattr(task_executor, "prepare_topic_workspace", fake_prepare_topic_workspace)
    return captured


def test_execute_task_stream_persists_sdk_records_without_magic_records(monkeypatch, tmp_path: Path):
    monkeypatch.setenv("DATAAGENT_CLAUDE_CLI_PATH", "/tmp/claude-cli")
    _install_fake_sdk(
        monkeypatch,
        [
            StreamEvent({"type": "message_start", "message": {"id": "req-1", "usage": {"input_tokens": 10}}}),
            StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "thinking", "thinking": ""}}),
            StreamEvent({"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": "先定位指标"}}),
            StreamEvent({"type": "content_block_stop", "index": 0}),
            StreamEvent(
                {
                    "type": "content_block_start",
                    "index": 1,
                    "content_block": {"type": "tool_use", "id": "tool-read-1", "name": "Read", "input": {"path": "reference.md"}},
                }
            ),
            StreamEvent({"type": "content_block_stop", "index": 1}),
            UserMessage(
                [
                    {
                        "type": "tool_result",
                        "tool_use_id": "tool-read-1",
                        "name": "Read",
                        "content": "{\"kind\":\"python_execution\",\"summary\":\"ok\"}",
                    }
                ]
            ),
            StreamEvent({"type": "content_block_start", "index": 2, "content_block": {"type": "text", "text": ""}}),
            StreamEvent({"type": "content_block_delta", "index": 2, "delta": {"type": "text_delta", "text": "最终回答"}}),
            StreamEvent(
                {
                    "type": "message_delta",
                    "delta": {"stop_reason": "end_turn", "usage": {"output_tokens": 5}},
                }
            ),
            StreamEvent({"type": "content_block_stop", "index": 2}),
            StreamEvent({"type": "message_stop"}),
            ResultMessage("success", session_id="sdk-session-1"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "https://example.invalid",
            "supports_partial_messages": True,
        },
    )
    runtime = _patch_skill_runtime(monkeypatch, tmp_path)

    emitted: list[dict] = []

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: emitted.append(record))

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.content == "最终回答"
    assert result.usage == {"input_tokens": 10, "output_tokens": 5}
    assert result.session_id == "sdk-session-1"
    assert ClaudeAgentOptions.last_kwargs["include_partial_messages"] is True
    assert ClaudeAgentOptions.last_kwargs["cwd"] == str(tmp_path)
    assert json.loads(ClaudeAgentOptions.last_kwargs["settings"]) == {
        "plansDirectory": ".claude/plans"
    }
    assert ClaudeAgentOptions.last_kwargs["cli_path"] == "/tmp/claude-cli"
    assert ClaudeAgentOptions.last_kwargs["skills"] == ["opendataworks-business-knowledge", "marketing-insights"]
    assert runtime["folders"] == ["opendataworks-business-knowledge", "marketing-insights"]
    assert ClaudeAgentOptions.last_kwargs["env"]["DISABLE_PROMPT_CACHING"] == ""

    assert emitted == []


def test_execute_task_stream_logs_sdk_iterator_progress(monkeypatch, tmp_path: Path, caplog):
    monkeypatch.setenv("DATAAGENT_CLAUDE_CLI_PATH", "/tmp/claude-cli")
    monkeypatch.setattr(task_executor, "_SDK_TURN_PROGRESS_THRESHOLDS", (3,))
    monkeypatch.setattr(task_executor, "get_topic_task_store", lambda: _SdkRecordStore())
    _install_fake_sdk(
        monkeypatch,
        [
            StreamEvent({"type": "message_start", "message": {"id": "req-progress"}}),
            StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
            StreamEvent({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "ok"}}),
            StreamEvent({"type": "content_block_stop", "index": 0}),
            StreamEvent({"type": "message_stop"}),
            ResultMessage("success", session_id="sdk-session-progress"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "https://example.invalid",
            "supports_partial_messages": True,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)
    caplog.set_level(logging.INFO, logger="core.task_executor")

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: None)

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    messages = [record.getMessage() for record in caplog.records]
    assert any("task.sdk_turn.progress" in message and "sdk_messages=3" in message for message in messages)
    assert any("task.sdk_turn.end" in message and "sdk_messages=6" in message for message in messages)


def test_execute_task_stream_logs_safe_runtime_base_url_and_preserves_env(monkeypatch, tmp_path: Path, caplog):
    base_url = "http://relay.example.internal/maas"
    _install_fake_sdk(
        monkeypatch,
        [
            ResultMessage("success", "查询结果如下。", session_id="sdk-session-log"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": "anthropic_compatible",
            "model": model,
            "api_key": "",
            "auth_token": "relay-token",
            "base_url": base_url,
            "supports_partial_messages": False,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)
    caplog.set_level(logging.INFO, logger="core.task_executor")

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: None)

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert ClaudeAgentOptions.last_kwargs["env"]["ANTHROPIC_BASE_URL"] == base_url
    assert "base_url=http://relay.example.internal/maas" in caplog.text
    assert "env_base_url=http://relay.example.internal/maas" in caplog.text
    assert "auth_token_set=True" in caplog.text
    assert "api_key_set=False" in caplog.text


def test_execute_task_stream_applies_agent_snapshot_runtime_overrides(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            AssistantMessage([TextBlock("custom-agent-ok")]),
            ResultMessage("success", session_id="sdk-session-agent"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "https://example.invalid",
            "supports_partial_messages": False,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)
    topic_workspace = tmp_path / "topics" / "topic-1"
    captured: dict[str, object] = {}

    def fake_prepare_topic_workspace(topic_id, folders, **kwargs):
        captured["topic_id"] = topic_id
        captured["folders"] = list(folders)
        captured["kwargs"] = dict(kwargs)
        return topic_workspace

    monkeypatch.setattr(task_executor, "prepare_topic_workspace", fake_prepare_topic_workspace)

    async def _run():
        return await task_executor.execute_task_stream(
            _build_input(
                agent_snapshot={
                    "agent_id": "agent_1",
                    "name": "自定义智能体",
                    "description": "",
                    "system_prompt": "只返回自定义智能体结果。",
                    "permission_mode": "default",
                    "allowed_tools": ["Read"],
                    "mcp_server_ids": [],
                    "skill_folders": [],
                    "max_turns": 7,
                    "env_vars": {"SAFE_FLAG": "1"},
                    "is_default": False,
                }
            ),
            emit=lambda record: None,
        )

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.content == "custom-agent-ok"
    assert captured["topic_id"] == "topic-1"
    assert captured["folders"] == []
    assert captured["kwargs"]["allow_empty"] is True
    assert ClaudeAgentOptions.last_kwargs["cwd"] == str(topic_workspace)
    assert ClaudeAgentOptions.last_kwargs["skills"] == []
    assert ClaudeAgentOptions.last_kwargs["allowed_tools"] == ["Read", "AskUserQuestion"]
    assert ClaudeAgentOptions.last_kwargs["mcp_servers"] == {}
    assert ClaudeAgentOptions.last_kwargs["max_turns"] == 7
    # Permission mode resolves through the runtime helper (root vs non-root differ);
    # assert against it rather than a hard-coded value.
    assert ClaudeAgentOptions.last_kwargs["permission_mode"] == task_executor._resolve_sdk_permission_mode("default")
    assert ClaudeAgentOptions.last_kwargs["env"]["SAFE_FLAG"] == "1"
    assert "只返回自定义智能体结果。" in ClaudeAgentOptions.last_kwargs["system_prompt"]
    assert "PreToolUse" in ClaudeAgentOptions.last_kwargs["hooks"]
    hook = ClaudeAgentOptions.last_kwargs["hooks"]["PreToolUse"][0].hooks[0]
    blocked = asyncio.run(
        hook(
            {"tool_name": "Read", "tool_input": {"file_path": "../secret.md"}},
            "tool-read-escape",
            {"signal": None},
        )
    )
    assert blocked["decision"] == "block"
    assert blocked["hookSpecificOutput"]["permissionDecision"] == "deny"


def test_execute_task_stream_prefers_session_permission_mode_over_snapshot(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            AssistantMessage([TextBlock("mode-ok")]),
            ResultMessage("success", session_id="sdk-session-mode"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "https://example.invalid",
            "supports_partial_messages": False,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)
    monkeypatch.setattr(task_executor, "prepare_topic_workspace", lambda topic_id, folders, **kwargs: tmp_path / "ws")

    requested: dict[str, object] = {}

    def fake_resolve(permission_mode=None):
        requested["mode"] = permission_mode
        return "bypassPermissions"

    monkeypatch.setattr(task_executor, "_resolve_sdk_permission_mode", fake_resolve)

    asyncio.run(
        task_executor.execute_task_stream(
            _build_input(
                agent_snapshot={
                    "agent_id": "agent_1",
                    "name": "自定义智能体",
                    "permission_mode": "bypassPermissions",
                    "allowed_tools": ["Read"],
                    "mcp_server_ids": [],
                    "skill_folders": [],
                    "max_turns": 0,
                    "env_vars": {},
                    "is_default": False,
                },
                permission_mode="plan",
            ),
            emit=lambda record: None,
        )
    )

    # Session-level mode on TaskExecutionInput wins over the legacy snapshot value.
    assert requested["mode"] == "plan"


def test_execute_task_stream_uses_topic_workspace_for_sdk_cwd_and_keeps_home_distinct(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            AssistantMessage([TextBlock("topic-workspace-ok")]),
            ResultMessage("success", session_id="sdk-session-topic"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "https://example.invalid",
            "supports_partial_messages": False,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)
    monkeypatch.setenv("HOME", "/stable/claude-home")
    monkeypatch.setenv("DATAAGENT_WORKSPACE_DIR", "/stale/env-workspace")
    monkeypatch.setenv("DATAAGENT_WORKSPACE_PREPARED", "1")
    topic_workspace = tmp_path / "topics" / "topic-1"
    captured: dict[str, object] = {}

    def fake_prepare_topic_workspace(topic_id, folders, **kwargs):
        captured["topic_id"] = topic_id
        captured["folders"] = list(folders)
        captured["kwargs"] = dict(kwargs)
        return topic_workspace

    monkeypatch.setattr(task_executor, "prepare_topic_workspace", fake_prepare_topic_workspace)

    async def _run():
        return await task_executor.execute_task_stream(
            _build_input(
                agent_snapshot={
                    "agent_id": "agent_1",
                    "name": "自定义智能体",
                    "permission_mode": "default",
                    "allowed_tools": ["Read"],
                    "mcp_server_ids": [],
                    "skill_folders": [],
                    "max_turns": 0,
                    "env_vars": {},
                    "is_default": False,
                }
            ),
            emit=lambda record: None,
        )

    result = asyncio.run(_run())

    assert result.content == "topic-workspace-ok"
    assert captured["topic_id"] == "topic-1"
    assert captured["folders"] == []
    assert captured["kwargs"]["allow_empty"] is True
    assert captured["kwargs"]["workspace_dir"] is None
    assert ClaudeAgentOptions.last_kwargs["cwd"] == str(topic_workspace)
    assert ClaudeAgentOptions.last_kwargs["env"]["HOME"] == "/stable/claude-home"
    assert ClaudeAgentOptions.last_kwargs["env"]["HOME"] != str(topic_workspace)
    assert json.loads(ClaudeAgentOptions.last_kwargs["settings"]) == {
        "plansDirectory": ".claude/plans"
    }
    hooks = ClaudeAgentOptions.last_kwargs["hooks"]
    boundary_hook = hooks["PreToolUse"][0].hooks[0]
    allowed = asyncio.run(
        boundary_hook(
            {
                "tool_name": "Write",
                "tool_input": {
                    "file_path": str(topic_workspace / ".claude" / "plans" / "runtime-plan.md"),
                    "content": "# Plan",
                },
            },
            "tool-write-plan",
            {"signal": None},
        )
    )
    assert allowed["continue_"] is True
    assert "DATAAGENT_WORKSPACE_DIR" not in ClaudeAgentOptions.last_kwargs["env"]
    assert "DATAAGENT_WORKSPACE_PREPARED" not in ClaudeAgentOptions.last_kwargs["env"]


def test_execute_task_stream_delegates_to_sandbox_runner_when_enabled(monkeypatch):
    from config import get_settings, update_settings

    original_mode = get_settings().dataagent_sandbox_mode
    update_settings({"dataagent_sandbox_mode": "container"})
    captured: dict[str, object] = {}

    async def fake_runner(params, *, emit, is_cancel_requested=None):
        captured["task_id"] = params.task_id
        captured["topic_id"] = params.topic_id
        return task_executor.TaskExecutionResult(
            task_status="finished",
            content="runner-ok",
            provider_id="openrouter",
            model="anthropic/claude-sonnet-4.5",
            session_id="sdk-session-runner",
        )

    monkeypatch.setattr(task_executor, "_execute_task_stream_via_runner", fake_runner)
    emitted: list[dict] = []
    try:
        result = asyncio.run(task_executor.execute_task_stream(_build_input(), emit=lambda record: emitted.append(record)))
    finally:
        update_settings({"dataagent_sandbox_mode": original_mode})

    assert captured == {"task_id": "task-1", "topic_id": "topic-1"}
    assert result.content == "runner-ok"
    assert result.session_id == "sdk-session-runner"
    assert emitted == []


def test_sandbox_runner_client_streams_records_and_returns_result(monkeypatch):
    from config import get_settings, update_settings

    class FakeResponse:
        def raise_for_status(self):
            return None

        async def aiter_lines(self):
            yield json.dumps(
                {
                    "type": "result",
                    "result": {
                        "task_status": "finished",
                        "content": "runner-stream-ok",
                        "usage": {"input_tokens": 1},
                        "provider_id": "openrouter",
                        "model": "anthropic/claude-sonnet-4.5",
                        "session_id": "sdk-session-stream",
                    },
                }
            )

    class FakeStreamContext:
        async def __aenter__(self):
            return FakeResponse()

        async def __aexit__(self, exc_type, exc, tb):
            return False

    class FakeClient:
        last_payload = None

        def __init__(self, **kwargs):
            self.kwargs = kwargs

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        def stream(self, method, url, *, json):
            FakeClient.last_payload = {"method": method, "url": url, "json": json}
            return FakeStreamContext()

    original_url = get_settings().dataagent_sandbox_runner_url
    update_settings({"dataagent_sandbox_runner_url": "http://runner.local"})
    monkeypatch.setattr(task_executor.httpx, "AsyncClient", FakeClient)
    emitted: list[dict] = []
    try:
        result = asyncio.run(
            task_executor._execute_task_stream_via_runner(
                _build_input(),
                emit=lambda record: emitted.append(record),
            )
        )
    finally:
        update_settings({"dataagent_sandbox_runner_url": original_url})

    assert FakeClient.last_payload["method"] == "POST"
    assert FakeClient.last_payload["url"] == "http://runner.local/internal/sandbox/runs"
    assert FakeClient.last_payload["json"]["topic_id"] == "topic-1"
    assert FakeClient.last_payload["json"]["task_id"] == "task-1"
    assert emitted == []
    assert result.content == "runner-stream-ok"
    assert result.usage == {"input_tokens": 1}
    assert result.session_id == "sdk-session-stream"


def test_execute_task_stream_preserves_native_partial_text_blocks_without_magic_records(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            StreamEvent({"type": "message_start", "message": {"id": "req-2"}}),
            StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
            StreamEvent(
                {
                    "type": "content_block_delta",
                    "index": 0,
                    "delta": {"type": "text_delta", "text": "我来帮你查询最近 30 天工作流发布次数的趋势。"},
                }
            ),
            StreamEvent({"type": "content_block_stop", "index": 0}),
            StreamEvent(
                {
                    "type": "content_block_start",
                    "index": 1,
                    "content_block": {"type": "tool_use", "id": "tool-bash-1", "name": "Bash", "input": {"command": "python run_sql.py"}},
                }
            ),
            StreamEvent({"type": "content_block_stop", "index": 1}),
            UserMessage(
                [
                    {
                        "type": "tool_result",
                        "tool_use_id": "tool-bash-1",
                        "name": "Bash",
                        "content": "2026-03-10,3\n2026-03-11,1",
                    }
                ]
            ),
            StreamEvent({"type": "content_block_start", "index": 2, "content_block": {"type": "text", "text": ""}}),
            StreamEvent(
                {
                    "type": "content_block_delta",
                    "index": 2,
                    "delta": {"type": "text_delta", "text": "最近 30 天累计发布 4 次。"},
                }
            ),
            StreamEvent({"type": "content_block_stop", "index": 2}),
            StreamEvent({"type": "message_stop"}),
            ResultMessage("success", session_id="sdk-session-2"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "https://example.invalid",
            "supports_partial_messages": True,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)

    emitted: list[dict] = []

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: emitted.append(record))

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.content == "我来帮你查询最近 30 天工作流发布次数的趋势。\n\n最近 30 天累计发布 4 次。"
    assert result.session_id == "sdk-session-2"
    assert emitted == []


def test_execute_task_stream_does_not_duplicate_trailing_assistant_message_after_partial_stream(monkeypatch, tmp_path: Path):
    # In partial-streaming mode the SDK emits per-block StreamEvent deltas and
    # then a trailing AssistantMessage carrying the same full content. The final
    # answer must not contain the streamed text twice.
    _install_fake_sdk(
        monkeypatch,
        [
            StreamEvent({"type": "message_start", "message": {"id": "req-3"}}),
            StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
            StreamEvent(
                {
                    "type": "content_block_delta",
                    "index": 0,
                    "delta": {"type": "text_delta", "text": "最近 30 天累计发布 4 次。"},
                }
            ),
            StreamEvent({"type": "content_block_stop", "index": 0}),
            StreamEvent({"type": "message_stop"}),
            AssistantMessage([TextBlock("最近 30 天累计发布 4 次。")]),
            ResultMessage("success", session_id="sdk-session-3"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "https://example.invalid",
            "supports_partial_messages": True,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)

    emitted: list[dict] = []

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: emitted.append(record))

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.content == "最近 30 天累计发布 4 次。"
    assert result.session_id == "sdk-session-3"
    assert emitted == []


def test_execute_task_stream_uses_message_level_sdk_text_when_partial_disabled(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            AssistantMessage([ThinkingBlock("先定位指标"), TextBlock("最终回答")]),
            ResultMessage("success"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": "anthropic_compatible",
            "model": model,
            "api_key": "",
            "auth_token": "token",
            "base_url": "https://relay.example.invalid",
            "supports_partial_messages": False,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)

    emitted: list[dict] = []

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: emitted.append(record))

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.content == "最终回答"
    assert ClaudeAgentOptions.last_kwargs["include_partial_messages"] is False
    assert ClaudeAgentOptions.last_kwargs["env"]["DISABLE_PROMPT_CACHING"] == "1"
    assert emitted == []


def test_execute_task_stream_keeps_one_shot_answer_out_of_reasoning_when_thinking_arrives_later(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            AssistantMessage([TextBlock("smoke-ok"), ThinkingBlock("这是一个简单的冒烟测试,不需要工具。")]),
            ResultMessage("success"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": "openrouter",
            "model": model,
            "api_key": "",
            "auth_token": "token",
            "base_url": "https://openrouter.example.invalid",
            "supports_partial_messages": False,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)

    emitted: list[dict] = []

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: emitted.append(record))

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.content == "smoke-ok"
    assert emitted == []


def test_execute_task_stream_keeps_tool_loop_in_compatibility_mode(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            AssistantMessage([ToolUseBlock(id="tool-bash-1", name="Bash", input={"command": "printf smoke-ok"})]),
            UserMessage([ToolResultBlock(tool_use_id="tool-bash-1", name="Bash", content="smoke-ok")]),
            AssistantMessage([TextBlock("smoke-ok")]),
            ResultMessage("success"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": "anthropic_compatible",
            "model": model,
            "api_key": "",
            "auth_token": "token",
            "base_url": "https://relay.example.invalid",
            "supports_partial_messages": False,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)

    emitted: list[dict] = []

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: emitted.append(record))

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.content == "smoke-ok"
    assert emitted == []


def test_execute_task_stream_preserves_pre_tool_text_in_compatibility_mode(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            AssistantMessage(
                [
                    TextBlock("我来帮你查询最近 30 天工作流发布次数的趋势。"),
                    ToolUseBlock(id="tool-bash-1", name="Bash", input={"command": "python scripts/run_sql.py --question trend"}),
                ]
            ),
            UserMessage([ToolResultBlock(tool_use_id="tool-bash-1", name="Bash", content="2026-03-10,3\n2026-03-11,1")]),
            AssistantMessage([TextBlock("最近 30 天累计发布 4 次。")]),
            ResultMessage("success"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": "anthropic_compatible",
            "model": model,
            "api_key": "",
            "auth_token": "token",
            "base_url": "https://relay.example.invalid",
            "supports_partial_messages": False,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)

    emitted: list[dict] = []

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: emitted.append(record))

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.content == "我来帮你查询最近 30 天工作流发布次数的趋势。\n\n最近 30 天累计发布 4 次。"
    assert emitted == []


def test_execute_task_stream_preserves_text_before_later_tool_in_compatibility_mode(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            AssistantMessage([TextBlock("我来帮你查询最近 30 天工作流发布次数的趋势数据。")]),
            AssistantMessage([ToolUseBlock(id="tool-skill-1", name="Skill", input={"skill": "opendataworks-business-knowledge"})]),
            UserMessage([ToolResultBlock(tool_use_id="tool-skill-1", name="Skill", content="Launching skill: opendataworks-business-knowledge")]),
            AssistantMessage([TextBlock("根据参考文档，这是一个趋势分析问题。现在执行 SQL 查询。")]),
            AssistantMessage([ToolUseBlock(id="tool-bash-1", name="Bash", input={"command": "python scripts/run_sql.py --question trend"})]),
            UserMessage([ToolResultBlock(tool_use_id="tool-bash-1", name="Bash", content="2026-03-10,3\n2026-03-11,1")]),
            AssistantMessage([TextBlock("最近 30 天内共发布 4 次。")]),
            ResultMessage("success"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": "openrouter",
            "model": model,
            "api_key": "",
            "auth_token": "token",
            "base_url": "https://openrouter.example.invalid",
            "supports_partial_messages": False,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)

    emitted: list[dict] = []

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: emitted.append(record))

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.content == (
        "我来帮你查询最近 30 天工作流发布次数的趋势数据。\n\n"
        "根据参考文档，这是一个趋势分析问题。现在执行 SQL 查询。\n\n"
        "最近 30 天内共发布 4 次。"
    )
    assert emitted == []


def test_execute_task_stream_surfaces_provider_error_instead_of_exit_code(monkeypatch, tmp_path: Path):
    provider_error = "API Error: 400 {'detail': 'invalid beta flag'}"
    _install_fake_sdk(
        monkeypatch,
        [
            AssistantMessage([TextBlock(provider_error)], error=provider_error),
            ResultMessage("error_api", provider_error, is_error=True),
        ],
        final_exception=RuntimeError("Command failed with exit code 1"),
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": "anthropic_compatible",
            "model": model,
            "api_key": "",
            "auth_token": "token",
            "base_url": "https://relay.example.invalid",
            "supports_partial_messages": False,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)

    emitted: list[dict] = []

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: emitted.append(record))

    result = asyncio.run(_run())

    assert result.task_status == "error"
    assert result.content == provider_error
    assert result.error == {
        "code": "error_api",
        "message": provider_error,
        "exception_type": "RuntimeError",
    }

    assert emitted == []


def test_execute_task_stream_normalizes_success_subtype_provider_error(monkeypatch, tmp_path: Path):
    provider_error = "There's an issue with the selected model (deepseek-v4-pro[1m])."
    _install_fake_sdk(
        monkeypatch,
        [
            AssistantMessage([TextBlock(provider_error)], error=provider_error),
            ResultMessage("success", None, is_error=False, session_id="sess-1"),
        ],
        final_exception=RuntimeError("Command failed with exit code 1"),
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": "anyrouter",
            "model": "deepseek-v4-pro[1m]",
            "api_key": "",
            "auth_token": "token",
            "base_url": "https://relay.example.invalid",
            "supports_partial_messages": True,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: None)

    result = asyncio.run(_run())

    assert result.task_status == "error"
    assert result.content == provider_error
    assert result.error == {
        "code": "provider_error",
        "message": provider_error,
        "exception_type": "RuntimeError",
    }


def test_execute_task_stream_resumes_sdk_session_without_replaying_history(monkeypatch, tmp_path: Path):
    QueryCapture.last_prompt = None
    QueryCapture.last_options = None
    _install_fake_sdk(
        monkeypatch,
        [
            ResultMessage("success", "继续回答", session_id="sdk-session-continued"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "https://example.invalid",
            "supports_partial_messages": True,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)

    async def _run():
        return await task_executor.execute_task_stream(
            _build_input(
                history=[
                    {"role": "user", "content": "上一轮问题"},
                    {"role": "assistant", "content": "上一轮回答"},
                ],
                resume_session_id="sdk-session-continued",
            ),
            emit=lambda record: None,
        )

    result = asyncio.run(_run())

    assert _prompt_text(QueryCapture.last_prompt) == "最近 30 天工作流发布次数趋势"
    assert ClaudeAgentOptions.last_kwargs["resume"] == "sdk-session-continued"
    assert result.session_id == "sdk-session-continued"


def test_execute_task_stream_injects_portal_mcp_servers(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            ResultMessage("success", "查询结果如下。", session_id="sdk-session-mcp"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "get_settings",
        lambda: SimpleNamespace(
            # A fake settings object has to name its engine: resolve_runtime_kind
            # falls back to the default for a missing attribute, and the default
            # is Pi.
            dataagent_runtime_kind="claude_code",
            claude_model="",
            agent_timeout_seconds=60,
                agent_background_max_turns=40,
                agent_max_turns=20,
                agent_max_buffer_size_bytes=10 * 1024 * 1024,
                query_result_limit=100,
            dataagent_portal_mcp_enabled=True,
            dataagent_portal_mcp_base_url="http://portal-mcp:8801/mcp",
            dataagent_portal_mcp_token="portal-token",
            dataagent_portal_mcp_token_header_name="X-Portal-MCP-Token",
        ),
    )

    class FakeRegistry:
        def list_mcp_servers(self):
            return [
                {
                    "server_id": "portal",
                    "transport": "http",
                    "url": "http://registry-portal:8801/mcp",
                    "headers": {"X-Portal-MCP-Token": "registry-token"},
                    "enabled": True,
                }
            ]

    monkeypatch.setattr("core.mcp_admin_service.get_runtime_registry_store", lambda: FakeRegistry())
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "https://example.invalid",
            "supports_partial_messages": True,
        },
    )
    _patch_skill_runtime(monkeypatch, tmp_path)
    monkeypatch.setattr(
        task_executor,
        "_build_runtime_env",
        lambda cfg, env_payload, params, skill_runtime=None: {
            "DATAAGENT_SKILL_ROOT": "/tmp/skill-root",
            "DATAAGENT_PYTHON_BIN": sys.executable,
            "PATH": str(tmp_path),
        },
    )

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: None)

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.session_id == "sdk-session-mcp"
    portal_server = ClaudeAgentOptions.last_kwargs["mcp_servers"]["portal"]
    assert portal_server["type"] == "http"
    assert portal_server["url"] == "http://registry-portal:8801/mcp/"
    assert portal_server["headers"] == {
        "X-Portal-MCP-Token": "registry-token"
    }
    assert "mcp__portal__portal_search_tables" in ClaudeAgentOptions.last_kwargs["allowed_tools"]
    assert "mcp__portal__portal_query_readonly" in ClaudeAgentOptions.last_kwargs["allowed_tools"]
    assert callable(ClaudeAgentOptions.last_kwargs["can_use_tool"])
    assert hasattr(QueryCapture.last_prompt, "__aiter__")
    assert asyncio.run(_collect_async_prompt(QueryCapture.last_prompt)) == [
        {
            "type": "user",
            "message": {"role": "user", "content": "[用户]: 最近 30 天工作流发布次数趋势"},
        }
    ]


def _patch_default_provider(monkeypatch):
    monkeypatch.setattr(
        task_executor,
        "resolve_runtime_provider_selection",
        lambda provider_id, model: {
            "provider_id": provider_id,
            "model": model,
            "api_key": "",
            "auth_token": "",
            "base_url": "https://example.invalid",
            "supports_partial_messages": True,
        },
    )


def test_execute_task_stream_runner_stop_returns_suspended_without_sdk_error(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            StreamEvent({"type": "message_start", "message": {"id": "req-1"}}),
        ],
    )
    _patch_default_provider(monkeypatch)
    _patch_skill_runtime(monkeypatch, tmp_path)
    store = _SdkRecordStore()
    monkeypatch.setattr(task_executor, "get_topic_task_store", lambda: store)

    async def _run():
        return await task_executor.execute_task_stream(
            _build_input(),
            emit=lambda record: None,
            is_cancel_requested=lambda: "runner_stop",
        )

    result = asyncio.run(_run())

    assert result.task_status == "suspended"
    assert result.error == {"code": "runner_stopped", "message": "执行资源已停止"}
    assert not [record for record in store.records if record["record_type"] == "error"]


def test_execute_task_stream_keeps_leaked_pseudo_tag_in_thinking_as_finish(monkeypatch, tmp_path: Path):
    # Pseudo tool-call format-drift detection was removed: a leaked tool-call tag
    # in a thinking block is no longer reclassified as an error. With a real
    # visible answer present, the run finishes normally and the thinking-only
    # tags never reach the visible content.
    _install_fake_sdk(
        monkeypatch,
        [
            StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
            StreamEvent({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "最近 30 天发布 174 次。"}}),
            StreamEvent({"type": "content_block_stop", "index": 0}),
            StreamEvent({"type": "content_block_start", "index": 1, "content_block": {"type": "thinking", "thinking": ""}}),
            StreamEvent(
                {
                    "type": "content_block_delta",
                    "index": 1,
                    "delta": {"type": "thinking_delta", "thinking": "Now let me also get some breakdown </parameter></function></tool_call>"},
                }
            ),
            StreamEvent({"type": "content_block_stop", "index": 1}),
            ResultMessage("success", session_id="sdk-no-drift-1"),
        ],
    )
    _patch_default_provider(monkeypatch)
    _patch_skill_runtime(monkeypatch, tmp_path)

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: None)

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.error is None
    assert "最近 30 天发布 174 次。" in result.content
    assert "</tool_call>" not in result.content
    assert "</parameter>" not in result.content


def test_execute_task_stream_recovers_thinking_only_empty_finish_once(monkeypatch, tmp_path: Path):
    # A clean (success) run that produced only a thinking block — no visible
    # answer, no tool_use, no leaked pseudo tag — is safe to resume once. The
    # recovery prompt stays inside the SDK session and should turn the same task
    # into a normal finished answer when the model continues correctly.
    _install_fake_sdk_runs(
        monkeypatch,
        [
            [
                StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "thinking", "thinking": ""}}),
                StreamEvent(
                    {
                        "type": "content_block_delta",
                        "index": 0,
                        "delta": {"type": "thinking_delta", "thinking": "Let me think about how to answer this question."},
                    }
                ),
                StreamEvent({"type": "content_block_stop", "index": 0}),
                ResultMessage("success", session_id="sdk-empty-1"),
            ],
            [
                StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
                StreamEvent({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "恢复后的回答"}}),
                StreamEvent({"type": "content_block_stop", "index": 0}),
                ResultMessage("success", session_id="sdk-empty-1"),
            ],
        ],
    )
    _patch_default_provider(monkeypatch)
    _patch_skill_runtime(monkeypatch, tmp_path)

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: None)

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.error is None
    assert result.content == "恢复后的回答"
    assert len(QueryCapture.calls) == 2
    assert QueryCapture.calls[1]["options"].kwargs["resume"] == "sdk-empty-1"
    _recovery_prompt = _prompt_text(QueryCapture.calls[1]["prompt"])
    assert "上一轮已经以 end_turn 结束" in _recovery_prompt
    assert "最近 30 天工作流发布次数趋势" in _recovery_prompt


def test_execute_task_stream_recovers_thinking_only_empty_finish_without_session(monkeypatch, tmp_path: Path):
    _install_fake_sdk_runs(
        monkeypatch,
        [
            [
                StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "thinking", "thinking": ""}}),
                StreamEvent(
                    {
                        "type": "content_block_delta",
                        "index": 0,
                        "delta": {"type": "thinking_delta", "thinking": "I should answer, but no text is emitted."},
                    }
                ),
                StreamEvent({"type": "content_block_stop", "index": 0}),
                ResultMessage("success"),
            ],
            [
                StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
                StreamEvent({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "无 session 恢复后的回答"}}),
                StreamEvent({"type": "content_block_stop", "index": 0}),
                ResultMessage("success", session_id="sdk-recovered-1"),
            ],
        ],
    )
    _patch_default_provider(monkeypatch)
    _patch_skill_runtime(monkeypatch, tmp_path)

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: None)

    result = asyncio.run(_run())

    assert result.task_status == "finished"
    assert result.error is None
    assert result.content == "无 session 恢复后的回答"
    assert len(QueryCapture.calls) == 2
    assert "resume" not in QueryCapture.calls[1]["options"].kwargs
    _recovery_prompt = _prompt_text(QueryCapture.calls[1]["prompt"])
    assert "上一轮已经以 end_turn 结束" in _recovery_prompt
    assert "最近 30 天工作流发布次数趋势" in _recovery_prompt


def test_execute_task_stream_marks_empty_finish_as_error_after_recovery_still_empty(monkeypatch, tmp_path: Path):
    _install_fake_sdk_runs(
        monkeypatch,
        [
            [
                StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "thinking", "thinking": ""}}),
                StreamEvent({"type": "content_block_delta", "index": 0, "delta": {"type": "thinking_delta", "thinking": "Thinking only."}}),
                StreamEvent({"type": "content_block_stop", "index": 0}),
                ResultMessage("success", session_id="sdk-empty-1"),
            ],
            [
                StreamEvent({"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}),
                StreamEvent({"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "\n\n"}}),
                StreamEvent({"type": "content_block_stop", "index": 0}),
                ResultMessage("success", session_id="sdk-empty-1"),
            ],
        ],
    )
    _patch_default_provider(monkeypatch)
    _patch_skill_runtime(monkeypatch, tmp_path)

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: None)

    result = asyncio.run(_run())

    assert result.task_status == "error"
    assert result.error is not None
    assert result.error["code"] == "empty_completion"
    assert "请重试" in result.error["message"]
    assert result.content != "已完成。"
    assert len(QueryCapture.calls) == 2


def test_execute_task_stream_marks_empty_finish_with_tool_use_as_incomplete_answer(monkeypatch, tmp_path: Path):
    # Tools ran but the model ended the turn with no final text and no leaked
    # tag. This must not be reported as a successful "已完成。" answer; it is an
    # incomplete answer error and is not auto-retried because replaying tools
    # could repeat writes.
    _install_fake_sdk(
        monkeypatch,
        [
            StreamEvent(
                {
                    "type": "content_block_start",
                    "index": 0,
                    "content_block": {"type": "tool_use", "id": "tool-bash-9", "name": "Bash", "input": {"command": "python run_sql.py"}},
                }
            ),
            StreamEvent({"type": "content_block_stop", "index": 0}),
            UserMessage([{"type": "tool_result", "tool_use_id": "tool-bash-9", "name": "Bash", "content": "2026-06-15"}]),
            StreamEvent({"type": "content_block_start", "index": 1, "content_block": {"type": "thinking", "thinking": ""}}),
            StreamEvent(
                {
                    "type": "content_block_delta",
                    "index": 1,
                    "delta": {"type": "thinking_delta", "thinking": "I now have the data and will summarize it."},
                }
            ),
            StreamEvent({"type": "content_block_stop", "index": 1}),
            ResultMessage("success", session_id="sdk-empty-2"),
        ],
    )
    _patch_default_provider(monkeypatch)
    _patch_skill_runtime(monkeypatch, tmp_path)

    async def _run():
        return await task_executor.execute_task_stream(_build_input(), emit=lambda record: None)

    result = asyncio.run(_run())

    assert result.task_status == "error"
    assert result.error is not None
    assert result.error["code"] == "incomplete_answer"
    assert "最终回答" in result.error["message"]
    assert result.content != "已完成。"
    assert len(QueryCapture.calls) == 1
