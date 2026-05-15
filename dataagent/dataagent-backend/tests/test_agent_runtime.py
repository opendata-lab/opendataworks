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
            "enabled_folders": ["dataagent-nl2sql", "marketing-insights"],
            "enabled_roots": {
                "dataagent-nl2sql": "/tmp/skill-root",
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
    assert runtime_env["DATAAGENT_ENABLED_SKILLS"] == "dataagent-nl2sql,marketing-insights"
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

    actual = agent_runtime._build_portal_mcp_servers(cfg)

    assert actual == {
        "portal": {
            "type": "http",
            "url": "http://portal-mcp:8801/mcp",
            "headers": {"X-Portal-MCP-Token": "portal-token"},
        }
    }


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


def test_build_allowed_tools_includes_portal_mcp_tools_once():
    allowed_tools = agent_runtime._build_allowed_tools(
        {
            "portal": {
                "type": "http",
                "url": "http://portal-mcp:8801/mcp",
                "headers": {"X-Portal-MCP-Token": "portal-token"},
            }
        }
    )

    assert allowed_tools[:6] == ["Skill", "Bash", "Read", "LS", "Glob", "Grep"]
    assert "mcp__portal__portal_search_tables" in allowed_tools
    assert "mcp__portal__portal_query_readonly" in allowed_tools
    assert len(allowed_tools) == len(set(allowed_tools))


def test_build_system_prompt_routes_to_enabled_skills_without_low_level_commands():
    prompt = agent_runtime._build_system_prompt(None, {"enabled_folders": ["dataagent-nl2sql", "marketing-insights"]})

    assert "当前已启用：dataagent-nl2sql、marketing-insights" in prompt
    assert "# Role" in prompt
    assert "# Primary Goal" in prompt
    assert "# Boundaries" in prompt
    assert "# Instruction Priority" in prompt
    assert "# Workflow" in prompt
    assert "# Routing Rules" in prompt
    assert "# Output Requirements" in prompt
    assert "业务语义" in prompt
    assert "通用问数 SQL" in prompt
    assert "portal-mcp" in prompt
    assert "run_sql.py" not in prompt
    assert "validate_sql.py" not in prompt
    assert "get_lineage.py" not in prompt
    assert "DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1" not in prompt
    assert "workflow_publish_record" not in prompt


def test_build_system_prompt_includes_methodology_and_non_negotiables():
    prompt = agent_runtime._build_system_prompt(None, {"enabled_folders": ["dataagent-nl2sql"]})

    required_fragments = [
        "企业数据分析 Data Agent",
        "优先正确理解问题，再选择合适的数据查询路径",
        "在术语、指标、口径不明确时，先做语义澄清或显式声明假设",
        "不展示冗长内部推理，只输出必要结论、依据、假设与限制",
        "不要臆造表、字段、指标口径、业务默认值或工具结果",
        "已启用 Skills",
        "默认只读分析",
    ]
    for fragment in required_fragments:
        assert fragment in prompt


def test_build_system_prompt_defines_three_layer_priority():
    prompt = agent_runtime._build_system_prompt(
        None,
        {"enabled_folders": ["dataagent-nl2sql", "opendataworks-business-knowledge"]},
    )

    required_fragments = [
        "先遵循本 system prompt",
        "业务术语、指标口径、本体映射、歧义消解",
        "业务语义 skill",
        "表选择、字段选择、SQL 生成、SQL 自检",
        "通用 SQL skill",
        "运行时资源 > 业务语义 skill > 通用 SQL skill > 默认常识",
    ]
    for fragment in required_fragments:
        assert fragment in prompt


def test_build_system_prompt_documents_high_level_work_order():
    prompt = agent_runtime._build_system_prompt(
        None,
        {"enabled_folders": ["dataagent-nl2sql", "opendataworks-business-knowledge"]},
    )

    required_fragments = [
        "判断任务类型：定义解释、指标查询、明细查询、趋势分析、对比分析、异常归因",
        "抽取关键槽位：业务对象、指标、维度、时间范围、过滤条件、统计粒度",
        "判断是否需要业务语义解析",
        "判断是否需要生成 SQL",
        "若信息不足，先提出最小必要澄清问题",
        "若信息足够，生成查询方案或最终答案",
        "输出时明确说明：采用口径、查询范围、核心结果、限制说明",
    ]
    for fragment in required_fragments:
        assert fragment in prompt
    assert "SQL 验证层" not in prompt
    assert "run_sql.py --database" not in prompt


def test_legacy_lf_system_prompt_template_is_removed():
    assert not (BACKEND_ROOT / "prompts" / "system_prompt.py").exists()
