from __future__ import annotations

import json
from pathlib import Path


SKILLS_ROOT = Path(__file__).resolve().parents[2] / ".claude" / "skills"
SYSTEM_PROMPT = Path(__file__).resolve().parents[1] / "prompts" / "data_agent_system_prompt.md"
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


def test_generic_nl2sql_methodology_lives_in_system_prompt_file():
    snapshot = SYSTEM_PROMPT.read_text(encoding="utf-8")

    required_tokens = [
        "企业级智能问数 Data Agent",
        "你不是单纯的 SQL 生成器",
        "任务路由",
        "标准工作流",
        "澄清规则",
        "SQL 与数据使用原则",
        "归因分析原则",
        "输出规范",
        "失败处理",
    ]
    for token in required_tokens:
        assert token in snapshot

    forbidden_tokens = [
        "dataagent-nl2sql",
        "通用 SQL skill",
        "workflow_publish_record",
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


def test_dataagent_nl2sql_skill_bundle_is_removed():
    assert not SQL_SKILL_ROOT.exists()


def test_system_prompt_documents_data_quality_gate():
    snapshot = SYSTEM_PROMPT.read_text(encoding="utf-8")

    required_tokens = [
        "先确认相关表、字段、关联关系和时间字段",
        "生成的查询应尽量简单、可解释、可审查",
        "查询条件、聚合逻辑和时间范围必须与用户问题一致",
        "区分目标表是每日全量快照表还是每日增量表",
        "每日全量快照表用于常规问数时默认只查最新日期",
        "只有归因分析、趋势分析、对比分析等需要历史基线的场景才查询历史日期",
        "每日增量表按用户给定或澄清后的时间范围查询",
        "不确定字段含义时，先查元数据或业务语义，不盲写",
        "输出时说明关键过滤条件与统计口径",
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
    assert "dataagent-nl2sql" not in snapshot
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
    assert "dataagent-nl2sql" not in snapshot
    assert "run_sql.py" not in snapshot
    assert "validate_sql.py" not in snapshot


def test_business_ontology_supports_platform_table_troubleshooting():
    ontology = json.loads((BUSINESS_SKILL_ROOT / "assets" / "ontology.json").read_text(encoding="utf-8"))

    object_types = {item["id"]: item for item in ontology["object_types"]}
    required_types = {
        "platform_table",
        "platform_table_field",
        "platform_task",
        "platform_task_table_relation",
        "platform_lineage_edge",
        "platform_task_execution_log",
        "platform_table_statistics_snapshot",
    }
    assert required_types <= set(object_types)

    task_columns = {prop["column"] for prop in object_types["platform_task"]["properties"]}
    assert {"id", "task_name", "task_code", "task_sql", "status"} <= task_columns

    registered_sources = {
        source["table"]
        for item in object_types.values()
        for source in item.get("physical_sources", [])
    }
    assert {
        "opendataworks.data_table",
        "opendataworks.data_field",
        "opendataworks.data_task",
        "opendataworks.table_task_relation",
        "opendataworks.data_lineage",
        "opendataworks.task_execution_log",
    } <= registered_sources

    relations = {item["id"]: item for item in ontology["object_relations"]}
    assert {
        "table_has_fields",
        "table_read_by_task",
        "table_written_by_task",
        "task_reads_table",
        "task_writes_table",
        "table_upstream_lineage",
        "table_downstream_lineage",
        "task_latest_execution_log",
    } <= set(relations)
