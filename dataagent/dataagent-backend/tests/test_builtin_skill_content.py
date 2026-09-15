from __future__ import annotations

import json
from pathlib import Path


SKILLS_ROOT = Path(__file__).resolve().parents[2] / ".claude" / "skills"
SYSTEM_PROMPT = Path(__file__).resolve().parents[1] / "prompts" / "data_agent_system_prompt.md"
SQL_SKILL_ROOT = SKILLS_ROOT / "dataagent-nl2sql"
BUSINESS_SKILL_ROOT = SKILLS_ROOT / "opendataworks-business-knowledge"
PLATFORM_TOOLS_SKILL_ROOT = SKILLS_ROOT / "opendataworks-platform-tools"
ONTOLOGY_MODELING_SKILL_ROOT = SKILLS_ROOT / "ontology-modeling-assistant"
DATA_DEV_SKILL_ROOT = SKILLS_ROOT / "opendataworks-data-dev"
METHODOLOGY_DAG_SKILL_ROOT = SKILLS_ROOT / "opendataworks-methodology-dag"
CHART_SKILL_ROOT = SKILLS_ROOT / "chart-visualization"
REPORT_SKILL_ROOT = SKILLS_ROOT / "report-generation"


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

    # 已抽离为独立技能的脚本不得再出现在基础提示词里，命令模板下沉到各自的 SKILL.md。
    # export_query.py / run_sql.py 仍是平台取数能力，不在本次抽离范围内。
    for leaked in ("build_chart_spec.py", "generate_report.py", "format_answer.py"):
        assert leaked not in snapshot
    assert "必须通过 Bash 工具实际执行当前已启用的图表技能脚本" in snapshot
    assert "必须通过 Bash 工具实际执行当前已启用的报告技能脚本" in snapshot


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


def test_business_knowledge_skill_routes_minimal_evidence_and_stops_on_ambiguity():
    snapshot = _skill_text_snapshot(BUSINESS_SKILL_ROOT)

    required_tokens = [
        "不要为每个问题顺序读完全部参考文件",
        "拿到足够语义后立即停止读取",
        "默认排除 `deleted=1`",
        "先只追问 `db_name`",
        "不要先做精确查询、模糊搜索或候选表推荐",
        "真实查询返回空结果时",
    ]
    for token in required_tokens:
        assert token in snapshot


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


def test_data_dev_skill_documents_engine_aware_ddl_standards():
    snapshot = _skill_text_snapshot(DATA_DEV_SKILL_ROOT)

    # DDL standards reference exists and is wired into the skill.
    assert (DATA_DEV_SKILL_ROOT / "reference" / "40-ddl-standards.md").exists()
    assert "40-ddl-standards.md" in snapshot

    # Engine-aware CREATE TABLE knowledge, aligned with the backend
    # DorisTableEngineHandler.buildCreateDdl conventions.
    doris_tokens = [
        "ENGINE=OLAP",
        "DUPLICATE",
        "AGGREGATE",
        "UNIQUE KEY",
        "DISTRIBUTED BY HASH",
        "BUCKETS",
        "replication_num",
        "PARTITION BY RANGE",
        "分桶数量建议",
        "明细表",
    ]
    for token in doris_tokens:
        assert token in snapshot, token

    # MySQL DDL standards (knowledge-first, not executed by the tool yet).
    for token in ["InnoDB", "utf8mb4", "PRIMARY KEY"]:
        assert token in snapshot, token

    # Machine-readable engine defaults must match the backend defaults exactly
    # (DorisTableEngineHandler: bucketNum default 10, replicaNum default 3).
    rules = json.loads(
        (DATA_DEV_SKILL_ROOT / "assets" / "engine-ddl-rules.json").read_text(encoding="utf-8")
    )
    assert rules["doris"]["default_bucket_num"] == 10
    assert rules["doris"]["default_replica_num"] == 3
    assert rules["doris"]["default_table_model"] == "DUPLICATE"
    assert rules["mysql"]["engine"] == "InnoDB"
    assert rules["mysql"]["charset"] == "utf8mb4"

    # The create-table tool is referenced as the execution path.
    assert "portal_create_table" in snapshot


def test_data_dev_skill_presets_scenarios_and_gates_dml_validation():
    snapshot = _skill_text_snapshot(DATA_DEV_SKILL_ROOT)

    # Preset scenario SQL templates exist and are wired into the skill.
    assert (DATA_DEV_SKILL_ROOT / "reference" / "50-sql-scenarios.md").exists()
    assert "50-sql-scenarios.md" in snapshot
    # Writes use the delete-then-insert idiom (idempotent rerun), not INSERT OVERWRITE.
    for token in ("每日增量", "DELETE FROM", "INSERT INTO", "GROUP BY"):
        assert token in snapshot, token

    # DDL builds the target table directly via the tool, never as a scheduled
    # "DDL task"; data tasks only carry DML.
    assert "单独创建执行 DDL 的数据任务" in snapshot

    # DML must pass validation before it becomes a task / enters the plan.
    assert "portal_analyze_sql" in snapshot
    assert "验证不通过不建任务" in snapshot


def test_methodology_dag_skill_routes_lookup_first_and_falls_back_on_a_miss():
    snapshot = _skill_text_snapshot(METHODOLOGY_DAG_SKILL_ROOT)

    required_tokens = [
        "OpenDataWorks Methodology DAG Skill",
        "lookup_methodology.py",
        "run_methodology.py",
        "validate_methodology.py",
        # Lookup precedes execution, and a miss is a first-class path.
        "先检索，再决定",
        "回落",
        "matched=0",
        # The caliber travels with the result and must reach the user.
        "caliber",
        # Attribution vocabulary is shared with the SQL tool path.
        "result_state",
        "error_code",
        "failure_attribution",
        "platform_tools_unavailable",
    ]
    for token in required_tokens:
        assert token in snapshot, token

    # The skill must not re-document a second SQL execution entrypoint.
    assert "唯一推荐的 SQL 执行入口" not in snapshot


def test_methodology_dag_skill_stays_portable_and_embeds_no_root_path():
    """A skill bundle must run wherever it is installed.

    Baking in a host-injected root variable, a repo path, or the runtime's
    workspace staging layout would tie the bundle to one particular host, so the
    documented invocation is relative to the skill's own directory instead.
    """
    snapshot = _skill_text_snapshot(METHODOLOGY_DAG_SKILL_ROOT)

    assert "python3 scripts/run_methodology.py" in snapshot
    assert "python3 scripts/lookup_methodology.py" in snapshot
    assert "路径一律相对本技能目录" in snapshot

    forbidden_tokens = [
        # A root path injected by this particular backend.
        "DATAAGENT_METHODOLOGY_DAG_SKILL_ROOT",
        "${DATAAGENT_SKILL_ROOT}",
        # The workspace staging layout, which is an internal runtime detail.
        ".claude/skills/opendataworks-methodology-dag/scripts",
        # Repo-relative and deployment-absolute paths.
        "dataagent/.claude/skills/opendataworks-methodology-dag/scripts",
        "/app/.claude/skills",
    ]
    for token in forbidden_tokens:
        assert token not in snapshot, token


def test_methodology_dag_skill_forbids_bypassing_a_registered_caliber():
    snapshot = _skill_text_snapshot(METHODOLOGY_DAG_SKILL_ROOT)

    for token in (
        "不得",
        "口径漂移",
        "临时修改注册表",
        "升 `version`",
    ):
        assert token in snapshot, token


def test_methodology_dag_skill_declares_the_platform_tools_prerequisite():
    snapshot = _skill_text_snapshot(METHODOLOGY_DAG_SKILL_ROOT)

    assert "必须与 `opendataworks-platform-tools` 一起安装并启用" in snapshot
    # The neighbour is located from the skill's own path, not from a host variable.
    assert "../opendataworks-platform-tools" in snapshot
    # The sql node delegates rather than re-implementing SQL execution.
    assert "run_sql.py" in snapshot


def test_methodology_dag_registry_entries_declare_an_executable_caliber():
    registry_dir = METHODOLOGY_DAG_SKILL_ROOT / "assets" / "registry"
    entries = sorted(registry_dir.glob("*.json"))
    assert entries, "注册表为空"

    for path in entries:
        payload = json.loads(path.read_text(encoding="utf-8"))
        assert payload["id"] == path.stem
        for field in ("version", "intent", "caliber", "owner", "target", "nodes"):
            assert payload.get(field), f"{path.name} 缺少 {field}"
        assert payload["target"] in payload["nodes"], f"{path.name} 的 target 不是已定义节点"



def test_data_dev_skill_documents_metadata_completion():
    """data-dev 技能必须记录「完善已有表元数据」的入口工具与批量扫描+逐个完善配方。"""
    snapshot = _skill_text_snapshot(DATA_DEV_SKILL_ROOT)
    for token in (
        "portal_update_table_metadata",
        "完善已有表",
        "批量扫描",
        "loaded_at_field",
        "数据新鲜度",
    ):
        assert token in snapshot, f"data-dev 技能缺少: {token}"


def test_generic_presentation_skills_are_free_of_platform_coupling():
    """图表与报告技能必须能在没有 OpenDataWorks 的环境独立运行。

    这两个技能是从 opendataworks-platform-tools 抽出来的，一旦平台概念回流，
    不接入 OpenDataWorks 的部署就又用不了它们了。
    """
    forbidden_tokens = [
        "OpenDataWorks",
        "opendataworks",
        "portal",
        "odw-cli",
        "DATAAGENT_PLATFORM_SKILL_ROOT",
        "DATAAGENT_DATA_SCOPE_JSON",
        "X-Agent-Data-Scope",
    ]
    for root in (CHART_SKILL_ROOT, REPORT_SKILL_ROOT):
        snapshot = _skill_text_snapshot(root)
        assert snapshot, f"{root.name} 的文档快照为空"
        for token in forbidden_tokens:
            assert token not in snapshot, f"{root.name} 泄漏平台概念: {token}"

        for script in sorted((root / "scripts").glob("*.py")):
            source = script.read_text(encoding="utf-8")
            for token in forbidden_tokens:
                assert token not in source, f"{script} 泄漏平台概念: {token}"


def test_generic_presentation_skills_use_shared_skills_dir_anchor():
    """跨技能引用必须走通用锚点，不能再退回 per-skill 专属环境变量。"""
    for root, script_name in (
        (CHART_SKILL_ROOT, "build_chart_spec.py"),
        (REPORT_SKILL_ROOT, "generate_report.py"),
    ):
        skill_md = (root / "SKILL.md").read_text(encoding="utf-8")
        expected = f'"$DATAAGENT_PYTHON_BIN" "${{SKILLS_ROOT_DIR}}/{root.name}/scripts/{script_name}"'
        assert expected in skill_md, f"{root.name}/SKILL.md 缺少标准调用形式"


def test_generic_presentation_skills_do_not_import_platform_runtime():
    """脚本只能依赖技能自带的 _skill_io，不得跨目录 import 平台运行时。"""
    for root in (CHART_SKILL_ROOT, REPORT_SKILL_ROOT):
        assert (root / "scripts" / "_skill_io.py").exists(), f"{root.name} 缺少 _skill_io.py"
        for script in sorted((root / "scripts").glob("*.py")):
            source = script.read_text(encoding="utf-8")
            assert "_opendataworks_runtime" not in source, f"{script} 仍在 import 平台运行时"


def test_migrated_scripts_are_gone_from_platform_tools():
    """不保留兼容副本，避免两份等价实现漂移。"""
    for name in ("build_chart_spec.py", "generate_report.py", "format_answer.py"):
        assert not (PLATFORM_TOOLS_SKILL_ROOT / "scripts" / name).exists(), f"{name} 仍留在平台技能中"
    assert not (PLATFORM_TOOLS_SKILL_ROOT / "assets" / "chart-template").exists()
    assert (CHART_SKILL_ROOT / "assets" / "chart-template" / "bar.json").exists()
