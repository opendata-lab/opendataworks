from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path
from types import SimpleNamespace

from fastapi.testclient import TestClient

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

import api.routes as routes
import main


DEFAULT_AGENT = {
    "agent_id": "agent_default",
    "name": "默认助手",
    "description": "default",
    "system_prompt": "",
    "permission_mode": "default",
    "allowed_tools": ["Read", "LS", "Glob", "Grep"],
    "mcp_server_ids": [],
    "skill_folders": [],
    "max_turns": 0,
    "env_vars": {},
    "is_default": True,
    "is_builtin": True,
    "created_at": "",
    "updated_at": "",
}


def _now() -> str:
    return datetime.now().isoformat(timespec="seconds")


class _FakeStore:
    def __init__(self):
        self.topics: dict[str, dict] = {}
        self.topic_messages: dict[str, list[dict]] = {}
        self.tasks: dict[str, dict] = {}
        self.task_events: dict[str, list[dict]] = {}
        self.queues: dict[str, dict] = {}
        self.schedules: dict[str, dict] = {}
        self.schedule_logs: dict[str, list[dict]] = {}
        self._topic_seq = 0
        self._task_seq = 0
        self._message_seq = 0
        self._queue_seq = 0
        self._schedule_seq = 0
        self._schedule_log_seq = 0
        self.get_message_contexts: list[dict | None] = []

    def init_schema(self):
        return None

    def _new_topic(self) -> str:
        self._topic_seq += 1
        return f"topic_{self._topic_seq}"

    def _new_task(self) -> str:
        self._task_seq += 1
        return f"task_{self._task_seq}"

    def _new_message(self) -> str:
        self._message_seq += 1
        return f"msg_{self._message_seq}"

    def _new_queue(self) -> str:
        self._queue_seq += 1
        return f"queue_{self._queue_seq}"

    def _new_schedule(self) -> str:
        self._schedule_seq += 1
        return f"schedule_{self._schedule_seq}"

    def _new_schedule_log(self) -> str:
        self._schedule_log_seq += 1
        return f"schedule_log_{self._schedule_log_seq}"

    def create_topic(self, *, title: str, agent_snapshot=None, context=None):
        topic_id = self._new_topic()
        snapshot = dict(agent_snapshot or DEFAULT_AGENT)
        self.topics[topic_id] = {
            "topic_id": topic_id,
            "title": title or "新话题",
            "chat_topic_id": f"chat_topic_{topic_id}",
            "chat_conversation_id": f"chat_conversation_{topic_id}",
            "agent_id": snapshot["agent_id"],
            "agent_snapshot": snapshot,
            "agent": {
                "agent_id": snapshot["agent_id"],
                "name": snapshot["name"],
                "description": snapshot.get("description", ""),
                "is_default": bool(snapshot.get("is_default")),
            },
            "current_task_id": None,
            "current_task_status": None,
            "message_count": 0,
            "last_message_preview": "",
            "created_at": _now(),
            "updated_at": _now(),
        }
        self.topic_messages[topic_id] = []
        return self.get_topic(topic_id)

    def list_topics(self, include_messages: bool = False, context=None, agent_id=None):
        rows = [dict(self.get_topic(topic_id) or {}) for topic_id in self.topics]
        if agent_id:
            rows = [row for row in rows if row.get("agent_id") == agent_id]
        if include_messages:
            for row in rows:
                row["messages"] = self.list_topic_messages(row["topic_id"])
        return rows

    def get_topic(self, topic_id: str, context=None):
        topic = self.topics.get(topic_id)
        if not topic:
            return None
        row = dict(topic)
        row["message_count"] = len(self.topic_messages.get(topic_id, []))
        row["last_message_preview"] = self.topic_messages.get(topic_id, [{}])[-1].get("content", "")[:120] if self.topic_messages.get(topic_id) else ""
        return row

    def update_topic(self, topic_id: str, *, title: str, context=None):
        if topic_id not in self.topics:
            return None
        self.topics[topic_id]["title"] = title
        self.topics[topic_id]["updated_at"] = _now()
        return self.get_topic(topic_id)

    def delete_topic(self, topic_id: str, context=None):
        self.topics.pop(topic_id, None)
        self.topic_messages.pop(topic_id, None)

    def list_topic_messages(self, topic_id: str):
        return list(self.topic_messages.get(topic_id, []))

    def list_topic_messages_page(self, *, topic_id: str, page: int = 1, page_size: int = 200, order: str = "asc", context=None):
        items = list(self.topic_messages.get(topic_id, []))
        if str(order).lower() == "desc":
            items = list(reversed(items))
        start = max(0, (page - 1) * page_size)
        end = start + page_size
        return {
            "topic_id": topic_id,
            "page": page,
            "page_size": page_size,
            "order": order,
            "total": len(items),
            "items": items[start:end],
        }

    def create_task(
        self,
        *,
        topic_id: str,
        prompt: str,
        provider_id: str,
        model: str,
        database_hint: str | None,
        debug: bool,
        execution_mode: str | None = None,
        source_queue_id: str | None = None,
        source_schedule_id: str | None = None,
        source_schedule_log_id: str | None = None,
    ):
        task_id = self._new_task()
        task = {
            "task_id": task_id,
            "topic_id": topic_id,
            "from_task_id": None,
            "agent_id": self.topics[topic_id].get("agent_id", "agent_default"),
            "agent_snapshot": self.topics[topic_id].get("agent_snapshot", DEFAULT_AGENT),
            "agent": self.topics[topic_id].get("agent"),
            "task_status": "waiting",
            "prompt": prompt,
            "provider_id": provider_id,
            "model": model,
            "database_hint": database_hint,
            "debug": debug,
            "cancel_requested_at": None,
            "started_at": None,
            "heartbeat_at": None,
            "finished_at": None,
            "error": None,
            "source_queue_id": source_queue_id,
            "source_schedule_id": source_schedule_id,
            "source_schedule_log_id": source_schedule_log_id,
            "created_at": _now(),
            "updated_at": _now(),
        }
        self.tasks[task_id] = task
        self.topics[topic_id]["current_task_id"] = task_id
        self.topics[topic_id]["current_task_status"] = "waiting"
        self.topics[topic_id]["updated_at"] = _now()
        self.task_events[task_id] = [
            {
                "record_type": "event",
                "seq_id": 1,
                "created_at": _now(),
                "event_type": "BEFORE_AGENT_REPLY",
                "correlation_id": "content_phase_1",
                "parent_correlation_id": None,
                "content_type": "content",
                "data": {"status": "running"},
            }
        ]
        return dict(task)

    def append_user_message(self, *, topic_id: str, task_id: str, content: str):
        message = {
            "message_id": self._new_message(),
            "topic_id": topic_id,
            "task_id": task_id,
            "sender_type": "user",
            "type": "chat",
            "status": "success",
            "content": content,
            "event": "",
            "steps": None,
            "tool": None,
            "seq_id": len(self.topic_messages[topic_id]) + 1,
            "correlation_id": None,
            "parent_correlation_id": None,
            "content_type": None,
            "usage": None,
            "feedback": "",
            "show_in_ui": True,
            "error": None,
            "created_at": _now(),
            "updated_at": _now(),
        }
        self.topic_messages[topic_id].append(message)
        self.topics[topic_id]["updated_at"] = _now()
        return dict(message)

    def ensure_assistant_message(self, *, topic_id: str, task_id: str, status: str):
        existing = self.get_assistant_message(task_id)
        if existing:
            existing["status"] = status
            return existing
        message = {
            "message_id": self._new_message(),
            "topic_id": topic_id,
            "task_id": task_id,
            "sender_type": "assistant",
            "type": "assistant",
            "status": status,
            "content": "",
            "event": "",
            "steps": None,
            "tool": None,
            "seq_id": len(self.topic_messages[topic_id]) + 1,
            "correlation_id": None,
            "parent_correlation_id": None,
            "content_type": None,
            "usage": None,
            "blocks": [],
            "resume_after_seq": 0,
            "feedback": "",
            "show_in_ui": True,
            "error": None,
            "created_at": _now(),
            "updated_at": _now(),
        }
        self.topic_messages[topic_id].append(message)
        return dict(message)

    def get_assistant_message(self, task_id: str):
        for messages in self.topic_messages.values():
            for message in messages:
                if message["task_id"] == task_id and message["sender_type"] == "assistant":
                    return message
        return None

    def get_message(self, message_id: str, context=None):
        self.get_message_contexts.append(context)
        for messages in self.topic_messages.values():
            for message in messages:
                if message["message_id"] == message_id:
                    return dict(message)
        return None

    def update_message_feedback(self, *, topic_id: str, message_id: str, feedback: str, context=None):
        for message in self.topic_messages.get(topic_id, []):
            if message["message_id"] == message_id and message.get("sender_type") == "assistant" and message.get("show_in_ui", True):
                message["feedback"] = feedback
                message["updated_at"] = _now()
                return dict(message)
        return None

    def get_task(self, task_id: str, context=None):
        task = self.tasks.get(task_id)
        return dict(task) if task else None

    def list_task_events(self, *, task_id: str, after_seq: int = 0, limit: int = 200, context=None):
        rows = [row for row in self.task_events.get(task_id, []) if int(row["seq_id"]) > after_seq]
        rows.sort(key=lambda row: int(row["seq_id"]))
        page = rows[:limit]
        task = self.tasks[task_id]
        return {
            "task_id": task_id,
            "task_status": task["task_status"],
            "after_seq": after_seq,
            "next_after_seq": int(page[-1]["seq_id"]) if page else after_seq,
            "has_more": len(rows) > limit,
            "events": page,
        }

    def request_task_cancel(self, task_id: str, context=None):
        task = self.tasks.get(task_id)
        if not task:
            return None
        task["cancel_requested_at"] = _now()
        task["task_status"] = "suspended"
        self.topics[task["topic_id"]]["current_task_status"] = "suspended"
        if task.get("source_queue_id"):
            queue = self.queues.get(task["source_queue_id"])
            if queue:
                queue["status"] = "suspended"
                queue["last_task_id"] = task_id
                queue["error_message"] = "任务已取消"
        if task.get("source_schedule_id"):
            schedule = self.schedules.get(task["source_schedule_id"])
            if schedule:
                schedule["last_task_id"] = task_id
                schedule["last_error_message"] = "任务已取消"
        if task.get("source_schedule_log_id"):
            for item in self.schedule_logs.get(task["source_schedule_id"] or "", []):
                if item["schedule_log_id"] == task["source_schedule_log_id"]:
                    item["task_id"] = task_id
                    item["status"] = "suspended"
                    item["error_message"] = "任务已取消"
                    item["finished_at"] = _now()
        return dict(task)

    def get_message_queue(self, queue_id: str, context=None):
        queue = self.queues.get(queue_id)
        return dict(queue) if queue else None

    def query_message_queues(self, *, topic_id: str | None = None, page: int = 1, page_size: int = 50, context=None):
        items = list(self.queues.values())
        if topic_id:
            items = [item for item in items if item["topic_id"] == topic_id]
        return {
            "page": page,
            "page_size": page_size,
            "total": len(items),
            "items": items[:page_size],
        }

    def create_message_queue(self, *, topic_id: str, message_type: str, message_content, source_schedule_id: str | None = None, source_schedule_log_id: str | None = None):
        queue_id = self._new_queue()
        queue = {
            "queue_id": queue_id,
            "topic_id": topic_id,
            "message_type": message_type,
            "message_content": message_content,
            "status": "queued",
            "last_task_id": None,
            "error_message": None,
            "source_schedule_id": source_schedule_id,
            "source_schedule_log_id": source_schedule_log_id,
            "created_at": _now(),
            "updated_at": _now(),
        }
        self.queues[queue_id] = queue
        return dict(queue)

    def update_message_queue(self, *, queue_id: str, topic_id: str, message_type: str, message_content):
        queue = self.queues.get(queue_id)
        if not queue:
            return None
        queue.update({
            "topic_id": topic_id,
            "message_type": message_type,
            "message_content": message_content,
            "updated_at": _now(),
        })
        return dict(queue)

    def delete_message_queue(self, queue_id: str):
        return self.queues.pop(queue_id, None) is not None

    def mark_message_queue_submitted(self, *, queue_id: str, task_id: str):
        queue = self.queues.get(queue_id)
        if not queue:
            return None
        queue["status"] = "running"
        queue["last_task_id"] = task_id
        queue["updated_at"] = _now()
        return dict(queue)

    def mark_message_queue_failed(self, *, queue_id: str, error_message: str):
        queue = self.queues.get(queue_id)
        if not queue:
            return None
        queue["status"] = "failed"
        queue["error_message"] = error_message
        queue["updated_at"] = _now()
        return dict(queue)

    def get_message_schedule(self, schedule_id: str, context=None):
        schedule = self.schedules.get(schedule_id)
        return dict(schedule) if schedule else None

    def query_message_schedules(self, *, topic_id: str | None = None, page: int = 1, page_size: int = 50, context=None):
        items = list(self.schedules.values())
        if topic_id:
            items = [item for item in items if item["topic_id"] == topic_id]
        return {
            "page": page,
            "page_size": page_size,
            "total": len(items),
            "items": items[:page_size],
        }

    def create_message_schedule(self, *, topic_id: str, name: str, message_type: str, message_content, cron_expr: str, enabled: bool, timezone: str, next_run_at):
        schedule_id = self._new_schedule()
        schedule = {
            "schedule_id": schedule_id,
            "topic_id": topic_id,
            "name": name,
            "message_type": message_type,
            "message_content": message_content,
            "cron_expr": cron_expr,
            "timezone": timezone,
            "enabled": enabled,
            "last_task_id": None,
            "last_queue_id": None,
            "last_run_at": None,
            "next_run_at": next_run_at.isoformat() if next_run_at else None,
            "last_error_message": None,
            "created_at": _now(),
            "updated_at": _now(),
        }
        self.schedules[schedule_id] = schedule
        self.schedule_logs[schedule_id] = []
        return dict(schedule)

    def update_message_schedule(self, *, schedule_id: str, topic_id: str, name: str, message_type: str, message_content, cron_expr: str, enabled: bool, timezone: str, next_run_at):
        schedule = self.schedules.get(schedule_id)
        if not schedule:
            return None
        schedule.update({
            "topic_id": topic_id,
            "name": name,
            "message_type": message_type,
            "message_content": message_content,
            "cron_expr": cron_expr,
            "enabled": enabled,
            "timezone": timezone,
            "next_run_at": next_run_at.isoformat() if next_run_at else None,
            "updated_at": _now(),
        })
        return dict(schedule)

    def delete_message_schedule(self, schedule_id: str):
        deleted = self.schedules.pop(schedule_id, None) is not None
        self.schedule_logs.pop(schedule_id, None)
        return deleted

    def create_message_schedule_log(self, *, schedule_id: str, status: str = "running", task_id: str | None = None):
        schedule = self.schedules[schedule_id]
        log = {
            "schedule_log_id": self._new_schedule_log(),
            "schedule_id": schedule_id,
            "topic_id": schedule["topic_id"],
            "queue_id": None,
            "task_id": task_id,
            "status": status,
            "error_message": None,
            "started_at": _now(),
            "finished_at": None,
            "created_at": _now(),
            "updated_at": _now(),
        }
        self.schedule_logs.setdefault(schedule_id, []).append(log)
        return dict(log)

    def list_message_schedule_logs(self, *, schedule_id: str, page: int = 1, page_size: int = 50):
        items = list(self.schedule_logs.get(schedule_id, []))
        return {
            "schedule_id": schedule_id,
            "page": page,
            "page_size": page_size,
            "total": len(items),
            "items": items[:page_size],
        }


class _FakeCoordinator:
    def __init__(self):
        self.cancelled: list[str] = []

    async def start(self):
        return None

    async def stop(self):
        return None

    async def request_cancel(self, task_id: str):
        self.cancelled.append(task_id)


def _submit_message_task_factory(store: _FakeStore, calls: list[dict]):
    async def _submit_message_task(**kwargs):
        calls.append(dict(kwargs))
        prompt = str(kwargs.get("message_content") or "")
        task = store.create_task(
            topic_id=str(kwargs["topic_id"]),
            prompt=prompt,
            provider_id=str(kwargs.get("provider_id") or "openrouter"),
            model=str(kwargs.get("model") or "anthropic/claude-sonnet-4.5"),
            database_hint=kwargs.get("database_hint"),
            debug=bool(kwargs.get("debug")),
            execution_mode=kwargs.get("execution_mode"),
            source_queue_id=kwargs.get("source_queue_id"),
            source_schedule_id=kwargs.get("source_schedule_id"),
            source_schedule_log_id=kwargs.get("source_schedule_log_id"),
        )
        user_message = store.append_user_message(topic_id=task["topic_id"], task_id=task["task_id"], content=prompt)
        assistant_message = store.ensure_assistant_message(topic_id=task["topic_id"], task_id=task["task_id"], status="waiting")
        if kwargs.get("source_queue_id"):
            store.mark_message_queue_submitted(queue_id=str(kwargs["source_queue_id"]), task_id=str(task["task_id"]))
        return {
            "accepted": True,
            "topic_id": task["topic_id"],
            "task_id": task["task_id"],
            "task_status": task["task_status"],
            "user_message_id": user_message["message_id"],
            "assistant_message_id": assistant_message["message_id"],
        }

    return _submit_message_task


def _build_client(monkeypatch):
    store = _FakeStore()
    coordinator = _FakeCoordinator()
    submit_calls: list[dict] = []

    monkeypatch.setattr(routes, "get_topic_task_store", lambda: store)
    monkeypatch.setattr(routes, "get_task_coordinator", lambda: coordinator)
    monkeypatch.setattr(routes, "get_agent_profile", lambda agent_id: DEFAULT_AGENT if agent_id == "agent_default" else None)
    monkeypatch.setattr(routes, "submit_message_task", _submit_message_task_factory(store, submit_calls))
    monkeypatch.setattr(routes, "compute_next_run_at", lambda cron_expr, timezone: datetime(2026, 3, 23, 4, 0, 0))

    monkeypatch.setattr(main, "get_topic_task_store", lambda: store)
    monkeypatch.setattr(main, "get_task_coordinator", lambda: coordinator)
    monkeypatch.setattr(main, "get_skill_admin_store", lambda: SimpleNamespace(init_schema=lambda: None))
    monkeypatch.setattr(main, "bootstrap_admin_settings", lambda: None)
    monkeypatch.setattr(main, "bootstrap_default_agent_profile", lambda: DEFAULT_AGENT)
    monkeypatch.setattr(main, "reindex_documents_from_disk", lambda: [])

    return TestClient(main.app), store, coordinator, submit_calls


def test_topics_tasks_and_legacy_routes(monkeypatch):
    client, store, coordinator, submit_calls = _build_client(monkeypatch)
    with client:
        created = client.post("/api/v1/nl2sql/topics", json={"title": "智能问数测试话题"})
        assert created.status_code == 200
        topic_id = created.json()["topic_id"]

        listed = client.get("/api/v1/nl2sql/topics")
        assert listed.status_code == 200
        assert listed.json()[0]["topic_id"] == topic_id

        detail = client.get(f"/api/v1/nl2sql/topics/{topic_id}")
        assert detail.status_code == 200
        assert "messages" not in detail.json()

        page = client.get(f"/api/v1/nl2sql/topics/{topic_id}/messages", params={"page": 1, "page_size": 50, "order": "asc"})
        assert page.status_code == 200
        assert page.json()["items"] == []

        delivered = client.post(
            "/api/v1/nl2sql/tasks/deliver-message",
            json={
                "topic_id": topic_id,
                "content": "最近 30 天工作流发布次数趋势",
                "provider_id": "openrouter",
                "model": "anthropic/claude-sonnet-4.5",
                "execution_mode": "auto",
            },
        )
        assert delivered.status_code == 200
        payload = delivered.json()
        task_id = payload["task_id"]
        assert payload["accepted"] is True
        assert payload["topic_id"] == topic_id
        assert payload["task_status"] == "waiting"
        assert len(store.topic_messages[topic_id]) == 2

        history = client.get(f"/api/v1/nl2sql/topics/{topic_id}/messages", params={"page": 1, "page_size": 50, "order": "asc"})
        assert history.status_code == 200
        assert history.json()["total"] == 2
        assert history.json()["items"][0]["sender_type"] == "user"
        assert history.json()["items"][1]["sender_type"] == "assistant"
        assert history.json()["items"][1]["blocks"] == []
        assert history.json()["items"][1]["resume_after_seq"] == 0
        assistant_message_id = history.json()["items"][1]["message_id"]

        feedback = client.put(
            f"/api/v1/nl2sql/topics/{topic_id}/messages/{assistant_message_id}/feedback",
            json={"feedback": "like"},
        )
        assert feedback.status_code == 200
        assert feedback.json()["message_id"] == assistant_message_id
        assert feedback.json()["feedback"] == "like"

        hydrated_feedback = client.get(f"/api/v1/nl2sql/topics/{topic_id}/messages", params={"page": 1, "page_size": 50, "order": "asc"})
        assert hydrated_feedback.status_code == 200
        assert hydrated_feedback.json()["items"][1]["feedback"] == "like"

        clear_feedback = client.put(
            f"/api/v1/nl2sql/topics/{topic_id}/messages/{assistant_message_id}/feedback",
            json={"feedback": ""},
        )
        assert clear_feedback.status_code == 200
        assert clear_feedback.json()["feedback"] == ""

        invalid_feedback = client.put(
            f"/api/v1/nl2sql/topics/{topic_id}/messages/{assistant_message_id}/feedback",
            json={"feedback": "star"},
        )
        assert invalid_feedback.status_code == 400

        user_message_id = history.json()["items"][0]["message_id"]
        user_feedback = client.put(
            f"/api/v1/nl2sql/topics/{topic_id}/messages/{user_message_id}/feedback",
            json={"feedback": "like"},
        )
        assert user_feedback.status_code == 400

        created_task = client.post(
            "/api/v1/nl2sql/tasks",
            json={
                "topic_id": topic_id,
                "message_type": "text",
                "message_content": "第二个问题",
                "provider_id": "openrouter",
                "model": "anthropic/claude-sonnet-4.5",
                "execution_mode": "background",
            },
        )
        assert created_task.status_code == 200
        assert len(submit_calls) == 2
        assert submit_calls[0]["message_content"] == "最近 30 天工作流发布次数趋势"
        assert submit_calls[1]["message_content"] == "第二个问题"

        task = client.get(f"/api/v1/nl2sql/tasks/{task_id}")
        assert task.status_code == 200
        assert task.json()["task_id"] == task_id
        assert task.json()["task_status"] == "waiting"

        events = client.get(f"/api/v1/nl2sql/tasks/{task_id}/events", params={"after_seq": 0})
        assert events.status_code == 200
        assert events.json()["events"][0]["event_type"] == "BEFORE_AGENT_REPLY"
        assert events.json()["events"][0]["content_type"] == "content"

        cancelled = client.post(f"/api/v1/nl2sql/tasks/{task_id}/cancel")
        assert cancelled.status_code == 200
        assert cancelled.json()["task_status"] == "suspended"
        assert coordinator.cancelled == [task_id]

        assert client.get("/api/v1/nl2sql/settings").status_code == 404
        assert client.post(
            f"/api/v1/nl2sql/topics/{topic_id}/messages",
            json={"content": "旧接口"},
        ).status_code == 405

        deleted = client.delete(f"/api/v1/nl2sql/topics/{topic_id}")
        assert deleted.status_code == 200
        assert store.get_topic(topic_id) is None


def test_followup_suggestions_route_generates_without_changing_message_contract(monkeypatch):
    client, store, _coordinator, _submit_calls = _build_client(monkeypatch)
    captured: dict = {}

    async def fake_generate_followup_suggestions(**kwargs):
        captured.update(kwargs)
        return {
            "suggestions": ["查看异常波动对应的明细", "按业务维度拆解这个趋势"],
            "source": "generated",
        }

    monkeypatch.setattr(routes, "generate_followup_suggestions", fake_generate_followup_suggestions, raising=False)

    with client:
        topic_id = client.post("/api/v1/nl2sql/topics", json={"title": "追问测试"}).json()["topic_id"]
        delivered = client.post(
            "/api/v1/nl2sql/tasks/deliver-message",
            json={
                "topic_id": topic_id,
                "content": "最近 30 天工作流发布次数趋势",
                "provider_id": "openrouter",
                "model": "anthropic/claude-sonnet-4.5",
            },
        )
        task_id = delivered.json()["task_id"]
        assistant_message_id = delivered.json()["assistant_message_id"]

        assistant = store.get_assistant_message(task_id)
        assistant["status"] = "finished"
        assistant["content"] = "最近 30 天工作流发布次数整体上升，5 月 20 日出现异常峰值。"

        response = client.post(f"/api/v1/nl2sql/topics/{topic_id}/messages/{assistant_message_id}/followup-suggestions")

        assert response.status_code == 200
        assert response.json() == {
            "topic_id": topic_id,
            "message_id": assistant_message_id,
            "suggestions": ["查看异常波动对应的明细", "按业务维度拆解这个趋势"],
            "source": "generated",
        }
        assert captured["previous_question"] == "最近 30 天工作流发布次数趋势"
        assert captured["answer_text"] == "最近 30 天工作流发布次数整体上升，5 月 20 日出现异常峰值。"
        assert captured["provider_id"] == "openrouter"
        assert captured["model"] == "anthropic/claude-sonnet-4.5"
        assert store.get_message_contexts[-1] == {
            "source": "portal",
            "website_id": "",
            "external_user_id": "",
            "visitor_id": "",
        }

        history = client.get(f"/api/v1/nl2sql/topics/{topic_id}/messages", params={"page": 1, "page_size": 50, "order": "asc"})
        assert history.status_code == 200
        assert "followup_suggestions" not in history.json()["items"][1]


def test_followup_suggestions_route_rejects_invalid_message_states(monkeypatch):
    client, store, _coordinator, _submit_calls = _build_client(monkeypatch)
    calls = []

    async def fake_generate_followup_suggestions(**kwargs):
        calls.append(kwargs)
        return {"suggestions": ["不应生成"], "source": "generated"}

    monkeypatch.setattr(routes, "generate_followup_suggestions", fake_generate_followup_suggestions, raising=False)

    with client:
        topic_id = client.post("/api/v1/nl2sql/topics", json={"title": "追问校验"}).json()["topic_id"]
        other_topic_id = client.post("/api/v1/nl2sql/topics", json={"title": "其他话题"}).json()["topic_id"]
        delivered = client.post(
            "/api/v1/nl2sql/tasks/deliver-message",
            json={"topic_id": topic_id, "content": "各数据层表数量对比"},
        )
        user_message_id = delivered.json()["user_message_id"]
        assistant_message_id = delivered.json()["assistant_message_id"]

        assert client.post(f"/api/v1/nl2sql/topics/{topic_id}/messages/missing/followup-suggestions").status_code == 404
        assert client.post(f"/api/v1/nl2sql/topics/{other_topic_id}/messages/{assistant_message_id}/followup-suggestions").status_code == 404
        assert client.post(f"/api/v1/nl2sql/topics/{topic_id}/messages/{user_message_id}/followup-suggestions").status_code == 400
        assert client.post(f"/api/v1/nl2sql/topics/{topic_id}/messages/{assistant_message_id}/followup-suggestions").status_code == 400

        assistant = store.get_assistant_message(delivered.json()["task_id"])
        assistant["status"] = "finished"
        assistant["content"] = ""
        assert client.post(f"/api/v1/nl2sql/topics/{topic_id}/messages/{assistant_message_id}/followup-suggestions").status_code == 400
        assert calls == []


def test_message_queue_and_schedule_routes(monkeypatch):
    client, store, _coordinator, submit_calls = _build_client(monkeypatch)
    with client:
        topic_id = client.post("/api/v1/nl2sql/topics", json={"title": "队列与调度"}).json()["topic_id"]

        queue_created = client.post(
            "/api/v1/nl2sql/message-queue",
            json={
                "topic_id": topic_id,
                "message_type": "text",
                "message_content": "队列消息",
            },
        )
        assert queue_created.status_code == 200
        queue_id = queue_created.json()["queue_id"]
        assert queue_created.json()["status"] == "queued"

        queue_query = client.post("/api/v1/nl2sql/message-queue/queries", json={"topic_id": topic_id, "page": 1, "page_size": 20})
        assert queue_query.status_code == 200
        assert queue_query.json()["total"] == 1
        assert queue_query.json()["list"][0]["queue_id"] == queue_id

        queue_updated = client.put(
            f"/api/v1/nl2sql/message-queue/{queue_id}",
            json={
                "topic_id": topic_id,
                "message_type": "text",
                "message_content": "更新后的队列消息",
            },
        )
        assert queue_updated.status_code == 200
        assert queue_updated.json()["message_content"] == "更新后的队列消息"

        queue_consumed = client.post(f"/api/v1/nl2sql/message-queue/{queue_id}/consume")
        assert queue_consumed.status_code == 200
        assert queue_consumed.json()["accepted"] is True
        assert store.get_message_queue(queue_id)["last_task_id"] == queue_consumed.json()["task_id"]
        assert submit_calls[-1]["source_queue_id"] == queue_id

        second_queue_id = client.post(
            "/api/v1/nl2sql/message-queue",
            json={"topic_id": topic_id, "message_type": "text", "message_content": "待删除消息"},
        ).json()["queue_id"]
        assert client.delete(f"/api/v1/nl2sql/message-queue/{second_queue_id}").status_code == 200

        schedule_created = client.post(
            "/api/v1/nl2sql/message-schedule",
            json={
                "topic_id": topic_id,
                "name": "每五分钟同步",
                "message_type": "text",
                "message_content": "执行调度",
                "cron_expr": "*/5 * * * *",
                "enabled": True,
                "timezone": "Asia/Shanghai",
            },
        )
        assert schedule_created.status_code == 200
        schedule_id = schedule_created.json()["schedule_id"]
        assert schedule_created.json()["next_run_at"].startswith("2026-03-23T04:00:00")

        schedule_updated = client.put(
            f"/api/v1/nl2sql/message-schedule/{schedule_id}",
            json={
                "topic_id": topic_id,
                "name": "每十分钟同步",
                "message_type": "text",
                "message_content": "执行调度更新",
                "cron_expr": "*/10 * * * *",
                "enabled": True,
                "timezone": "Asia/Shanghai",
            },
        )
        assert schedule_updated.status_code == 200
        assert schedule_updated.json()["name"] == "每十分钟同步"

        schedule_detail = client.get(f"/api/v1/nl2sql/message-schedule/{schedule_id}")
        assert schedule_detail.status_code == 200
        assert schedule_detail.json()["schedule_id"] == schedule_id

        store.create_message_schedule_log(schedule_id=schedule_id, status="completed", task_id="task-from-schedule")
        logs = client.post(f"/api/v1/nl2sql/message-schedule/{schedule_id}/logs", json={"page": 1, "page_size": 20})
        assert logs.status_code == 200
        assert logs.json()["total"] == 1
        assert logs.json()["list"][0]["task_id"] == "task-from-schedule"

        assert client.delete(f"/api/v1/nl2sql/message-schedule/{schedule_id}").status_code == 200
