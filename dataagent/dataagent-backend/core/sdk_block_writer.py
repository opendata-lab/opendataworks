"""SDK block writer — records native Claude SDK messages to da_agent_sdk_record."""
from __future__ import annotations

import json
import logging
import re
import time
from dataclasses import dataclass, field
from typing import Any

logger = logging.getLogger(__name__)

_THINKING_CHAR_WARN_THRESHOLDS = (4096, 8192, 16384, 32768)
_THINKING_DELTA_WARN_THRESHOLDS = (1000, 3000, 6000, 10000)
_THINKING_DURATION_WARN_SECONDS = 60
_REPEATED_SEGMENT_WARN_COUNTS = {3, 5, 10, 20, 50}
_SEGMENT_SPLIT_RE = re.compile(r"\n+|(?<=[。！？.!?])\s*")


@dataclass
class _BlockStats:
    index: int
    block_type: str
    started_at: float = field(default_factory=time.monotonic)
    delta_count: int = 0
    char_count: int = 0
    next_char_threshold_index: int = 0
    next_delta_threshold_index: int = 0
    long_logged: bool = False
    segment_counts: dict[str, int] = field(default_factory=dict)


class SdkBlockWriter:
    """Writes raw Claude SDK messages into da_agent_sdk_record for the v2 stream endpoint.

    In sandbox mode this writer normally runs inside the task child process. Its
    ``sdk_stream.*`` logs are execution-process observability: the sandbox runner
    captures child stderr and mirrors those lines into the per-task log.
    """

    def __init__(self, store: Any, task_id: str, topic_id: str) -> None:
        self._store = store
        self._task_id = task_id
        self._topic_id = topic_id
        self._turn_index = 0
        self._saw_stream_event = False
        self._active_blocks: dict[int, _BlockStats] = {}

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
        self._observe_stream_event(evt, etype)
        self._store.append_sdk_record(
            task_id=self._task_id,
            topic_id=self._topic_id,
            turn_index=self._turn_index,
            record_type="stream",
            event_type=etype or None,
            data=evt,
        )

    def _observe_stream_event(self, evt: dict[str, Any], etype: str) -> None:
        if etype == "message_start":
            logger.info(
                "sdk_stream.message_start task_id=%s topic_id=%s turn_index=%s",
                self._task_id,
                self._topic_id,
                self._turn_index,
            )
            return
        if etype == "message_stop":
            if self._active_blocks:
                logger.warning(
                    "sdk_stream.message_stop_unclosed_blocks task_id=%s topic_id=%s turn_index=%s block_indexes=%s",
                    self._task_id,
                    self._topic_id,
                    self._turn_index,
                    ",".join(str(index) for index in sorted(self._active_blocks)),
                )
            logger.info(
                "sdk_stream.message_stop task_id=%s topic_id=%s turn_index=%s",
                self._task_id,
                self._topic_id,
                self._turn_index,
            )
            self._active_blocks.clear()
            return

        if etype == "content_block_start":
            index = _event_index(evt)
            if index is None:
                return
            content_block = evt.get("content_block")
            block_type = ""
            if isinstance(content_block, dict):
                block_type = str(content_block.get("type") or "")
            self._active_blocks[index] = _BlockStats(index=index, block_type=block_type)
            logger.info(
                "sdk_stream.block_start task_id=%s topic_id=%s turn_index=%s block_index=%s block_type=%s",
                self._task_id,
                self._topic_id,
                self._turn_index,
                index,
                block_type,
            )
            return

        if etype == "content_block_delta":
            self._observe_stream_delta(evt)
            return

        if etype == "content_block_stop":
            index = _event_index(evt)
            if index is None:
                return
            stats = self._active_blocks.pop(index, None)
            if stats is None:
                return
            elapsed_ms = int((time.monotonic() - stats.started_at) * 1000)
            logger.info(
                "sdk_stream.block_stop task_id=%s topic_id=%s turn_index=%s block_index=%s block_type=%s char_count=%s delta_count=%s elapsed_ms=%s",
                self._task_id,
                self._topic_id,
                self._turn_index,
                index,
                stats.block_type,
                stats.char_count,
                stats.delta_count,
                elapsed_ms,
            )

    def _observe_stream_delta(self, evt: dict[str, Any]) -> None:
        index = _event_index(evt)
        if index is None:
            return
        delta = evt.get("delta")
        if not isinstance(delta, dict):
            return
        delta_type = str(delta.get("type") or "")
        if delta_type != "thinking_delta":
            return
        text = str(delta.get("thinking") or "")
        if not text:
            return
        stats = self._active_blocks.setdefault(index, _BlockStats(index=index, block_type="thinking"))
        stats.delta_count += 1
        stats.char_count += len(text)
        elapsed_seconds = time.monotonic() - stats.started_at

        while (
            stats.next_char_threshold_index < len(_THINKING_CHAR_WARN_THRESHOLDS)
            and stats.char_count >= _THINKING_CHAR_WARN_THRESHOLDS[stats.next_char_threshold_index]
        ):
            threshold = _THINKING_CHAR_WARN_THRESHOLDS[stats.next_char_threshold_index]
            stats.next_char_threshold_index += 1
            logger.warning(
                "sdk_stream.thinking_large task_id=%s topic_id=%s turn_index=%s block_index=%s char_count=%s threshold=%s delta_count=%s elapsed_ms=%s",
                self._task_id,
                self._topic_id,
                self._turn_index,
                index,
                stats.char_count,
                threshold,
                stats.delta_count,
                int(elapsed_seconds * 1000),
            )

        while (
            stats.next_delta_threshold_index < len(_THINKING_DELTA_WARN_THRESHOLDS)
            and stats.delta_count >= _THINKING_DELTA_WARN_THRESHOLDS[stats.next_delta_threshold_index]
        ):
            threshold = _THINKING_DELTA_WARN_THRESHOLDS[stats.next_delta_threshold_index]
            stats.next_delta_threshold_index += 1
            logger.warning(
                "sdk_stream.thinking_many_deltas task_id=%s topic_id=%s turn_index=%s block_index=%s delta_count=%s threshold=%s char_count=%s elapsed_ms=%s",
                self._task_id,
                self._topic_id,
                self._turn_index,
                index,
                stats.delta_count,
                threshold,
                stats.char_count,
                int(elapsed_seconds * 1000),
            )

        if not stats.long_logged and elapsed_seconds >= _THINKING_DURATION_WARN_SECONDS:
            stats.long_logged = True
            logger.warning(
                "sdk_stream.thinking_long task_id=%s topic_id=%s turn_index=%s block_index=%s elapsed_ms=%s char_count=%s delta_count=%s",
                self._task_id,
                self._topic_id,
                self._turn_index,
                index,
                int(elapsed_seconds * 1000),
                stats.char_count,
                stats.delta_count,
            )

        for segment in _meaningful_segments(text):
            count = stats.segment_counts.get(segment, 0) + 1
            stats.segment_counts[segment] = count
            if count in _REPEATED_SEGMENT_WARN_COUNTS:
                logger.warning(
                    "sdk_stream.thinking_repeated_segment task_id=%s topic_id=%s turn_index=%s block_index=%s repeat_count=%s segment_preview=%s",
                    self._task_id,
                    self._topic_id,
                    self._turn_index,
                    index,
                    count,
                    _clip_log_text(segment),
                )

    def append_permission_request(
        self,
        *,
        request_id: str,
        tool_name: str,
        risk_level: str = "high",
        title: str = "",
        summary: str = "",
        payload_preview: Any = None,
        deadline_at: str = "",
    ) -> None:
        """Record a generic Chat V2 permission-request block.

        Skill-agnostic: ``payload_preview`` is opaque JSON shown verbatim by the
        confirmation card. Projected into a ``permission_request`` block that the
        portal chat and widget render identically.
        """
        self._store.append_sdk_record(
            task_id=self._task_id,
            topic_id=self._topic_id,
            turn_index=self._turn_index,
            record_type="permission_request",
            event_type=None,
            data={
                "request_id": str(request_id),
                "tool_name": str(tool_name or ""),
                "risk_level": str(risk_level or "high"),
                "title": str(title or ""),
                "summary": str(summary or ""),
                "payload_preview": payload_preview,
                # When the card settles by policy if unanswered; empty waits indefinitely.
                "deadline_at": str(deadline_at or ""),
            },
        )

    def append_permission_decision(
        self,
        *,
        request_id: str,
        decision: str,
        note: str = "",
        decided_at: str = "",
    ) -> None:
        """Record the user's decision for a prior permission request.

        ``decision`` is one of ``allow`` / ``deny`` / ``timeout``; it merges
        onto the matching ``permission_request`` block during projection.
        """
        self._store.append_sdk_record(
            task_id=self._task_id,
            topic_id=self._topic_id,
            turn_index=self._turn_index,
            record_type="permission_decision",
            event_type=None,
            data={
                "request_id": str(request_id),
                "decision": str(decision or "pending"),
                "note": str(note or ""),
                "decided_at": str(decided_at or ""),
            },
        )

    def append_question_request(
        self,
        *,
        request_id: str,
        questions: Any,
        deadline_at: str = "",
    ) -> None:
        """Record an ``ask_user_question`` request block.

        Skill-agnostic: ``questions`` is the opaque tool input rendered verbatim
        as a selection card by the chat UI and widget. Projected into a
        ``question_request`` block.
        """
        self._store.append_sdk_record(
            task_id=self._task_id,
            topic_id=self._topic_id,
            turn_index=self._turn_index,
            record_type="question_request",
            event_type=None,
            data={
                "request_id": str(request_id),
                "questions": questions if isinstance(questions, list) else [],
                "deadline_at": str(deadline_at or ""),
            },
        )

    def append_question_answer(
        self,
        *,
        request_id: str,
        answers: Any,
        answered_at: str = "",
    ) -> None:
        """Record the user's answer for a prior ``ask_user_question`` request.

        Merges onto the matching ``question_request`` block during projection.
        """
        self._store.append_sdk_record(
            task_id=self._task_id,
            topic_id=self._topic_id,
            turn_index=self._turn_index,
            record_type="question_answer",
            event_type=None,
            data={
                "request_id": str(request_id),
                "answers": answers if isinstance(answers, list) else [],
                "answered_at": str(answered_at or ""),
            },
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
            if self._active_blocks:
                logger.warning(
                    "sdk_stream.terminal_unclosed_blocks task_id=%s topic_id=%s turn_index=%s record_type=%s block_indexes=%s",
                    self._task_id,
                    self._topic_id,
                    self._turn_index,
                    record_type,
                    ",".join(str(index) for index in sorted(self._active_blocks)),
                )
                self._active_blocks.clear()
            logger.info(
                "sdk_stream.terminal_record task_id=%s topic_id=%s turn_index=%s record_type=%s",
                self._task_id,
                self._topic_id,
                self._turn_index,
                record_type,
            )
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


def _event_index(evt: dict[str, Any]) -> int | None:
    try:
        return int(evt.get("index"))
    except (TypeError, ValueError):
        return None


def _meaningful_segments(text: str) -> list[str]:
    segments: list[str] = []
    for raw in _SEGMENT_SPLIT_RE.split(str(text or "")):
        segment = raw.strip()
        if len(segment) >= 4:
            segments.append(segment)
    return segments


def _clip_log_text(text: str, limit: int = 120) -> str:
    value = " ".join(str(text or "").split())
    if len(value) <= limit:
        return value
    return value[: limit - 3] + "..."


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
