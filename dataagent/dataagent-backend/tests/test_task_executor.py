from __future__ import annotations

import asyncio
import logging
import sys
from pathlib import Path
from types import SimpleNamespace

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

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

    def fake_prepare_enabled_skills_project_cwd(folders):
        captured["folders"] = list(folders)
        return tmp_path

    monkeypatch.setattr(task_executor, "prepare_enabled_skills_project_cwd", fake_prepare_enabled_skills_project_cwd)
    return captured


def test_execute_task_stream_converts_claude_events_to_magic_records(monkeypatch, tmp_path: Path):
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
    assert runtime["folders"] == ["opendataworks-business-knowledge", "marketing-insights"]
    assert ClaudeAgentOptions.last_kwargs["env"]["DISABLE_PROMPT_CACHING"] == ""

    lifecycle = [record["event_type"] for record in emitted if record.get("record_type") == "event"]
    assert lifecycle == [
        "BEFORE_AGENT_THINK",
        "BEFORE_AGENT_REPLY",
        "AFTER_AGENT_REPLY",
        "PENDING_TOOL_CALL",
        "BEFORE_TOOL_CALL",
        "AFTER_TOOL_CALL",
        "AFTER_AGENT_THINK",
        "BEFORE_AGENT_REPLY",
        "AFTER_AGENT_REPLY",
    ]

    chunks = [record for record in emitted if record.get("record_type") == "chunk"]
    assert [(item["metadata"]["content_type"], item["delta"]["status"]) for item in chunks] == [
        ("reasoning", "START"),
        ("reasoning", "END"),
        ("content", "START"),
        ("content", "END"),
    ]
    assert chunks[-1]["content"] == "最终回答"


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
    agent_cwd = tmp_path / "agents" / "agent_1"
    captured: dict[str, object] = {}

    def fake_prepare_enabled_skills_project_cwd(folders, **kwargs):
        captured["folders"] = list(folders)
        captured["kwargs"] = dict(kwargs)
        return agent_cwd

    monkeypatch.setattr(task_executor, "prepare_enabled_skills_project_cwd", fake_prepare_enabled_skills_project_cwd)
    monkeypatch.setattr(task_executor, "resolved_agent_workdir", lambda agent_id, is_default=False: str(agent_cwd))

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
    assert captured["folders"] == []
    assert captured["kwargs"]["runtime_project_cwd"] == str(agent_cwd)
    assert captured["kwargs"]["allow_empty"] is True
    assert ClaudeAgentOptions.last_kwargs["cwd"] == str(agent_cwd)
    assert ClaudeAgentOptions.last_kwargs["allowed_tools"] == ["Read"]
    assert ClaudeAgentOptions.last_kwargs["mcp_servers"] == {}
    assert ClaudeAgentOptions.last_kwargs["max_turns"] == 7
    assert ClaudeAgentOptions.last_kwargs["permission_mode"] == "default"
    assert ClaudeAgentOptions.last_kwargs["env"]["SAFE_FLAG"] == "1"
    assert "只返回自定义智能体结果。" in ClaudeAgentOptions.last_kwargs["system_prompt"]


def test_execute_task_stream_buffers_partial_text_until_turn_end(monkeypatch, tmp_path: Path):
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
    assert result.content == "最近 30 天累计发布 4 次。"
    assert result.session_id == "sdk-session-2"

    chunks = [record for record in emitted if record.get("record_type") == "chunk"]
    assert [(item["metadata"]["content_type"], item["delta"]["status"]) for item in chunks] == [
        ("reasoning", "START"),
        ("reasoning", "END"),
        ("content", "START"),
        ("content", "END"),
    ]
    assert chunks[0]["content"] == "我来帮你查询最近 30 天工作流发布次数的趋势。"
    assert chunks[-1]["content"] == "最近 30 天累计发布 4 次。"


def test_execute_task_stream_uses_message_level_magic_events_when_partial_disabled(monkeypatch, tmp_path: Path):
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

    lifecycle = [record["event_type"] for record in emitted if record.get("record_type") == "event"]
    assert lifecycle == [
        "BEFORE_AGENT_THINK",
        "BEFORE_AGENT_REPLY",
        "AFTER_AGENT_REPLY",
        "AFTER_AGENT_THINK",
        "BEFORE_AGENT_REPLY",
        "AFTER_AGENT_REPLY",
    ]

    chunks = [record for record in emitted if record.get("record_type") == "chunk"]
    assert [(item["metadata"]["content_type"], item["delta"]["status"]) for item in chunks] == [
        ("reasoning", "START"),
        ("reasoning", "END"),
        ("content", "START"),
        ("content", "END"),
    ]
    assert chunks[-1]["content"] == "最终回答"


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

    chunks = [record for record in emitted if record.get("record_type") == "chunk"]
    assert [(item["metadata"]["content_type"], item["delta"]["status"]) for item in chunks] == [
        ("reasoning", "START"),
        ("reasoning", "END"),
        ("content", "START"),
        ("content", "END"),
    ]
    assert chunks[0]["content"] == "这是一个简单的冒烟测试,不需要工具。"
    assert chunks[-1]["content"] == "smoke-ok"


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

    lifecycle = [record["event_type"] for record in emitted if record.get("record_type") == "event"]
    assert lifecycle == [
        "PENDING_TOOL_CALL",
        "BEFORE_TOOL_CALL",
        "AFTER_TOOL_CALL",
        "BEFORE_AGENT_REPLY",
        "AFTER_AGENT_REPLY",
    ]

    tool_events = [record for record in emitted if record.get("event_type") == "AFTER_TOOL_CALL"]
    assert tool_events[0]["data"]["tool"]["output"] == "smoke-ok"

    chunks = [record for record in emitted if record.get("record_type") == "chunk"]
    assert [(item["metadata"]["content_type"], item["delta"]["status"]) for item in chunks] == [
        ("content", "START"),
        ("content", "END"),
    ]
    assert chunks[-1]["content"] == "smoke-ok"


def test_execute_task_stream_treats_pre_tool_text_as_reasoning_in_compatibility_mode(monkeypatch, tmp_path: Path):
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
    assert result.content == "最近 30 天累计发布 4 次。"

    lifecycle = [record["event_type"] for record in emitted if record.get("record_type") == "event"]
    assert lifecycle == [
        "BEFORE_AGENT_THINK",
        "BEFORE_AGENT_REPLY",
        "AFTER_AGENT_REPLY",
        "PENDING_TOOL_CALL",
        "BEFORE_TOOL_CALL",
        "AFTER_TOOL_CALL",
        "AFTER_AGENT_THINK",
        "BEFORE_AGENT_REPLY",
        "AFTER_AGENT_REPLY",
    ]

    chunks = [record for record in emitted if record.get("record_type") == "chunk"]
    assert [(item["metadata"]["content_type"], item["delta"]["status"]) for item in chunks] == [
        ("reasoning", "START"),
        ("reasoning", "END"),
        ("content", "START"),
        ("content", "END"),
    ]
    assert chunks[0]["content"] == "我来帮你查询最近 30 天工作流发布次数的趋势。"
    assert chunks[-1]["content"] == "最近 30 天累计发布 4 次。"


def test_execute_task_stream_treats_text_before_later_tool_as_reasoning_in_compatibility_mode(monkeypatch, tmp_path: Path):
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
    assert result.content == "最近 30 天内共发布 4 次。"

    lifecycle = [record["event_type"] for record in emitted if record.get("record_type") == "event"]
    assert lifecycle == [
        "BEFORE_AGENT_THINK",
        "BEFORE_AGENT_REPLY",
        "AFTER_AGENT_REPLY",
        "PENDING_TOOL_CALL",
        "BEFORE_TOOL_CALL",
        "AFTER_TOOL_CALL",
        "BEFORE_AGENT_REPLY",
        "AFTER_AGENT_REPLY",
        "PENDING_TOOL_CALL",
        "BEFORE_TOOL_CALL",
        "AFTER_TOOL_CALL",
        "AFTER_AGENT_THINK",
        "BEFORE_AGENT_REPLY",
        "AFTER_AGENT_REPLY",
    ]

    chunks = [record for record in emitted if record.get("record_type") == "chunk"]
    assert [(item["metadata"]["content_type"], item["delta"]["status"]) for item in chunks] == [
        ("reasoning", "START"),
        ("reasoning", "END"),
        ("reasoning", "START"),
        ("reasoning", "END"),
        ("content", "START"),
        ("content", "END"),
    ]
    assert chunks[0]["content"] == "我来帮你查询最近 30 天工作流发布次数的趋势数据。"
    assert chunks[2]["content"] == "根据参考文档，这是一个趋势分析问题。现在执行 SQL 查询。"
    assert chunks[-1]["content"] == "最近 30 天内共发布 4 次。"


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

    error_events = [
        record for record in emitted
        if record.get("record_type") == "event" and record.get("event_type") == "ERROR"
    ]
    assert error_events[-1]["data"]["error"]["message"] == provider_error
    assert error_events[-1]["data"]["error"]["code"] == "error_api"
    assert [record for record in emitted if record.get("record_type") == "chunk"] == []


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
