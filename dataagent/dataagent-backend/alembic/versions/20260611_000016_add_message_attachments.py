"""add generated-file attachments to agent messages

Revision ID: 20260611_000016
Revises: 20260602_000015
Create Date: 2026-06-11 10:00:00
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect
from sqlalchemy.dialects import mysql


revision = "20260611_000016"
down_revision = "20260602_000015"
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
    if not _has_column("da_agent_message", "attachments_json"):
        op.add_column(
            "da_agent_message",
            sa.Column(
                "attachments_json",
                sa.Text().with_variant(mysql.LONGTEXT(), "mysql"),
                nullable=True,
                comment="本次运行生成的工作区文件快照(JSON数组,WorkspaceFile结构)",
            ),
        )


def downgrade() -> None:
    if _has_table("da_agent_message") and _has_column("da_agent_message", "attachments_json"):
        op.drop_column("da_agent_message", "attachments_json")
