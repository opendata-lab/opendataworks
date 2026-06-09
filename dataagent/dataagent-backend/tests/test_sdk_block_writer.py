from __future__ import annotations

import json
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core.sdk_block_writer import SdkBlockWriter


class FakeStore:
    def __init__(self) -> None:
        self.records: list[dict] = []

    def append_sdk_record(self, **kwargs) -> None:
        self.records.append(kwargs)


class AssistantMessage:
    def __init__(self, content):
        self.content = content


class UserMessage:
    def __init__(self, content):
        self.content = content


class TextBlock:
    type = "text"

    def __init__(self, text: str):
        self.text = text


class ToolUseBlock:
    type = "tool_use"

    def __init__(self, *, id: str, name: str, input):
        self.id = id
        self.name = name
        self.input = input


class ToolResultBlock:
    type = "tool_result"

    def __init__(self, *, tool_use_id: str, content, is_error: bool = False):
        self.tool_use_id = tool_use_id
        self.content = content
        self.is_error = is_error


def test_assistant_message_tool_use_is_recorded_as_stream_events() -> None:
    store = FakeStore()
    writer = SdkBlockWriter(store, task_id="task-1", topic_id="topic-1")

    writer.ingest(
        AssistantMessage(
            [
                TextBlock("我先查看业务知识。"),
                ToolUseBlock(
                    id="call_skill_1",
                    name="Skill",
                    input={"skill": "opendataworks-business-knowledge"},
                ),
            ]
        )
    )
    writer.ingest(
        UserMessage(
            [
                ToolResultBlock(
                    tool_use_id="call_skill_1",
                    content="Launching skill: opendataworks-business-knowledge",
                )
            ]
        )
    )

    event_types = [record["event_type"] for record in store.records if record["record_type"] == "stream"]
    assert event_types == [
        "message_start",
        "content_block_start",
        "content_block_delta",
        "content_block_stop",
        "content_block_start",
        "content_block_delta",
        "content_block_stop",
        "message_stop",
    ]

    tool_start = store.records[4]
    assert tool_start["turn_index"] == 1
    assert tool_start["data"] == {
        "type": "content_block_start",
        "index": 1,
        "content_block": {"type": "tool_use", "id": "call_skill_1", "name": "Skill"},
    }

    tool_delta = store.records[5]
    assert json.loads(tool_delta["data"]["delta"]["partial_json"]) == {
        "skill": "opendataworks-business-knowledge"
    }

    tool_result = store.records[-1]
    assert tool_result["turn_index"] == 1
    assert tool_result["record_type"] == "tool_result"
    assert tool_result["data"] == {
        "tool_use_id": "call_skill_1",
        "content": "Launching skill: opendataworks-business-knowledge",
        "is_error": False,
    }


def test_sdk_dataclass_tool_use_without_type_field_is_recorded() -> None:
    from claude_agent_sdk import AssistantMessage as SdkAssistantMessage
    from claude_agent_sdk import ToolUseBlock as SdkToolUseBlock

    store = FakeStore()
    writer = SdkBlockWriter(store, task_id="task-1", topic_id="topic-1")

    writer.ingest(
        SdkAssistantMessage(
            content=[
                SdkToolUseBlock(
                    id="call_skill_1",
                    name="Skill",
                    input={"skill": "opendataworks-business-knowledge"},
                )
            ],
            model="deepseek-v4-pro",
        )
    )

    tool_starts = [
        record
        for record in store.records
        if record["record_type"] == "stream"
        and record["event_type"] == "content_block_start"
        and record["data"]["content_block"]["type"] == "tool_use"
    ]
    assert len(tool_starts) == 1
    assert tool_starts[0]["data"]["content_block"] == {
        "type": "tool_use",
        "id": "call_skill_1",
        "name": "Skill",
    }
