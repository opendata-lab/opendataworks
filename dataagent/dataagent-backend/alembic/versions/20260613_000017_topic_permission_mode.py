"""move permission_mode from agent profile to topic (session) level

Revision ID: 20260613_000017
Revises: 20260611_000016
Create Date: 2026-06-13 00:00:00

Permission mode is a per-session choice (aligned with the Claude Agent SDK
permission modes), not an agent attribute. It moves to ``da_agent_topic`` where
it stores only the latest selection (mutable, may switch mid-conversation), and
is removed from ``da_agent_profile``.
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect


revision = "20260613_000017"
down_revision = "20260611_000016"
branch_labels = None
depends_on = None


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def _has_column(table_name: str, column_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(column.get("name") == column_name for column in inspector.get_columns(table_name))


def upgrade() -> None:
    if _has_table("da_agent_topic") and not _has_column("da_agent_topic", "permission_mode"):
        op.add_column(
            "da_agent_topic",
            sa.Column(
                "permission_mode",
                sa.String(length=32),
                nullable=False,
                server_default="default",
                comment="会话权限模式(SDK 对齐:default/acceptEdits/plan/bypassPermissions),只存最新选择",
            ),
        )
        # Existing sessions default to ``default``; the column default covers
        # backfill for already-present rows.
        op.execute(
            "UPDATE da_agent_topic SET permission_mode = 'default' "
            "WHERE permission_mode IS NULL OR permission_mode = ''"
        )

    if _has_table("da_agent_profile") and _has_column("da_agent_profile", "permission_mode"):
        op.drop_column("da_agent_profile", "permission_mode")


def downgrade() -> None:
    if _has_table("da_agent_profile") and not _has_column("da_agent_profile", "permission_mode"):
        op.add_column(
            "da_agent_profile",
            sa.Column(
                "permission_mode",
                sa.String(length=32),
                nullable=False,
                server_default="inherit",
                comment="权限模式",
            ),
        )

    if _has_table("da_agent_topic") and _has_column("da_agent_topic", "permission_mode"):
        op.drop_column("da_agent_topic", "permission_mode")
