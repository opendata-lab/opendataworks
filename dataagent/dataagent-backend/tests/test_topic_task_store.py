from __future__ import annotations

import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core.topic_task_store import TopicTaskStore, _project_task_history


def test_project_task_history_rebuilds_finished_blocks():
    payload = _project_task_history(
        [
            {
                "record_type": "event",
                "seq_id": 1,
                "event_type": "BEFORE_AGENT_REPLY",
                "correlation_id": "reasoning_1",
                "content_type": "reasoning",
                "data": {"status": "running"},
            },
            {
                "record_type": "chunk",
                "seq_id": 2,
                "content": "先定位指标。",
                "delta": {"status": "END"},
                "metadata": {"correlation_id": "reasoning_1", "content_type": "reasoning"},
            },
            {
                "record_type": "event",
                "seq_id": 3,
                "event_type": "PENDING_TOOL_CALL",
                "correlation_id": "tool_1",
                "data": {"tool": {"id": "tool_1", "name": "Read", "status": "pending", "input": {"path": "skill.md"}}},
            },
            {
                "record_type": "event",
                "seq_id": 4,
                "event_type": "AFTER_TOOL_CALL",
                "correlation_id": "tool_1",
                "data": {"tool": {"id": "tool_1", "name": "Read", "status": "success", "output": "读取完成"}},
            },
            {
                "record_type": "event",
                "seq_id": 5,
                "event_type": "BEFORE_AGENT_REPLY",
                "correlation_id": "content_1",
                "content_type": "content",
                "data": {"status": "running"},
            },
            {
                "record_type": "chunk",
                "seq_id": 6,
                "content": "最近 30 天累计发布 4 次。",
                "delta": {"status": "END"},
                "metadata": {"correlation_id": "content_1", "content_type": "content"},
            },
        ]
    )

    assert payload["resume_after_seq"] == 6
    assert [block["type"] for block in payload["blocks"]] == ["thinking", "tool", "main_text"]
    assert payload["blocks"][0]["text"] == "先定位指标。"
    assert payload["blocks"][1]["tool_name"] == "Read"
    assert payload["blocks"][1]["output"] == "读取完成"
    assert payload["blocks"][2]["text"] == "最近 30 天累计发布 4 次。"


def test_project_task_history_preserves_running_blocks_and_fallback_cursor():
    payload = _project_task_history(
        [
            {
                "record_type": "event",
                "seq_id": 2,
                "event_type": "BEFORE_AGENT_REPLY",
                "correlation_id": "reasoning_2",
                "content_type": "reasoning",
                "data": {"status": "running"},
            },
            {
                "record_type": "chunk",
                "seq_id": 3,
                "content": "正在检查数据源",
                "delta": {"status": "STREAMING"},
                "metadata": {"correlation_id": "reasoning_2", "content_type": "reasoning"},
            },
            {
                "record_type": "event",
                "seq_id": 4,
                "event_type": "PENDING_TOOL_CALL",
                "correlation_id": "tool_2",
                "data": {"tool": {"id": "tool_2", "name": "Bash", "status": "pending"}},
            },
        ],
        fallback_resume_after_seq=9,
    )

    assert payload["resume_after_seq"] == 9
    assert [block["type"] for block in payload["blocks"]] == ["thinking", "tool"]
    assert payload["blocks"][0]["status"] == "streaming"
    assert payload["blocks"][1]["status"] == "pending"


def test_project_task_history_builds_error_block():
    payload = _project_task_history(
        [
            {
                "record_type": "event",
                "seq_id": 7,
                "event_type": "ERROR",
                "data": {"status": "error", "error": {"message": "SQL 执行失败"}},
            }
        ]
    )

    assert payload["resume_after_seq"] == 7
    assert len(payload["blocks"]) == 1
    assert payload["blocks"][0]["type"] == "error"
    assert payload["blocks"][0]["text"] == "SQL 执行失败"


def test_normalize_message_row_only_attaches_history_to_assistant():
    store = TopicTaskStore()

    assistant = store._normalize_message_row(  # noqa: SLF001
        {
            "message_id": "msg_1",
            "topic_id": "topic_1",
            "task_id": "task_1",
            "sender_type": "assistant",
            "type": "assistant",
            "status": "finished",
            "content": "最终结果",
            "event": "",
            "steps_json": None,
            "tool_json": None,
            "seq_id": 2,
            "correlation_id": None,
            "parent_correlation_id": None,
            "content_type": None,
            "usage_json": None,
            "feedback": "like",
            "error_json": None,
            "show_in_ui": 1,
            "created_at": None,
            "updated_at": None,
        },
        history={"blocks": [{"block_id": "main_1", "type": "main_text", "text": "最终结果", "status": "success"}], "resume_after_seq": 12},
    )
    user = store._normalize_message_row(  # noqa: SLF001
        {
            "message_id": "msg_2",
            "topic_id": "topic_1",
            "task_id": "task_1",
            "sender_type": "user",
            "type": "chat",
            "status": "success",
            "content": "问题",
            "event": "",
            "steps_json": None,
            "tool_json": None,
            "seq_id": 1,
            "correlation_id": None,
            "parent_correlation_id": None,
            "content_type": None,
            "usage_json": None,
            "error_json": None,
            "show_in_ui": 1,
            "created_at": None,
            "updated_at": None,
        },
        history={"blocks": [{"block_id": "ignored", "type": "thinking", "text": "忽略", "status": "success"}], "resume_after_seq": 9},
    )

    assert assistant["blocks"][0]["type"] == "main_text"
    assert assistant["resume_after_seq"] == 12
    assert assistant["feedback"] == "like"
    assert "blocks" not in user
    assert "resume_after_seq" not in user
    assert user["feedback"] == ""
