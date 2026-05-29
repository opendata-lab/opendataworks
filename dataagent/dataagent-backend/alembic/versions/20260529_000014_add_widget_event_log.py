"""add da_agent_widget_event table for widget behavior tracking

Revision ID: 20260529_000014
Revises: d4385ed88340
Create Date: 2026-05-29 12:00:00
"""
from __future__ import annotations

from alembic import op
from sqlalchemy import inspect


revision = "20260529_000014"
down_revision = "d4385ed88340"
branch_labels = None
depends_on = None


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def upgrade() -> None:
    if _has_table("da_agent_widget_event"):
        return
    op.execute(
        """
        CREATE TABLE da_agent_widget_event (
            id               BIGINT AUTO_INCREMENT PRIMARY KEY,
            event_type       VARCHAR(64)  NOT NULL,
            source           VARCHAR(32)  NOT NULL DEFAULT 'portal',
            website_id       VARCHAR(128) NOT NULL DEFAULT '',
            external_user_id VARCHAR(255) NOT NULL DEFAULT '',
            visitor_id       VARCHAR(128) NOT NULL DEFAULT '',
            agent_id         VARCHAR(64)  NOT NULL DEFAULT '',
            topic_id         VARCHAR(64)  DEFAULT NULL,
            task_id          VARCHAR(64)  DEFAULT NULL,
            message_id       VARCHAR(64)  DEFAULT NULL,
            payload_json     JSON         DEFAULT NULL,
            client_ts        DATETIME(3)  DEFAULT NULL,
            created_at       DATETIME(3)  NOT NULL DEFAULT NOW(3),
            KEY idx_widget_event_context (source, website_id, external_user_id, visitor_id, created_at),
            KEY idx_widget_event_type (event_type, created_at),
            KEY idx_widget_event_topic (topic_id)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
        """
    )


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS da_agent_widget_event")
