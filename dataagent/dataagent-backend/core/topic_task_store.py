from __future__ import annotations

import json
import logging
import threading
import uuid
from datetime import datetime
from typing import Any

import pymysql

from config import get_settings

logger = logging.getLogger(__name__)


def _to_iso(value) -> str:
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value) if value is not None else ""


def _json_default(value):
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    return str(value)


def _safe_json_load(raw: str | None) -> Any:
    if not raw:
        return None
    try:
        return json.loads(raw)
    except Exception:
        return None


def _new_id(prefix: str) -> str:
    return f"{prefix}_{uuid.uuid4().hex[:24]}"


def _is_placeholder_conversation_id(value: str | None) -> bool:
    text = str(value or "").strip()
    if not text:
        return True
    return text.startswith("chat_conv_") or text.startswith("chat_conversation_")


def _append_str(base: str | None, delta: str | None) -> str:
    next_text = str(delta or "")
    if not next_text:
        return str(base or "")
    prev_text = str(base or "")
    if not prev_text:
        return next_text
    if next_text == prev_text:
        return prev_text
    if next_text.startswith(prev_text):
        return next_text
    return f"{prev_text}{next_text}"


def _normalize_tool_status(value: str | None) -> str:
    status = str(value or "").strip().lower()
    if status in {"", "success", "finished", "completed"}:
        return "success"
    if status in {"waiting", "pending"}:
        return "pending"
    if status in {"running", "streaming", "in_progress"}:
        return "streaming"
    if status in {"error", "failed", "cancelled", "canceled", "suspended"}:
        return "failed"
    return status


def _tool_status_from_event(event_type: str, tool_status: str | None) -> str:
    if event_type == "PENDING_TOOL_CALL":
        return "pending"
    if event_type == "BEFORE_TOOL_CALL":
        return "streaming"
    return _normalize_tool_status(tool_status)


def _ensure_projected_block(
    ordered_blocks: list[dict[str, Any]],
    block_map: dict[str, dict[str, Any]],
    *,
    block_id: str,
    block_type: str,
) -> dict[str, Any]:
    if block_id not in block_map:
        block: dict[str, Any] = {
            "block_id": block_id,
            "type": block_type,
            "status": "streaming",
        }
        if block_type in {"thinking", "main_text", "error"}:
            block["text"] = ""
        elif block_type == "tool":
            block["tool_id"] = None
            block["tool_name"] = "Tool"
            block["input"] = None
            block["output"] = None
        ordered_blocks.append(block)
        block_map[block_id] = block
    return block_map[block_id]


def _extract_error_message(record: dict[str, Any]) -> str:
    data = dict(record.get("data") or {})
    error = data.get("error")
    if isinstance(error, dict):
        message = str(error.get("message") or "").strip()
        if message:
            return message
    message = str(data.get("message") or "").strip()
    if message:
        return message
    return "请求失败"


def _project_task_history(records: list[dict[str, Any]], *, fallback_resume_after_seq: int = 0) -> dict[str, Any]:
    ordered_blocks: list[dict[str, Any]] = []
    block_map: dict[str, dict[str, Any]] = {}
    resume_after_seq = max(0, int(fallback_resume_after_seq or 0))

    for record in sorted(records, key=lambda item: int(item.get("seq_id") or 0)):
        seq_id = int(record.get("seq_id") or 0)
        if seq_id > resume_after_seq:
            resume_after_seq = seq_id

        record_type = str(record.get("record_type") or "").strip()
        if record_type == "chunk":
            metadata = dict(record.get("metadata") or {})
            delta = dict(record.get("delta") or {})
            correlation_id = str(metadata.get("correlation_id") or "").strip()
            content_type = str(metadata.get("content_type") or "").strip()
            if not correlation_id or content_type not in {"reasoning", "content"}:
                continue
            block_type = "thinking" if content_type == "reasoning" else "main_text"
            block = _ensure_projected_block(
                ordered_blocks,
                block_map,
                block_id=f"{content_type}:{correlation_id}",
                block_type=block_type,
            )
            chunk_text = str(record.get("content") or "")
            delta_status = str(delta.get("status") or "").strip().upper()
            block["status"] = "success" if delta_status == "END" else "streaming"
            block["text"] = chunk_text if delta_status == "END" else _append_str(block.get("text"), chunk_text)
            continue

        if record_type != "event":
            continue

        event_type = str(record.get("event_type") or "").strip()
        correlation_id = str(record.get("correlation_id") or "").strip()
        content_type = str(record.get("content_type") or "").strip()
        data = dict(record.get("data") or {})

        if event_type == "BEFORE_AGENT_REPLY" and correlation_id and content_type in {"reasoning", "content"}:
            block_type = "thinking" if content_type == "reasoning" else "main_text"
            _ensure_projected_block(
                ordered_blocks,
                block_map,
                block_id=f"{content_type}:{correlation_id}",
                block_type=block_type,
            )["status"] = "streaming"
            continue

        if event_type == "AFTER_AGENT_REPLY" and correlation_id and content_type in {"reasoning", "content"}:
            block = block_map.get(f"{content_type}:{correlation_id}")
            if block and str(block.get("status") or "") != "failed":
                block["status"] = "success"
            continue

        if event_type in {"PENDING_TOOL_CALL", "BEFORE_TOOL_CALL", "AFTER_TOOL_CALL"}:
            tool = data.get("tool")
            if not isinstance(tool, dict):
                tool = {}
            tool_id = str(tool.get("id") or correlation_id or "").strip() or f"tool:{seq_id}"
            block = _ensure_projected_block(
                ordered_blocks,
                block_map,
                block_id=f"tool:{tool_id}",
                block_type="tool",
            )
            block["tool_id"] = tool_id
            block["tool_name"] = str(tool.get("name") or block.get("tool_name") or "Tool")
            if "input" in tool:
                block["input"] = tool.get("input")
            if "output" in tool:
                block["output"] = tool.get("output")
            block["status"] = _tool_status_from_event(event_type, str(tool.get("status") or ""))
            continue

        if event_type in {"ERROR", "AGENT_SUSPENDED"}:
            message = _extract_error_message(record)
            block = _ensure_projected_block(
                ordered_blocks,
                block_map,
                block_id=f"error:{seq_id}",
                block_type="error",
            )
            block["status"] = "failed"
            block["text"] = message

    return {
        "blocks": ordered_blocks,
        "resume_after_seq": resume_after_seq,
    }


class TopicTaskStore:
    def __init__(self):
        self._ready = False
        self._ready_lock = threading.Lock()

    def _connect(self, database: str | None):
        cfg = get_settings()
        return pymysql.connect(
            host=cfg.mysql_host,
            port=cfg.mysql_port,
            user=cfg.mysql_user,
            password=cfg.mysql_password,
            database=database,
            charset="utf8mb4",
            cursorclass=pymysql.cursors.DictCursor,
            autocommit=False,
        )

    def _schema_name(self) -> str:
        cfg = get_settings()
        return cfg.session_mysql_database

    def init_schema(self):
        if self._ready:
            return
        with self._ready_lock:
            if self._ready:
                return
            self._ready = True
            logger.info("Topic/task store is ready; schema is expected to be managed by Alembic")

    def _ensure_ready(self):
        if not self._ready:
            self.init_schema()

    def _normalize_context(self, context: dict[str, Any] | None = None) -> dict[str, str]:
        source = str((context or {}).get("source") or "portal").strip().lower()
        if source != "widget":
            return {
                "source": "portal",
                "website_id": "",
                "external_user_id": "",
                "visitor_id": "",
            }

        website_id = str((context or {}).get("website_id") or "").strip()
        external_user_id = str((context or {}).get("external_user_id") or "").strip()
        visitor_id = str((context or {}).get("visitor_id") or "").strip()
        return {
            "source": "widget",
            "website_id": website_id,
            "external_user_id": external_user_id,
            "visitor_id": "" if external_user_id else visitor_id,
        }

    def _topic_context_predicate(self, context: dict[str, Any] | None = None, *, alias: str = "t") -> tuple[str, list[str]]:
        if context is None:
            return "1 = 1", []

        normalized = self._normalize_context(context)
        prefix = f"{alias}." if alias else ""
        if normalized["source"] != "widget":
            return f"COALESCE({prefix}source, 'portal') = %s", ["portal"]

        if normalized["external_user_id"]:
            return (
                f"{prefix}source = %s AND {prefix}website_id = %s AND {prefix}external_user_id = %s",
                ["widget", normalized["website_id"], normalized["external_user_id"]],
            )

        return (
            f"{prefix}source = %s AND {prefix}website_id = %s AND {prefix}external_user_id = '' AND {prefix}visitor_id = %s",
            ["widget", normalized["website_id"], normalized["visitor_id"]],
        )

    def _next_topic_seq(self, cur, topic_id: str) -> int:
        cur.execute(
            """
            UPDATE da_agent_topic
            SET last_message_seq = last_message_seq + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE topic_id = %s
            """,
            (topic_id,),
        )
        cur.execute("SELECT last_message_seq FROM da_agent_topic WHERE topic_id = %s LIMIT 1", (topic_id,))
        row = cur.fetchone() or {}
        return int(row.get("last_message_seq") or 0)

    def _next_task_seq(self, cur, task_id: str) -> int:
        cur.execute(
            """
            UPDATE da_agent_task
            SET last_event_seq = last_event_seq + 1,
                heartbeat_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE task_id = %s
            """,
            (task_id,),
        )
        cur.execute("SELECT last_event_seq FROM da_agent_task WHERE task_id = %s LIMIT 1", (task_id,))
        row = cur.fetchone() or {}
        return int(row.get("last_event_seq") or 0)

    def create_topic(self, *, title: str, context: dict[str, Any] | None = None) -> dict[str, Any]:
        self._ensure_ready()
        normalized_context = self._normalize_context(context)
        topic_id = _new_id("topic")
        chat_topic_id = _new_id("chat_topic")
        chat_conversation_id = _new_id("chat_conv")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_agent_topic (
                        topic_id, title, chat_topic_id, chat_conversation_id,
                        current_task_id, current_task_status, last_message_seq,
                        source, website_id, external_user_id, visitor_id
                    ) VALUES (%s, %s, %s, %s, NULL, NULL, 0, %s, %s, %s, %s)
                    """,
                    (
                        topic_id,
                        title or "新话题",
                        chat_topic_id,
                        chat_conversation_id,
                        normalized_context["source"],
                        normalized_context["website_id"],
                        normalized_context["external_user_id"],
                        normalized_context["visitor_id"],
                    ),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_topic(topic_id, context=normalized_context) or {}

    def list_topics(self, include_messages: bool = False, context: dict[str, Any] | None = None) -> list[dict[str, Any]]:
        self._ensure_ready()
        context_sql, context_params = self._topic_context_predicate(context, alias="t")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    f"""
                    SELECT t.topic_id, t.title, t.chat_topic_id, t.chat_conversation_id,
                           t.current_task_id, t.current_task_status, t.source, t.website_id,
                           t.external_user_id, t.visitor_id, t.created_at, t.updated_at,
                           COALESCE(stats.message_count, 0) AS message_count,
                           COALESCE(stats.last_message_preview, '') AS last_message_preview
                    FROM da_agent_topic t
                    LEFT JOIN (
                        SELECT topic_id,
                               COUNT(*) AS message_count,
                               SUBSTRING_INDEX(
                                   GROUP_CONCAT(COALESCE(NULLIF(content, ''), '') ORDER BY seq_id DESC SEPARATOR '\n'),
                                   '\n',
                                   1
                               ) AS last_message_preview
                        FROM da_agent_message
                        WHERE show_in_ui = 1
                        GROUP BY topic_id
                    ) stats ON stats.topic_id = t.topic_id
                    WHERE {context_sql}
                    ORDER BY t.updated_at DESC, t.created_at DESC
                    """,
                    context_params,
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()

        result = [self._normalize_topic_row(row, include_messages=False) for row in rows]
        if include_messages:
            for item in result:
                item["messages"] = self.list_topic_messages(item["topic_id"])
        return result

    def get_topic(self, topic_id: str, context: dict[str, Any] | None = None) -> dict[str, Any] | None:
        self._ensure_ready()
        context_sql, context_params = self._topic_context_predicate(context, alias="t")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    f"""
                    SELECT t.topic_id, t.title, t.chat_topic_id, t.chat_conversation_id,
                           t.current_task_id, t.current_task_status, t.source, t.website_id,
                           t.external_user_id, t.visitor_id, t.created_at, t.updated_at,
                           COALESCE(stats.message_count, 0) AS message_count,
                           COALESCE(stats.last_message_preview, '') AS last_message_preview
                    FROM da_agent_topic t
                    LEFT JOIN (
                        SELECT topic_id,
                               COUNT(*) AS message_count,
                               SUBSTRING_INDEX(
                                   GROUP_CONCAT(COALESCE(NULLIF(content, ''), '') ORDER BY seq_id DESC SEPARATOR '\n'),
                                   '\n',
                                   1
                               ) AS last_message_preview
                        FROM da_agent_message
                        WHERE show_in_ui = 1
                        GROUP BY topic_id
                    ) stats ON stats.topic_id = t.topic_id
                    WHERE t.topic_id = %s
                      AND {context_sql}
                    LIMIT 1
                    """,
                    [topic_id, *context_params],
                )
                row = cur.fetchone()
        finally:
            conn.close()
        if not row:
            return None
        return self._normalize_topic_row(row, include_messages=False)

    def update_topic(self, topic_id: str, *, title: str, context: dict[str, Any] | None = None) -> dict[str, Any] | None:
        self._ensure_ready()
        if not self.get_topic(topic_id, context=context):
            return None
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_topic
                    SET title = %s,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE topic_id = %s
                    """,
                    (title or "新话题", topic_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_topic(topic_id, context=context)

    def update_topic_conversation_id(self, topic_id: str, *, conversation_id: str) -> dict[str, Any] | None:
        value = str(conversation_id or "").strip()
        if not value:
            return self.get_topic(topic_id)
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_topic
                    SET chat_conversation_id = %s,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE topic_id = %s
                    """,
                    (value, topic_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_topic(topic_id)

    def get_resumable_conversation_id(self, topic_id: str) -> str | None:
        topic = self.get_topic(topic_id)
        if not topic:
            return None
        conversation_id = str(topic.get("chat_conversation_id") or "").strip()
        if _is_placeholder_conversation_id(conversation_id):
            return None
        return conversation_id or None

    def delete_topic(self, topic_id: str, context: dict[str, Any] | None = None):
        self._ensure_ready()
        if not self.get_topic(topic_id, context=context):
            return
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM da_agent_topic WHERE topic_id = %s", (topic_id,))
            conn.commit()
        finally:
            conn.close()

    def list_topic_messages(self, topic_id: str) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT message_id, topic_id, task_id, sender_type, type, status, content, event,
                           steps_json, tool_json, seq_id, correlation_id, parent_correlation_id,
                           content_type, usage_json, error_json, show_in_ui, created_at, updated_at
                    FROM da_agent_message
                    WHERE topic_id = %s AND show_in_ui = 1
                    ORDER BY seq_id ASC, created_at ASC
                    """,
                    (topic_id,),
                )
                rows = cur.fetchall() or []
                history_by_task_id = self._load_task_history_views(conn, self._assistant_task_ids_from_rows(rows))
        finally:
            conn.close()
        return [self._normalize_message_row(row, history=history_by_task_id.get(str(row.get("task_id") or "").strip())) for row in rows]

    def list_topic_messages_page(
        self,
        *,
        topic_id: str,
        page: int = 1,
        page_size: int = 200,
        order: str = "asc",
        context: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        if not self.get_topic(topic_id, context=context):
            return {
                "topic_id": topic_id,
                "page": max(1, int(page or 1)),
                "page_size": max(1, min(500, int(page_size or 200))),
                "order": "desc" if str(order or "").strip().lower() == "desc" else "asc",
                "total": 0,
                "items": [],
            }
        safe_page = max(1, int(page or 1))
        safe_page_size = max(1, min(500, int(page_size or 200)))
        sort_direction = "DESC" if str(order or "").strip().lower() == "desc" else "ASC"
        offset = (safe_page - 1) * safe_page_size
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT COUNT(*) AS total
                    FROM da_agent_message
                    WHERE topic_id = %s AND show_in_ui = 1
                    """,
                    (topic_id,),
                )
                total_row = cur.fetchone() or {}
                cur.execute(
                    f"""
                    SELECT message_id, topic_id, task_id, sender_type, type, status, content, event,
                           steps_json, tool_json, seq_id, correlation_id, parent_correlation_id,
                           content_type, usage_json, error_json, show_in_ui, created_at, updated_at
                    FROM da_agent_message
                    WHERE topic_id = %s AND show_in_ui = 1
                    ORDER BY seq_id {sort_direction}, created_at {sort_direction}
                    LIMIT %s OFFSET %s
                    """,
                    (topic_id, safe_page_size, offset),
                )
                rows = cur.fetchall() or []
                history_by_task_id = self._load_task_history_views(conn, self._assistant_task_ids_from_rows(rows))
        finally:
            conn.close()
        return {
            "topic_id": topic_id,
            "page": safe_page,
            "page_size": safe_page_size,
            "order": "desc" if sort_direction == "DESC" else "asc",
            "total": int(total_row.get("total") or 0),
            "items": [
                self._normalize_message_row(
                    row,
                    history=history_by_task_id.get(str(row.get("task_id") or "").strip()),
                )
                for row in rows
            ],
        }

    def append_user_message(self, *, topic_id: str, task_id: str, content: str) -> dict[str, Any]:
        self._ensure_ready()
        message_id = _new_id("msg")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                seq_id = self._next_topic_seq(cur, topic_id)
                cur.execute(
                    """
                    INSERT INTO da_agent_message (
                        message_id, topic_id, task_id, sender_type, type, status, content, event,
                        steps_json, tool_json, seq_id, correlation_id, parent_correlation_id,
                        content_type, usage_json, error_json, show_in_ui
                    ) VALUES (%s, %s, %s, 'user', 'chat', 'success', %s, '', NULL, NULL, %s, NULL, NULL, NULL, NULL, NULL, 1)
                    """,
                    (message_id, topic_id, task_id, content or "", seq_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message(message_id) or {}

    def ensure_assistant_message(self, *, topic_id: str, task_id: str, status: str) -> dict[str, Any]:
        existing = self.get_assistant_message(task_id)
        if existing:
            if status and existing.get("status") != status:
                self.update_assistant_message(
                    topic_id=topic_id,
                    task_id=task_id,
                    status=status,
                    content=str(existing.get("content") or ""),
                    usage=existing.get("usage"),
                    error=existing.get("error"),
                )
            return self.get_assistant_message(task_id) or existing

        self._ensure_ready()
        message_id = _new_id("msg")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                seq_id = self._next_topic_seq(cur, topic_id)
                cur.execute(
                    """
                    INSERT INTO da_agent_message (
                        message_id, topic_id, task_id, sender_type, type, status, content, event,
                        steps_json, tool_json, seq_id, correlation_id, parent_correlation_id,
                        content_type, usage_json, error_json, show_in_ui
                    ) VALUES (%s, %s, %s, 'assistant', 'assistant', %s, '', '', NULL, NULL, %s, NULL, NULL, NULL, NULL, NULL, 1)
                    """,
                    (message_id, topic_id, task_id, status, seq_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message(message_id) or {}

    def update_assistant_message(
        self,
        *,
        topic_id: str,
        task_id: str,
        status: str,
        content: str,
        usage: dict[str, Any] | None = None,
        error: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        usage_json = json.dumps(usage, ensure_ascii=False, default=_json_default) if usage else None
        error_json = json.dumps(error, ensure_ascii=False, default=_json_default) if error else None
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_message
                    SET status = %s,
                        content = %s,
                        usage_json = %s,
                        error_json = %s,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE topic_id = %s
                      AND task_id = %s
                      AND sender_type = 'assistant'
                      AND show_in_ui = 1
                    ORDER BY seq_id ASC
                    LIMIT 1
                    """,
                    (status, content or "", usage_json, error_json, topic_id, task_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_assistant_message(task_id) or {}

    def get_assistant_message(self, task_id: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT message_id, topic_id, task_id, sender_type, type, status, content, event,
                           steps_json, tool_json, seq_id, correlation_id, parent_correlation_id,
                           content_type, usage_json, error_json, show_in_ui, created_at, updated_at
                    FROM da_agent_message
                    WHERE task_id = %s AND sender_type = 'assistant' AND show_in_ui = 1
                    ORDER BY seq_id ASC
                    LIMIT 1
                    """,
                    (task_id,),
                )
                row = cur.fetchone()
                history_by_task_id = self._load_task_history_views(conn, [task_id]) if row else {}
        finally:
            conn.close()
        return self._normalize_message_row(row, history=history_by_task_id.get(task_id)) if row else None

    def get_message(self, message_id: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT message_id, topic_id, task_id, sender_type, type, status, content, event,
                           steps_json, tool_json, seq_id, correlation_id, parent_correlation_id,
                           content_type, usage_json, error_json, show_in_ui, created_at, updated_at
                    FROM da_agent_message
                    WHERE message_id = %s
                    LIMIT 1
                    """,
                    (message_id,),
                )
                row = cur.fetchone()
                history_by_task_id = {}
                if row and str(row.get("sender_type") or "") == "assistant":
                    task_id = str(row.get("task_id") or "").strip()
                    history_by_task_id = self._load_task_history_views(conn, [task_id]) if task_id else {}
        finally:
            conn.close()
        return (
            self._normalize_message_row(
                row,
                history=history_by_task_id.get(str(row.get("task_id") or "").strip()),
            )
            if row
            else None
        )

    def create_task(
        self,
        *,
        topic_id: str,
        prompt: str,
        provider_id: str,
        model: str,
        database_hint: str | None,
        debug: bool,
        timeout_seconds: int,
        sql_read_timeout_seconds: int,
        sql_write_timeout_seconds: int,
        from_task_id: str | None = None,
        source_queue_id: str | None = None,
        source_schedule_id: str | None = None,
        source_schedule_log_id: str | None = None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        task_id = _new_id("task")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_agent_task (
                        task_id, topic_id, from_task_id, source_queue_id, source_schedule_id, source_schedule_log_id,
                        task_status, prompt, provider_id, model_name,
                        database_hint, debug_enabled, timeout_seconds, sql_read_timeout_seconds,
                        sql_write_timeout_seconds, last_event_seq
                    ) VALUES (%s, %s, %s, %s, %s, %s, 'waiting', %s, %s, %s, %s, %s, %s, %s, %s, 0)
                    """,
                    (
                        task_id,
                        topic_id,
                        from_task_id,
                        source_queue_id,
                        source_schedule_id,
                        source_schedule_log_id,
                        prompt or "",
                        provider_id or "",
                        model or "",
                        database_hint,
                        1 if debug else 0,
                        int(timeout_seconds or 0),
                        int(sql_read_timeout_seconds or 0),
                        int(sql_write_timeout_seconds or 0),
                    ),
                )
                cur.execute(
                    """
                    UPDATE da_agent_topic
                    SET current_task_id = %s,
                        current_task_status = 'waiting',
                        updated_at = CURRENT_TIMESTAMP
                    WHERE topic_id = %s
                    """,
                    (task_id, topic_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_task(task_id) or {}

    def get_task(self, task_id: str, context: dict[str, Any] | None = None) -> dict[str, Any] | None:
        self._ensure_ready()
        context_sql, context_params = self._topic_context_predicate(context, alias="topic")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    f"""
                    SELECT task.task_id, task.topic_id, task.from_task_id, task.source_queue_id,
                           task.source_schedule_id, task.source_schedule_log_id,
                           task.task_status, task.prompt, task.provider_id, task.model_name,
                           task.database_hint, task.debug_enabled, task.timeout_seconds,
                           task.sql_read_timeout_seconds, task.sql_write_timeout_seconds,
                           task.last_event_seq, task.cancel_requested_at, task.started_at,
                           task.heartbeat_at, task.finished_at, task.error_json,
                           task.created_at, task.updated_at
                    FROM da_agent_task task
                    JOIN da_agent_topic topic ON topic.topic_id = task.topic_id
                    WHERE task.task_id = %s
                      AND {context_sql}
                    LIMIT 1
                    """,
                    [task_id, *context_params],
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_task_row(row) if row else None

    def list_waiting_tasks(self, *, limit: int = 20) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT task_id, topic_id, from_task_id, source_queue_id, source_schedule_id, source_schedule_log_id,
                           task_status, prompt, provider_id, model_name,
                           database_hint, debug_enabled, timeout_seconds, sql_read_timeout_seconds,
                           sql_write_timeout_seconds, last_event_seq, cancel_requested_at, started_at,
                           heartbeat_at, finished_at, error_json, created_at, updated_at
                    FROM da_agent_task
                    WHERE task_status = 'waiting'
                      AND finished_at IS NULL
                    ORDER BY created_at ASC
                    LIMIT %s
                    """,
                    (max(1, limit),),
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return [self._normalize_task_row(row) for row in rows]

    def list_running_tasks(self, *, limit: int = 20) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT task_id, topic_id, from_task_id, source_queue_id, source_schedule_id, source_schedule_log_id,
                           task_status, prompt, provider_id, model_name,
                           database_hint, debug_enabled, timeout_seconds, sql_read_timeout_seconds,
                           sql_write_timeout_seconds, last_event_seq, cancel_requested_at, started_at,
                           heartbeat_at, finished_at, error_json, created_at, updated_at
                    FROM da_agent_task
                    WHERE task_status = 'running'
                      AND finished_at IS NULL
                    ORDER BY updated_at ASC
                    LIMIT %s
                    """,
                    (max(1, limit),),
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return [self._normalize_task_row(row) for row in rows]

    def has_active_child_task(self, parent_task_id: str) -> bool:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT 1
                    FROM da_agent_task
                    WHERE from_task_id = %s
                      AND task_status IN ('waiting', 'running')
                      AND finished_at IS NULL
                    LIMIT 1
                    """,
                    (parent_task_id,),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return bool(row)

    def mark_task_running(self, task_id: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_task
                    SET task_status = 'running',
                        started_at = COALESCE(started_at, CURRENT_TIMESTAMP),
                        heartbeat_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE task_id = %s
                    """,
                    (task_id,),
                )
                cur.execute(
                    """
                    UPDATE da_agent_topic t
                    JOIN da_agent_task k ON k.topic_id = t.topic_id
                    SET t.current_task_status = 'running',
                        t.updated_at = CURRENT_TIMESTAMP
                    WHERE k.task_id = %s
                      AND t.current_task_id = k.task_id
                    """,
                    (task_id,),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_task(task_id)

    def heartbeat_task(self, task_id: str):
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_task
                    SET heartbeat_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE task_id = %s
                    """,
                    (task_id,),
                )
            conn.commit()
        finally:
            conn.close()

    def finish_task(self, *, task_id: str, task_status: str, error: dict[str, Any] | None = None) -> dict[str, Any] | None:
        self._ensure_ready()
        error_json = json.dumps(error, ensure_ascii=False, default=_json_default) if error else None
        error_message = str((error or {}).get("message") or "").strip() or None
        downstream_status = "completed" if task_status == "finished" else ("suspended" if task_status == "suspended" else "failed")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_task
                    SET task_status = %s,
                        error_json = %s,
                        heartbeat_at = CURRENT_TIMESTAMP,
                        finished_at = CURRENT_TIMESTAMP,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE task_id = %s
                    """,
                    (task_status, error_json, task_id),
                )
                cur.execute(
                    """
                    UPDATE da_agent_topic t
                    JOIN da_agent_task k ON k.topic_id = t.topic_id
                    SET t.current_task_status = %s,
                        t.updated_at = CURRENT_TIMESTAMP
                    WHERE k.task_id = %s
                      AND t.current_task_id = k.task_id
                    """,
                    (task_status, task_id),
                )
                cur.execute(
                    """
                    SELECT source_queue_id, source_schedule_id, source_schedule_log_id
                    FROM da_agent_task
                    WHERE task_id = %s
                    LIMIT 1
                    """,
                    (task_id,),
                )
                source_row = cur.fetchone() or {}
                source_queue_id = str(source_row.get("source_queue_id") or "") or None
                source_schedule_id = str(source_row.get("source_schedule_id") or "") or None
                source_schedule_log_id = str(source_row.get("source_schedule_log_id") or "") or None

                if source_queue_id:
                    cur.execute(
                        """
                        UPDATE da_agent_message_queue
                        SET status = %s,
                            last_task_id = %s,
                            error_message = %s,
                            consumed_at = COALESCE(consumed_at, CURRENT_TIMESTAMP),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE queue_id = %s
                        """,
                        (downstream_status, task_id, error_message, source_queue_id),
                    )

                if source_schedule_id:
                    cur.execute(
                        """
                        UPDATE da_agent_message_schedule
                        SET last_task_id = %s,
                            last_error_message = %s,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE schedule_id = %s
                        """,
                        (task_id, error_message, source_schedule_id),
                    )

                if source_schedule_log_id:
                    cur.execute(
                        """
                        UPDATE da_agent_message_schedule_log
                        SET task_id = %s,
                            status = %s,
                            error_message = %s,
                            finished_at = CURRENT_TIMESTAMP
                        WHERE schedule_log_id = %s
                        """,
                        (task_id, downstream_status, error_message, source_schedule_log_id),
                    )
            conn.commit()
        finally:
            conn.close()
        return self.get_task(task_id)

    def request_task_cancel(self, task_id: str, context: dict[str, Any] | None = None) -> dict[str, Any] | None:
        self._ensure_ready()
        if not self.get_task(task_id, context=context):
            return None
        cancel_error = {"code": "task_cancelled", "message": "任务已取消"}
        cancel_error_json = json.dumps(cancel_error, ensure_ascii=False, default=_json_default)
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_task
                    SET cancel_requested_at = COALESCE(cancel_requested_at, CURRENT_TIMESTAMP),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE task_id = %s
                    """,
                    (task_id,),
                )
                cur.execute(
                    """
                    SELECT task_status, topic_id, source_queue_id, source_schedule_id, source_schedule_log_id
                    FROM da_agent_task
                    WHERE task_id = %s
                    LIMIT 1
                    """,
                    (task_id,),
                )
                row = cur.fetchone() or {}
                if str(row.get("task_status") or "") == "waiting":
                    cur.execute(
                        """
                        UPDATE da_agent_task
                        SET task_status = 'suspended',
                            heartbeat_at = CURRENT_TIMESTAMP,
                            finished_at = CURRENT_TIMESTAMP,
                            error_json = %s,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE task_id = %s
                        """,
                        (cancel_error_json, task_id),
                    )
                    cur.execute(
                        """
                        UPDATE da_agent_topic
                        SET current_task_status = 'suspended',
                            updated_at = CURRENT_TIMESTAMP
                        WHERE current_task_id = %s
                        """,
                        (task_id,),
                    )
                    source_queue_id = str(row.get("source_queue_id") or "") or None
                    source_schedule_id = str(row.get("source_schedule_id") or "") or None
                    source_schedule_log_id = str(row.get("source_schedule_log_id") or "") or None
                    if source_queue_id:
                        cur.execute(
                            """
                            UPDATE da_agent_message_queue
                            SET status = 'suspended',
                                error_message = %s,
                                consumed_at = COALESCE(consumed_at, CURRENT_TIMESTAMP),
                                updated_at = CURRENT_TIMESTAMP
                            WHERE queue_id = %s
                            """,
                            (cancel_error["message"], source_queue_id),
                        )
                    if source_schedule_id:
                        cur.execute(
                            """
                            UPDATE da_agent_message_schedule
                            SET last_task_id = %s,
                                last_error_message = %s,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE schedule_id = %s
                            """,
                            (task_id, cancel_error["message"], source_schedule_id),
                        )
                    if source_schedule_log_id:
                        cur.execute(
                            """
                            UPDATE da_agent_message_schedule_log
                            SET task_id = %s,
                                status = 'suspended',
                                error_message = %s,
                                finished_at = CURRENT_TIMESTAMP
                            WHERE schedule_log_id = %s
                            """,
                            (task_id, cancel_error["message"], source_schedule_log_id),
                        )
            conn.commit()
        finally:
            conn.close()
        return self.get_task(task_id, context=context)

    def is_task_cancel_requested(self, task_id: str) -> bool:
        task = self.get_task(task_id)
        if not task:
            return False
        return bool(task.get("cancel_requested_at")) and str(task.get("task_status") or "") not in {
            "finished",
            "error",
            "suspended",
        }

    def append_lifecycle_event(
        self,
        *,
        topic_id: str,
        task_id: str,
        event_type: str,
        content_type: str | None,
        correlation_id: str | None,
        parent_correlation_id: str | None,
        data: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        message_id = _new_id("evt")
        data = data or {}
        content = str(data.get("content") or "")
        steps_json = json.dumps(data.get("steps"), ensure_ascii=False, default=_json_default) if data.get("steps") else None
        tool_json = json.dumps(data.get("tool"), ensure_ascii=False, default=_json_default) if data.get("tool") else None
        usage_json = json.dumps(data.get("token_usage"), ensure_ascii=False, default=_json_default) if data.get("token_usage") else None
        error_json = json.dumps(data.get("error"), ensure_ascii=False, default=_json_default) if data.get("error") else None
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                seq_id = self._next_task_seq(cur, task_id)
                cur.execute(
                    """
                    INSERT INTO da_agent_message (
                        message_id, topic_id, task_id, sender_type, type, status, content, event,
                        steps_json, tool_json, seq_id, correlation_id, parent_correlation_id,
                        content_type, usage_json, error_json, show_in_ui
                    ) VALUES (%s, %s, %s, 'assistant', 'event', %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, 0)
                    """,
                    (
                        message_id,
                        topic_id,
                        task_id,
                        str(data.get("status") or "running"),
                        content,
                        event_type,
                        steps_json,
                        tool_json,
                        seq_id,
                        correlation_id,
                        parent_correlation_id,
                        content_type,
                        usage_json,
                        error_json,
                    ),
                )
            conn.commit()
        finally:
            conn.close()
        return {
            "record_type": "event",
            "seq_id": seq_id,
            "created_at": _to_iso(datetime.utcnow()),
            "event_type": event_type,
            "correlation_id": correlation_id,
            "parent_correlation_id": parent_correlation_id,
            "content_type": content_type,
            "data": data,
        }

    def append_chunk(
        self,
        *,
        topic_id: str,
        task_id: str,
        request_id: str,
        chunk_id: int,
        content: str | None,
        delta: dict[str, Any],
        metadata: dict[str, Any],
    ) -> dict[str, Any]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                seq_id = self._next_task_seq(cur, task_id)
                cur.execute(
                    """
                    INSERT INTO da_agent_chunk (
                        topic_id, task_id, seq_id, request_id, chunk_id, content,
                        delta_status, finish_reason, delta_extra_json,
                        correlation_id, parent_correlation_id, model_id, content_type, metadata_extra_json
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        topic_id,
                        task_id,
                        seq_id,
                        request_id,
                        int(chunk_id),
                        content,
                        str(delta.get("status") or ""),
                        str(delta.get("finish_reason") or "") or None,
                        json.dumps(delta.get("extra_fields"), ensure_ascii=False, default=_json_default)
                        if delta.get("extra_fields")
                        else None,
                        str(metadata.get("correlation_id") or "") or None,
                        str(metadata.get("parent_correlation_id") or "") or None,
                        str(metadata.get("model_id") or "") or None,
                        str(metadata.get("content_type") or "") or None,
                        json.dumps(metadata.get("extra_data"), ensure_ascii=False, default=_json_default)
                        if metadata.get("extra_data")
                        else None,
                    ),
                )
            conn.commit()
        finally:
            conn.close()
        return {
            "record_type": "chunk",
            "seq_id": seq_id,
            "created_at": _to_iso(datetime.utcnow()),
            "request_id": request_id,
            "chunk_id": int(chunk_id),
            "content": content,
            "delta": delta,
            "metadata": metadata,
        }

    def list_task_events(
        self,
        *,
        task_id: str,
        after_seq: int = 0,
        limit: int = 200,
        context: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        task = self.get_task(task_id, context=context)
        if not task:
            return {
                "task_id": task_id,
                "task_status": "missing",
                "after_seq": max(0, after_seq),
                "next_after_seq": max(0, after_seq),
                "has_more": False,
                "events": [],
            }
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT seq_id, event, correlation_id, parent_correlation_id, content_type,
                           content, steps_json, tool_json, usage_json, error_json, status, created_at
                    FROM da_agent_message
                    WHERE task_id = %s
                      AND show_in_ui = 0
                      AND seq_id > %s
                    ORDER BY seq_id ASC
                    LIMIT %s
                    """,
                    (task_id, max(0, after_seq), max(1, limit) + 1),
                )
                event_rows = cur.fetchall() or []
                cur.execute(
                    """
                    SELECT seq_id, request_id, chunk_id, content, delta_status, finish_reason,
                           delta_extra_json, correlation_id, parent_correlation_id, model_id,
                           content_type, metadata_extra_json, created_at
                    FROM da_agent_chunk
                    WHERE task_id = %s
                      AND seq_id > %s
                    ORDER BY seq_id ASC
                    LIMIT %s
                    """,
                    (task_id, max(0, after_seq), max(1, limit) + 1),
                )
                chunk_rows = cur.fetchall() or []
        finally:
            conn.close()

        merged: list[dict[str, Any]] = []
        for row in event_rows:
            merged.append(self._normalize_task_event_record(row))
        for row in chunk_rows:
            merged.append(self._normalize_task_chunk_record(row))
        merged.sort(key=lambda item: int(item.get("seq_id") or 0))
        has_more = len(merged) > max(1, limit)
        page = merged[: max(1, limit)]
        next_after_seq = int(page[-1]["seq_id"]) if page else max(0, after_seq)
        return {
            "task_id": task_id,
            "task_status": str(task.get("task_status") or "waiting"),
            "after_seq": max(0, after_seq),
            "next_after_seq": next_after_seq,
            "has_more": has_more,
            "events": page,
        }

    def get_message_queue(self, queue_id: str, context: dict[str, Any] | None = None) -> dict[str, Any] | None:
        self._ensure_ready()
        context_sql, context_params = self._topic_context_predicate(context, alias="t")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    f"""
                    SELECT q.queue_id, q.topic_id, q.source_schedule_id, q.source_schedule_log_id,
                           q.message_type, q.message_content_json, q.status, q.last_task_id,
                           q.error_message, q.created_at, q.updated_at
                    FROM da_agent_message_queue q
                    JOIN da_agent_topic t ON t.topic_id = q.topic_id
                    WHERE q.queue_id = %s
                      AND {context_sql}
                    LIMIT 1
                    """,
                    [queue_id, *context_params],
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_queue_row(row) if row else None

    def query_message_queues(
        self,
        *,
        topic_id: str | None = None,
        page: int = 1,
        page_size: int = 50,
        context: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        safe_page = max(1, int(page or 1))
        safe_page_size = max(1, min(500, int(page_size or 50)))
        offset = (safe_page - 1) * safe_page_size
        context_sql, context_params = self._topic_context_predicate(context, alias="t")
        filters = [context_sql]
        params: list[Any] = [*context_params]
        if topic_id:
            filters.append("q.topic_id = %s")
            params.append(topic_id)
        where_sql = f"WHERE {' AND '.join(filters)}"
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    f"""
                    SELECT COUNT(*) AS total
                    FROM da_agent_message_queue q
                    JOIN da_agent_topic t ON t.topic_id = q.topic_id
                    {where_sql}
                    """,
                    tuple(params),
                )
                total_row = cur.fetchone() or {}
                cur.execute(
                    f"""
                    SELECT q.queue_id, q.topic_id, q.source_schedule_id, q.source_schedule_log_id,
                           q.message_type, q.message_content_json, q.status, q.last_task_id,
                           q.error_message, q.created_at, q.updated_at
                    FROM da_agent_message_queue q
                    JOIN da_agent_topic t ON t.topic_id = q.topic_id
                    {where_sql}
                    ORDER BY q.updated_at DESC, q.created_at DESC
                    LIMIT %s OFFSET %s
                    """,
                    tuple([*params, safe_page_size, offset]),
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return {
            "page": safe_page,
            "page_size": safe_page_size,
            "total": int(total_row.get("total") or 0),
            "list": [self._normalize_queue_row(row) for row in rows],
        }

    def create_message_queue(
        self,
        *,
        topic_id: str,
        message_type: str,
        message_content: Any,
        source_schedule_id: str | None = None,
        source_schedule_log_id: str | None = None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        queue_id = _new_id("queue")
        payload_json = json.dumps(message_content, ensure_ascii=False, default=_json_default)
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_agent_message_queue (
                        queue_id, topic_id, source_schedule_id, source_schedule_log_id,
                        message_type, message_content_json, status
                    ) VALUES (%s, %s, %s, %s, %s, %s, 'queued')
                    """,
                    (queue_id, topic_id, source_schedule_id, source_schedule_log_id, message_type, payload_json),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_queue(queue_id) or {}

    def update_message_queue(
        self,
        *,
        queue_id: str,
        topic_id: str,
        message_type: str,
        message_content: Any,
    ) -> dict[str, Any] | None:
        self._ensure_ready()
        payload_json = json.dumps(message_content, ensure_ascii=False, default=_json_default)
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_message_queue
                    SET topic_id = %s,
                        message_type = %s,
                        message_content_json = %s,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE queue_id = %s
                    """,
                    (topic_id, message_type, payload_json, queue_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_queue(queue_id)

    def delete_message_queue(self, queue_id: str) -> bool:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM da_agent_message_queue WHERE queue_id = %s", (queue_id,))
                deleted = int(cur.rowcount or 0) > 0
            conn.commit()
        finally:
            conn.close()
        return deleted

    def mark_message_queue_submitted(self, *, queue_id: str, task_id: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_message_queue
                    SET status = 'running',
                        last_task_id = %s,
                        error_message = NULL,
                        consumed_at = COALESCE(consumed_at, CURRENT_TIMESTAMP),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE queue_id = %s
                    """,
                    (task_id, queue_id),
                )
                cur.execute(
                    """
                    UPDATE da_agent_message_schedule_log
                    SET task_id = %s
                    WHERE queue_id = %s
                    """,
                    (task_id, queue_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_queue(queue_id)

    def mark_message_queue_failed(self, *, queue_id: str, error_message: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_message_queue
                    SET status = 'failed',
                        error_message = %s,
                        consumed_at = COALESCE(consumed_at, CURRENT_TIMESTAMP),
                        updated_at = CURRENT_TIMESTAMP
                    WHERE queue_id = %s
                    """,
                    (error_message, queue_id),
                )
                cur.execute(
                    """
                    UPDATE da_agent_message_schedule_log
                    SET status = 'failed',
                        error_message = %s,
                        finished_at = CURRENT_TIMESTAMP
                    WHERE queue_id = %s AND finished_at IS NULL
                    """,
                    (error_message, queue_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_queue(queue_id)

    def get_message_schedule(self, schedule_id: str, context: dict[str, Any] | None = None) -> dict[str, Any] | None:
        self._ensure_ready()
        context_sql, context_params = self._topic_context_predicate(context, alias="t")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    f"""
                    SELECT s.schedule_id, s.topic_id, s.name, s.message_type, s.message_content_json,
                           s.cron_expr, s.timezone, s.enabled, s.last_task_id, s.last_queue_id,
                           s.last_run_at, s.next_run_at, s.last_error_message, s.created_at, s.updated_at
                    FROM da_agent_message_schedule s
                    JOIN da_agent_topic t ON t.topic_id = s.topic_id
                    WHERE s.schedule_id = %s
                      AND {context_sql}
                    LIMIT 1
                    """,
                    [schedule_id, *context_params],
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_schedule_row(row) if row else None

    def query_message_schedules(
        self,
        *,
        topic_id: str | None = None,
        page: int = 1,
        page_size: int = 50,
        context: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        safe_page = max(1, int(page or 1))
        safe_page_size = max(1, min(500, int(page_size or 50)))
        offset = (safe_page - 1) * safe_page_size
        context_sql, context_params = self._topic_context_predicate(context, alias="t")
        filters = [context_sql]
        params: list[Any] = [*context_params]
        if topic_id:
            filters.append("s.topic_id = %s")
            params.append(topic_id)
        where_sql = f"WHERE {' AND '.join(filters)}"
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    f"""
                    SELECT COUNT(*) AS total
                    FROM da_agent_message_schedule s
                    JOIN da_agent_topic t ON t.topic_id = s.topic_id
                    {where_sql}
                    """,
                    tuple(params),
                )
                total_row = cur.fetchone() or {}
                cur.execute(
                    f"""
                    SELECT s.schedule_id, s.topic_id, s.name, s.message_type, s.message_content_json,
                           s.cron_expr, s.timezone, s.enabled, s.last_task_id, s.last_queue_id,
                           s.last_run_at, s.next_run_at, s.last_error_message, s.created_at, s.updated_at
                    FROM da_agent_message_schedule s
                    JOIN da_agent_topic t ON t.topic_id = s.topic_id
                    {where_sql}
                    ORDER BY s.updated_at DESC, s.created_at DESC
                    LIMIT %s OFFSET %s
                    """,
                    tuple([*params, safe_page_size, offset]),
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return {
            "page": safe_page,
            "page_size": safe_page_size,
            "total": int(total_row.get("total") or 0),
            "list": [self._normalize_schedule_row(row) for row in rows],
        }

    def create_message_schedule(
        self,
        *,
        topic_id: str,
        name: str,
        message_type: str,
        message_content: Any,
        cron_expr: str,
        enabled: bool,
        timezone: str,
        next_run_at: datetime | None,
    ) -> dict[str, Any]:
        self._ensure_ready()
        schedule_id = _new_id("schedule")
        payload_json = json.dumps(message_content, ensure_ascii=False, default=_json_default)
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_agent_message_schedule (
                        schedule_id, topic_id, name, message_type, message_content_json,
                        cron_expr, timezone, enabled, next_run_at
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        schedule_id,
                        topic_id,
                        name,
                        message_type,
                        payload_json,
                        cron_expr,
                        timezone,
                        1 if enabled else 0,
                        next_run_at,
                    ),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_schedule(schedule_id) or {}

    def update_message_schedule(
        self,
        *,
        schedule_id: str,
        topic_id: str,
        name: str,
        message_type: str,
        message_content: Any,
        cron_expr: str,
        enabled: bool,
        timezone: str,
        next_run_at: datetime | None,
    ) -> dict[str, Any] | None:
        self._ensure_ready()
        payload_json = json.dumps(message_content, ensure_ascii=False, default=_json_default)
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_message_schedule
                    SET topic_id = %s,
                        name = %s,
                        message_type = %s,
                        message_content_json = %s,
                        cron_expr = %s,
                        timezone = %s,
                        enabled = %s,
                        next_run_at = %s,
                        last_error_message = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE schedule_id = %s
                    """,
                    (
                        topic_id,
                        name,
                        message_type,
                        payload_json,
                        cron_expr,
                        timezone,
                        1 if enabled else 0,
                        next_run_at,
                        schedule_id,
                    ),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_schedule(schedule_id)

    def delete_message_schedule(self, schedule_id: str) -> bool:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute("DELETE FROM da_agent_message_schedule WHERE schedule_id = %s", (schedule_id,))
                deleted = int(cur.rowcount or 0) > 0
            conn.commit()
        finally:
            conn.close()
        return deleted

    def list_due_message_schedules(self, *, now_utc: datetime, limit: int = 20) -> list[dict[str, Any]]:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT schedule_id, topic_id, name, message_type, message_content_json,
                           cron_expr, timezone, enabled, last_task_id, last_queue_id,
                           last_run_at, next_run_at, last_error_message, created_at, updated_at
                    FROM da_agent_message_schedule
                    WHERE enabled = 1
                      AND next_run_at IS NOT NULL
                      AND next_run_at <= %s
                    ORDER BY next_run_at ASC
                    LIMIT %s
                    """,
                    (now_utc, max(1, limit)),
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return [self._normalize_schedule_row(row) for row in rows]

    def mark_message_schedule_triggered(
        self,
        *,
        schedule_id: str,
        queue_id: str,
        fired_at: datetime,
        next_run_at: datetime | None,
    ) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_message_schedule
                    SET last_queue_id = %s,
                        last_run_at = %s,
                        next_run_at = %s,
                        last_error_message = NULL,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE schedule_id = %s
                    """,
                    (queue_id, fired_at, next_run_at, schedule_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_schedule(schedule_id)

    def mark_message_schedule_failed(self, *, schedule_id: str, error_message: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_message_schedule
                    SET last_error_message = %s,
                        updated_at = CURRENT_TIMESTAMP
                    WHERE schedule_id = %s
                    """,
                    (error_message, schedule_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_schedule(schedule_id)

    def create_message_schedule_log(self, *, schedule_id: str, status: str = "running", started_at: datetime | None = None) -> dict[str, Any]:
        self._ensure_ready()
        log_id = _new_id("schedule_log")
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    INSERT INTO da_agent_message_schedule_log (
                        schedule_log_id, schedule_id, status, started_at
                    ) VALUES (%s, %s, %s, %s)
                    """,
                    (log_id, schedule_id, status, started_at),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_schedule_log(log_id) or {}

    def get_message_schedule_log(self, schedule_log_id: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT schedule_log_id, schedule_id, queue_id, task_id, status,
                           error_message, started_at, finished_at, created_at
                    FROM da_agent_message_schedule_log
                    WHERE schedule_log_id = %s
                    LIMIT 1
                    """,
                    (schedule_log_id,),
                )
                row = cur.fetchone()
        finally:
            conn.close()
        return self._normalize_schedule_log_row(row) if row else None

    def attach_queue_to_schedule_log(self, *, schedule_log_id: str, queue_id: str) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_message_schedule_log
                    SET queue_id = %s
                    WHERE schedule_log_id = %s
                    """,
                    (queue_id, schedule_log_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_schedule_log(schedule_log_id)

    def finish_message_schedule_log(
        self,
        *,
        schedule_log_id: str,
        status: str,
        error_message: str | None = None,
        task_id: str | None = None,
    ) -> dict[str, Any] | None:
        self._ensure_ready()
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    UPDATE da_agent_message_schedule_log
                    SET task_id = COALESCE(%s, task_id),
                        status = %s,
                        error_message = %s,
                        finished_at = CURRENT_TIMESTAMP
                    WHERE schedule_log_id = %s
                    """,
                    (task_id, status, error_message, schedule_log_id),
                )
            conn.commit()
        finally:
            conn.close()
        return self.get_message_schedule_log(schedule_log_id)

    def list_message_schedule_logs(
        self,
        *,
        schedule_id: str,
        page: int = 1,
        page_size: int = 50,
    ) -> dict[str, Any]:
        self._ensure_ready()
        safe_page = max(1, int(page or 1))
        safe_page_size = max(1, min(500, int(page_size or 50)))
        offset = (safe_page - 1) * safe_page_size
        conn = self._connect(database=self._schema_name())
        try:
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT COUNT(*) AS total
                    FROM da_agent_message_schedule_log
                    WHERE schedule_id = %s
                    """,
                    (schedule_id,),
                )
                total_row = cur.fetchone() or {}
                cur.execute(
                    """
                    SELECT schedule_log_id, schedule_id, queue_id, task_id, status,
                           error_message, started_at, finished_at, created_at
                    FROM da_agent_message_schedule_log
                    WHERE schedule_id = %s
                    ORDER BY created_at DESC
                    LIMIT %s OFFSET %s
                    """,
                    (schedule_id, safe_page_size, offset),
                )
                rows = cur.fetchall() or []
        finally:
            conn.close()
        return {
            "schedule_id": schedule_id,
            "page": safe_page,
            "page_size": safe_page_size,
            "total": int(total_row.get("total") or 0),
            "list": [self._normalize_schedule_log_row(row) for row in rows],
        }

    def create_recovery_task(self, parent_task_id: str) -> dict[str, Any] | None:
        parent = self.get_task(parent_task_id)
        if not parent:
            return None
        if self.has_active_child_task(parent_task_id):
            return None
        replacement = self.create_task(
            topic_id=str(parent.get("topic_id") or ""),
            prompt=str(parent.get("prompt") or ""),
            provider_id=str(parent.get("provider_id") or ""),
            model=str(parent.get("model") or ""),
            database_hint=str(parent.get("database_hint") or "") or None,
            debug=bool(parent.get("debug")),
            timeout_seconds=int(parent.get("timeout_seconds") or 0),
            sql_read_timeout_seconds=int(parent.get("sql_read_timeout_seconds") or 0),
            sql_write_timeout_seconds=int(parent.get("sql_write_timeout_seconds") or 0),
            from_task_id=parent_task_id,
            source_queue_id=str(parent.get("source_queue_id") or "") or None,
            source_schedule_id=str(parent.get("source_schedule_id") or "") or None,
            source_schedule_log_id=str(parent.get("source_schedule_log_id") or "") or None,
        )
        self.finish_task(
            task_id=parent_task_id,
            task_status="suspended",
            error={"code": "task_recovered", "message": f"任务租约已过期，已转移到 {replacement.get('task_id')}"},
        )
        self.ensure_assistant_message(
            topic_id=str(replacement.get("topic_id") or ""),
            task_id=str(replacement.get("task_id") or ""),
            status="waiting",
        )
        return replacement

    def _normalize_topic_row(self, row: dict[str, Any], *, include_messages: bool) -> dict[str, Any]:
        topic = {
            "topic_id": str(row.get("topic_id") or ""),
            "title": str(row.get("title") or "新话题"),
            "chat_topic_id": str(row.get("chat_topic_id") or ""),
            "chat_conversation_id": str(row.get("chat_conversation_id") or ""),
            "current_task_id": str(row.get("current_task_id") or "") or None,
            "current_task_status": str(row.get("current_task_status") or "") or None,
            "source": str(row.get("source") or "portal"),
            "website_id": str(row.get("website_id") or ""),
            "external_user_id": str(row.get("external_user_id") or ""),
            "visitor_id": str(row.get("visitor_id") or ""),
            "message_count": int(row.get("message_count") or 0),
            "last_message_preview": str(row.get("last_message_preview") or ""),
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }
        if include_messages:
            topic["messages"] = self.list_topic_messages(topic["topic_id"])
        return topic

    def _assistant_task_ids_from_rows(self, rows: list[dict[str, Any]]) -> list[str]:
        task_ids: list[str] = []
        seen: set[str] = set()
        for row in rows:
            if str(row.get("sender_type") or "") != "assistant":
                continue
            task_id = str(row.get("task_id") or "").strip()
            if not task_id or task_id in seen:
                continue
            seen.add(task_id)
            task_ids.append(task_id)
        return task_ids

    def _normalize_task_event_record(self, row: dict[str, Any]) -> dict[str, Any]:
        payload: dict[str, Any] = {}
        if row.get("content"):
            payload["content"] = str(row.get("content") or "")
        steps = _safe_json_load(row.get("steps_json"))
        if steps is not None:
            payload["steps"] = steps
        tool = _safe_json_load(row.get("tool_json"))
        if tool is not None:
            payload["tool"] = tool
        usage = _safe_json_load(row.get("usage_json"))
        if usage is not None:
            payload["token_usage"] = usage
        error = _safe_json_load(row.get("error_json"))
        if error is not None:
            payload["error"] = error
        payload["status"] = str(row.get("status") or "running")
        return {
            "record_type": "event",
            "seq_id": int(row.get("seq_id") or 0),
            "created_at": _to_iso(row.get("created_at")),
            "event_type": str(row.get("event") or ""),
            "correlation_id": str(row.get("correlation_id") or "") or None,
            "parent_correlation_id": str(row.get("parent_correlation_id") or "") or None,
            "content_type": str(row.get("content_type") or "") or None,
            "data": payload,
        }

    def _normalize_task_chunk_record(self, row: dict[str, Any]) -> dict[str, Any]:
        delta_extra = _safe_json_load(row.get("delta_extra_json"))
        meta_extra = _safe_json_load(row.get("metadata_extra_json"))
        return {
            "record_type": "chunk",
            "seq_id": int(row.get("seq_id") or 0),
            "created_at": _to_iso(row.get("created_at")),
            "request_id": str(row.get("request_id") or ""),
            "chunk_id": int(row.get("chunk_id") or 0),
            "content": row.get("content"),
            "delta": {
                "status": str(row.get("delta_status") or ""),
                "finish_reason": str(row.get("finish_reason") or "") or None,
                "extra_fields": delta_extra if isinstance(delta_extra, dict) else None,
            },
            "metadata": {
                "correlation_id": str(row.get("correlation_id") or "") or None,
                "parent_correlation_id": str(row.get("parent_correlation_id") or "") or None,
                "model_id": str(row.get("model_id") or "") or None,
                "content_type": str(row.get("content_type") or "") or None,
                "extra_data": meta_extra if isinstance(meta_extra, dict) else None,
            },
        }

    def _load_task_history_views(self, conn, task_ids: list[str]) -> dict[str, dict[str, Any]]:
        task_ids = [str(task_id or "").strip() for task_id in task_ids if str(task_id or "").strip()]
        if not task_ids:
            return {}

        placeholders = ", ".join(["%s"] * len(task_ids))
        history_by_task_id: dict[str, dict[str, Any]] = {}
        event_records: dict[str, list[dict[str, Any]]] = {task_id: [] for task_id in task_ids}
        last_seq_by_task_id: dict[str, int] = {task_id: 0 for task_id in task_ids}

        with conn.cursor() as cur:
            cur.execute(
                f"""
                SELECT task_id, last_event_seq
                FROM da_agent_task
                WHERE task_id IN ({placeholders})
                """,
                task_ids,
            )
            for row in cur.fetchall() or []:
                task_id = str(row.get("task_id") or "").strip()
                if task_id:
                    last_seq_by_task_id[task_id] = int(row.get("last_event_seq") or 0)

            cur.execute(
                f"""
                SELECT task_id, seq_id, event, correlation_id, parent_correlation_id, content_type,
                       content, steps_json, tool_json, usage_json, error_json, status, created_at
                FROM da_agent_message
                WHERE task_id IN ({placeholders})
                  AND show_in_ui = 0
                ORDER BY task_id ASC, seq_id ASC
                """,
                task_ids,
            )
            for row in cur.fetchall() or []:
                task_id = str(row.get("task_id") or "").strip()
                if task_id:
                    event_records.setdefault(task_id, []).append(self._normalize_task_event_record(row))

            cur.execute(
                f"""
                SELECT task_id, seq_id, request_id, chunk_id, content, delta_status, finish_reason,
                       delta_extra_json, correlation_id, parent_correlation_id, model_id,
                       content_type, metadata_extra_json, created_at
                FROM da_agent_chunk
                WHERE task_id IN ({placeholders})
                ORDER BY task_id ASC, seq_id ASC
                """,
                task_ids,
            )
            for row in cur.fetchall() or []:
                task_id = str(row.get("task_id") or "").strip()
                if task_id:
                    event_records.setdefault(task_id, []).append(self._normalize_task_chunk_record(row))

        for task_id in task_ids:
            history_by_task_id[task_id] = _project_task_history(
                event_records.get(task_id, []),
                fallback_resume_after_seq=last_seq_by_task_id.get(task_id, 0),
            )
        return history_by_task_id

    def _normalize_message_row(self, row: dict[str, Any], *, history: dict[str, Any] | None = None) -> dict[str, Any]:
        normalized = {
            "message_id": str(row.get("message_id") or ""),
            "topic_id": str(row.get("topic_id") or ""),
            "task_id": str(row.get("task_id") or "") or None,
            "sender_type": str(row.get("sender_type") or ""),
            "type": str(row.get("type") or ""),
            "status": str(row.get("status") or "success"),
            "content": str(row.get("content") or ""),
            "event": str(row.get("event") or ""),
            "steps": _safe_json_load(row.get("steps_json")),
            "tool": _safe_json_load(row.get("tool_json")),
            "seq_id": int(row.get("seq_id") or 0),
            "correlation_id": str(row.get("correlation_id") or "") or None,
            "parent_correlation_id": str(row.get("parent_correlation_id") or "") or None,
            "content_type": str(row.get("content_type") or "") or None,
            "usage": _safe_json_load(row.get("usage_json")),
            "show_in_ui": bool(row.get("show_in_ui")),
            "error": _safe_json_load(row.get("error_json")),
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }
        if normalized["sender_type"] == "assistant":
            normalized["blocks"] = list(history.get("blocks") or []) if history else []
            normalized["resume_after_seq"] = int(history.get("resume_after_seq") or 0) if history else 0
        return normalized

    def _normalize_task_row(self, row: dict[str, Any]) -> dict[str, Any]:
        return {
            "task_id": str(row.get("task_id") or ""),
            "topic_id": str(row.get("topic_id") or ""),
            "from_task_id": str(row.get("from_task_id") or "") or None,
            "source_queue_id": str(row.get("source_queue_id") or "") or None,
            "source_schedule_id": str(row.get("source_schedule_id") or "") or None,
            "source_schedule_log_id": str(row.get("source_schedule_log_id") or "") or None,
            "task_status": str(row.get("task_status") or "waiting"),
            "prompt": str(row.get("prompt") or ""),
            "provider_id": str(row.get("provider_id") or ""),
            "model": str(row.get("model_name") or ""),
            "database_hint": str(row.get("database_hint") or "") or None,
            "debug": bool(row.get("debug_enabled")),
            "timeout_seconds": int(row.get("timeout_seconds") or 0),
            "sql_read_timeout_seconds": int(row.get("sql_read_timeout_seconds") or 0),
            "sql_write_timeout_seconds": int(row.get("sql_write_timeout_seconds") or 0),
            "last_event_seq": int(row.get("last_event_seq") or 0),
            "cancel_requested_at": _to_iso(row.get("cancel_requested_at")) or None,
            "started_at": _to_iso(row.get("started_at")) or None,
            "heartbeat_at": _to_iso(row.get("heartbeat_at")) or None,
            "finished_at": _to_iso(row.get("finished_at")) or None,
            "error": _safe_json_load(row.get("error_json")),
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }

    def _normalize_queue_row(self, row: dict[str, Any]) -> dict[str, Any]:
        return {
            "queue_id": str(row.get("queue_id") or ""),
            "topic_id": str(row.get("topic_id") or ""),
            "source_schedule_id": str(row.get("source_schedule_id") or "") or None,
            "source_schedule_log_id": str(row.get("source_schedule_log_id") or "") or None,
            "message_type": str(row.get("message_type") or ""),
            "message_content": _safe_json_load(row.get("message_content_json")),
            "status": str(row.get("status") or "queued"),
            "last_task_id": str(row.get("last_task_id") or "") or None,
            "error_message": str(row.get("error_message") or "") or None,
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }

    def _normalize_schedule_row(self, row: dict[str, Any]) -> dict[str, Any]:
        return {
            "schedule_id": str(row.get("schedule_id") or ""),
            "topic_id": str(row.get("topic_id") or ""),
            "name": str(row.get("name") or ""),
            "message_type": str(row.get("message_type") or ""),
            "message_content": _safe_json_load(row.get("message_content_json")),
            "cron_expr": str(row.get("cron_expr") or ""),
            "timezone": str(row.get("timezone") or "Asia/Shanghai"),
            "enabled": bool(row.get("enabled")),
            "last_task_id": str(row.get("last_task_id") or "") or None,
            "last_queue_id": str(row.get("last_queue_id") or "") or None,
            "last_run_at": _to_iso(row.get("last_run_at")) or None,
            "next_run_at": _to_iso(row.get("next_run_at")) or None,
            "last_error_message": str(row.get("last_error_message") or "") or None,
            "created_at": _to_iso(row.get("created_at")),
            "updated_at": _to_iso(row.get("updated_at")),
        }

    def _normalize_schedule_log_row(self, row: dict[str, Any]) -> dict[str, Any]:
        return {
            "schedule_log_id": str(row.get("schedule_log_id") or ""),
            "schedule_id": str(row.get("schedule_id") or ""),
            "queue_id": str(row.get("queue_id") or "") or None,
            "task_id": str(row.get("task_id") or "") or None,
            "status": str(row.get("status") or "running"),
            "error_message": str(row.get("error_message") or "") or None,
            "started_at": _to_iso(row.get("started_at")) or None,
            "finished_at": _to_iso(row.get("finished_at")) or None,
            "created_at": _to_iso(row.get("created_at")),
        }


_STORE: TopicTaskStore | None = None
_STORE_LOCK = threading.Lock()


def get_topic_task_store() -> TopicTaskStore:
    global _STORE
    if _STORE is None:
        with _STORE_LOCK:
            if _STORE is None:
                _STORE = TopicTaskStore()
    return _STORE
