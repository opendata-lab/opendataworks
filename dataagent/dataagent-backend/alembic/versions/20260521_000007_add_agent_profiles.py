"""add dataagent agent profiles

Revision ID: 20260521_000007
Revises: 20260429_000006
Create Date: 2026-05-21 10:00:00
"""
from __future__ import annotations

import json

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect


revision = "20260521_000007"
down_revision = "20260429_000006"
branch_labels = None
depends_on = None

DEFAULT_AGENT_ID = "agent_default"
DEFAULT_AGENT_SNAPSHOT = {
    "agent_id": DEFAULT_AGENT_ID,
    "name": "默认智能问数助手",
    "description": "使用当前 DataAgent 默认提示词、Skills、工具和 MCP 配置。",
    "system_prompt": "",
    "permission_mode": "inherit",
    "allowed_tools": ["Skill", "Bash", "Read", "LS", "Glob", "Grep"],
    "mcp_server_ids": ["portal"],
    "skill_folders": [],
    "max_turns": 0,
    "env_vars": {},
    "is_default": True,
}


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def _has_column(table_name: str, column_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(column.get("name") == column_name for column in inspector.get_columns(table_name))


def _has_index(table_name: str, index_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(index.get("name") == index_name for index in inspector.get_indexes(table_name))


def _json(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def upgrade() -> None:
    if not _has_table("da_agent_profile"):
        op.create_table(
            "da_agent_profile",
            sa.Column("agent_id", sa.String(length=64), nullable=False),
            sa.Column("name", sa.String(length=128), nullable=False),
            sa.Column("description", sa.Text(), nullable=False),
            sa.Column("system_prompt", sa.Text(), nullable=False),
            sa.Column("permission_mode", sa.String(length=32), nullable=False, server_default="inherit"),
            sa.Column("allowed_tools_json", sa.Text(), nullable=False),
            sa.Column("mcp_server_ids_json", sa.Text(), nullable=False),
            sa.Column("skill_folders_json", sa.Text(), nullable=False),
            sa.Column("max_turns", sa.Integer(), nullable=False, server_default="0"),
            sa.Column("env_vars_json", sa.Text(), nullable=False),
            sa.Column("is_default", sa.Boolean(), nullable=False, server_default=sa.text("0")),
            sa.Column("created_at", sa.DateTime(), nullable=False, server_default=sa.text("CURRENT_TIMESTAMP")),
            sa.Column(
                "updated_at",
                sa.DateTime(),
                nullable=False,
                server_default=sa.text("CURRENT_TIMESTAMP"),
            ),
            sa.PrimaryKeyConstraint("agent_id"),
            mysql_charset="utf8mb4",
            mysql_collate="utf8mb4_unicode_ci",
            mysql_engine="InnoDB",
        )
    if not _has_index("da_agent_profile", "idx_da_agent_profile_default_updated"):
        op.create_index("idx_da_agent_profile_default_updated", "da_agent_profile", ["is_default", "updated_at"])

    bind = op.get_bind()
    bind.execute(
        sa.text(
            """
        INSERT INTO da_agent_profile (
            agent_id, name, description, system_prompt, permission_mode,
            allowed_tools_json, mcp_server_ids_json, skill_folders_json,
            max_turns, env_vars_json, is_default
        ) VALUES (
            :agent_id, :name, :description, :system_prompt, :permission_mode,
            :allowed_tools_json, :mcp_server_ids_json, :skill_folders_json,
            :max_turns, :env_vars_json, 1
        )
        ON DUPLICATE KEY UPDATE
            is_default = 1,
            updated_at = CURRENT_TIMESTAMP
        """
        ),
        {
            "agent_id": DEFAULT_AGENT_ID,
            "name": DEFAULT_AGENT_SNAPSHOT["name"],
            "description": DEFAULT_AGENT_SNAPSHOT["description"],
            "system_prompt": "",
            "permission_mode": "inherit",
            "allowed_tools_json": _json(DEFAULT_AGENT_SNAPSHOT["allowed_tools"]),
            "mcp_server_ids_json": _json(DEFAULT_AGENT_SNAPSHOT["mcp_server_ids"]),
            "skill_folders_json": _json(DEFAULT_AGENT_SNAPSHOT["skill_folders"]),
            "max_turns": 0,
            "env_vars_json": _json(DEFAULT_AGENT_SNAPSHOT["env_vars"]),
        },
    )

    default_snapshot_json = _json(DEFAULT_AGENT_SNAPSHOT)
    if not _has_column("da_agent_topic", "agent_id"):
        op.add_column(
            "da_agent_topic",
            sa.Column("agent_id", sa.String(length=64), nullable=False, server_default=DEFAULT_AGENT_ID),
        )
    if not _has_column("da_agent_topic", "agent_snapshot_json"):
        op.add_column("da_agent_topic", sa.Column("agent_snapshot_json", sa.Text(), nullable=True))
    if not _has_index("da_agent_topic", "idx_da_agent_topic_agent_updated"):
        op.create_index("idx_da_agent_topic_agent_updated", "da_agent_topic", ["agent_id", "updated_at"])

    if not _has_column("da_agent_task", "agent_id"):
        op.add_column(
            "da_agent_task",
            sa.Column("agent_id", sa.String(length=64), nullable=False, server_default=DEFAULT_AGENT_ID),
        )
    if not _has_column("da_agent_task", "agent_snapshot_json"):
        op.add_column("da_agent_task", sa.Column("agent_snapshot_json", sa.Text(), nullable=True))
    if not _has_index("da_agent_task", "idx_da_agent_task_agent_created"):
        op.create_index("idx_da_agent_task_agent_created", "da_agent_task", ["agent_id", "created_at"])

    bind.execute(
        sa.text(
            "UPDATE da_agent_topic SET agent_id = :agent_id, agent_snapshot_json = :snapshot WHERE agent_snapshot_json IS NULL OR agent_snapshot_json = ''"
        ),
        {"agent_id": DEFAULT_AGENT_ID, "snapshot": default_snapshot_json},
    )
    bind.execute(
        sa.text(
            "UPDATE da_agent_task SET agent_id = :agent_id, agent_snapshot_json = :snapshot WHERE agent_snapshot_json IS NULL OR agent_snapshot_json = ''"
        ),
        {"agent_id": DEFAULT_AGENT_ID, "snapshot": default_snapshot_json},
    )


def downgrade() -> None:
    if _has_index("da_agent_task", "idx_da_agent_task_agent_created"):
        op.drop_index("idx_da_agent_task_agent_created", table_name="da_agent_task")
    if _has_column("da_agent_task", "agent_snapshot_json"):
        op.drop_column("da_agent_task", "agent_snapshot_json")
    if _has_column("da_agent_task", "agent_id"):
        op.drop_column("da_agent_task", "agent_id")

    if _has_index("da_agent_topic", "idx_da_agent_topic_agent_updated"):
        op.drop_index("idx_da_agent_topic_agent_updated", table_name="da_agent_topic")
    if _has_column("da_agent_topic", "agent_snapshot_json"):
        op.drop_column("da_agent_topic", "agent_snapshot_json")
    if _has_column("da_agent_topic", "agent_id"):
        op.drop_column("da_agent_topic", "agent_id")

    if _has_table("da_agent_profile"):
        op.drop_table("da_agent_profile")
