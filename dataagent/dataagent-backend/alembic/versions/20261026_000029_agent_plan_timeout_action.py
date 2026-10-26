"""Agent setting for a plan card nobody answers: keep waiting or approve."""
from alembic import op

revision = "20261026_000029"
down_revision = "20261022_000028"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        "ALTER TABLE da_agent_profile ADD COLUMN plan_timeout_action VARCHAR(16) NOT NULL DEFAULT 'park' "
        "COMMENT '计划等待超时后的处理：park 继续等待，approve 自动执行'"
    )


def downgrade() -> None:
    op.execute("ALTER TABLE da_agent_profile DROP COLUMN plan_timeout_action")
