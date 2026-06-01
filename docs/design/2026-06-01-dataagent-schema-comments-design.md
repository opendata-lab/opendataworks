# DataAgent Schema Comments Design

## Current State

DataAgent persists runtime settings, skill documents, topics, tasks, messages, schedules, agent profiles, and SDK stream records in a dedicated MySQL schema managed by Alembic under `dataagent/dataagent-backend/alembic/versions/`.

The main OpenDataWorks backend Flyway migrations already use MySQL table and column comments extensively. The DataAgent Alembic migrations currently create most `da_*` tables without table-level or column-level `COMMENT` metadata, which makes database inspection, governance checks, and operations handoff harder.

## Problem

The DataAgent session schema is structurally usable but lacks human-readable metadata. Operators inspecting `information_schema`, database tools, or governance reports cannot quickly identify what each table and field represents.

Updating old Alembic migrations alone would not fix already-deployed environments, because those migrations have already run. It would also create drift between historical migration files and live schemas.

## Scope

This change covers the current DataAgent-owned `da_*` tables at Alembic head:

- `da_agent_settings`
- `da_skill_document`
- `da_skill_document_version`
- `da_agent_topic`
- `da_agent_task`
- `da_agent_message`
- `da_agent_chunk`
- `da_agent_message_queue`
- `da_agent_message_schedule`
- `da_agent_message_schedule_log`
- `da_agent_profile`
- `da_agent_sdk_record`

The legacy `da_chat_*` tables are out of scope because they are dropped by `20260323_000004_magic_task_model.py`.

No public API, runtime behavior, persistence contract, or deployment contract changes are intended.

## Solution

Add a new Alembic migration after `20260529_000013_add_agent_preset_questions.py` that applies MySQL table and column comments to the current DataAgent schema.

The migration will be defensive:

- skip when the connected dialect is not MySQL or MariaDB
- skip missing tables and columns so partially migrated local schemas do not fail
- set table comments with `ALTER TABLE <table> COMMENT = '<comment>'`
- set column comments by reconstructing the existing column definition from `information_schema.COLUMNS`, then running `ALTER TABLE <table> MODIFY COLUMN <column> ... COMMENT '<comment>'`

Reconstructing column definitions avoids hardcoding every type, default, nullable flag, `AUTO_INCREMENT`, and `ON UPDATE` clause in the migration. This keeps the migration aligned with the live schema and reduces the chance of accidentally dropping defaults while adding comments.

## Interfaces

The only persistent interface change is database metadata:

- `information_schema.TABLES.TABLE_COMMENT`
- `information_schema.COLUMNS.COLUMN_COMMENT`

Application SQL reads and writes continue to use the same tables and columns.

## Risks And Tradeoffs

`MODIFY COLUMN` can be risky if it omits part of the current column definition. To reduce that risk, the migration builds the column definition from `information_schema` and includes type, charset/collation, nullability, default, generated default marker where needed, `ON UPDATE`, and `AUTO_INCREMENT`.

The migration targets MySQL/MariaDB only. That matches the DataAgent runtime and `alembic/env.py`, which builds a `mysql+pymysql` URL from `SESSION_MYSQL_DATABASE`.

## Verification

Verification should include:

- focused pytest coverage for the helper that renders MySQL `MODIFY COLUMN` definitions
- Python syntax/import check for the new migration
- `alembic upgrade head` against the local DataAgent MySQL schema when available
- `information_schema.TABLES` and `information_schema.COLUMNS` checks confirming comments exist after upgrade

If the local MySQL service is unavailable, the result should explicitly report that only script-level and unit-level verification was completed.
