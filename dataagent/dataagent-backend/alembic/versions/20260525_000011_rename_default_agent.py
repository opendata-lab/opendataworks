"""rename default agent display name

Revision ID: 20260525_000011
Revises: 20260525_000010
Create Date: 2026-05-25 16:40:00
"""
from __future__ import annotations

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect


revision = "20260525_000011"
down_revision = "20260525_000010"
branch_labels = None
depends_on = None

DEFAULT_AGENT_ID = "agent_default"
OLD_DEFAULT_AGENT_NAME = "通用智能体"
NEW_DEFAULT_AGENT_NAME = "默认助手"


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def _has_column(table_name: str, column_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(column.get("name") == column_name for column in inspector.get_columns(table_name))


def _rename_profile_name(old_name: str, new_name: str) -> None:
    bind = op.get_bind()
    if _has_table("da_agent_profile") and _has_column("da_agent_profile", "name"):
        bind.execute(
            sa.text(
                """
                UPDATE da_agent_profile
                SET name = :new_name
                WHERE agent_id = :agent_id
                  AND name = :old_name
                """
            ),
            {
                "agent_id": DEFAULT_AGENT_ID,
                "old_name": old_name,
                "new_name": new_name,
            },
        )

    for table_name in ("da_agent_topic", "da_agent_task"):
        if not _has_table(table_name) or not _has_column(table_name, "agent_snapshot_json"):
            continue
        bind.execute(
            sa.text(
                f"""
                UPDATE {table_name}
                SET agent_snapshot_json = JSON_SET(agent_snapshot_json, '$.name', :new_name)
                WHERE agent_id = :agent_id
                  AND JSON_VALID(agent_snapshot_json)
                  AND JSON_UNQUOTE(JSON_EXTRACT(agent_snapshot_json, '$.name')) = :old_name
                """
            ),
            {
                "agent_id": DEFAULT_AGENT_ID,
                "old_name": old_name,
                "new_name": new_name,
            },
        )


def upgrade() -> None:
    _rename_profile_name(OLD_DEFAULT_AGENT_NAME, NEW_DEFAULT_AGENT_NAME)


def downgrade() -> None:
    _rename_profile_name(NEW_DEFAULT_AGENT_NAME, OLD_DEFAULT_AGENT_NAME)
