"""SDK block writer — records native Claude SDK messages to da_agent_sdk_record."""
from __future__ import annotations

import json
import logging
from typing import Any

logger = logging.getLogger(__name__)


class SdkBlockWriter:
    """Writes raw Claude SDK messages into da_agent_sdk_record for the v2 stream endpoint."""

    def __init__(self, store: Any, task_id: str, topic_id: str) -> None:
        self._store = store
        self._task_id = task_id
        self._topic_id = topic_id
        self._turn_index = 0
        self._saw_stream_event = False

    def ingest(self, msg: Any) -> None:
        """Process one SDK message from the claude_query() stream."""
        try:
            self._ingest_safe(msg)
        except Exception:
            logger.exception("sdk_block_writer: failed to ingest message type=%s", type(msg).__name__)

    def _ingest_safe(self, msg: Any) -> None:
        type_name = type(msg).__name__

        if type_name == "StreamEvent":
            self._saw_stream_event = True
            evt = getattr(msg, "event", None) or {}
            self._append_stream_event(evt)

        elif type_name == "AssistantMessage":
            self._ingest_assistant_message(msg)

        elif type_name == "UserMessage":
            content = getattr(msg, "content", None) or []
            for block in content:
                tool_use_id = getattr(block, "tool_use_id", None)
                if not tool_use_id:
                    continue
                raw_content = getattr(block, "content", None)
                # Normalise content to a JSON-serialisable form
                if hasattr(raw_content, "__iter__") and not isinstance(raw_content, str):
                    serialised = _serialise_blocks(raw_content)
                else:
                    serialised = raw_content
                self._store.append_sdk_record(
                    task_id=self._task_id,
                    topic_id=self._topic_id,
                    turn_index=self._turn_index,
                    record_type="tool_result",
                    event_type=None,
                    data={
                        "tool_use_id": str(tool_use_id),
                        "content": serialised,
                        "is_error": bool(getattr(block, "is_error", False)),
                    },
                )

        elif type_name == "ResultMessage":
            subtype = str(getattr(msg, "subtype", "") or "")
            is_error = bool(getattr(msg, "is_error", False))
            self._store.append_sdk_record(
                task_id=self._task_id,
                topic_id=self._topic_id,
                turn_index=self._turn_index,
                record_type="done",
                event_type=None,
                data={
                    "is_error": is_error,
                    "subtype": subtype,
                },
            )
            if is_error or subtype.startswith("error"):
                self.append_error(
                    code=subtype or "provider_error",
                    message=_stringify(getattr(msg, "result", None)) or "模型会话异常结束",
                )

    def _ingest_assistant_message(self, msg: Any) -> None:
        # In partial-streaming mode the SDK already emitted StreamEvent records
        # carrying every block of this message. Projecting the whole
        # AssistantMessage on top of that would duplicate thinking, tool calls,
        # and conclusion blocks. Only normalize whole messages when no partial
        # StreamEvent was observed (supports_partial_messages=false providers).
        if self._saw_stream_event:
            return
        content = getattr(msg, "content", None)
        if isinstance(content, str):
            blocks = [{"type": "text", "text": content}]
        elif isinstance(content, list):
            blocks = content
        else:
            return
        if not blocks:
            return

        self._append_stream_event({"type": "message_start"})
        for index, block in enumerate(blocks):
            self._append_assistant_block(index, block)
        self._append_stream_event({"type": "message_stop"})

    def _append_assistant_block(self, index: int, block: Any) -> None:
        block_type = _block_type(block)
        if block_type == "tool_use":
            self._append_tool_use_block(index, block)
            return
        if block_type == "thinking":
            self._append_text_like_block(index, "thinking", block, "thinking_delta", "thinking", _block_value(block, "thinking", "text"))
            return
        if block_type == "text":
            self._append_text_like_block(index, "text", block, "text_delta", "text", _block_value(block, "text", "content"))

    def _append_text_like_block(
        self,
        index: int,
        block_type: str,
        block: Any,
        delta_type: str,
        delta_field: str,
        text: Any,
    ) -> None:
        self._append_stream_event(
            {
                "type": "content_block_start",
                "index": index,
                "content_block": {"type": block_type},
            }
        )
        text_value = str(text or "")
        if text_value:
            self._append_stream_event(
                {
                    "type": "content_block_delta",
                    "index": index,
                    "delta": {"type": delta_type, delta_field: text_value},
                }
            )
        self._append_stream_event({"type": "content_block_stop", "index": index})

    def _append_tool_use_block(self, index: int, block: Any) -> None:
        tool_id = str(_block_value(block, "id") or "")
        tool_name = str(_block_value(block, "name") or "Tool")
        self._append_stream_event(
            {
                "type": "content_block_start",
                "index": index,
                "content_block": {"type": "tool_use", "id": tool_id, "name": tool_name},
            }
        )
        tool_input = _block_value(block, "input")
        if tool_input is not None:
            self._append_stream_event(
                {
                    "type": "content_block_delta",
                    "index": index,
                    "delta": {
                        "type": "input_json_delta",
                        "partial_json": json.dumps(tool_input, ensure_ascii=False, separators=(",", ":")),
                    },
                }
            )
        self._append_stream_event({"type": "content_block_stop", "index": index})

    def _append_stream_event(self, evt: dict[str, Any]) -> None:
        etype = str(evt.get("type") or "")
        if etype == "message_start":
            self._turn_index += 1
        self._store.append_sdk_record(
            task_id=self._task_id,
            topic_id=self._topic_id,
            turn_index=self._turn_index,
            record_type="stream",
            event_type=etype or None,
            data=evt,
        )

    def append_done(self, *, is_error: bool, subtype: str = "") -> None:
        self._append_terminal_record(
            record_type="done",
            data={"is_error": bool(is_error), "subtype": str(subtype or "")},
        )

    def append_error(self, *, code: str = "model_error", message: str = "请求失败", detail: str = "", exception_type: str = "") -> None:
        payload = {
            "code": str(code or "model_error"),
            "message": str(message or "请求失败"),
        }
        if detail:
            payload["detail"] = str(detail)
        if exception_type:
            payload["exception_type"] = str(exception_type)
        self._append_terminal_record(record_type="error", data=payload)

    def _append_terminal_record(self, *, record_type: str, data: dict[str, Any]) -> None:
        try:
            self._store.append_sdk_record(
                task_id=self._task_id,
                topic_id=self._topic_id,
                turn_index=self._turn_index,
                record_type=record_type,
                event_type=None,
                data=data,
            )
        except Exception:
            logger.exception("sdk_block_writer: failed to append terminal record type=%s", record_type)


def _serialise_blocks(blocks: Any) -> Any:
    """Convert SDK block objects into plain dicts for JSON storage."""
    result = []
    for b in blocks:
        if isinstance(b, dict):
            result.append(b)
        elif hasattr(b, "__dict__"):
            result.append({k: v for k, v in vars(b).items() if not k.startswith("_")})
        else:
            result.append(str(b))
    return result


def _block_type(block: Any) -> str:
    value = _block_value(block, "type")
    text = str(value or type(block).__name__ or "").strip().lower()
    if text.endswith("block"):
        text = text.removesuffix("block")
    compact = text.replace("_", "")
    if compact in {"tooluse", "servertooluse"}:
        return "tool_use"
    if compact in {"toolresult", "servertoolresult"}:
        return "tool_result"
    return text


def _block_value(block: Any, *names: str) -> Any:
    for name in names:
        if isinstance(block, dict) and name in block:
            return block.get(name)
        if hasattr(block, name):
            return getattr(block, name)
    return None


def _stringify(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, str):
        return value
    try:
        return json.dumps(value, ensure_ascii=False)
    except Exception:
        return str(value)
