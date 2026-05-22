"""add data scope to agent profiles

Revision ID: 20260522_000009
Revises: 20260522_000008
Create Date: 2026-05-22 15:00:00
"""
from __future__ import annotations

import json

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect


revision = "20260522_000009"
down_revision = "20260522_000008"
branch_labels = None
depends_on = None


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def _has_column(table_name: str, column_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(column.get("name") == column_name for column in inspector.get_columns(table_name))


def upgrade() -> None:
    if not _has_table("da_agent_profile"):
        return
    if not _has_column("da_agent_profile", "data_scope_json"):
        op.add_column(
            "da_agent_profile",
            sa.Column("data_scope_json", sa.Text(), nullable=True),
        )

    empty_scope = json.dumps({"allowed_scopes": []}, ensure_ascii=False, sort_keys=True)
    bind = op.get_bind()
    bind.execute(
        sa.text(
            """
            UPDATE da_agent_profile
            SET data_scope_json = :empty_scope
            WHERE data_scope_json IS NULL OR data_scope_json = ''
            """
        ),
        {"empty_scope": empty_scope},
    )


def downgrade() -> None:
    if _has_table("da_agent_profile") and _has_column("da_agent_profile", "data_scope_json"):
        op.drop_column("da_agent_profile", "data_scope_json")
