from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core import agent_runtime
from core.claude_cli import resolve_claude_cli_path
from config import Settings


def test_build_runtime_env_does_not_expose_direct_db_connection_settings(monkeypatch):
    monkeypatch.setattr(agent_runtime, "resolve_builtin_skill_root_dir", lambda: Path("/tmp/skill-root"))

    cfg = SimpleNamespace(query_result_limit=120)
    params = SimpleNamespace(
        question="workflow_publish_record 的上游表有哪些",
        sql_read_timeout_seconds=45,
        sql_write_timeout_seconds=90,
    )

    runtime_env = agent_runtime._build_runtime_env(
        cfg,
        {"CUSTOM_FLAG": "1"},
        params,
        {
            "primary_root": "/tmp/skill-root",
            "enabled_folders": ["opendataworks-business-knowledge", "opendataworks-platform-tools", "marketing-insights"],
            "enabled_roots": {
                "opendataworks-business-knowledge": "/tmp/skill-root",
                "opendataworks-platform-tools": "/tmp/platform-tools",
                "marketing-insights": "/tmp/marketing-insights",
            },
        },
    )

    assert runtime_env["CUSTOM_FLAG"] == "1"
    assert runtime_env["DATAAGENT_QUERY_LIMIT"] == "120"
    assert runtime_env["DATAAGENT_RESULT_PREVIEW_ROWS"] == "20"
    assert runtime_env["DATAAGENT_SQL_READ_TIMEOUT_SECONDS"] == "45"
    assert runtime_env["DATAAGENT_ORIGINAL_QUESTION"] == "workflow_publish_record 的上游表有哪些"
    assert runtime_env["DATAAGENT_SKILL_ROOT"] == str(Path("/tmp/skill-root").resolve())
    assert runtime_env["DATAAGENT_PLATFORM_SKILL_ROOT"] == str(Path("/tmp/platform-tools").resolve())
    assert runtime_env["DATAAGENT_ENABLED_SKILLS"] == "opendataworks-business-knowledge,opendataworks-platform-tools,marketing-insights"
    assert "marketing-insights" in runtime_env["DATAAGENT_ENABLED_SKILL_ROOTS"]
    assert "ODW_MYSQL_HOST" not in runtime_env
    assert "ODW_MYSQL_PORT" not in runtime_env
    assert "ODW_MYSQL_USER" not in runtime_env
    assert "ODW_MYSQL_PASSWORD" not in runtime_env
    assert "ODW_MYSQL_DATABASE" not in runtime_env
    assert "DATAAGENT_SQL_WRITE_TIMEOUT_SECONDS" not in runtime_env


def test_build_runtime_env_defaults_query_limit_to_backend_max(monkeypatch):
    monkeypatch.setattr(agent_runtime, "resolve_builtin_skill_root_dir", lambda: Path("/tmp/skill-root"))

    runtime_env = agent_runtime._build_runtime_env(
        SimpleNamespace(query_result_limit=0),
        {},
        SimpleNamespace(question="", sql_read_timeout_seconds=0),
    )

    assert runtime_env["DATAAGENT_QUERY_LIMIT"] == "1000"
    assert runtime_env["DATAAGENT_RESULT_PREVIEW_ROWS"] == "20"


def test_build_runtime_env_derives_platform_root_from_primary_root_when_enabled_roots_lag(tmp_path: Path):
    primary_root = tmp_path / ".claude" / "skills" / "opendataworks-business-knowledge"
    platform_root = tmp_path / ".claude" / "skills" / "opendataworks-platform-tools"
    primary_root.mkdir(parents=True)
    platform_root.mkdir(parents=True)

    runtime_env = agent_runtime._build_runtime_env(
        SimpleNamespace(query_result_limit=1000),
        {},
        SimpleNamespace(question="", sql_read_timeout_seconds=0),
        {
            "primary_root": str(primary_root),
            "enabled_folders": ["opendataworks-business-knowledge"],
            "enabled_roots": {"opendataworks-business-knowledge": str(primary_root)},
        },
    )

    assert runtime_env["DATAAGENT_PLATFORM_SKILL_ROOT"] == str(platform_root.resolve())


def test_safe_base_url_preserves_path_without_query_or_fragment():
    actual = agent_runtime._safe_base_url("http://relay.example.internal/maas?token=secret#frag")

    assert actual == "http://relay.example.internal/maas"


def test_safe_base_url_drops_userinfo_from_log_value():
    actual = agent_runtime._safe_base_url("https://user:pass@relay.example.internal/maas/v1/messages")

    assert actual == "https://relay.example.internal/maas/v1/messages"


def test_settings_defaults_query_result_limit_to_backend_max(monkeypatch):
    monkeypatch.delenv("QUERY_RESULT_LIMIT", raising=False)

    assert Settings(_env_file=None).query_result_limit == 1000


def test_resolve_claude_cli_path_prefers_configured_value(monkeypatch):
    monkeypatch.setenv("DATAAGENT_CLAUDE_CLI_PATH", "/tmp/from-env")

    assert resolve_claude_cli_path(SimpleNamespace(claude_cli_path="/tmp/from-config")) == "/tmp/from-config"


def test_resolve_claude_cli_path_supports_env_alias(monkeypatch):
    monkeypatch.delenv("DATAAGENT_CLAUDE_CLI_PATH", raising=False)
    monkeypatch.setenv("CLAUDE_CLI_PATH", "/tmp/from-alias")

    assert resolve_claude_cli_path(SimpleNamespace(claude_cli_path="")) == "/tmp/from-alias"


def test_build_portal_mcp_servers_returns_http_config():
    cfg = SimpleNamespace(
        dataagent_portal_mcp_enabled=True,
        dataagent_portal_mcp_base_url="http://portal-mcp:8801/mcp/",
        dataagent_portal_mcp_token="portal-token",
        dataagent_portal_mcp_token_header_name="X-Portal-MCP-Token",
    )

    actual = agent_runtime._build_portal_mcp_servers(
        cfg,
        agent_snapshot={
            "data_scope": {
                "allowed_scopes": [
                    {"cluster_id": 3, "source_type": "DORIS", "database": "ads_user"}
                ]
            }
        },
    )

    assert actual == {
        "portal": {
            "type": "http",
            "url": "http://portal-mcp:8801/mcp/",
            "headers": {
                "X-Portal-MCP-Token": "portal-token",
                "X-Agent-Data-Scope": "eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19",
            },
        }
    }


def test_build_system_prompt_includes_authorized_data_scope():
    prompt = agent_runtime._build_system_prompt(
        None,
        {"enabled_folders": ["opendataworks-platform-tools"]},
        {
            "data_scope": {
                "allowed_scopes": [
                    {"cluster_id": 3, "source_type": "DORIS", "database": "ads_user"}
                ]
            }
        },
    )

    assert "已授权数据范围" in prompt
    assert "cluster_id=3, source_type=DORIS, database=ads_user" in prompt


def test_build_portal_mcp_servers_returns_empty_when_disabled_or_incomplete():
    disabled = SimpleNamespace(
        dataagent_portal_mcp_enabled=False,
        dataagent_portal_mcp_base_url="http://portal-mcp:8801/mcp",
        dataagent_portal_mcp_token="portal-token",
    )
    missing_token = SimpleNamespace(
        dataagent_portal_mcp_enabled=True,
        dataagent_portal_mcp_base_url="http://portal-mcp:8801/mcp",
        dataagent_portal_mcp_token="",
    )

    assert agent_runtime._build_portal_mcp_servers(disabled) == {}
    assert agent_runtime._build_portal_mcp_servers(missing_token) == {}


def test_build_portal_mcp_servers_adds_streamable_http_mount_slash():
    cfg = SimpleNamespace(
        dataagent_portal_mcp_enabled=True,
        dataagent_portal_mcp_base_url="http://portal-mcp:8801/mcp",
        dataagent_portal_mcp_token="portal-token",
        dataagent_portal_mcp_token_header_name="X-Portal-MCP-Token",
    )

    actual = agent_runtime._build_portal_mcp_servers(cfg)

    assert actual["portal"]["url"] == "http://portal-mcp:8801/mcp/"


def test_build_allowed_tools_includes_portal_mcp_tools_once():
    allowed_tools = agent_runtime._build_allowed_tools(
        {
            "portal": {
                "type": "http",
                "url": "http://portal-mcp:8801/mcp/",
                "headers": {"X-Portal-MCP-Token": "portal-token"},
            }
        }
    )

    assert allowed_tools[:6] == ["Skill", "Bash", "Read", "LS", "Glob", "Grep"]
    assert "mcp__portal__portal_search_tables" in allowed_tools
    assert "mcp__portal__portal_query_readonly" in allowed_tools
    assert len(allowed_tools) == len(set(allowed_tools))


def test_system_prompt_template_is_markdown_file():
    prompt_path = BACKEND_ROOT / "prompts" / "data_agent_system_prompt.md"

    assert prompt_path.exists()
    assert prompt_path.suffix == ".md"
    assert "你是企业级智能问数 Data Agent" in prompt_path.read_text(encoding="utf-8")


def test_build_system_prompt_reads_markdown_and_appends_runtime_context():
    prompt = agent_runtime._build_system_prompt(
        "opendataworks",
        {"enabled_folders": ["opendataworks-business-knowledge", "opendataworks-platform-tools"]},
    )

    assert "你是企业级智能问数 Data Agent" in prompt
    assert "你不是单纯的 SQL 生成器" in prompt
    assert "# 三、任务路由" in prompt
    assert "# 四、标准工作流" in prompt
    assert "# 六、SQL 与数据使用原则" in prompt
    assert "# 运行时上下文" in prompt
    assert "当前已启用：opendataworks-business-knowledge、opendataworks-platform-tools" in prompt
    assert "用户显式提供的 database hint: opendataworks" in prompt
    assert "dataagent-nl2sql" not in prompt
    assert "通用 SQL skill" not in prompt
    assert "run_sql.py" not in prompt
    assert "validate_sql.py" not in prompt
    assert "get_lineage.py" not in prompt
    assert "mcp__portal" not in prompt
    assert "DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1" not in prompt
    assert "workflow_publish_record" not in prompt


def test_build_system_prompt_includes_methodology_and_non_negotiables():
    prompt = agent_runtime._build_system_prompt(None, {"enabled_folders": ["opendataworks-business-knowledge"]})

    required_fragments = [
        "企业级智能问数 Data Agent",
        "准确理解用户的数据分析需求",
        "输出可解释、可复核、边界清晰的结果",
        "不编造表、字段、指标、口径、数据结果",
        "不在未确认 schema、字段或业务语义时直接臆造 SQL",
        "如果上下文不足以完成查询，先提澄清问题",
        "已启用 Skills",
        "不执行有副作用的操作",
    ]
    for fragment in required_fragments:
        assert fragment in prompt


def test_build_system_prompt_defines_evidence_first_task_routing():
    prompt = agent_runtime._build_system_prompt(
        None,
        {"enabled_folders": ["opendataworks-business-knowledge", "opendataworks-platform-tools"]},
    )

    required_fragments = [
        "先理解问题，再决定是否查语义、查元数据、生成 SQL、做分析",
        "如果用户在问业务术语、指标定义、统计口径、实体含义、时间口径",
        "如果用户在问“查什么表、字段、DDL、血缘、来源关系”",
        "如果用户在问具体数据结果、统计值、明细、排行、汇总",
        "如果用户在问“为什么上涨/下降”“原因是什么”“哪个因素导致变化”",
        "通过可用能力获取业务语义、元数据或查询结果",
        "如证据不足，不输出伪确定结论",
    ]
    for fragment in required_fragments:
        assert fragment in prompt


def test_build_system_prompt_documents_high_level_work_order():
    prompt = agent_runtime._build_system_prompt(
        None,
        {"enabled_folders": ["opendataworks-business-knowledge"]},
    )

    required_fragments = [
        "提取指标、维度、对象、过滤条件、时间范围、比较关系、输出形式",
        "判断是否存在歧义、缺失条件或业务术语未定义",
        "如果缺少关键条件且会影响结果，先发起澄清",
        "若可基于默认口径继续，必须明确说明默认假设",
        "先给结论",
        "再给支撑证据",
        "再说明口径、过滤条件、时间范围和限制",
    ]
    for fragment in required_fragments:
        assert fragment in prompt
    assert "SQL 验证层" not in prompt
    assert "run_sql.py --database" not in prompt


def test_legacy_lf_system_prompt_template_is_removed():
    assert not (BACKEND_ROOT / "prompts" / "system_prompt.py").exists()
