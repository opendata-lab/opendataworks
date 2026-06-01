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
