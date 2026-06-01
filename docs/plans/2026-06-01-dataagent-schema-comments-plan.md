# DataAgent Schema Comments Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add MySQL table and column comments to the DataAgent-owned Alembic schema without changing runtime behavior.

**Architecture:** Add one Alembic migration after the current head. The migration stores comment metadata in Python dictionaries and applies comments defensively only when tables and columns exist. A focused pytest module covers the DDL-rendering helper so the migration preserves column defaults and special clauses while adding comments.

**Tech Stack:** Python, Alembic, SQLAlchemy, PyMySQL, MySQL 8, pytest.

---

### Task 1: Add DDL Helper Contract Tests

**Files:**

- Create: `dataagent/dataagent-backend/tests/test_schema_comment_migration.py`

- [ ] **Step 1: Write the failing test**

Create tests that import the new migration module and verify the helper renders safe MySQL column definitions:

```python
from __future__ import annotations

import importlib.util
from pathlib import Path
from types import ModuleType


MIGRATION_PATH = (
    Path(__file__).resolve().parents[1]
    / "alembic"
    / "versions"
    / "20260601_000014_add_dataagent_schema_comments.py"
)


def _load_migration() -> ModuleType:
    spec = importlib.util.spec_from_file_location("schema_comment_migration", MIGRATION_PATH)
    assert spec is not None
    assert spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_build_column_definition_preserves_varchar_default_and_comment() -> None:
    migration = _load_migration()
    row = {
        "COLUMN_TYPE": "varchar(64)",
        "DATA_TYPE": "varchar",
        "IS_NULLABLE": "NO",
        "COLUMN_DEFAULT": "waiting",
        "EXTRA": "",
        "CHARACTER_SET_NAME": "utf8mb4",
        "COLLATION_NAME": "utf8mb4_unicode_ci",
    }

    assert migration._build_column_definition(row, "任务状态") == (
        "varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci "
        "NOT NULL DEFAULT 'waiting' COMMENT '任务状态'"
    )


def test_build_column_definition_preserves_timestamp_on_update() -> None:
    migration = _load_migration()
    row = {
        "COLUMN_TYPE": "datetime",
        "DATA_TYPE": "datetime",
        "IS_NULLABLE": "NO",
        "COLUMN_DEFAULT": "CURRENT_TIMESTAMP",
        "EXTRA": "on update CURRENT_TIMESTAMP",
        "CHARACTER_SET_NAME": None,
        "COLLATION_NAME": None,
    }

    assert migration._build_column_definition(row, "更新时间") == (
        "datetime NOT NULL DEFAULT CURRENT_TIMESTAMP "
        "ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'"
    )


def test_build_column_definition_preserves_timestamp_default_with_empty_parentheses() -> None:
    migration = _load_migration()
    row = {
        "COLUMN_TYPE": "datetime",
        "DATA_TYPE": "datetime",
        "IS_NULLABLE": "NO",
        "COLUMN_DEFAULT": "current_timestamp()",
        "EXTRA": "on update current_timestamp()",
        "CHARACTER_SET_NAME": None,
        "COLLATION_NAME": None,
    }

    assert migration._build_column_definition(row, "更新时间") == (
        "datetime NOT NULL DEFAULT CURRENT_TIMESTAMP() "
        "ON UPDATE CURRENT_TIMESTAMP() COMMENT '更新时间'"
    )


def test_build_column_definition_preserves_auto_increment() -> None:
    migration = _load_migration()
    row = {
        "COLUMN_TYPE": "bigint",
        "DATA_TYPE": "bigint",
        "IS_NULLABLE": "NO",
        "COLUMN_DEFAULT": None,
        "EXTRA": "auto_increment",
        "CHARACTER_SET_NAME": None,
        "COLLATION_NAME": None,
    }

    assert migration._build_column_definition(row, "自增主键") == (
        "bigint NOT NULL AUTO_INCREMENT COMMENT '自增主键'"
    )


def test_comment_migration_extends_current_dataagent_head() -> None:
    migration = _load_migration()

    assert migration.down_revision == "20260529_000014"


def test_comment_migration_covers_widget_event_table() -> None:
    migration = _load_migration()

    assert migration.TABLE_COMMENTS["da_agent_widget_event"] == "DataAgent嵌入组件事件埋点表"
    assert migration.COLUMN_COMMENTS["da_agent_widget_event"]["event_type"] == "事件类型"
    assert migration.COLUMN_COMMENTS["da_agent_widget_event"]["payload_json"] == "事件负载JSON"
```

- [ ] **Step 2: Run the test and confirm RED**

Run:

```bash
cd dataagent/dataagent-backend
. .venv-py313/bin/activate
pytest tests/test_schema_comment_migration.py -q
```

Expected: fail with `ModuleNotFoundError` because the migration has not been created yet.

### Task 2: Add DataAgent Schema Comment Migration

**Files:**

- Create: `dataagent/dataagent-backend/alembic/versions/20260601_000014_add_dataagent_schema_comments.py`

- [ ] **Step 1: Implement the migration**

Create a migration with revision `20260601_000014`, down revision `20260529_000014`, helper functions for quoting/default rendering, and comment dictionaries for all in-scope tables and columns.

- [ ] **Step 2: Re-run the focused test and confirm GREEN**

Run:

```bash
cd dataagent/dataagent-backend
. .venv-py313/bin/activate
pytest tests/test_schema_comment_migration.py -q
```

Expected: all tests pass.

### Task 3: Verify Alembic And MySQL Metadata

**Files:**

- Use: `dataagent/dataagent-backend/alembic.ini`
- Use: `dataagent/dataagent-backend/alembic/env.py`

- [ ] **Step 1: Syntax-check the migration**

Run:

```bash
cd dataagent/dataagent-backend
. .venv-py313/bin/activate
python -m py_compile alembic/versions/20260601_000014_add_dataagent_schema_comments.py
```

Expected: command exits successfully.

- [ ] **Step 2: Run local Alembic upgrade when MySQL is reachable**

Use the repository default local DataAgent session database:

```bash
cd dataagent/dataagent-backend
. .venv-py313/bin/activate
MYSQL_HOST=127.0.0.1 \
MYSQL_PORT=3316 \
MYSQL_USER=dataagent \
MYSQL_PASSWORD=dataagent123 \
MYSQL_DATABASE=opendataworks \
SESSION_MYSQL_DATABASE=dataagent \
alembic upgrade head
```

Expected: upgrade completes successfully.

- [ ] **Step 3: Query representative comments**

Run a MySQL metadata query for representative table and column comments:

```sql
SELECT TABLE_NAME, TABLE_COMMENT
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = 'dataagent'
  AND TABLE_NAME IN ('da_agent_task', 'da_agent_profile');

SELECT TABLE_NAME, COLUMN_NAME, COLUMN_COMMENT
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = 'dataagent'
  AND TABLE_NAME = 'da_agent_task'
  AND COLUMN_NAME IN ('task_id', 'task_status', 'timeout_seconds');
```

Expected: table and column comments are non-empty and match the migration dictionary.
