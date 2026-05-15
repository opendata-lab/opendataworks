from __future__ import annotations

from pathlib import Path


SKILLS_ROOT = Path(__file__).resolve().parents[2] / ".claude" / "skills"
SQL_SKILL_ROOT = SKILLS_ROOT / "dataagent-nl2sql"
BUSINESS_SKILL_ROOT = SKILLS_ROOT / "opendataworks-business-knowledge"
PLATFORM_TOOLS_SKILL_ROOT = SKILLS_ROOT / "opendataworks-platform-tools"


def _skill_text_snapshot(root: Path) -> str:
    paths = [root / "SKILL.md"]
    for folder in ("reference", "assets"):
        folder_path = root / folder
        if folder_path.exists():
            paths.extend(sorted(folder_path.rglob("*.md")))
            paths.extend(sorted(folder_path.rglob("*.json")))
    return "\n".join(path.read_text(encoding="utf-8") for path in paths)


def test_generic_sql_skill_keeps_methodology_and_tool_contracts_only():
    snapshot = _skill_text_snapshot(SQL_SKILL_ROOT)

    required_tokens = [
        "DataAgent Generic SQL Skill",
        "通用问数 SQL 方法",
        "SQL 前检查",
        "相似表",
        "候选表",
        "业务知识 Skill",
        "OpenDataWorks Platform Tools Skill",
        "失败处理",
    ]
    for token in required_tokens:
        assert token in snapshot

    forbidden_tokens = [
        "workflow_publish_record",
        "data_table",
        "数据层级",
        "工作流发布记录",
        "发布记录数",
        "失败发布次数",
        "DF快照表",
        "DI增量表",
        "mcp__portal__portal_search_tables",
        "mcp__portal__portal_get_lineage",
        "mcp__portal__portal_get_table_ddl",
        "mcp__portal__portal_query_readonly",
        "validate_sql.py",
        "run_sql.py",
        "DATAAGENT_PLATFORM_SKILL_ROOT",
        "tool output contract",
    ]
    for token in forbidden_tokens:
        assert token not in snapshot
    assert not (SQL_SKILL_ROOT / "scripts").exists()
    assert not (SQL_SKILL_ROOT / "bin").exists()


def test_generic_sql_skill_documents_data_quality_gate():
    snapshot = _skill_text_snapshot(SQL_SKILL_ROOT)

    required_tokens = [
        "数据问数质量门禁",
        "执行 SQL 前必须确认目标、库/引擎/schema、表、使用字段、指标公式、过滤条件、时间范围、维度/粒度",
        "涉及 JOIN、去重、明细定位、血缘映射时，必须确认主键、唯一键或关联键",
        "主键、唯一键或关联键不是所有简单聚合的硬门槛",
    ]
    for token in required_tokens:
        assert token in snapshot


def test_platform_tools_skill_documents_run_sql_as_only_recommended_sql_execution_entrypoint():
    snapshot = _skill_text_snapshot(PLATFORM_TOOLS_SKILL_ROOT)

    required_tokens = [
        "OpenDataWorks Platform Tools Skill",
        "DATAAGENT_PLATFORM_SKILL_ROOT",
        "validate_sql.py 是唯一推荐的 SQL 验证入口",
        "run_sql.py 是唯一推荐的 SQL 执行入口",
        "语义确认 → SQL 生成 → SQL 验证 → run_sql.py 执行 → 结果收口",
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


def test_platform_tools_skill_contains_platform_capabilities_without_business_semantics():
    snapshot = _skill_text_snapshot(PLATFORM_TOOLS_SKILL_ROOT)

    required_tokens = [
        "OpenDataWorks Platform Tools Skill",
        "平台工具 Skill",
        "获取表",
        "获取字段",
        "获取血缘",
        "执行只读 SQL",
        "portal-mcp",
        "mcp__portal__portal_search_tables",
        "mcp__portal__portal_get_lineage",
        "mcp__portal__portal_get_table_ddl",
        "mcp__portal__portal_query_readonly",
        "inspect_metadata.py",
        "resolve_datasource.py",
        "get_table_ddl.py",
        "get_lineage.py",
        "validate_sql.py",
        "run_sql.py",
        "sql_execution",
        "chart_spec",
    ]
    for token in required_tokens:
        assert token in snapshot

    assert (PLATFORM_TOOLS_SKILL_ROOT / "scripts" / "run_sql.py").exists()
    assert (PLATFORM_TOOLS_SKILL_ROOT / "scripts" / "validate_sql.py").exists()
    assert (PLATFORM_TOOLS_SKILL_ROOT / "bin" / "odw-cli").exists()
    assert "metrics.json" not in snapshot
    assert "ontology.json" not in snapshot
    assert "发布记录数" not in snapshot


def test_business_knowledge_skill_contains_semantics_without_execution_scripts():
    snapshot = _skill_text_snapshot(BUSINESS_SKILL_ROOT)

    required_tokens = [
        "OpenDataWorks Business Knowledge Skill",
        "业务知识 Skill",
        "术语",
        "本体",
        "指标口径",
        "别名",
        "歧义消解",
        "业务规则例外",
        "数据层级",
        "workflow_publish_record",
        "发布记录数",
        "失败发布次数",
        "ontology.json",
        "不提供 SQL 验证或执行脚本",
    ]
    for token in required_tokens:
        assert token in snapshot

    assert not (BUSINESS_SKILL_ROOT / "scripts").exists()
    assert not (BUSINESS_SKILL_ROOT / "bin").exists()
    assert "run_sql.py" not in snapshot
    assert "validate_sql.py" not in snapshot
