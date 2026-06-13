"""enable opendataworks-data-dev skill on the builtin platform assistant

Revision ID: 20260613_000018
Revises: 20260613_000017
Create Date: 2026-06-13 01:00:00

Appends the data-development skill folder to the builtin ``agent_opendataworks``
profile and refreshes its system prompt to mention data development. Guarded so
admin-customized profiles are not overwritten:
- only the builtin row (``is_builtin = 1``) is touched;
- the skill is appended only if not already present;
- the system prompt is updated only when it still equals the previous builtin
  default (otherwise an operator has customized it and we leave it alone).
"""
from __future__ import annotations

import json

from alembic import op
from sqlalchemy import inspect


revision = "20260613_000018"
down_revision = "20260613_000017"
branch_labels = None
depends_on = None

AGENT_ID = "agent_opendataworks"
SKILL = "opendataworks-data-dev"

_PREV_PROMPT = "你是 OpenDataWorks 数据门户助手，优先围绕平台元数据、工作流、血缘、数据质量和智能问数场景提供帮助。"
_NEW_PROMPT = (
    "你是 OpenDataWorks 数据门户助手，优先围绕平台元数据、工作流、血缘、数据质量和智能问数场景提供帮助。"
    "你同时具备数据开发能力：可以生成与润色 SQL、创建数据任务、组装工作流、发布与上线、配置调度。"
    "数据开发以问数为主、开发为辅；执行开发动作时严格遵循 opendataworks-data-dev 技能的 playbook，"
    "发布与上线等高危操作必须先预览再经用户确认，绝不跳过。"
)


def _has_table(name: str) -> bool:
    return inspect(op.get_bind()).has_table(name)


def _load_folders(raw) -> list[str]:
    if raw is None:
        return []
    if isinstance(raw, list):
        return [str(x) for x in raw]
    try:
        value = json.loads(str(raw))
        return [str(x) for x in value] if isinstance(value, list) else []
    except Exception:
        return []


def upgrade() -> None:
    if not _has_table("da_agent_profile"):
        return
    bind = op.get_bind()
    row = bind.exec_driver_sql(
        "SELECT skill_folders_json, system_prompt, is_builtin FROM da_agent_profile WHERE agent_id = %s",
        (AGENT_ID,),
    ).fetchone()
    if not row:
        return
    skill_raw, system_prompt, is_builtin = row[0], row[1], row[2]
    if not is_builtin:
        return

    folders = _load_folders(skill_raw)
    if SKILL not in folders:
        folders.append(SKILL)
        bind.exec_driver_sql(
            "UPDATE da_agent_profile SET skill_folders_json = %s, updated_at = CURRENT_TIMESTAMP WHERE agent_id = %s",
            (json.dumps(folders, ensure_ascii=False, sort_keys=True), AGENT_ID),
        )
    if str(system_prompt or "").strip() == _PREV_PROMPT:
        bind.exec_driver_sql(
            "UPDATE da_agent_profile SET system_prompt = %s, updated_at = CURRENT_TIMESTAMP WHERE agent_id = %s",
            (_NEW_PROMPT, AGENT_ID),
        )


def downgrade() -> None:
    if not _has_table("da_agent_profile"):
        return
    bind = op.get_bind()
    row = bind.exec_driver_sql(
        "SELECT skill_folders_json, system_prompt, is_builtin FROM da_agent_profile WHERE agent_id = %s",
        (AGENT_ID,),
    ).fetchone()
    if not row or not row[2]:
        return
    folders = [f for f in _load_folders(row[0]) if f != SKILL]
    bind.exec_driver_sql(
        "UPDATE da_agent_profile SET skill_folders_json = %s, updated_at = CURRENT_TIMESTAMP WHERE agent_id = %s",
        (json.dumps(folders, ensure_ascii=False, sort_keys=True), AGENT_ID),
    )
    if str(row[1] or "").strip() == _NEW_PROMPT:
        bind.exec_driver_sql(
            "UPDATE da_agent_profile SET system_prompt = %s, updated_at = CURRENT_TIMESTAMP WHERE agent_id = %s",
            (_PREV_PROMPT, AGENT_ID),
        )
