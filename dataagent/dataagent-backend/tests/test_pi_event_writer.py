"""Unit tests for the Pi data plane's event writer."""

from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from core.pi_event_writer import PiEventWriter  # noqa: E402


class _FakeStore:
    def __init__(self) -> None:
        self.records: list[dict[str, Any]] = []

    def append_sdk_record(self, **kwargs: Any) -> None:
        self.records.append(kwargs)


def _event(sequence: int, event_type: str, payload: dict[str, Any] | None = None) -> dict[str, Any]:
    return {
        "event_id": f"ev-{sequence}",
        "run_id": "task-1",
        "task_id": "task-1",
        "task_attempt_id": "attempt-1",
        "sequence": sequence,
        "type": event_type,
        "payload": payload or {},
    }


def test_writes_neutral_records_as_agent_event():
    """The record type carries the envelope now; "pi_event" is the legacy name.

    Both are accepted on read, so existing rows keep replaying.
    """
    store = _FakeStore()
    writer = PiEventWriter(store, "task-1", "topic-1")

    writer.ingest(_event(1, "run.started"))
    writer.ingest(_event(2, "turn.started", {"turn_id": "turn-1"}))
    writer.ingest(_event(3, "content.delta", {"content_id": "c-0", "kind": "answer", "delta": "hi"}))

    assert [r["record_type"] for r in store.records] == ["agent_event"] * 3
    assert [r["event_type"] for r in store.records] == ["run.started", "turn.started", "content.delta"]
    assert store.records[-1]["data"] == {"content_id": "c-0", "kind": "answer", "delta": "hi"}


def test_turn_index_advances_on_turn_started():
    store = _FakeStore()
    writer = PiEventWriter(store, "task-1", "topic-1")

    writer.ingest(_event(1, "run.started"))
    assert store.records[-1]["turn_index"] == 0

    writer.ingest(_event(2, "turn.started", {"turn_id": "turn-1"}))
    assert store.records[-1]["turn_index"] == 1

    writer.ingest(_event(3, "turn.started", {"turn_id": "turn-2"}))
    assert store.records[-1]["turn_index"] == 2
    assert writer.turn_index == 2


def test_out_of_order_and_replayed_events_are_dropped():
    store = _FakeStore()
    writer = PiEventWriter(store, "task-1", "topic-1")

    writer.ingest(_event(1, "run.started"))
    writer.ingest(_event(3, "content.delta", {"delta": "kept"}))
    writer.ingest(_event(2, "content.delta", {"delta": "stale"}))
    writer.ingest(_event(3, "content.delta", {"delta": "replayed"}))

    assert len(store.records) == 2
    assert store.records[-1]["data"]["delta"] == "kept"


def test_run_failed_also_emits_the_shared_error_record():
    """Terminal failures must surface through the same record type as the SDK path."""
    store = _FakeStore()
    writer = PiEventWriter(store, "task-1", "topic-1")

    writer.ingest(
        _event(1, "run.failed", {"error_code": "CELL_LOSS", "message": "child exited", "detail": "signal 9"})
    )

    assert [r["record_type"] for r in store.records] == ["agent_event", "error"]
    error_payload = store.records[-1]["data"]
    assert error_payload["code"] == "CELL_LOSS"
    assert error_payload["message"] == "child exited"
    assert error_payload["detail"] == "signal 9"


def test_malformed_event_never_raises():
    store = _FakeStore()
    writer = PiEventWriter(store, "task-1", "topic-1")

    writer.ingest({})                      # no type
    writer.ingest({"type": ""})            # blank type
    writer.ingest("not a dict")            # type: ignore[arg-type]
    writer.ingest({"type": "content.delta", "payload": "not a dict"})

    # Only the last one is well-formed enough to record; payload coerces to {}.
    assert [r["event_type"] for r in store.records] == ["content.delta"]
    assert store.records[0]["data"] == {}


def test_store_failure_is_swallowed_not_propagated():
    """A persistence hiccup must not abort an in-flight run."""

    class _BrokenStore:
        def append_sdk_record(self, **kwargs: Any) -> None:
            raise RuntimeError("db down")

    writer = PiEventWriter(_BrokenStore(), "task-1", "topic-1")
    writer.ingest(_event(1, "content.delta", {"delta": "x"}))
