"""add preset questions to agent profiles

Revision ID: 20260529_000013
Revises: 20260529_000012
Create Date: 2026-05-29 00:00:00
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect


revision = "20260529_000013"
down_revision = "20260529_000012"
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
    if not _has_column("da_agent_profile", "preset_questions_json"):
        op.execute(
            "ALTER TABLE da_agent_profile ADD COLUMN preset_questions_json TEXT NULL AFTER data_scope_json"
        )


def downgrade() -> None:
    if _has_table("da_agent_profile") and _has_column("da_agent_profile", "preset_questions_json"):
        op.drop_column("da_agent_profile", "preset_questions_json")
