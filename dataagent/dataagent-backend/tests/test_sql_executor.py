from __future__ import annotations

import sys
import types
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

if "pymysql" not in sys.modules:
    sys.modules["pymysql"] = types.SimpleNamespace(
        connect=lambda *args, **kwargs: None,
        cursors=types.SimpleNamespace(DictCursor=object),
        connections=types.SimpleNamespace(Connection=object),
    )

from core.sql_executor import execute_sql


def test_execute_sql_requires_explicit_database():
    result = execute_sql("SELECT 1", database=None)
    assert result.error == "未提供目标 database，请让 Skill 或调用方显式指定库名"


def test_execute_sql_rejects_database_outside_runtime_data_scope(monkeypatch):
    monkeypatch.setenv(
        "DATAAGENT_DATA_SCOPE_JSON",
        '{"allowed_scopes":[{"cluster_id":3,"source_type":"DORIS","database":"ads_user"}]}',
    )

    result = execute_sql("SELECT 1", database="ods_user")

    assert "数据范围限制" in result.error
