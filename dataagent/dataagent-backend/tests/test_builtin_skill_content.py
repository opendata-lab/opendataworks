from __future__ import annotations

import json
from pathlib import Path


SKILLS_ROOT = Path(__file__).resolve().parents[2] / ".claude" / "skills"
SYSTEM_PROMPT = Path(__file__).resolve().parents[1] / "prompts" / "data_agent_system_prompt.md"
SQL_SKILL_ROOT = SKILLS_ROOT / "dataagent-nl2sql"
BUSINESS_SKILL_ROOT = SKILLS_ROOT / "opendataworks-business-knowledge"
PLATFORM_TOOLS_SKILL_ROOT = SKILLS_ROOT / "opendataworks-platform-tools"
ONTOLOGY_MODELING_SKILL_ROOT = SKILLS_ROOT / "ontology-modeling-assistant"


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
        "硬性约束",
        "SQL 前确认清单",
        "图表输出",
        "结论与图表的关联要求",
        "结论部分必须明确引用该图表",
        "不得超出图表所呈现的事实范围做推断",
        "输出要求",
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
        "tool output contract",
    ]
    for token in forbidden_tokens:
        assert token not in snapshot

    assert '"$DATAAGENT_PYTHON_BIN" "${DATAAGENT_PLATFORM_SKILL_ROOT}/scripts/build_chart_spec.py"' in snapshot


def test_dataagent_nl2sql_skill_bundle_is_removed():
    assert not SQL_SKILL_ROOT.exists()


def test_system_prompt_documents_data_quality_gate():
    snapshot = SYSTEM_PROMPT.read_text(encoding="utf-8")

    required_tokens = [
        "是每日全量快照表还是每日增量表",
        "每日全量快照表用于常规问数时默认只取最新快照日期",
        "避免重复累计历史快照",
        "只有归因分析、趋势分析、对比分析等需要历史基线的场景才查询历史日期",
        "每日增量表按用户给定或澄清后的时间范围查询",
        "未指定时优先采用本体或指标定义中的默认时间字段与默认时间范围",
        "任何默认时间口径都必须在回答中显式说明",
    ]
    for token in required_tokens:
        assert token in snapshot


def test_system_prompt_documents_pre_sql_confirmation_checklist():
    snapshot = SYSTEM_PROMPT.read_text(encoding="utf-8")

    required_tokens = [
        "SQL 前确认清单",
        "本体业务含义确认",
        "时间维度确认",
        "物理结构确认",
        "取值与过滤条件确认",
        "关联与粒度确认",
        "结果合理性校验",
        "不在 SQL 中使用任何未经元数据验证的表名或字段名",
        "不凭经验猜枚举值",
        "join 键来自本体关系或血缘定义",
        "警惕一对多 join 导致的重复计数",
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


def test_ontology_modeling_skill_contains_modeling_assets_without_sql_execution_entrypoints():
    snapshot = _skill_text_snapshot(ONTOLOGY_MODELING_SKILL_ROOT)

    required_tokens = [
        "Ontology Modeling Assistant Skill",
        "本体建模",
        "上传文档",
        "数据库表",
        "ontology.json",
        "ontology.schema.json",
        "ontology_schema.py",
        "lookup_ontology.py",
        "validate_ontology.py",
        "x-field-dictionary",
        "domain_ontology_skill",
        "scaffold_domain_ontology_skill",
        "relation_kind",
        "semantic_mapping",
        "caliber_rule",
        "object_types.kind",
        "cardinality",
    ]
    for token in required_tokens:
        assert token in snapshot

    ontology = json.loads((ONTOLOGY_MODELING_SKILL_ROOT / "assets" / "ontology.json").read_text(encoding="utf-8"))
    object_types = {item["id"]: item for item in ontology["object_types"]}
    assert {
        "domain_ontology_skill",
        "business_domain",
        "source_document",
        "physical_table",
        "domain_entity",
        "domain_attribute",
        "semantic_relation",
    } <= set(object_types)

    assert "semantic_edges" not in ontology
    assert "evidence_sources" not in ontology
    assert "quality_gates" not in ontology

    relations = {item["id"]: item for item in ontology["object_relations"]}
    assert {
        "term_maps_to_table_column",
        "document_mentions_domain_entity",
        "table_column_supports_attribute",
    } <= set(relations)
    assert relations["term_maps_to_table_column"]["relation_kind"] == "semantic_mapping"
    assert relations["document_mentions_domain_entity"]["relation_kind"] == "supports"
    assert relations["table_column_supports_attribute"]["relation_kind"] == "semantic_mapping"
    assert "document_evidence" not in snapshot
    assert "schema_evidence" not in snapshot
    assert "evidence_sources" not in snapshot
    assert "confidence" not in snapshot
    assert "置信" not in snapshot

    assert (ONTOLOGY_MODELING_SKILL_ROOT / "scripts" / "lookup_ontology.py").exists()
    assert (ONTOLOGY_MODELING_SKILL_ROOT / "scripts" / "validate_ontology.py").exists()
    assert (ONTOLOGY_MODELING_SKILL_ROOT / "scripts" / "ontology_schema.py").exists()
    assert (ONTOLOGY_MODELING_SKILL_ROOT / "assets" / "ontology.schema.json").exists()
    assert not (ONTOLOGY_MODELING_SKILL_ROOT / "reference" / "ontology-field-dictionary.md").exists()
    assert not (ONTOLOGY_MODELING_SKILL_ROOT / "reference" / "ontology-model-spec.md").exists()
    assert not (ONTOLOGY_MODELING_SKILL_ROOT / "bin").exists()
    assert "run_sql.py 是唯一推荐的 SQL 执行入口" not in snapshot
    assert "validate_sql.py 是唯一推荐的 SQL 验证入口" not in snapshot
