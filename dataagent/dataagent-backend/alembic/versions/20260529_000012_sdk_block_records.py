"""add da_agent_sdk_record table for native SDK block stream

Revision ID: 20260529_000012
Revises: 20260525_000011
Create Date: 2026-05-29 00:00:00
"""
from __future__ import annotations

from alembic import op
from sqlalchemy import inspect


revision = "20260529_000012"
down_revision = "20260525_000011"
branch_labels = None
depends_on = None


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def upgrade() -> None:
    if _has_table("da_agent_sdk_record"):
        return
    op.execute(
        """
        CREATE TABLE da_agent_sdk_record (
            id          BIGINT AUTO_INCREMENT PRIMARY KEY,
            topic_id    VARCHAR(64) NOT NULL,
            task_id     VARCHAR(64) NOT NULL,
            turn_index  SMALLINT NOT NULL DEFAULT 0,
            record_type VARCHAR(32) NOT NULL,
            event_type  VARCHAR(64) DEFAULT NULL,
            data        JSON NOT NULL,
            created_at  DATETIME(3) DEFAULT NOW(3),
            KEY idx_sdk_record_task_id (task_id, id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS da_agent_sdk_record")
