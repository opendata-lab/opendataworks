from __future__ import annotations

import asyncio

import pytest

from core import ask_user_question, permission_wait
from core.task_control import RunnerStoppedError, TaskCancelledError


def test_wait_for_decision_returns_persisted_decision(monkeypatch):
    class Store:
        def get_resolved_permission_decision(self, *, task_id: str, request_id: str):
            return "allow"

    monkeypatch.setattr(permission_wait, "get_topic_task_store", lambda: Store())

    result = asyncio.run(permission_wait.wait_for_decision("task-1", "req-1", poll_interval_seconds=0.1))

    assert result == "allow"


def test_wait_for_decision_raises_on_cancel_reason():
    with pytest.raises(TaskCancelledError):
        asyncio.run(
            permission_wait.wait_for_decision(
                "task-1",
                "req-1",
                poll_interval_seconds=0.1,
                cancel_reason=lambda: "user_cancel",
            )
        )

    with pytest.raises(RunnerStoppedError):
        asyncio.run(
            permission_wait.wait_for_decision(
                "task-1",
                "req-1",
                poll_interval_seconds=0.1,
                cancel_reason=lambda: "runner_stop",
            )
        )


def test_wait_for_answer_returns_persisted_answers(monkeypatch):
    answers = [{"question": "维度?", "selected": ["按天"]}]

    class Store:
        def get_resolved_question_answer(self, *, task_id: str, request_id: str):
            return answers

    from core import topic_task_store

    monkeypatch.setattr(topic_task_store, "get_topic_task_store", lambda: Store())

    result = asyncio.run(ask_user_question.wait_for_answer("task-1", "req-1", poll_interval_seconds=0.1))

    assert result == answers


def test_wait_for_answer_raises_on_cancel_reason():
    with pytest.raises(TaskCancelledError):
        asyncio.run(
            ask_user_question.wait_for_answer(
                "task-1",
                "req-1",
                poll_interval_seconds=0.1,
                cancel_reason=lambda: "user_cancel",
            )
        )

    with pytest.raises(RunnerStoppedError):
        asyncio.run(
            ask_user_question.wait_for_answer(
                "task-1",
                "req-1",
                poll_interval_seconds=0.1,
                cancel_reason=lambda: "runner_stop",
            )
        )


class _Expired:
    expired = True


def test_wait_for_decision_retries_timeout_policy_until_it_lands(monkeypatch):
    # A MySQL blip at the deadline must not leave the run parked forever.
    state = {"calls": 0, "decision": None}

    class Store:
        def get_resolved_permission_decision(self, *, task_id: str, request_id: str):
            return state["decision"]

    def on_timeout():
        state["calls"] += 1
        if state["calls"] < 3:
            raise RuntimeError("mysql gone away")
        state["decision"] = "allow"

    monkeypatch.setattr(permission_wait, "get_topic_task_store", lambda: Store())

    result = asyncio.run(
        permission_wait.wait_for_decision(
            "task-1", "req-1", poll_interval_seconds=0.1, deadline=_Expired(), on_timeout=on_timeout
        )
    )

    assert result == "allow"
    assert state["calls"] == 3


def test_wait_for_answer_runs_timeout_policy_once_after_it_lands(monkeypatch):
    state = {"calls": 0, "polls": 0}

    class Store:
        def get_resolved_question_answer(self, *, task_id: str, request_id: str):
            state["polls"] += 1
            # The policy's write becomes visible two polls later.
            return [{"selected": ["按天"], "auto": True}] if state["polls"] > 3 else None

    def on_timeout():
        state["calls"] += 1

    from core import topic_task_store

    monkeypatch.setattr(topic_task_store, "get_topic_task_store", lambda: Store())

    result = asyncio.run(
        ask_user_question.wait_for_answer(
            "task-1", "req-1", poll_interval_seconds=0.1, deadline=_Expired(), on_timeout=on_timeout
        )
    )

    assert result == [{"selected": ["按天"], "auto": True}]
    assert state["calls"] == 1
