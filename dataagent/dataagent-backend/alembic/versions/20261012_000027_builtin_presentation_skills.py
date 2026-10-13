"""Restore presentation skills on existing built-in agents and their drafts."""
from __future__ import annotations

import json

import sqlalchemy as sa
from alembic import op

revision = "20261012_000027"
down_revision = "20261005_000026"
branch_labels = None
depends_on = None

AGENT_IDS = ("agent_default", "agent_opendataworks")
SKILLS = ("chart-visualization", "report-generation")


def _append_skills(folders: list[str]) -> list[str]:
    return [*folders, *(skill for skill in SKILLS if skill not in folders)]


def _snapshot(raw: str | None) -> str | None:
    if raw is None:
        return None
    snapshot = json.loads(raw)
    folders = snapshot.get("skill_folders") or []
    updated = _append_skills(folders)
    if updated == folders:
        return raw
    snapshot["skill_folders"] = updated
    return json.dumps(snapshot, ensure_ascii=False, sort_keys=True)


def upgrade() -> None:
    bind = op.get_bind()
    for agent_id in AGENT_IDS:
        params = {"agent_id": agent_id}
        # Match the publish endpoint's draft -> profile lock order.
        draft = bind.execute(sa.text(
            "SELECT * FROM da_agent_draft WHERE agent_id = :agent_id FOR UPDATE"
        ), params).mappings().first()
        profile = bind.execute(sa.text(
            "SELECT skill_folders_json, is_builtin FROM da_agent_profile "
            "WHERE agent_id = :agent_id FOR UPDATE"
        ), params).mappings().first()
        if not profile or not profile["is_builtin"]:
            continue
        folders = json.loads(profile["skill_folders_json"] or "[]")
        updated = _append_skills(folders)
        if updated != folders:
            bind.execute(sa.text(
                "UPDATE da_agent_profile SET skill_folders_json = :skills, "
                "updated_at = CURRENT_TIMESTAMP WHERE agent_id = :agent_id"
            ), {**params, "skills": json.dumps(updated, ensure_ascii=False)})
        if not draft:
            continue
        draft_json = _snapshot(draft["draft_json"])
        published_json = _snapshot(draft["published_json"])
        draft_changed = draft_json != draft["draft_json"]
        published_changed = published_json != draft["published_json"]
        if draft_changed or published_changed:
            bind.execute(sa.text(
                "UPDATE da_agent_draft SET draft_json = :draft_json, published_json = :published_json, "
                "revision = revision + :draft_changed, published_version = published_version + :published_changed, "
                "preview_task_id = NULL, preview_revision = NULL, preview_token = NULL, "
                "updated_at = CURRENT_TIMESTAMP WHERE agent_id = :agent_id"
            ), {**params, "draft_json": draft_json, "published_json": published_json,
                "draft_changed": int(draft_changed), "published_changed": int(published_changed)})


def downgrade() -> None:
    # Ownership of an existing binding is unknown; never remove operator choices.
    pass
