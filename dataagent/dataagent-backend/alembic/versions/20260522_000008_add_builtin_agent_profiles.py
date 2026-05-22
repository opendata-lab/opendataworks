"""add builtin dataagent profiles

Revision ID: 20260522_000008
Revises: 20260521_000007
Create Date: 2026-05-22 10:50:00
"""
from __future__ import annotations

import json

import sqlalchemy as sa
from alembic import op
from sqlalchemy import inspect


revision = "20260522_000008"
down_revision = "20260521_000007"
branch_labels = None
depends_on = None

DEFAULT_AGENT_ID = "agent_default"
OPENDATAWORKS_AGENT_ID = "agent_opendataworks"

GENERAL_AGENT_SNAPSHOT = {
    "agent_id": DEFAULT_AGENT_ID,
    "name": "通用智能体",
    "description": "通用对话与分析入口，不预置 OpenDataWorks 专属 Skills。",
    "system_prompt": "",
    "permission_mode": "default",
    "allowed_tools": ["Read", "LS", "Glob", "Grep"],
    "mcp_server_ids": [],
    "skill_folders": [],
    "max_turns": 0,
    "env_vars": {},
    "is_default": True,
    "is_builtin": True,
}

OPENDATAWORKS_AGENT_SNAPSHOT = {
    "agent_id": OPENDATAWORKS_AGENT_ID,
    "name": "OpenDataWorks助手智能体",
    "description": "面向 OpenDataWorks 数据门户、元数据、血缘、工作流和智能问数场景。",
    "system_prompt": "你是 OpenDataWorks 数据门户助手，优先围绕平台元数据、工作流、血缘、数据质量和智能问数场景提供帮助。",
    "permission_mode": "inherit",
    "allowed_tools": ["Skill", "Bash", "Read", "LS", "Glob", "Grep"],
    "mcp_server_ids": ["portal"],
    "skill_folders": ["opendataworks-business-knowledge", "opendataworks-platform-tools"],
    "max_turns": 0,
    "env_vars": {},
    "is_default": False,
    "is_builtin": True,
}


def _has_table(table_name: str) -> bool:
    return inspect(op.get_bind()).has_table(table_name)


def _has_column(table_name: str, column_name: str) -> bool:
    inspector = inspect(op.get_bind())
    return any(column.get("name") == column_name for column in inspector.get_columns(table_name))


def _json(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _upsert_agent(snapshot: dict, *, is_default: bool) -> None:
    bind = op.get_bind()
    bind.execute(
        sa.text(
            """
        INSERT INTO da_agent_profile (
            agent_id, name, description, system_prompt, permission_mode,
            allowed_tools_json, mcp_server_ids_json, skill_folders_json,
            max_turns, env_vars_json, is_default, is_builtin
        ) VALUES (
            :agent_id, :name, :description, :system_prompt, :permission_mode,
            :allowed_tools_json, :mcp_server_ids_json, :skill_folders_json,
            :max_turns, :env_vars_json, :is_default, 1
        )
        ON DUPLICATE KEY UPDATE
            name = VALUES(name),
            description = VALUES(description),
            system_prompt = VALUES(system_prompt),
            permission_mode = VALUES(permission_mode),
            allowed_tools_json = VALUES(allowed_tools_json),
            mcp_server_ids_json = VALUES(mcp_server_ids_json),
            skill_folders_json = VALUES(skill_folders_json),
            max_turns = VALUES(max_turns),
            env_vars_json = VALUES(env_vars_json),
            is_default = VALUES(is_default),
            is_builtin = 1,
            updated_at = CURRENT_TIMESTAMP
        """
        ),
        {
            "agent_id": snapshot["agent_id"],
            "name": snapshot["name"],
            "description": snapshot["description"],
            "system_prompt": snapshot["system_prompt"],
            "permission_mode": snapshot["permission_mode"],
            "allowed_tools_json": _json(snapshot["allowed_tools"]),
            "mcp_server_ids_json": _json(snapshot["mcp_server_ids"]),
            "skill_folders_json": _json(snapshot["skill_folders"]),
            "max_turns": snapshot["max_turns"],
            "env_vars_json": _json(snapshot["env_vars"]),
            "is_default": 1 if is_default else 0,
        },
    )


def upgrade() -> None:
    if not _has_table("da_agent_profile"):
        return
    if not _has_column("da_agent_profile", "is_builtin"):
        op.add_column(
            "da_agent_profile",
            sa.Column("is_builtin", sa.Boolean(), nullable=False, server_default=sa.text("0")),
        )

    _upsert_agent(GENERAL_AGENT_SNAPSHOT, is_default=True)
    _upsert_agent(OPENDATAWORKS_AGENT_SNAPSHOT, is_default=False)

    general_snapshot_json = _json(GENERAL_AGENT_SNAPSHOT)
    bind = op.get_bind()
    for table_name in ("da_agent_topic", "da_agent_task"):
        if not _has_table(table_name) or not _has_column(table_name, "agent_snapshot_json"):
            continue
        bind.execute(
            sa.text(
                f"""
                UPDATE {table_name}
                SET agent_id = :agent_id,
                    agent_snapshot_json = :snapshot
                WHERE agent_id IS NULL
                   OR agent_id = ''
                   OR agent_snapshot_json IS NULL
                   OR agent_snapshot_json = ''
                   OR (
                        agent_id = :agent_id
                        AND JSON_VALID(agent_snapshot_json)
                        AND JSON_UNQUOTE(JSON_EXTRACT(agent_snapshot_json, '$.name')) = :legacy_name
                   )
                """
            ),
            {
                "agent_id": DEFAULT_AGENT_ID,
                "snapshot": general_snapshot_json,
                "legacy_name": "默认智能问数助手",
            },
        )


def downgrade() -> None:
    if not _has_table("da_agent_profile"):
        return
    bind = op.get_bind()
    bind.execute(sa.text("DELETE FROM da_agent_profile WHERE agent_id = :agent_id"), {"agent_id": OPENDATAWORKS_AGENT_ID})
    if _has_column("da_agent_profile", "is_builtin"):
        op.drop_column("da_agent_profile", "is_builtin")
