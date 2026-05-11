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

    forbidden_tokens = [
        "CFC环境名称",
        "数据中心名称",
        "组件名称",
        "接口名称",
        "env_name",
        "PROD",
        "SIM",
        "component_name",
        "interface_name",
        "dwd_tech_dev_inspection_rule_cnt_di",
        "host sh+curl+pymysql",
        "ODW_MYSQL_HOST",
        "ODW_MYSQL_PORT",
        "ODW_MYSQL_USER",
        "ODW_MYSQL_PASSWORD",
        "ODW_MYSQL_DATABASE",
        "数据源账号密码只在脚本内部使用",
    ]
    for token in forbidden_tokens:
        assert token not in snapshot

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
        "不再直连业务数据库",
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
