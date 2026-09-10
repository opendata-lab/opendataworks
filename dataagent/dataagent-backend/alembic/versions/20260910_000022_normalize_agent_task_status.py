"""normalize pi task statuses and repair downstream rows

Revision ID: 20260910_000022
Revises: 20260721_000021
Create Date: 2026-09-10 12:00:00

Pi used to write its own outcome vocabulary into the task row: 'success' and
'cancelled'. Neither is a platform status, so two consumers misread every Pi
run — the SSE loop never closed on 'success', and finish_task's downstream
mapping recorded it as a failure. The code fix lands separately; this repairs
the rows already written.

Every repair is constrained through a real relationship (queue.last_task_id,
schedule_log.task_id, schedule.last_task_id) and requires the task to be both
finished and error-free, so a genuinely failed run keeps its failed status.
Statements are idempotent: re-running changes nothing.
"""
from __future__ import annotations

import logging

from alembic import op
from sqlalchemy import inspect

revision = "20260910_000022"
down_revision = "20260721_000021"
branch_labels = None
depends_on = None

logger = logging.getLogger("alembic.runtime.migration")

_LEGACY_TO_PLATFORM = (("success", "finished"), ("cancelled", "suspended"))


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def _count(sql: str) -> int:
    return int(op.get_bind().exec_driver_sql(sql).scalar() or 0)


def _audit(label: str, sql: str) -> None:
    """Record pre-migration counts.

    There is no down migration for a data correction, so the audit log is the
    only way to scope a targeted compensation if a repair turns out wrong.
    """
    try:
        logger.info("task-status audit: %s = %s", label, _count(sql))
    except Exception:  # pragma: no cover - auditing must never block the fix
        logger.warning("task-status audit failed for %s", label, exc_info=True)


def upgrade() -> None:
    if not _has_table("da_agent_task"):
        return

    _audit(
        "tasks with a legacy status",
        "SELECT COUNT(*) FROM da_agent_task WHERE task_status IN ('success', 'cancelled')",
    )
    if _has_table("da_agent_topic"):
        _audit(
            "topics with a legacy status",
            "SELECT COUNT(*) FROM da_agent_topic "
            "WHERE current_task_status IN ('success', 'cancelled')",
        )
    if _has_table("da_agent_message_queue"):
        _audit(
            "queue rows failed against a successful task",
            """
            SELECT COUNT(*) FROM da_agent_message_queue q
            JOIN da_agent_task t ON t.task_id = q.last_task_id AND t.source_queue_id = q.queue_id
            WHERE q.status = 'failed' AND t.task_status IN ('success', 'finished')
            """,
        )
    if _has_table("da_agent_message_schedule_log"):
        _audit(
            "schedule logs failed against a successful task",
            """
            SELECT COUNT(*) FROM da_agent_message_schedule_log l
            JOIN da_agent_task t ON t.task_id = l.task_id
                 AND t.source_schedule_log_id = l.schedule_log_id
            WHERE l.status = 'failed' AND t.task_status IN ('success', 'finished')
            """,
        )

    for legacy, platform in _LEGACY_TO_PLATFORM:
        op.execute(
            f"UPDATE da_agent_task SET task_status = '{platform}' "
            f"WHERE task_status = '{legacy}'"
        )
        if _has_table("da_agent_topic"):
            op.execute(
                f"UPDATE da_agent_topic SET current_task_status = '{platform}' "
                f"WHERE current_task_status = '{legacy}'"
            )

    # Downstream rows are only repaired where the task really succeeded and
    # carries no error, so a failure that happened to follow a rename is left
    # alone.
    if _has_table("da_agent_message_queue"):
        op.execute(
            """
            UPDATE da_agent_message_queue q
            JOIN da_agent_task t
              ON t.task_id = q.last_task_id
             AND t.source_queue_id = q.queue_id
            SET q.status = 'completed', q.error_message = NULL
            WHERE q.status = 'failed'
              AND t.task_status = 'finished'
              AND (t.error_json IS NULL OR t.error_json = '')
              AND (q.error_message IS NULL OR q.error_message = '')
            """
        )

    if _has_table("da_agent_message_schedule_log"):
        op.execute(
            """
            UPDATE da_agent_message_schedule_log l
            JOIN da_agent_task t
              ON t.task_id = l.task_id
             AND t.source_schedule_log_id = l.schedule_log_id
            SET l.status = 'completed', l.error_message = NULL
            WHERE l.status = 'failed'
              AND t.task_status = 'finished'
              AND (t.error_json IS NULL OR t.error_json = '')
              AND (l.error_message IS NULL OR l.error_message = '')
            """
        )



# da_agent_message_schedule.last_error_message is deliberately left alone. The
# success->failed defect wrote NULL there anyway, so there is no known dirty
# value to repair, and clearing it for any schedule that happens to point at a
# finished task would erase unrelated evidence.


def downgrade() -> None:
    """Intentionally empty.

    Reversing would rewrite correct statuses back into ones the platform does
    not recognise, reintroducing the hang and the false downstream failures.
    Use the pre-migration audit counts to scope a targeted compensation instead.
    """
