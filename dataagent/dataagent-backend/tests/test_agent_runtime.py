from __future__ import annotations

import asyncio
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


def test_sanitize_user_visible_content_preserves_procedural_preamble():
    content = "我来先查看表结构并确认字段名。\n结论：最近 30 天工作流发布次数为 3 次。"

    assert agent_runtime._sanitize_user_visible_content("最近 30 天工作流发布次数趋势", content) == content


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


_PORTAL_MCP = {
    "portal": {
        "type": "http",
        "url": "http://portal-mcp:8801/mcp/",
        "headers": {"X-Portal-MCP-Token": "portal-token"},
    }
}


def test_build_allowed_tools_mounts_write_tools_in_default_mode():
    allowed = agent_runtime._build_allowed_tools(_PORTAL_MCP, permission_mode="default")
    assert "mcp__portal__portal_create_task" in allowed
    assert "mcp__portal__portal_publish_workflow" in allowed
    assert "mcp__portal__portal_search_tables" in allowed


def test_build_allowed_tools_plan_mode_strips_write_tools():
    allowed = agent_runtime._build_allowed_tools(_PORTAL_MCP, permission_mode="plan")
    # read tools remain mounted
    assert "mcp__portal__portal_search_tables" in allowed
    # write + high-risk tools are not mounted under plan
    assert "mcp__portal__portal_create_task" not in allowed
    assert "mcp__portal__portal_publish_workflow" not in allowed
    assert "mcp__portal__portal_workflow_schedule_online" not in allowed


def test_workspace_boundary_denies_parent_directory_file_lookup(tmp_path: Path):
    workspace = tmp_path / "runtime" / "workspaces" / "agent_default"
    workspace.mkdir(parents=True)
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    denial = agent_runtime._validate_workspace_tool_boundary(
        "Read",
        {"file_path": "../secret.md"},
        workspace,
        allowed_roots,
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    )

    assert denial is not None
    assert "parent directory" in denial


def test_workspace_boundary_allows_workspace_and_enabled_skill_roots(tmp_path: Path):
    workspace = tmp_path / "runtime" / "workspaces" / "agent_opendataworks"
    workspace.mkdir(parents=True)
    local_doc = workspace / "notes.md"
    local_doc.write_text("ok", encoding="utf-8")
    skill_root = tmp_path / "skills" / "opendataworks-platform-tools"
    skill_root.mkdir(parents=True)
    skill_doc = skill_root / "SKILL.md"
    skill_doc.write_text("# skill\n", encoding="utf-8")
    allowed_roots = agent_runtime._build_workspace_allowed_roots(
        workspace,
        {"enabled_roots": {"opendataworks-platform-tools": str(skill_root)}},
    )

    assert agent_runtime._validate_workspace_tool_boundary(
        "Read",
        {"file_path": str(local_doc)},
        workspace,
        allowed_roots,
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    ) is None
    assert agent_runtime._validate_workspace_tool_boundary(
        "Read",
        {"file_path": str(skill_doc)},
        workspace,
        allowed_roots,
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    ) is None
    denial = agent_runtime._validate_workspace_tool_boundary(
        "Read",
        {"file_path": str(tmp_path / "outside.md")},
        workspace,
        allowed_roots,
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    )
    assert denial is not None
    assert "outside workspace" in denial


def test_workspace_boundary_denies_write_outside_workspace(tmp_path: Path):
    workspace = tmp_path / "runtime" / "workspaces" / "agent_default"
    workspace.mkdir(parents=True)
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    denial = agent_runtime._validate_workspace_tool_boundary(
        "Write",
        {"file_path": str(tmp_path / "outside.txt")},
        workspace,
        allowed_roots,
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    )

    assert denial is not None
    assert "outside workspace" in denial


def test_workspace_boundary_denies_edit_parent_directory(tmp_path: Path):
    workspace = tmp_path / "runtime" / "workspaces" / "agent_default"
    workspace.mkdir(parents=True)
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    denial = agent_runtime._validate_workspace_tool_boundary(
        "Edit",
        {"file_path": "../escape.txt"},
        workspace,
        allowed_roots,
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    )

    assert denial is not None
    assert "parent directory" in denial


def test_workspace_boundary_allows_write_inside_workspace(tmp_path: Path):
    workspace = tmp_path / "runtime" / "workspaces" / "agent_default"
    workspace.mkdir(parents=True)
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    assert agent_runtime._validate_workspace_tool_boundary(
        "Write",
        {"file_path": str(workspace / "report.md")},
        workspace,
        allowed_roots,
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    ) is None


def test_workspace_boundary_denies_notebook_edit_outside_workspace(tmp_path: Path):
    workspace = tmp_path / "runtime" / "workspaces" / "agent_default"
    workspace.mkdir(parents=True)
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    denial = agent_runtime._validate_workspace_tool_boundary(
        "NotebookEdit",
        {"notebook_path": str(tmp_path / "outside.ipynb")},
        workspace,
        allowed_roots,
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    )

    assert denial is not None
    assert "outside workspace" in denial


def test_workspace_boundary_denies_bash_parent_directory_lookup(tmp_path: Path):
    workspace = tmp_path / "runtime" / "workspaces" / "agent_default"
    workspace.mkdir(parents=True)
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    denial = agent_runtime._validate_workspace_tool_boundary(
        "Bash",
        {"command": "find .. -name '*.md'"},
        workspace,
        allowed_roots,
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    )

    assert denial is not None
    assert "parent directory" in denial


def test_workspace_boundary_denies_shared_container_runtime_root_lookup():
    workspace = Path("/dataagent_runtime/topic_1/workspace")
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    denial = agent_runtime._validate_workspace_tool_boundary(
        "Read",
        {"file_path": "/dataagent_runtime"},
        workspace,
        allowed_roots,
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    )

    assert denial is not None
    assert "outside workspace" in denial


def test_workspace_boundary_hook_returns_pretooluse_denial(tmp_path: Path):
    workspace = tmp_path / "runtime" / "workspaces" / "agent_default"
    workspace.mkdir(parents=True)
    hooks = agent_runtime._build_workspace_boundary_hooks(
        workspace,
        {"enabled_roots": {}},
        {"DATAAGENT_PYTHON_BIN": sys.executable},
    )
    hook = hooks["PreToolUse"][0].hooks[0]

    result = asyncio.run(
        hook(
            {"tool_name": "Read", "tool_input": {"path": "../secret.md"}},
            "tool-read-1",
            {"signal": None},
        )
    )

    assert result["decision"] == "block"
    assert result["hookSpecificOutput"]["permissionDecision"] == "deny"


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
    assert "# 一、硬性约束" in prompt
    assert "# 二、SQL 前确认清单" in prompt
    assert "# 三、图表输出" in prompt
    assert "# 运行时上下文" in prompt
    assert "当前已启用：opendataworks-business-knowledge、opendataworks-platform-tools" in prompt
    assert "用户显式提供的 database hint: opendataworks" in prompt
    assert "dataagent-nl2sql" not in prompt
    assert "通用 SQL skill" not in prompt
    assert "run_sql.py" in prompt
    assert "validate_sql.py" not in prompt
    assert "get_lineage.py" not in prompt
    assert "mcp__portal" not in prompt
    assert "DATAAGENT_ALLOW_LINEAGE_SQL_FALLBACK=1" not in prompt
    assert "workflow_publish_record" not in prompt


def test_build_system_prompt_includes_methodology_and_non_negotiables():
    prompt = agent_runtime._build_system_prompt(None, {"enabled_folders": ["opendataworks-business-knowledge"]})

    required_fragments = [
        "企业级智能问数 Data Agent",
        "无论用户使用何种语言提问，必须始终使用简体中文回复",
        "不编造表、字段、指标、口径、数据结果",
        "不执行有副作用的操作",
        "文件读写只在当前会话工作区目录内进行",
        "不伪造成功结果",
        "先提出最小澄清问题",
        "必须显式说明默认假设",
        "已启用 Skills",
    ]
    for fragment in required_fragments:
        assert fragment in prompt


def test_build_system_prompt_omits_generic_model_known_methodology():
    prompt = agent_runtime._build_system_prompt(
        None,
        {"enabled_folders": ["opendataworks-business-knowledge", "opendataworks-platform-tools"]},
    )

    removed_fragments = [
        "任务路由",
        "标准工作流",
        "澄清规则",
        "归因分析原则",
        "失败处理",
        "先理解问题，再决定是否查语义、查元数据、生成 SQL、做分析",
        "提取指标、维度、对象、过滤条件、时间范围、比较关系、输出形式",
        "先给结论",
    ]
    for fragment in removed_fragments:
        assert fragment not in prompt
    assert "SQL 验证层" not in prompt
    assert "run_sql.py --database" not in prompt


def test_legacy_lf_system_prompt_template_is_removed():
    assert not (BACKEND_ROOT / "prompts" / "system_prompt.py").exists()


def test_contains_pseudo_tool_call_detects_leaked_tags():
    assert agent_runtime._contains_pseudo_tool_call("Now let me check </parameter></function></tool_call>")
    assert agent_runtime._contains_pseudo_tool_call("<tool_call>{...}")
    assert agent_runtime._contains_pseudo_tool_call("</invoke>")
    assert not agent_runtime._contains_pseudo_tool_call("最近 30 天工作流发布次数为 3 次。")
    assert not agent_runtime._contains_pseudo_tool_call("使用 a < b 与 c > d 的比较条件")


def test_strip_pseudo_tool_call_tags_removes_only_tags():
    raw = "结论：发布 174 次 </parameter></function></tool_call>"
    cleaned = agent_runtime._strip_pseudo_tool_call_tags(raw)
    assert "结论：发布 174 次" in cleaned
    assert "</tool_call>" not in cleaned
    assert "</parameter>" not in cleaned
    assert "</function>" not in cleaned


def test_partial_completion_note_for_tool_call_format_drift():
    note = agent_runtime._partial_completion_note("模型工具调用格式异常未正常收口")
    assert "工具调用格式异常" in note
