"""Pi event writer — records neutral AgentEvents from the Pi Cell into da_agent_sdk_record.

Sibling of :class:`core.sdk_block_writer.SdkBlockWriter`. Both write to the same
table through the same ``append_sdk_record`` call; they differ only in the shape
of what arrives.

``SdkBlockWriter`` cannot be reused here: it dispatches on
``type(msg).__name__`` over ``claude_agent_sdk`` Python objects (``StreamEvent`` /
``AssistantMessage`` / ``UserMessage``). The Pi Cell is a Node child process that
speaks neutral JSON over stdio, so its events would fall through every branch.

The table itself is engine neutral (``record_type`` + ``event_type`` +
``data JSON``), so no schema change or migration is needed:

* ``record_type`` — ``"pi_event"``
* ``event_type``  — the neutral event type (``content.delta``, ``tool.started``, ...)
* ``data``        — the event payload

Terminal failures additionally emit the standard ``record_type="error"`` record so
the existing error surfacing keeps working unchanged across both data planes.
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any

logger = logging.getLogger(__name__)


def _parse_occurred_at(value: Any) -> Any:
    """Producer timestamp as a datetime, or None if it is not usable.

    A malformed timestamp must not cost us the record: ordering falls back to
    the row's own created_at, which is close enough for display.
    """
    text = str(value or "").strip()
    if not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None

# Payload keys are deliberately aligned with the SDK path's projected block
# fields (``output`` / ``is_error``) rather than inventing parallel names
# (``result`` / ``error``). Both projections and the frontend render adapter
# ``blockToToolProp`` already read these, so alignment here is what lets a Pi
# turn render through the exact same components as an SDK turn.
TERMINAL_EVENT_TYPES = frozenset(
    {"run.completed", "run.failed", "run.cancelled", "run.suspended"}
)


class PiEventWriter:
    """Writes neutral Pi AgentEvents into ``da_agent_sdk_record``."""

    def __init__(self, store: Any, task_id: str, topic_id: str) -> None:
        self._store = store
        self._task_id = task_id
        self._topic_id = topic_id
        self._turn_index = 0
        self._highest_sequence = 0

    @property
    def turn_index(self) -> int:
        return self._turn_index

    def ingest(self, event: dict[str, Any]) -> None:
        """Persist one neutral AgentEvent.

        Never raises: a malformed event from the child must not abort the run.
        Out-of-order and replayed events are dropped by sequence, mirroring the
        monotonic guarantee the Cell's run state machine provides.
        """
        try:
            self._ingest_safe(event)
        except Exception:
            logger.exception(
                "pi_event.ingest_failed task_id=%s event_type=%s",
                self._task_id,
                (event or {}).get("type"),
            )

    def _ingest_safe(self, event: dict[str, Any]) -> None:
        if not isinstance(event, dict):
            return
        event_type = str(event.get("type") or "").strip()
        if not event_type:
            return

        sequence = event.get("sequence")
        if isinstance(sequence, int):
            if sequence <= self._highest_sequence:
                logger.warning(
                    "pi_event.out_of_order task_id=%s sequence=%s highest=%s",
                    self._task_id,
                    sequence,
                    self._highest_sequence,
                )
                return
            self._highest_sequence = sequence

        # A new turn starts a fresh block group, matching SdkBlockWriter's
        # message_start semantics so history projection groups the same way.
        if event_type == "turn.started":
            self._turn_index += 1

        # A malformed payload must not cost us the event itself: the type and
        # sequence still carry information the projection and the terminal
        # handling need, so coerce rather than drop.
        raw_payload = event.get("payload")
        payload = dict(raw_payload) if isinstance(raw_payload, dict) else {}
        # The envelope was previously discarded, so a stored record could not be
        # deduplicated, ordered against its producer, or attributed to an engine
        # — everything history replay needs once it stops going through a second
        # projection.
        self._store.append_sdk_record(
            task_id=self._task_id,
            topic_id=self._topic_id,
            turn_index=self._turn_index,
            record_type="agent_event",
            event_type=event_type,
            data=payload,
            envelope={
                "contract_version": 1,
                "engine_kind": "pi_agent_core",
                "event_id": str(event.get("event_id") or "") or None,
                "run_id": str(event.get("run_id") or "") or None,
                "task_attempt_id": str(event.get("task_attempt_id") or "") or None,
                "engine_sequence": sequence if isinstance(sequence, int) else None,
                "occurred_at": _parse_occurred_at(event.get("timestamp")),
            },
        )

        if event_type == "run.failed":
            self.append_error(
                code=str(payload.get("error_code") or "pi_runtime_error"),
                message=str(payload.get("message") or "Pi 运行时执行失败"),
                detail=str(payload.get("detail") or ""),
            )

    def append_error(self, *, code: str = "pi_runtime_error", message: str = "请求失败", detail: str = "") -> None:
        """Emit the shared terminal error record.

        Same ``record_type``/shape as ``SdkBlockWriter.append_error`` so error
        handling downstream does not need to know which data plane produced it.
        """
        payload: dict[str, Any] = {
            "code": str(code or "pi_runtime_error"),
            "message": str(message or "请求失败"),
        }
        if detail:
            payload["detail"] = str(detail)
        self._store.append_sdk_record(
            task_id=self._task_id,
            topic_id=self._topic_id,
            turn_index=self._turn_index,
            record_type="error",
            event_type=None,
            data=payload,
        )
