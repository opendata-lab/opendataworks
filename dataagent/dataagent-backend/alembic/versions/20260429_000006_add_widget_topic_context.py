"""add widget topic context

Revision ID: 20260429_000006
Revises: 20260323_000005
Create Date: 2026-04-29 18:00:00
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect


revision = "20260429_000006"
down_revision = "20260323_000005"
branch_labels = None
depends_on = None


def _has_column(table_name: str, column_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(column.get("name") == column_name for column in inspector.get_columns(table_name))


def _has_index(table_name: str, index_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(index.get("name") == index_name for index in inspector.get_indexes(table_name))


def upgrade() -> None:
    if not _has_column("da_agent_topic", "source"):
        op.add_column("da_agent_topic", sa.Column("source", sa.String(length=32), nullable=False, server_default="portal"))
    if not _has_column("da_agent_topic", "website_id"):
        op.add_column("da_agent_topic", sa.Column("website_id", sa.String(length=128), nullable=False, server_default=""))
    if not _has_column("da_agent_topic", "external_user_id"):
        op.add_column("da_agent_topic", sa.Column("external_user_id", sa.String(length=255), nullable=False, server_default=""))
    if not _has_column("da_agent_topic", "visitor_id"):
        op.add_column("da_agent_topic", sa.Column("visitor_id", sa.String(length=128), nullable=False, server_default=""))

    if not _has_index("da_agent_topic", "idx_da_agent_topic_context_updated"):
        op.create_index(
            "idx_da_agent_topic_context_updated",
            "da_agent_topic",
            ["source", "website_id", "external_user_id", "visitor_id", "updated_at"],
        )


def downgrade() -> None:
    if _has_index("da_agent_topic", "idx_da_agent_topic_context_updated"):
        op.drop_index("idx_da_agent_topic_context_updated", table_name="da_agent_topic")
    for column_name in ("visitor_id", "external_user_id", "website_id", "source"):
        if _has_column("da_agent_topic", column_name):
            op.drop_column("da_agent_topic", column_name)
