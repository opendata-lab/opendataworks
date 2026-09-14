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
    # provider_type and api_format describe different protocol contracts, so
    # existing rows cannot be migrated safely by copying or guessing values.
    # Reset the small provider registry and require an explicit reconfiguration.
    op.execute("DELETE FROM da_model_provider")
    # The legacy settings row contains a second provider copy. If it is left in
    # place, bootstrap_admin_settings recreates every deleted provider on the
    # next application start. Remove only provider-related fields and preserve
    # database, skill and widget settings.
    op.execute(
        """
        UPDATE da_agent_settings
        SET provider_id = '',
            model_name = '',
            anthropic_api_key = '',
            anthropic_auth_token = '',
            anthropic_base_url = '',
            raw_json = CASE
                WHEN JSON_VALID(raw_json) THEN JSON_REMOVE(
                    raw_json,
                    '$.provider_id',
                    '$.model',
                    '$.anthropic_api_key',
                    '$.anthropic_auth_token',
                    '$.anthropic_base_url',
                    '$.provider_settings',
                    '$.providers',
                    '$.validated_provider_id',
                    '$.validated_model',
                    '$.provider_validation_status',
                    '$.provider_validation_message',
                    '$.provider_validated_at'
                )
                ELSE JSON_OBJECT()
            END
        """
    )


def downgrade() -> None:
    op.execute("ALTER TABLE da_model_provider DROP COLUMN api_format")
