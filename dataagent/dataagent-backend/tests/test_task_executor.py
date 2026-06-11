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


class ClaudeAgentOptions:
    last_kwargs = None

    def __init__(self, **kwargs):
        ClaudeAgentOptions.last_kwargs = kwargs
        self.kwargs = kwargs


class QueryCapture:
    last_prompt = None
    last_options = None


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
    async def fake_query(*, prompt, options):
        QueryCapture.last_prompt = prompt
        QueryCapture.last_options = options
        for message in messages:
            yield message
        if final_exception is not None:
            raise final_exception

    monkeypatch.setitem(
        sys.modules,
        "claude_agent_sdk",
        SimpleNamespace(ClaudeAgentOptions=ClaudeAgentOptions, query=fake_query),
    )


def _build_input(*, history=None, resume_session_id=None, agent_snapshot=None):
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
    assert ClaudeAgentOptions.last_kwargs["cli_path"] == "/tmp/claude-cli"
    assert ClaudeAgentOptions.last_kwargs["skills"] == ["opendataworks-business-knowledge", "marketing-insights"]
    assert runtime["folders"] == ["opendataworks-business-knowledge", "marketing-insights"]
    assert ClaudeAgentOptions.last_kwargs["env"]["DISABLE_PROMPT_CACHING"] == ""

    assert emitted == []


def test_execute_task_stream_logs_safe_runtime_base_url_and_preserves_env(monkeypatch, tmp_path: Path, caplog):
    base_url = "http://relay.example.internal/maas"
    _install_fake_sdk(
        monkeypatch,
        [
            ResultMessage("success", session_id="sdk-session-log"),
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
    assert ClaudeAgentOptions.last_kwargs["allowed_tools"] == ["Read"]
    assert ClaudeAgentOptions.last_kwargs["mcp_servers"] == {}
    assert ClaudeAgentOptions.last_kwargs["max_turns"] == 7
    assert ClaudeAgentOptions.last_kwargs["permission_mode"] == "default"
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


def test_execute_task_stream_resumes_sdk_session_without_replaying_history(monkeypatch, tmp_path: Path):
    QueryCapture.last_prompt = None
    QueryCapture.last_options = None
    _install_fake_sdk(
        monkeypatch,
        [
            ResultMessage("success", session_id="sdk-session-continued"),
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

    assert QueryCapture.last_prompt == "最近 30 天工作流发布次数趋势"
    assert ClaudeAgentOptions.last_kwargs["resume"] == "sdk-session-continued"
    assert result.session_id == "sdk-session-continued"


def test_execute_task_stream_injects_portal_mcp_servers(monkeypatch, tmp_path: Path):
    _install_fake_sdk(
        monkeypatch,
        [
            ResultMessage("success", session_id="sdk-session-mcp"),
        ],
    )
    monkeypatch.setattr(
        task_executor,
        "get_settings",
        lambda: SimpleNamespace(
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
    assert ClaudeAgentOptions.last_kwargs["mcp_servers"] == {
        "portal": {
            "type": "http",
            "url": "http://portal-mcp:8801/mcp/",
            "headers": {"X-Portal-MCP-Token": "portal-token"},
        }
    }
    assert "mcp__portal__portal_search_tables" in ClaudeAgentOptions.last_kwargs["allowed_tools"]
    assert "mcp__portal__portal_query_readonly" in ClaudeAgentOptions.last_kwargs["allowed_tools"]
