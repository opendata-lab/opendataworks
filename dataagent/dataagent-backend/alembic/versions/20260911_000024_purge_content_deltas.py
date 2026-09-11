"""purge persisted content.delta records for finished tasks, in bounded batches

Revision ID: 20260911_000024
Revises: 20260910_000023
Create Date: 2026-09-11 10:30:00

content.delta is required only while a task is active so an SSE reconnect can
resume from after_id. Terminal history is reconstructed from content.completed,
whose payload already contains the complete text. Existing delta rows are data
bloat and make the task history query choose a table scan plus filesort.
"""
from __future__ import annotations

import logging

from alembic import op
from sqlalchemy import inspect

revision = "20260911_000024"
down_revision = "20260910_000023"
branch_labels = None
depends_on = None

logger = logging.getLogger("alembic.runtime.migration")

_DELETE_BATCH_SIZE = 5_000


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def upgrade() -> None:
    if not _has_table("da_agent_sdk_record"):
        return

    total_deleted = 0
    # Alembic's outer migration transaction would otherwise turn the loop into
    # one large transaction. AUTOCOMMIT makes each bounded DELETE release its
    # locks before the next batch starts.
    with op.get_context().autocommit_block():
        while True:
            result = op.get_bind().exec_driver_sql(
                # Same predicate the runtime uses: only a task that finished
                # normally. An active task still needs its deltas for after_id
                # reconnection, and one cut short keeps them because its last
                # block never closed — a rolling deploy would otherwise strip
                # both out from under running conversations.
                # Same predicate the runtime uses: only a task that finished
                # normally. An active task still needs its deltas for after_id
                # reconnection, and one cut short keeps them because its last
                # block never closed — a rolling deploy would otherwise strip
                # both out from under running conversations.
                #
                # The ids are chosen in a subquery because MySQL rejects
                # ORDER BY / LIMIT on a multi-table DELETE, and the extra
                # derived table is what lets it read from the same table it is
                # deleting from.
                "DELETE FROM da_agent_sdk_record "
                "WHERE id IN ("
                "  SELECT id FROM ("
                "    SELECT r.id FROM da_agent_sdk_record r "
                "    JOIN da_agent_task t ON t.task_id = r.task_id "
                "    WHERE r.event_type = 'content.delta' "
                "      AND t.task_status = 'finished' "
                f"    ORDER BY r.id LIMIT {_DELETE_BATCH_SIZE}"
                "  ) batch"
                ")"
            )
            deleted = max(0, int(result.rowcount or 0))
            total_deleted += deleted
            logger.info(
                "content.delta purge batch deleted=%s total_deleted=%s",
                deleted,
                total_deleted,
            )
            if deleted < _DELETE_BATCH_SIZE:
                break


def downgrade() -> None:
    # Intentionally empty: deleted streaming fragments cannot be restored, and
    # content.completed remains the durable source for historical replay.
    pass
