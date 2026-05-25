from __future__ import annotations

import logging
import json
from typing import AsyncIterator

import anyio
from fastapi import APIRouter, HTTPException, Query, Request
from fastapi.responses import JSONResponse, StreamingResponse

from config import get_settings
from core.agent_profile_service import DEFAULT_AGENT_ID, build_agent_snapshot, get_agent_profile
from core.magic_events import TERMINAL_TASK_STATUSES, encode_sse
from core.skill_admin_service import resolved_chat_settings_payload
from core.task_coordinator import get_task_coordinator
from core.task_submission_service import compute_next_run_at, current_utc_naive, submit_message_task
from core.topic_task_store import get_topic_task_store
from models.schemas import (
    CancelTaskResponse,
    CreateTaskRequest,
    CreateTopicRequest,
    DeliverMessageRequest,
    MessageQueuePageResponse,
    MessageQueueQueryRequest,
    MessageQueueRecord,
    MessageQueueUpsertRequest,
    MessageScheduleLogPageResponse,
    MessageScheduleLogsQueryRequest,
    MessageSchedulePageResponse,
    MessageScheduleQueryRequest,
    MessageScheduleRecord,
    MessageScheduleUpsertRequest,
    RuntimeConfigResponse,
    RuntimeProviderConfig,
    TaskEventPageResponse,
    TaskEventRecord,
    TaskStatusResponse,
    TaskSubmissionResponse,
    TopicDetail,
    TopicMessage,
    TopicMessagePageResponse,
    TopicSummary,
    UpdateMessageFeedbackRequest,
    UpdateTopicRequest,
)

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api/v1/nl2sql")
topic_router = APIRouter(prefix="/topics")
task_router = APIRouter(prefix="/tasks")
queue_router = APIRouter(prefix="/message-queue")
schedule_router = APIRouter(prefix="/message-schedule")


def _clean_header(value: str | None, max_length: int = 255) -> str:
    text = str(value or "").strip()
    if len(text) > max_length:
        return text[:max_length]
    return text


def _allowed_widget_sites() -> list[dict]:
    cfg = get_settings()
    raw = str(getattr(cfg, "widget_allowed_sites_json", "") or "[]").strip() or "[]"
    try:
        parsed = json.loads(raw)
    except Exception:
        logger.warning("Invalid WIDGET_ALLOWED_SITES_JSON; widget requests will be rejected")
        return []
    if not isinstance(parsed, list):
        return []
    return [item for item in parsed if isinstance(item, dict)]


def _origin_allowed(origin: str, allowed_origins: list[str]) -> bool:
    normalized_origin = str(origin or "").strip().rstrip("/")
    normalized_allowed = [str(item or "").strip().rstrip("/") for item in allowed_origins]
    if "*" in normalized_allowed:
        return True
    if not normalized_origin:
        return False
    return normalized_origin in normalized_allowed


def _request_context(request: Request) -> dict[str, str]:
    client = _clean_header(request.headers.get("X-ODW-Client"), 32).lower()
    if client != "widget":
        return {
            "source": "portal",
            "website_id": "",
            "external_user_id": "",
            "visitor_id": "",
        }

    website_id = _clean_header(request.headers.get("X-ODW-Website-Id"), 128)
    external_user_id = _clean_header(request.headers.get("X-ODW-User-Id"), 255)
    visitor_id = _clean_header(request.headers.get("X-ODW-Visitor-Id"), 128)
    if not website_id:
        raise HTTPException(status_code=400, detail="X-ODW-Website-Id is required")
    if not external_user_id and not visitor_id:
        raise HTTPException(status_code=400, detail="X-ODW-User-Id or X-ODW-Visitor-Id is required")

    matched_site = None
    for site in _allowed_widget_sites():
        if str(site.get("website_id") or "").strip() == website_id:
            matched_site = site
            break
    if not matched_site:
        raise HTTPException(status_code=403, detail="Widget site is not allowed")

    allowed_origins = matched_site.get("allowed_origins") or []
    if not isinstance(allowed_origins, list):
        allowed_origins = []
    if not _origin_allowed(request.headers.get("Origin") or "", allowed_origins):
        raise HTTPException(status_code=403, detail="Widget origin is not allowed")

    return {
        "source": "widget",
        "website_id": website_id,
        "external_user_id": external_user_id,
        "visitor_id": "" if external_user_id else visitor_id,
    }


@router.get("/health")
async def api_health():
    cfg = get_settings()
    return {
        "status": "ok",
        "provider_id": cfg.llm_provider,
        "model": cfg.claude_model,
        "skills_output_dir": cfg.skills_output_dir,
        "redis_host": cfg.redis_host,
        "redis_port": cfg.redis_port,
    }


@router.get("/runtime-config", response_model=RuntimeConfigResponse)
async def api_runtime_config():
    payload = resolved_chat_settings_payload()
    providers = []
    for item in payload.get("providers") or []:
        providers.append(
            RuntimeProviderConfig(
                provider_id=str(item.get("provider_id") or ""),
                display_name=str(item.get("display_name") or ""),
                provider_group=str(item.get("provider_group") or ""),
                models=list(item.get("models") or []),
                default_model=str(item.get("default_model") or ""),
                enabled=bool(item.get("enabled")),
                provider_enabled=bool(item.get("provider_enabled")),
                supports_partial_messages=bool(item.get("supports_partial_messages", True)),
                validation_status=str(item.get("validation_status") or "unverified"),
                validation_message=str(item.get("validation_message") or ""),
            )
        )

    return RuntimeConfigResponse(
        default_provider_id=str(payload.get("default_provider_id") or ""),
        default_model=str(payload.get("default_model") or ""),
        providers=providers,
    )


@topic_router.post("", response_model=TopicDetail)
async def api_create_topic(http_request: Request, request: CreateTopicRequest | None = None):
    store = _get_store()
    context = _request_context(http_request)
    payload = request or CreateTopicRequest()
    if context.get("source") == "widget" and not str(payload.agent_id or "").strip():
        raise HTTPException(status_code=400, detail="agent_id is required for widget requests")
    profile = _require_agent_profile(payload.agent_id)
    topic = store.create_topic(
        title=str(payload.title or "").strip() or "新话题",
        agent_snapshot=build_agent_snapshot(profile),
        context=context,
    )
    return TopicDetail.model_validate(topic)


@topic_router.get("", response_model=list[TopicSummary])
async def api_list_topics(request: Request, agent_id: str | None = Query(default=None)):
    store = _get_store()
    topics = store.list_topics(include_messages=False, context=_request_context(request), agent_id=agent_id)
    return [TopicSummary.model_validate(item) for item in topics]


@topic_router.get("/{topic_id}", response_model=TopicDetail)
async def api_get_topic(topic_id: str, request: Request):
    topic = _require_topic(topic_id, _request_context(request))
    return TopicDetail.model_validate(topic)


@topic_router.put("/{topic_id}", response_model=TopicDetail)
async def api_update_topic(topic_id: str, payload: UpdateTopicRequest, request: Request):
    context = _request_context(request)
    title = str(payload.title or "").strip()
    if not title:
        raise HTTPException(status_code=400, detail="title is required")
    _require_topic(topic_id, context)
    topic = _get_store().update_topic(topic_id, title=title, context=context)
    if not topic:
        raise HTTPException(status_code=404, detail="Topic not found")
    return TopicDetail.model_validate(topic)


@topic_router.delete("/{topic_id}")
async def api_delete_topic(topic_id: str, request: Request):
    context = _request_context(request)
    store = _get_store()
    _require_topic(topic_id, context)
    store.delete_topic(topic_id, context=context)
    return JSONResponse({"status": "ok"})


@topic_router.get("/{topic_id}/messages", response_model=TopicMessagePageResponse)
async def api_list_topic_messages(
    topic_id: str,
    request: Request,
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=200, ge=1, le=500),
    order: str = Query(default="asc", pattern="^(asc|desc)$"),
):
    context = _request_context(request)
    _require_topic(topic_id, context)
    payload = _get_store().list_topic_messages_page(topic_id=topic_id, page=page, page_size=page_size, order=order, context=context)
    return TopicMessagePageResponse.model_validate(payload)


@topic_router.put("/{topic_id}/messages/{message_id}/feedback", response_model=TopicMessage)
async def api_update_message_feedback(
    topic_id: str,
    message_id: str,
    payload: UpdateMessageFeedbackRequest,
    request: Request,
):
    context = _request_context(request)
    _require_topic(topic_id, context)

    feedback = str(payload.feedback or "").strip().lower()
    if feedback not in {"", "like", "dislike"}:
        raise HTTPException(status_code=400, detail="feedback must be like, dislike, or empty")

    store = _get_store()
    message = store.get_message(message_id)
    if not message or str(message.get("topic_id") or "") != topic_id or not message.get("show_in_ui", True):
        raise HTTPException(status_code=404, detail="Message not found")
    if str(message.get("sender_type") or "") != "assistant":
        raise HTTPException(status_code=400, detail="feedback is only supported for assistant messages")

    updated = store.update_message_feedback(topic_id=topic_id, message_id=message_id, feedback=feedback, context=context)
    if not updated:
        raise HTTPException(status_code=404, detail="Message not found")
    return TopicMessage.model_validate(updated)


@task_router.post("/deliver-message", response_model=TaskSubmissionResponse)
async def api_deliver_message(payload: DeliverMessageRequest, request: Request):
    context = _request_context(request)
    topic_id = str(payload.topic_id or "").strip()
    content = str(payload.content or "").strip()
    if not topic_id:
        raise HTTPException(status_code=400, detail="topic_id is required")
    if not content:
        raise HTTPException(status_code=400, detail="content is required")
    _require_topic(topic_id, context)
    try:
        submitted = await submit_message_task(
            topic_id=topic_id,
            message_type="text",
            message_content=content,
            provider_id=payload.provider_id,
            model=payload.model,
            database_hint=payload.database,
            debug=bool(payload.debug),
            execution_mode=payload.execution_mode,
            agent_id=payload.agent_id,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return TaskSubmissionResponse.model_validate(submitted)


@task_router.post("", response_model=TaskSubmissionResponse)
async def api_create_task(payload: CreateTaskRequest, request: Request):
    context = _request_context(request)
    topic_id = str(payload.topic_id or "").strip()
    if not topic_id:
        raise HTTPException(status_code=400, detail="topic_id is required")
    _require_topic(topic_id, context)
    try:
        submitted = await submit_message_task(
            topic_id=topic_id,
            message_type=payload.message_type,
            message_content=payload.message_content,
            provider_id=payload.provider_id,
            model=payload.model,
            database_hint=payload.database,
            debug=bool(payload.debug),
            execution_mode=payload.execution_mode,
            agent_id=payload.agent_id,
            source_queue_id=payload.source_queue_id,
            source_schedule_id=payload.source_schedule_id,
            source_schedule_log_id=payload.source_schedule_log_id,
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return TaskSubmissionResponse.model_validate(submitted)


@task_router.get("/{task_id}", response_model=TaskStatusResponse)
async def api_get_task(task_id: str, request: Request):
    store = _get_store()
    task = store.get_task(task_id, context=_request_context(request))
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    return TaskStatusResponse.model_validate(task)


@task_router.get("/{task_id}/events", response_model=TaskEventPageResponse)
async def api_get_task_events(
    task_id: str,
    request: Request,
    after_seq: int = Query(default=0, ge=0),
    limit: int = Query(default=200, ge=1, le=1000),
):
    store = _get_store()
    context = _request_context(request)
    task = store.get_task(task_id, context=context)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    page = store.list_task_events(task_id=task_id, after_seq=after_seq, limit=limit, context=context)
    return TaskEventPageResponse.model_validate(
        {
            "task_id": task_id,
            "task_status": str(page.get("task_status") or task.get("task_status") or "waiting"),
            "after_seq": int(page.get("after_seq") or after_seq),
            "next_after_seq": int(page.get("next_after_seq") or after_seq),
            "has_more": bool(page.get("has_more")),
            "events": [TaskEventRecord.model_validate(item) for item in page.get("events") or []],
        }
    )


@task_router.get("/{task_id}/events/stream")
async def api_stream_task_events(task_id: str, request: Request, after_seq: int = Query(default=0, ge=0)):
    store = _get_store()
    context = _request_context(request)
    task = store.get_task(task_id, context=context)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    return StreamingResponse(
        _stream_task_events(task_id=task_id, after_seq=after_seq, context=context),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        },
    )


@task_router.post("/{task_id}/cancel", response_model=CancelTaskResponse)
async def api_cancel_task(task_id: str, request: Request):
    store = _get_store()
    context = _request_context(request)
    task = store.request_task_cancel(task_id, context=context)
    if not task:
        raise HTTPException(status_code=404, detail="Task not found")
    await get_task_coordinator().request_cancel(task_id)
    task = store.get_task(task_id, context=context) or task
    return CancelTaskResponse.model_validate(task)


@queue_router.post("/queries", response_model=MessageQueuePageResponse)
async def api_query_message_queues(payload: MessageQueueQueryRequest, request: Request):
    context = _request_context(request)
    if payload.topic_id:
        _require_topic(str(payload.topic_id), context)
    page = _get_store().query_message_queues(topic_id=payload.topic_id, page=payload.page, page_size=payload.page_size, context=context)
    return MessageQueuePageResponse.model_validate(
        {
            "page": int(page.get("page") or payload.page),
            "page_size": int(page.get("page_size") or payload.page_size),
            "total": int(page.get("total") or 0),
            "list": [MessageQueueRecord.model_validate(item) for item in page.get("items") or []],
        }
    )


@queue_router.post("", response_model=MessageQueueRecord)
async def api_create_message_queue(payload: MessageQueueUpsertRequest, request: Request):
    context = _request_context(request)
    topic_id = str(payload.topic_id or "").strip()
    if not topic_id:
        raise HTTPException(status_code=400, detail="topic_id is required")
    _require_topic(topic_id, context)
    created = _get_store().create_message_queue(
        topic_id=topic_id,
        message_type=str(payload.message_type or "").strip() or "text",
        message_content=payload.message_content,
    )
    return MessageQueueRecord.model_validate(created)


@queue_router.put("/{queue_id}", response_model=MessageQueueRecord)
async def api_update_message_queue(queue_id: str, payload: MessageQueueUpsertRequest, request: Request):
    context = _request_context(request)
    store = _get_store()
    _require_topic(str(payload.topic_id or "").strip(), context)
    if not store.get_message_queue(queue_id, context=context):
        raise HTTPException(status_code=404, detail="Queue message not found")
    updated = store.update_message_queue(
        queue_id=queue_id,
        topic_id=str(payload.topic_id or "").strip(),
        message_type=str(payload.message_type or "").strip() or "text",
        message_content=payload.message_content,
    )
    if not updated:
        raise HTTPException(status_code=404, detail="Queue message not found")
    return MessageQueueRecord.model_validate(updated)


@queue_router.delete("/{queue_id}")
async def api_delete_message_queue(queue_id: str, request: Request):
    store = _get_store()
    context = _request_context(request)
    if not store.get_message_queue(queue_id, context=context):
        raise HTTPException(status_code=404, detail="Queue message not found")
    deleted = store.delete_message_queue(queue_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Queue message not found")
    return JSONResponse({"status": "ok"})


@queue_router.post("/{queue_id}/consume", response_model=TaskSubmissionResponse)
async def api_consume_message_queue(queue_id: str, request: Request):
    store = _get_store()
    context = _request_context(request)
    queue = store.get_message_queue(queue_id, context=context)
    if not queue:
        raise HTTPException(status_code=404, detail="Queue message not found")
    if str(queue.get("status") or "") == "running":
        raise HTTPException(status_code=409, detail="Queue message is already running")
    try:
        submitted = await submit_message_task(
            topic_id=str(queue.get("topic_id") or ""),
            message_type=str(queue.get("message_type") or "text"),
            message_content=queue.get("message_content"),
            execution_mode="background",
            source_queue_id=str(queue.get("queue_id") or ""),
            source_schedule_id=str(queue.get("source_schedule_id") or "") or None,
            source_schedule_log_id=str(queue.get("source_schedule_log_id") or "") or None,
        )
    except ValueError as exc:
        store.mark_message_queue_failed(queue_id=queue_id, error_message=str(exc))
        raise HTTPException(status_code=400, detail=str(exc)) from exc
    return TaskSubmissionResponse.model_validate(submitted)


@schedule_router.post("/queries", response_model=MessageSchedulePageResponse)
async def api_query_message_schedules(payload: MessageScheduleQueryRequest, request: Request):
    context = _request_context(request)
    if payload.topic_id:
        _require_topic(str(payload.topic_id), context)
    page = _get_store().query_message_schedules(topic_id=payload.topic_id, page=payload.page, page_size=payload.page_size, context=context)
    return MessageSchedulePageResponse.model_validate(
        {
            "page": int(page.get("page") or payload.page),
            "page_size": int(page.get("page_size") or payload.page_size),
            "total": int(page.get("total") or 0),
            "list": [MessageScheduleRecord.model_validate(item) for item in page.get("items") or []],
        }
    )


@schedule_router.post("", response_model=MessageScheduleRecord)
async def api_create_message_schedule(payload: MessageScheduleUpsertRequest, request: Request):
    context = _request_context(request)
    topic_id = str(payload.topic_id or "").strip()
    if not topic_id:
        raise HTTPException(status_code=400, detail="topic_id is required")
    _require_topic(topic_id, context)
    try:
        next_run_at = compute_next_run_at(payload.cron_expr, payload.timezone) if payload.enabled else None
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"invalid cron_expr or timezone: {exc}") from exc
    created = _get_store().create_message_schedule(
        topic_id=topic_id,
        name=str(payload.name or "").strip(),
        message_type=str(payload.message_type or "").strip() or "text",
        message_content=payload.message_content,
        cron_expr=str(payload.cron_expr or "").strip(),
        enabled=bool(payload.enabled),
        timezone=str(payload.timezone or "").strip() or "Asia/Shanghai",
        next_run_at=next_run_at,
    )
    return MessageScheduleRecord.model_validate(created)


@schedule_router.put("/{schedule_id}", response_model=MessageScheduleRecord)
async def api_update_message_schedule(schedule_id: str, payload: MessageScheduleUpsertRequest, request: Request):
    context = _request_context(request)
    store = _get_store()
    topic_id = str(payload.topic_id or "").strip()
    _require_topic(topic_id, context)
    if not store.get_message_schedule(schedule_id, context=context):
        raise HTTPException(status_code=404, detail="Message schedule not found")
    try:
        next_run_at = compute_next_run_at(payload.cron_expr, payload.timezone) if payload.enabled else None
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"invalid cron_expr or timezone: {exc}") from exc
    updated = store.update_message_schedule(
        schedule_id=schedule_id,
        topic_id=topic_id,
        name=str(payload.name or "").strip(),
        message_type=str(payload.message_type or "").strip() or "text",
        message_content=payload.message_content,
        cron_expr=str(payload.cron_expr or "").strip(),
        enabled=bool(payload.enabled),
        timezone=str(payload.timezone or "").strip() or "Asia/Shanghai",
        next_run_at=next_run_at,
    )
    if not updated:
        raise HTTPException(status_code=404, detail="Message schedule not found")
    return MessageScheduleRecord.model_validate(updated)


@schedule_router.delete("/{schedule_id}")
async def api_delete_message_schedule(schedule_id: str, request: Request):
    store = _get_store()
    context = _request_context(request)
    if not store.get_message_schedule(schedule_id, context=context):
        raise HTTPException(status_code=404, detail="Message schedule not found")
    deleted = store.delete_message_schedule(schedule_id)
    if not deleted:
        raise HTTPException(status_code=404, detail="Message schedule not found")
    return JSONResponse({"status": "ok"})


@schedule_router.get("/{schedule_id}", response_model=MessageScheduleRecord)
async def api_get_message_schedule(schedule_id: str, request: Request):
    schedule = _get_store().get_message_schedule(schedule_id, context=_request_context(request))
    if not schedule:
        raise HTTPException(status_code=404, detail="Message schedule not found")
    return MessageScheduleRecord.model_validate(schedule)


@schedule_router.post("/{schedule_id}/logs", response_model=MessageScheduleLogPageResponse)
async def api_list_message_schedule_logs(schedule_id: str, payload: MessageScheduleLogsQueryRequest, request: Request):
    store = _get_store()
    schedule = store.get_message_schedule(schedule_id, context=_request_context(request))
    if not schedule:
        raise HTTPException(status_code=404, detail="Message schedule not found")
    page_payload = store.list_message_schedule_logs(
        schedule_id=schedule_id,
        page=payload.page,
        page_size=payload.page_size,
    )
    return MessageScheduleLogPageResponse.model_validate(
        {
            "schedule_id": schedule_id,
            "page": int(page_payload.get("page") or payload.page),
            "page_size": int(page_payload.get("page_size") or payload.page_size),
            "total": int(page_payload.get("total") or 0),
            "list": page_payload.get("items") or [],
        }
    )


router.include_router(topic_router)
router.include_router(task_router)
router.include_router(queue_router)
router.include_router(schedule_router)


async def _stream_task_events(task_id: str, after_seq: int, context: dict[str, str] | None = None) -> AsyncIterator[str]:
    cfg = get_settings()
    poll_interval = max(1, int(cfg.run_events_stream_poll_interval_seconds or 1))
    ping_seconds = max(5, int(cfg.run_events_stream_ping_seconds or 10))
    next_after_seq = max(0, after_seq)
    since_ping = 0
    store = _get_store()

    while True:
        page = store.list_task_events(task_id=task_id, after_seq=next_after_seq, limit=200, context=context)
        events = list(page.get("events") or [])
        for event in events:
            next_after_seq = max(next_after_seq, int(event.get("seq_id") or 0))
            yield encode_sse(event)
        if events:
            since_ping = 0
        else:
            since_ping += poll_interval
            if since_ping >= ping_seconds:
                yield ": ping\n\n"
                since_ping = 0

        task = store.get_task(task_id, context=context)
        if not task:
            break
        if str(task.get("task_status") or "") in TERMINAL_TASK_STATUSES and not page.get("has_more") and not events:
            break
        await anyio.sleep(poll_interval)


def _get_store():
    store = get_topic_task_store()
    store.init_schema()
    return store


def _require_topic(topic_id: str, context: dict[str, str] | None = None) -> dict:
    store = _get_store()
    topic = store.get_topic(topic_id, context=context)
    if not topic:
        raise HTTPException(status_code=404, detail="Topic not found")
    return topic


def _require_agent_profile(agent_id: str | None = None) -> dict:
    profile = get_agent_profile(str(agent_id or "").strip() or DEFAULT_AGENT_ID)
    if not profile:
        raise HTTPException(status_code=400, detail="agent not found")
    return profile
