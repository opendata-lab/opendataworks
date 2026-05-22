from __future__ import annotations

import json
from datetime import datetime, timezone
from typing import Any
from zoneinfo import ZoneInfo

from croniter import croniter

from config import get_settings
from core.skill_admin_service import resolve_runtime_provider_selection
from core.topic_task_store import TopicTaskStore, get_topic_task_store


def current_utc_naive() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def resolve_task_timeouts(execution_mode: str | None) -> dict[str, int]:
    cfg = get_settings()
    mode = str(execution_mode or "").strip().lower()
    is_background = mode in {"background", "auto"}
    if is_background:
        timeout_seconds = int(cfg.agent_background_timeout_seconds or 1800)
        sql_read_timeout_seconds = int(cfg.agent_background_sql_read_timeout_seconds or 900)
    else:
        timeout_seconds = int(cfg.agent_interactive_timeout_seconds or cfg.agent_timeout_seconds or 360)
        sql_read_timeout_seconds = int(cfg.agent_interactive_sql_read_timeout_seconds or 300)
    return {
        "timeout_seconds": timeout_seconds,
        "sql_read_timeout_seconds": sql_read_timeout_seconds,
        "sql_write_timeout_seconds": int(cfg.agent_sql_write_timeout_seconds or 60),
    }


def normalize_message_prompt(message_type: str, message_content: Any) -> str:
    message_kind = str(message_type or "").strip().lower()
    if isinstance(message_content, str):
        return message_content.strip()
    if isinstance(message_content, dict):
        if message_kind in {"text", "chat", "plain_text"}:
            for key in ("content", "text", "value", "question"):
                text = str(message_content.get(key) or "").strip()
                if text:
                    return text
        return json.dumps(message_content, ensure_ascii=False)
    if isinstance(message_content, list):
        return json.dumps(message_content, ensure_ascii=False)
    return str(message_content or "").strip()


def compute_next_run_at(cron_expr: str, timezone_name: str, *, base_utc: datetime | None = None) -> datetime:
    base = base_utc or current_utc_naive()
    local_tz = ZoneInfo(timezone_name or "Asia/Shanghai")
    localized = base.replace(tzinfo=timezone.utc).astimezone(local_tz)
    next_local = croniter(cron_expr, localized).get_next(datetime)
    if next_local.tzinfo is None:
        next_local = next_local.replace(tzinfo=local_tz)
    return next_local.astimezone(timezone.utc).replace(tzinfo=None)


async def submit_message_task(
    *,
    topic_id: str,
    message_type: str,
    message_content: Any,
    agent_id: str | None = None,
    provider_id: str | None = None,
    model: str | None = None,
    database_hint: str | None = None,
    debug: bool = False,
    execution_mode: str | None = None,
    source_queue_id: str | None = None,
    source_schedule_id: str | None = None,
    source_schedule_log_id: str | None = None,
    store: TopicTaskStore | None = None,
    coordinator: Any | None = None,
) -> dict[str, Any]:
    store = store or get_topic_task_store()
    if coordinator is None:
        from core.task_coordinator import get_task_coordinator

        coordinator = get_task_coordinator()
    prompt = normalize_message_prompt(message_type, message_content)
    if not prompt:
        raise ValueError("message_content is required")
    topic = store.get_topic(topic_id)
    if not topic:
        raise ValueError("topic not found")
    bound_agent_id = str(topic.get("agent_id") or "").strip()
    requested_agent_id = str(agent_id or "").strip()
    if requested_agent_id and bound_agent_id and requested_agent_id != bound_agent_id:
        raise ValueError("agent_id does not match topic agent")

    runtime_target = resolve_runtime_provider_selection(provider_id, model)
    timeouts = resolve_task_timeouts(execution_mode)
    task = store.create_task(
        topic_id=topic_id,
        prompt=prompt,
        provider_id=str(runtime_target.get("provider_id") or ""),
        model=str(runtime_target.get("model") or ""),
        database_hint=str(database_hint or "").strip() or None,
        debug=bool(debug),
        timeout_seconds=int(timeouts["timeout_seconds"]),
        sql_read_timeout_seconds=int(timeouts["sql_read_timeout_seconds"]),
        sql_write_timeout_seconds=int(timeouts["sql_write_timeout_seconds"]),
        source_queue_id=source_queue_id,
        source_schedule_id=source_schedule_id,
        source_schedule_log_id=source_schedule_log_id,
    )
    task_id = str(task.get("task_id") or "")
    user_message = store.append_user_message(topic_id=topic_id, task_id=task_id, content=prompt)
    assistant_message = store.ensure_assistant_message(topic_id=topic_id, task_id=task_id, status="waiting")
    if source_queue_id:
        store.mark_message_queue_submitted(queue_id=source_queue_id, task_id=task_id)
    task = await coordinator.submit_task(task_id) or task
    return {
        "accepted": True,
        "topic_id": topic_id,
        "task_id": task_id,
        "task_status": str(task.get("task_status") or "waiting"),
        "user_message_id": str(user_message.get("message_id") or ""),
        "assistant_message_id": str(assistant_message.get("message_id") or ""),
    }
