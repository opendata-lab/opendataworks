from __future__ import annotations

from core.task_coordinator import TaskCoordinator


def test_task_coordinator_persists_only_emitted_sdk_records():
    appended: list[dict] = []

    class FakeStore:
        def append_sdk_record(self, **kwargs):
            appended.append(kwargs)

    coordinator = TaskCoordinator(store=FakeStore())

    coordinator._persist_emitted_sdk_record(
        topic_id="topic-1",
        task_id="task-1",
        record={
            "record_type": "stream",
            "event_type": "message_start",
            "turn_index": 2,
            "data": {"type": "message_start"},
        },
    )
    coordinator._persist_emitted_sdk_record(
        topic_id="topic-1",
        task_id="task-1",
        record={
            "record_type": "event",
            "event_type": "lifecycle",
            "data": {"legacy": True},
        },
    )

    assert appended == [
        {
            "task_id": "task-1",
            "topic_id": "topic-1",
            "turn_index": 2,
            "record_type": "stream",
            "event_type": "message_start",
            "data": {"type": "message_start"},
        }
    ]
