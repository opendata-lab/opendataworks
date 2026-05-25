"""add feedback to agent messages

Revision ID: 20260525_000010
Revises: 20260522_000009
Create Date: 2026-05-25 11:55:00
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect


revision = "20260525_000010"
down_revision = "20260522_000009"
branch_labels = None
depends_on = None


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def _has_column(table_name: str, column_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(column.get("name") == column_name for column in inspector.get_columns(table_name))


def upgrade() -> None:
    if not _has_table("da_agent_message"):
        return
    if not _has_column("da_agent_message", "feedback"):
        op.add_column(
            "da_agent_message",
            sa.Column("feedback", sa.String(length=16), nullable=False, server_default=""),
        )


def downgrade() -> None:
    if _has_table("da_agent_message") and _has_column("da_agent_message", "feedback"):
        op.drop_column("da_agent_message", "feedback")
