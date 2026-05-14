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


def test_build_system_prompt_prefers_lineage_and_ddl_tools():
    prompt = agent_runtime._build_system_prompt(None, {"enabled_folders": ["dataagent-nl2sql", "marketing-insights"]})

    assert "当前已启用：dataagent-nl2sql、marketing-insights" in prompt
    assert "mcp__portal__portal_get_lineage" in prompt
    assert "get_lineage.py" in prompt
    assert "DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1" in prompt
    assert "mcp__portal__portal_get_table_ddl" in prompt
    assert "get_table_ddl.py" in prompt


def test_build_system_prompt_includes_methodology_and_non_negotiables():
    prompt = agent_runtime._build_system_prompt(None, {"enabled_folders": ["dataagent-nl2sql"]})

    required_fragments = [
        "内部工作循环",
        "先判定用户意图与信息缺口",
        "获取必要上下文",
        "制定最小执行路径",
        "基于真实工具结果执行和收口",
        "不要向用户暴露隐藏推理",
        "不得臆造表、字段、指标口径或租户私有默认值",
        "不得绕过已启用 Skills 或 portal-mcp 优先级",
        "不得重复试探等价 SQL",
        "工具结果不足以支撑结论时，必须最小追问或说明缺口",
        "只允许只读执行",
    ]
    for fragment in required_fragments:
        assert fragment in prompt


def test_build_system_prompt_requires_domain_skill_execution_and_stop_rules():
    prompt = agent_runtime._build_system_prompt(
        None,
        {"enabled_folders": ["dataagent-nl2sql", "domain-governance"]},
    )

    required_fragments = [
        "领域型 Skill",
        "不要先退回 OpenDataWorks 通用元数据",
        "能执行真实只读查询时，不要只返回待执行 SQL",
        "空结果、权限不足、工具超时或服务调用失败",
        "不要继续换表、换字段、换路径或重复试探",
        "首次有效结果后结束当前查询链路",
    ]
    for fragment in required_fragments:
        assert fragment in prompt


def test_build_system_prompt_documents_layered_query_pipeline():
    prompt = agent_runtime._build_system_prompt(
        None,
        {"enabled_folders": ["dataagent-nl2sql", "business-domain-assistant"]},
    )

    required_fragments = [
        "固定分层问数管线",
        "一、上下文语义层",
        "命中业务知识问数",
        "没有命中业务知识 Skill 时",
        "二、SQL 生成层",
        "database、engine、tables、fields、filters、time_window",
        "三、SQL 验证层",
        "统一使用通用问数 Skill 的 validate_sql.py",
        "业务知识 Skill 只提供本体、口径、关系和 SQL example",
        "四、SQL 执行层",
        "run_sql.py --database <db> --engine <mysql|doris> --sql \"<SQL>\"",
        "不得只输出 SQL 或让用户自行执行",
        "非交互评测场景不得追问用户",
    ]
    for fragment in required_fragments:
        assert fragment in prompt
    assert "私有 Skill 的 validate_sql.py" not in prompt


def test_legacy_lf_system_prompt_template_is_removed():
    assert not (BACKEND_ROOT / "prompts" / "system_prompt.py").exists()
