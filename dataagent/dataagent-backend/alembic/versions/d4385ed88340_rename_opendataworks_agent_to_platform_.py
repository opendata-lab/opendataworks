"""rename opendataworks agent to platform assistant

Revision ID: d4385ed88340
Revises: 20260529_000013
Create Date: 2026-05-29 15:27:43.695715
"""
from __future__ import annotations

from alembic import op
import sqlalchemy as sa



# revision identifiers, used by Alembic.
revision = 'd4385ed88340'
down_revision = '20260529_000013'
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        "UPDATE da_agent_profile "
        "SET name = 'OpenDataWorks平台助手' "
        "WHERE agent_id = 'agent_opendataworks'"
    )


def downgrade() -> None:
    op.execute(
        "UPDATE da_agent_profile "
        "SET name = 'OpenDataWorks助手智能体' "
        "WHERE agent_id = 'agent_opendataworks'"
    )
