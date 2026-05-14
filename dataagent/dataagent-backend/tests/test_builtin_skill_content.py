from __future__ import annotations

from pathlib import Path


SKILL_ROOT = Path(__file__).resolve().parents[2] / ".claude" / "skills" / "dataagent-nl2sql"


def _skill_text_snapshot() -> str:
    paths = [SKILL_ROOT / "SKILL.md"]
    paths.extend(sorted((SKILL_ROOT / "reference").rglob("*.md")))
    paths.extend(sorted((SKILL_ROOT / "assets").rglob("*.json")))
    return "\n".join(path.read_text(encoding="utf-8") for path in paths)


def test_builtin_skill_keeps_generic_df_di_rules_without_business_defaults():
    snapshot = _skill_text_snapshot()

    required_tokens = [
        "DF快照表",
        "DI增量表",
        "workflow_publish_record",
        "data_lineage",
        "ds",
        "portal-mcp",
        "mcp__portal__portal_search_tables",
        "mcp__portal__portal_get_lineage",
        "mcp__portal__portal_get_table_ddl",
        "mcp__portal__portal_query_readonly",
        "get_lineage.py",
        "get_table_ddl.py",
        "odw-cli lineage",
        "odw-cli ddl",
        "query-readonly",
        "/api/v1/ai/query/read",
        "不再直连外部数据源",
        "http://backend:8080/api/v1/ai",
        "DATAAGENT_ORIGINAL_QUESTION",
        "DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1",
    ]
    for token in required_tokens:
        assert token in snapshot


def test_builtin_skill_documents_data_quality_gate():
    snapshot = _skill_text_snapshot()

    required_tokens = [
        "数据问数质量门禁",
        "执行 SQL 前必须确认目标、库/引擎/schema、表、使用字段、指标公式、过滤条件、时间范围、维度/粒度",
        "涉及 JOIN、去重、明细定位、血缘映射时，必须确认主键、唯一键或关联键",
        "主键、唯一键或关联键不是所有简单聚合的硬门槛",
    ]
    for token in required_tokens:
        assert token in snapshot


def test_builtin_skill_documents_run_sql_as_only_recommended_sql_execution_entrypoint():
    snapshot = _skill_text_snapshot()

    required_tokens = [
        "validate_sql.py 是唯一推荐的 SQL 验证入口",
        "run_sql.py 是唯一推荐的 SQL 执行入口",
        "语义匹配 → SQL 生成 → SQL 验证 → run_sql.py 执行 → 结果收口",
        "先 `validate_sql.py`，再 `run_sql.py`",
        "必须拿到真实只读结果后回答",
        "不得只输出 SQL 或要求用户自行执行",
        "看不到 run_sql.py 或 backend 查询不可用时",
        "failure_attribution",
        "error_code",
        "result_state",
    ]
    for token in required_tokens:
        assert token in snapshot
    assert "私有 Skill 的 validate_sql.py" not in snapshot
