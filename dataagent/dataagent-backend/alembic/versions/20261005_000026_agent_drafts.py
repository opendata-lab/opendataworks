"""Separate agent drafts from published configurations and preview topics."""
from alembic import op

revision = "20261005_000026"
down_revision = "20260914_add_api_format"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute("""
        CREATE TABLE da_agent_draft (
            agent_id VARCHAR(64) NOT NULL PRIMARY KEY,
            draft_json LONGTEXT NOT NULL,
            revision BIGINT NOT NULL DEFAULT 1,
            published_version BIGINT NOT NULL DEFAULT 0,
            published_json LONGTEXT NULL,
            preview_task_id VARCHAR(64) NULL,
            preview_revision BIGINT NULL,
            preview_token VARCHAR(64) NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
            updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """)
    op.execute("ALTER TABLE da_agent_topic ADD COLUMN is_agent_preview TINYINT NOT NULL DEFAULT 0")


def downgrade() -> None:
    op.execute("ALTER TABLE da_agent_topic DROP COLUMN is_agent_preview")
    op.execute("DROP TABLE da_agent_draft")
