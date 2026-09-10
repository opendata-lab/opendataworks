"""add AgentRecordV1 envelope columns to da_agent_sdk_record

Revision ID: 20260910_000023
Revises: 20260910_000022
Create Date: 2026-09-10 20:00:00

The writer declared it stored NeutralAgentEvents but persisted only the payload,
discarding event_id, run_id, sequence and the engine's timestamp. Without them a
record cannot be deduplicated, ordered against its producer, or attributed to an
engine — all of which history replay needs once it stops going through a second
projection.

Every column is nullable so existing rows stay valid and the reader can tell a
legacy row from an enveloped one by contract_version being NULL.
"""
from __future__ import annotations

from alembic import op
from sqlalchemy import inspect

revision = "20260910_000023"
down_revision = "20260910_000022"
branch_labels = None
depends_on = None

_COLUMNS = (
    ("contract_version", "SMALLINT NULL"),
    ("engine_kind", "VARCHAR(32) NULL"),
    ("event_id", "VARCHAR(64) NULL"),
    ("run_id", "VARCHAR(64) NULL"),
    ("task_attempt_id", "VARCHAR(64) NULL"),
    ("engine_sequence", "BIGINT NULL"),
    ("occurred_at", "DATETIME(3) NULL"),
)


def _existing_columns() -> set[str]:
    inspector = inspect(op.get_bind())
    if not inspector.has_table("da_agent_sdk_record"):
        return set()
    return {col["name"] for col in inspector.get_columns("da_agent_sdk_record")}


def upgrade() -> None:
    existing = _existing_columns()
    if not existing:
        return
    for name, ddl in _COLUMNS:
        if name not in existing:
            op.execute(f"ALTER TABLE da_agent_sdk_record ADD COLUMN {name} {ddl}")

    # Dedup by producer identity. Partial uniqueness is not available in MySQL,
    # so this is a plain index: legacy rows have NULL event_id and are exempt
    # from uniqueness anyway.
    existing_indexes = {
        idx["name"] for idx in inspect(op.get_bind()).get_indexes("da_agent_sdk_record")
    }
    if "idx_da_agent_sdk_record_event_id" not in existing_indexes:
        op.execute(
            "CREATE INDEX idx_da_agent_sdk_record_event_id "
            "ON da_agent_sdk_record (task_id, event_id)"
        )


def downgrade() -> None:
    existing = _existing_columns()
    if not existing:
        return
    existing_indexes = {
        idx["name"] for idx in inspect(op.get_bind()).get_indexes("da_agent_sdk_record")
    }
    if "idx_da_agent_sdk_record_event_id" in existing_indexes:
        op.execute("DROP INDEX idx_da_agent_sdk_record_event_id ON da_agent_sdk_record")
    for name, _ddl in _COLUMNS:
        if name in existing:
            op.execute(f"ALTER TABLE da_agent_sdk_record DROP COLUMN {name}")
