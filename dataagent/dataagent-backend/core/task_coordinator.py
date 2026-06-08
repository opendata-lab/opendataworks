from __future__ import annotations

import asyncio
import logging
import os
import socket
import uuid
from contextlib import suppress
from typing import Any

import redis.asyncio as redis

from config import get_settings
from core.task_submission_service import compute_next_run_at, current_utc_naive, submit_message_task
from core.task_executor import TaskExecutionInput, execute_task_stream
from core.topic_task_store import TopicTaskStore, get_topic_task_store

logger = logging.getLogger(__name__)


class TaskCoordinator:
    def __init__(self, *, store: TopicTaskStore | None = None):
        self.store = store or get_topic_task_store()
        self.settings = get_settings()
        self.instance_id = f"{socket.gethostname()}:{os.getpid()}:{uuid.uuid4().hex[:8]}"
        self._redis: redis.Redis | None = None
        self._queue: asyncio.Queue[str] = asyncio.Queue()
        self._queue_task: asyncio.Task | None = None
        self._recovery_task: asyncio.Task | None = None
        self._schedule_task: asyncio.Task | None = None
        self._active_tasks: dict[str, asyncio.Task] = {}
        self._queued_task_ids: set[str] = set()
        self._closing = False
        self._semaphore = asyncio.Semaphore(max(1, int(self.settings.task_max_concurrency or 4)))

    async def start(self) -> None:
        if self._redis is not None:
            return
        self._redis = redis.Redis(
            host=self.settings.redis_host,
            port=int(self.settings.redis_port or 6379),
            password=self.settings.redis_password or None,
            db=int(self.settings.redis_db or 0),
            decode_responses=True,
        )
        await self._redis.ping()
        self._queue_task = asyncio.create_task(self._queue_loop(), name="dataagent-task-queue")
        self._recovery_task = asyncio.create_task(self._recovery_loop(), name="dataagent-task-recovery")
        self._schedule_task = asyncio.create_task(self._schedule_loop(), name="dataagent-task-schedule")

    async def stop(self) -> None:
        self._closing = True
        for task in (self._queue_task, self._recovery_task, self._schedule_task, *self._active_tasks.values()):
            if task is not None:
                task.cancel()
        for task in (self._queue_task, self._recovery_task, self._schedule_task, *self._active_tasks.values()):
            if task is not None:
                with suppress(asyncio.CancelledError):
                    await task
        self._active_tasks.clear()
        self._queued_task_ids.clear()
        if self._redis is not None:
            await self._redis.aclose()
            self._redis = None

    async def submit_task(self, task_id: str) -> dict[str, Any] | None:
        task = self.store.get_task(task_id)
        if not task:
            return None
        if str(task.get("task_status") or "") != "waiting":
            return task
        if task_id in self._queued_task_ids or task_id in self._active_tasks:
            return task
        if not await self._acquire_lease(task_id):
            return task
        self._queued_task_ids.add(task_id)
        await self._queue.put(task_id)
        return self.store.get_task(task_id) or task

    async def request_cancel(self, task_id: str) -> None:
        if self._redis is None:
            return
        await self._redis.set(self._cancel_key(task_id), "1", ex=max(60, int(self.settings.task_lease_ttl_seconds or 30) * 10))

    async def is_cancel_requested(self, task_id: str) -> bool:
        if self._redis is not None and await self._redis.exists(self._cancel_key(task_id)):
            return True
        return self.store.is_task_cancel_requested(task_id)

    async def _queue_loop(self) -> None:
        while not self._closing:
            task_id = await self._queue.get()
            self._queued_task_ids.discard(task_id)
            if task_id in self._active_tasks:
                continue
            worker = asyncio.create_task(self._run_task(task_id), name=f"dataagent-task-{task_id}")
            self._active_tasks[task_id] = worker
            worker.add_done_callback(lambda done, current_task_id=task_id: self._active_tasks.pop(current_task_id, None))

    async def _run_task(self, task_id: str) -> None:
        async with self._semaphore:
            task = self.store.get_task(task_id)
            if not task:
                await self._release_lease(task_id)
                return
            if str(task.get("task_status") or "") not in {"waiting", "running"}:
                await self._release_lease(task_id)
                return

            topic_id = str(task.get("topic_id") or "")
            resume_session_id = self.store.get_resumable_conversation_id(topic_id)
            self.store.mark_task_running(task_id)
            self.store.ensure_assistant_message(topic_id=topic_id, task_id=task_id, status="running")
            stop_heartbeat = asyncio.Event()
            lease_lost = asyncio.Event()
            heartbeat_task = asyncio.create_task(
                self._heartbeat_loop(task_id, stop_event=stop_heartbeat, lease_lost=lease_lost),
                name=f"dataagent-task-heartbeat-{task_id}",
            )

            try:
                history = self._build_history(topic_id=topic_id, task_id=task_id)
                result = await execute_task_stream(
                    TaskExecutionInput(
                        task_id=task_id,
                        topic_id=topic_id,
                        question=str(task.get("prompt") or ""),
                        history=history,
                        resume_session_id=resume_session_id,
                        provider_id=str(task.get("provider_id") or ""),
                        model=str(task.get("model") or ""),
                        database_hint=str(task.get("database_hint") or "") or None,
                        debug=bool(task.get("debug")),
                        timeout_seconds=int(task.get("timeout_seconds") or 0),
                        sql_read_timeout_seconds=int(task.get("sql_read_timeout_seconds") or 0),
                        sql_write_timeout_seconds=int(task.get("sql_write_timeout_seconds") or 0),
                        agent_snapshot=task.get("agent_snapshot"),
                    ),
                    emit=lambda record: self._persist_emitted_sdk_record(
                        topic_id=topic_id,
                        task_id=task_id,
                        record=record,
                    ),
                    is_cancel_requested=lambda: self._should_stop_task(task_id, lease_lost),
                )
                self.store.update_assistant_message(
                    topic_id=topic_id,
                    task_id=task_id,
                    status=result.task_status,
                    content=result.content,
                    usage=result.usage,
                    error=result.error,
                )
                if result.session_id:
                    self.store.update_topic_conversation_id(topic_id, conversation_id=result.session_id)
                self.store.finish_task(task_id=task_id, task_status=result.task_status, error=result.error)
            except asyncio.CancelledError:
                raise
            except Exception as exc:
                logger.exception("Task execution crashed task_id=%s", task_id)
                error = {"code": "task_execution_failed", "message": str(exc)}
                self.store.update_assistant_message(
                    topic_id=topic_id,
                    task_id=task_id,
                    status="error",
                    content=str(exc),
                    usage=None,
                    error=error,
                )
                self.store.finish_task(task_id=task_id, task_status="error", error=error)
            finally:
                stop_heartbeat.set()
                heartbeat_task.cancel()
                with suppress(asyncio.CancelledError):
                    await heartbeat_task
                await self._release_lease(task_id)
                if self._redis is not None:
                    await self._redis.delete(self._cancel_key(task_id))

    def _persist_emitted_sdk_record(self, *, topic_id: str, task_id: str, record: dict[str, Any]) -> None:
        if not isinstance(record, dict):
            return
        record_type = str(record.get("record_type") or "").strip()
        if record_type not in {"stream", "tool_result", "done", "error"}:
            return
        try:
            turn_index = int(record.get("turn_index") or 0)
        except (TypeError, ValueError):
            turn_index = 0
        raw_event_type = record.get("event_type")
        event_type = str(raw_event_type) if raw_event_type is not None else None
        data = record.get("data")
        if not isinstance(data, dict):
            data = {"value": data}
        self.store.append_sdk_record(
            task_id=task_id,
            topic_id=topic_id,
            turn_index=turn_index,
            record_type=record_type,
            event_type=event_type,
            data=data,
        )

    async def _heartbeat_loop(self, task_id: str, *, stop_event: asyncio.Event, lease_lost: asyncio.Event) -> None:
        interval = max(1, int(self.settings.task_heartbeat_seconds or 5))
        while not stop_event.is_set():
            await asyncio.sleep(interval)
            self.store.heartbeat_task(task_id)
            renewed = await self._renew_lease(task_id)
            if not renewed:
                lease_lost.set()
                return

    async def _should_stop_task(self, task_id: str, lease_lost: asyncio.Event) -> bool:
        if lease_lost.is_set():
            return True
        return await self.is_cancel_requested(task_id)

    def _build_history(self, *, topic_id: str, task_id: str) -> list[dict[str, str]]:
        messages = self.store.list_topic_messages(topic_id)
        history: list[dict[str, str]] = []
        for message in messages:
            sender = str(message.get("sender_type") or "")
            content = str(message.get("content") or "").strip()
            if not content:
                continue
            if str(message.get("task_id") or "") == task_id:
                if sender == "assistant":
                    continue
                if sender == "user":
                    continue
            if sender == "user":
                history.append({"role": "user", "content": content})
            elif sender == "assistant":
                history.append({"role": "assistant", "content": content})
        return history

    async def _recovery_loop(self) -> None:
        interval = max(1, int(self.settings.task_recovery_scan_interval_seconds or 2))
        batch_size = max(1, int(self.settings.task_recovery_batch_size or 20))
        while not self._closing:
            try:
                if await self._acquire_recovery_lock():
                    try:
                        await self._recover_waiting_tasks(batch_size=batch_size)
                        await self._recover_expired_tasks(batch_size=batch_size)
                    finally:
                        await self._release_recovery_lock()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Task recovery loop failed")
            await asyncio.sleep(interval)

    async def _recover_waiting_tasks(self, *, batch_size: int) -> None:
        for task in self.store.list_waiting_tasks(limit=batch_size):
            await self.submit_task(str(task.get("task_id") or ""))

    async def _recover_expired_tasks(self, *, batch_size: int) -> None:
        for task in self.store.list_running_tasks(limit=batch_size):
            task_id = str(task.get("task_id") or "")
            if not task_id:
                continue
            if await self._lease_exists(task_id):
                continue
            replacement = self.store.create_recovery_task(task_id)
            if replacement:
                await self.submit_task(str(replacement.get("task_id") or ""))

    async def _schedule_loop(self) -> None:
        interval = max(5, int(self.settings.schedule_scan_interval_seconds or 10))
        batch_size = max(1, int(self.settings.schedule_scan_batch_size or 10))
        while not self._closing:
            try:
                if await self._acquire_schedule_lock():
                    try:
                        for schedule in self.store.list_due_message_schedules(now_utc=current_utc_naive(), limit=batch_size):
                            await self._fire_schedule(schedule)
                    finally:
                        await self._release_schedule_lock()
            except asyncio.CancelledError:
                raise
            except Exception:
                logger.exception("Task schedule loop failed")
            await asyncio.sleep(interval)

    async def _fire_schedule(self, schedule: dict[str, Any]) -> None:
        schedule_id = str(schedule.get("schedule_id") or "")
        if not schedule_id:
            return
        fired_at = current_utc_naive()
        schedule_log = None
        queue = None
        try:
            next_run_at = compute_next_run_at(
                str(schedule.get("cron_expr") or ""),
                str(schedule.get("timezone") or "Asia/Shanghai"),
                base_utc=fired_at,
            )
            schedule_log = self.store.create_message_schedule_log(schedule_id=schedule_id, status="running", started_at=fired_at)
            queue = self.store.create_message_queue(
                topic_id=str(schedule.get("topic_id") or ""),
                message_type=str(schedule.get("message_type") or "text"),
                message_content=schedule.get("message_content"),
                source_schedule_id=schedule_id,
                source_schedule_log_id=str(schedule_log.get("schedule_log_id") or ""),
            )
            self.store.attach_queue_to_schedule_log(
                schedule_log_id=str(schedule_log.get("schedule_log_id") or ""),
                queue_id=str(queue.get("queue_id") or ""),
            )
            self.store.mark_message_schedule_triggered(
                schedule_id=schedule_id,
                queue_id=str(queue.get("queue_id") or ""),
                fired_at=fired_at,
                next_run_at=next_run_at,
            )
            await submit_message_task(
                topic_id=str(schedule.get("topic_id") or ""),
                message_type=str(schedule.get("message_type") or "text"),
                message_content=schedule.get("message_content"),
                execution_mode="background",
                source_queue_id=str(queue.get("queue_id") or ""),
                source_schedule_id=schedule_id,
                source_schedule_log_id=str(schedule_log.get("schedule_log_id") or ""),
                store=self.store,
                coordinator=self,
            )
        except Exception as exc:
            error_message = str(exc)
            logger.exception("Schedule fire failed schedule_id=%s", schedule_id)
            if queue:
                self.store.mark_message_queue_failed(queue_id=str(queue.get("queue_id") or ""), error_message=error_message)
            if schedule_log:
                self.store.finish_message_schedule_log(
                    schedule_log_id=str(schedule_log.get("schedule_log_id") or ""),
                    status="failed",
                    error_message=error_message,
                )
            self.store.mark_message_schedule_failed(schedule_id=schedule_id, error_message=error_message)

    async def _lease_exists(self, task_id: str) -> bool:
        if self._redis is None:
            return False
        return bool(await self._redis.exists(self._lease_key(task_id)))

    async def _acquire_lease(self, task_id: str) -> bool:
        if self._redis is None:
            raise RuntimeError("TaskCoordinator is not started")
        return bool(
            await self._redis.set(
                self._lease_key(task_id),
                self.instance_id,
                ex=max(5, int(self.settings.task_lease_ttl_seconds or 30)),
                nx=True,
            )
        )

    async def _renew_lease(self, task_id: str) -> bool:
        if self._redis is None:
            return False
        current = await self._redis.get(self._lease_key(task_id))
        if current != self.instance_id:
            return False
        await self._redis.expire(self._lease_key(task_id), max(5, int(self.settings.task_lease_ttl_seconds or 30)))
        return True

    async def _release_lease(self, task_id: str) -> None:
        if self._redis is None:
            return
        current = await self._redis.get(self._lease_key(task_id))
        if current == self.instance_id:
            await self._redis.delete(self._lease_key(task_id))

    async def _acquire_recovery_lock(self) -> bool:
        if self._redis is None:
            return False
        return bool(
            await self._redis.set(
                self._recovery_key(),
                self.instance_id,
                ex=max(5, int(self.settings.task_recovery_scan_interval_seconds or 2) * 3),
                nx=True,
            )
        )

    async def _release_recovery_lock(self) -> None:
        if self._redis is None:
            return
        current = await self._redis.get(self._recovery_key())
        if current == self.instance_id:
            await self._redis.delete(self._recovery_key())

    async def _acquire_schedule_lock(self) -> bool:
        if self._redis is None:
            return False
        return bool(
            await self._redis.set(
                self._schedule_key(),
                self.instance_id,
                ex=max(10, int(self.settings.schedule_lock_ttl_seconds or 60)),
                nx=True,
            )
        )

    async def _release_schedule_lock(self) -> None:
        if self._redis is None:
            return
        current = await self._redis.get(self._schedule_key())
        if current == self.instance_id:
            await self._redis.delete(self._schedule_key())

    def _lease_key(self, task_id: str) -> str:
        return f"da:task:lease:{task_id}"

    def _cancel_key(self, task_id: str) -> str:
        return f"da:task:cancel:{task_id}"

    def _recovery_key(self) -> str:
        return "da:task:recovery:lock"

    def _schedule_key(self) -> str:
        return "da:task:schedule:lock"


_COORDINATOR: TaskCoordinator | None = None


def get_task_coordinator() -> TaskCoordinator:
    global _COORDINATOR
    if _COORDINATOR is None:
        _COORDINATOR = TaskCoordinator()
    return _COORDINATOR
