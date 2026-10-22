"""Persist the effective system prompt of each task, deduplicated by content hash."""
from alembic import op

revision = "20261022_000028"
down_revision = "20261012_000027"
branch_labels = None
depends_on = None


def upgrade() -> None:
    # Collation must match da_agent_task: the hash is joined across the two
    # tables, and MySQL 8's server default (utf8mb4_0900_ai_ci) refuses that.
    op.execute("""
        CREATE TABLE da_system_prompt (
            prompt_sha256 CHAR(64) NOT NULL PRIMARY KEY,
            content MEDIUMTEXT NOT NULL,
            byte_length INT NOT NULL,
            created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
    """)
    op.execute("ALTER TABLE da_agent_task ADD COLUMN system_prompt_sha256 CHAR(64) NULL")


def downgrade() -> None:
    op.execute("ALTER TABLE da_agent_task DROP COLUMN system_prompt_sha256")
    op.execute("DROP TABLE da_system_prompt")
