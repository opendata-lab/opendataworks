"""Parallel SDK block writer — records native Claude SDK messages to da_agent_sdk_record.

Runs alongside ClaudeToMagicAdapter without touching the existing magic-event path.
"""
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

    def ingest(self, msg: Any) -> None:
        """Process one SDK message from the claude_query() stream."""
        try:
            self._ingest_safe(msg)
        except Exception:
            logger.exception("sdk_block_writer: failed to ingest message type=%s", type(msg).__name__)

    def _ingest_safe(self, msg: Any) -> None:
        type_name = type(msg).__name__

        if type_name == "StreamEvent":
            evt = getattr(msg, "event", None) or {}
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
            self._store.append_sdk_record(
                task_id=self._task_id,
                topic_id=self._topic_id,
                turn_index=self._turn_index,
                record_type="done",
                event_type=None,
                data={
                    "is_error": bool(getattr(msg, "is_error", False)),
                    "subtype": str(getattr(msg, "subtype", "") or ""),
                },
            )


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
