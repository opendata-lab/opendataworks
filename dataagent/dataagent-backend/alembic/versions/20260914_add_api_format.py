"""Add api_format column to da_model_provider."""
from alembic import op

revision = "20260914_add_api_format"
down_revision = "20260911_000025"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        ALTER TABLE da_model_provider
        ADD COLUMN api_format VARCHAR(64) NOT NULL DEFAULT '/v1/messages' COMMENT 'API格式: /v1/messages 或 /v1/chat/completions'
        AFTER provider_type
        """
    )


def downgrade() -> None:
    op.execute("ALTER TABLE da_model_provider DROP COLUMN api_format")
