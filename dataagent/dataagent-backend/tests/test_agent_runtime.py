from __future__ import annotations

import asyncio
import re
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core import agent_runtime
from core.claude_cli import resolve_claude_cli_path
from config import Settings, resolve_workspace_scratch_dirs


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
    assert runtime_env["MCP_TOOL_TIMEOUT"] == "180000"
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


def test_build_runtime_env_uses_configured_portal_mcp_timeout_and_protects_cli_invariants(monkeypatch):
    monkeypatch.setattr(agent_runtime, "resolve_builtin_skill_root_dir", lambda: Path("/tmp/skill-root"))

    runtime_env = agent_runtime._build_runtime_env(
        SimpleNamespace(
            query_result_limit=1000,
            dataagent_portal_mcp_tool_timeout_seconds=240,
        ),
        {},
        SimpleNamespace(
            question="",
            sql_read_timeout_seconds=30,
            agent_env_vars={
                "MCP_TOOL_TIMEOUT": "1",
            },
        ),
    )

    assert runtime_env["MCP_TOOL_TIMEOUT"] == "240000"


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


def test_resolve_sql_read_timeout_seconds_selects_mode_specific_value():
    from config import resolve_sql_read_timeout_seconds

    cfg = SimpleNamespace(
        agent_interactive_sql_read_timeout_seconds=300,
        agent_background_sql_read_timeout_seconds=900,
    )
    assert resolve_sql_read_timeout_seconds(cfg, "interactive") == 300
    assert resolve_sql_read_timeout_seconds(cfg, "background") == 900
    # auto 归入后台档
    assert resolve_sql_read_timeout_seconds(cfg, "auto") == 900
    # 未知/空模式按交互档处理
    assert resolve_sql_read_timeout_seconds(cfg, None) == 300


def test_build_runtime_env_falls_back_to_mode_sql_read_timeout_when_unset(monkeypatch):
    monkeypatch.setattr(agent_runtime, "resolve_builtin_skill_root_dir", lambda: Path("/tmp/skill-root"))
    cfg = SimpleNamespace(
        query_result_limit=1000,
        agent_interactive_sql_read_timeout_seconds=300,
        agent_background_sql_read_timeout_seconds=900,
    )

    # 未显式设置（0）时按执行模式回落，而不是向技能传递 0
    env_interactive = agent_runtime._build_runtime_env(
        cfg, {}, SimpleNamespace(question="", sql_read_timeout_seconds=0, execution_mode="interactive")
    )
    assert env_interactive["DATAAGENT_SQL_READ_TIMEOUT_SECONDS"] == "300"

    env_background = agent_runtime._build_runtime_env(
        cfg, {}, SimpleNamespace(question="", sql_read_timeout_seconds=0, execution_mode="background")
    )
    assert env_background["DATAAGENT_SQL_READ_TIMEOUT_SECONDS"] == "900"

    # 显式值优先，不被回落覆盖
    env_explicit = agent_runtime._build_runtime_env(
        cfg, {}, SimpleNamespace(question="", sql_read_timeout_seconds=45, execution_mode="background")
    )
    assert env_explicit["DATAAGENT_SQL_READ_TIMEOUT_SECONDS"] == "45"


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


def test_build_portal_mcp_servers_uses_registry_http_with_data_scope(monkeypatch):
    class FakeRegistry:
        def list_mcp_servers(self):
            return [
                {
                    "server_id": "portal",
                    "name": "Portal MCP",
                    "source": "plugin",
                    "transport": "http",
                    "url": "http://registry-portal:8801/mcp/",
                    "headers": {"X-Portal-MCP-Token": "registry-token"},
                    "enabled": True,
                }
            ]

    monkeypatch.setattr("core.mcp_admin_service.get_runtime_registry_store", lambda: FakeRegistry())
    cfg = SimpleNamespace(
        dataagent_portal_mcp_enabled=True,
        dataagent_portal_mcp_base_url="http://environment-must-not-win:8801/mcp/",
        dataagent_portal_mcp_token="environment-token",
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
            "url": "http://registry-portal:8801/mcp/",
            "headers": {
                "X-Agent-Data-Scope": "eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19",
                "X-Portal-MCP-Token": "registry-token",
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


def test_build_portal_mcp_servers_returns_empty_when_registry_row_is_disabled_or_missing(monkeypatch):
    class FakeRegistry:
        rows = []

        def list_mcp_servers(self):
            return list(self.rows)

    registry = FakeRegistry()
    monkeypatch.setattr("core.mcp_admin_service.get_runtime_registry_store", lambda: registry)
    cfg = SimpleNamespace(
        dataagent_portal_mcp_enabled=True,
        dataagent_portal_mcp_base_url="http://environment-must-not-fallback:8801/mcp",
        dataagent_portal_mcp_token="environment-token",
    )

    assert agent_runtime._build_portal_mcp_servers(cfg) == {}

    registry.rows = [
        {
            "server_id": "portal",
            "transport": "http",
            "url": "http://registry-portal:8801/mcp",
            "headers": {"X-Portal-MCP-Token": "registry-token"},
            "enabled": False,
        }
    ]
    assert agent_runtime._build_portal_mcp_servers(cfg) == {}


def test_build_portal_mcp_servers_adds_streamable_http_mount_slash(monkeypatch):
    class FakeRegistry:
        def list_mcp_servers(self):
            return [
                {
                    "server_id": "portal",
                    "transport": "http",
                    "url": "http://registry-portal:8801/mcp",
                    "headers": {"X-Portal-MCP-Token": "registry-token"},
                    "enabled": True,
                }
            ]

    monkeypatch.setattr("core.mcp_admin_service.get_runtime_registry_store", lambda: FakeRegistry())
    cfg = SimpleNamespace(
        dataagent_portal_mcp_enabled=True,
        dataagent_portal_mcp_base_url="http://environment-must-not-win:8801/mcp",
        dataagent_portal_mcp_token="environment-token",
        dataagent_portal_mcp_token_header_name="X-Portal-MCP-Token",
    )

    actual = agent_runtime._build_portal_mcp_servers(cfg)

    assert actual["portal"]["url"] == "http://registry-portal:8801/mcp/"


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


def test_build_allowed_tools_default_mode_keeps_mcp_writes_unmounted():
    allowed = agent_runtime._build_allowed_tools(_PORTAL_MCP, permission_mode="default")
    assert "mcp__portal__portal_search_tables" in allowed
    assert "mcp__portal__portal_create_task" not in allowed
    assert "mcp__portal__portal_publish_workflow" not in allowed


def test_build_allowed_tools_accept_edits_mounts_only_draft_writes():
    allowed = agent_runtime._build_allowed_tools(_PORTAL_MCP, permission_mode="acceptEdits")
    assert "mcp__portal__portal_search_tables" in allowed
    assert "mcp__portal__portal_create_workflow" in allowed
    assert "mcp__portal__portal_upsert_schedule" in allowed
    assert "mcp__portal__portal_publish_workflow" not in allowed
    assert "mcp__portal__portal_workflow_schedule_online" not in allowed


def test_build_allowed_tools_bypass_permissions_mounts_all_writes():
    allowed = agent_runtime._build_allowed_tools(_PORTAL_MCP, permission_mode="bypassPermissions")
    assert "mcp__portal__portal_create_workflow" in allowed
    assert "mcp__portal__portal_publish_workflow" in allowed
    assert "mcp__portal__portal_workflow_schedule_online" in allowed


def test_build_allowed_tools_plan_mode_strips_write_tools():
    allowed = agent_runtime._build_allowed_tools(_PORTAL_MCP, permission_mode="plan")
    # read tools remain mounted
    assert "mcp__portal__portal_search_tables" in allowed
    # write + high-risk tools are not mounted under plan
    assert "mcp__portal__portal_create_task" not in allowed
    assert "mcp__portal__portal_publish_workflow" not in allowed
    assert "mcp__portal__portal_workflow_schedule_online" not in allowed


def test_resolve_sdk_permission_mode_identity_non_root(monkeypatch):
    # The SDK mode mirrors the logical mode 1:1 when not running as root, so the
    # in-run can_use_tool gate fires for plan/default/acceptEdits.
    monkeypatch.setattr(agent_runtime, "_is_running_as_root", lambda: False)
    for mode in ("default", "acceptEdits", "plan", "bypassPermissions"):
        assert agent_runtime._resolve_sdk_permission_mode(mode) == mode
    # Unknown / legacy values normalize to default.
    assert agent_runtime._resolve_sdk_permission_mode("inherit") == "default"


def test_resolve_sdk_permission_mode_root_degrades_only_bypass(monkeypatch):
    # The sandbox runner runs as uid 0 (it needs the docker socket); Claude Code
    # rejects bypassPermissions under root, so only that mode degrades to default.
    monkeypatch.setattr(agent_runtime, "_is_running_as_root", lambda: True)
    assert agent_runtime._resolve_sdk_permission_mode("bypassPermissions") == "default"
    assert agent_runtime._resolve_sdk_permission_mode("plan") == "plan"
    assert agent_runtime._resolve_sdk_permission_mode("default") == "default"
    assert agent_runtime._resolve_sdk_permission_mode("acceptEdits") == "acceptEdits"


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


def test_resolve_workspace_scratch_dirs_defaults_to_tmp():
    # /tmp is what the sandbox mounts as the child container's writable tmpfs, so
    # the boundary allow-list has to agree with it out of the box.
    assert resolve_workspace_scratch_dirs(Settings()) == ["/tmp"]


def test_resolve_workspace_scratch_dirs_drops_unsafe_entries():
    cfg = SimpleNamespace(
        dataagent_workspace_scratch_dirs=(
            "/tmp/, ,relative/dir,/,//,/var/lib/dataagent/scratch,"
            "/tmp,/opt/../etc,~/scratch"
        )
    )

    # Relative paths, bare root, and parent segments are dropped; trailing slashes
    # normalize so "/tmp/" and "/tmp" collapse to one entry in first-seen order.
    assert resolve_workspace_scratch_dirs(cfg) == ["/tmp", "/var/lib/dataagent/scratch"]


def test_resolve_workspace_scratch_dirs_empty_disables_allowance():
    assert resolve_workspace_scratch_dirs(SimpleNamespace(dataagent_workspace_scratch_dirs="")) == []


def test_workspace_boundary_allows_configured_scratch_dir(tmp_path: Path):
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(
        workspace, {"enabled_roots": {}}, [str(scratch)]
    )

    assert agent_runtime._validate_workspace_tool_boundary(
        "Write", {"file_path": str(scratch / "chunk.csv")}, workspace, allowed_roots, runtime_env
    ) is None
    assert agent_runtime._validate_workspace_tool_boundary(
        "Read", {"file_path": str(scratch / "chunk.csv")}, workspace, allowed_roots, runtime_env
    ) is None
    # Redirect targets are validated too, so a scratch redirect must pass.
    assert agent_runtime._validate_workspace_tool_boundary(
        "Bash", {"command": f"sort input.csv > {scratch}/sorted.csv"}, workspace, allowed_roots, runtime_env
    ) is None

    # The allowance is exactly the configured root: its siblings stay denied.
    denial = agent_runtime._validate_workspace_tool_boundary(
        "Write", {"file_path": str(tmp_path / "elsewhere.csv")}, workspace, allowed_roots, runtime_env
    )
    assert denial is not None
    assert "outside workspace" in denial


def test_workspace_boundary_allows_dev_null_redirects(tmp_path: Path):
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable}
    # No scratch dirs configured: /dev/null is allowed on its own, not by virtue of
    # some directory being whitelisted.
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    for command in (
        "python export.py > /dev/null 2>&1",
        "grep -q pattern data.csv 2>/dev/null",
        "python check.py 1>/dev/null",
        "cat /dev/null > notes.md",
    ):
        assert agent_runtime._validate_workspace_tool_boundary(
            "Bash", {"command": command}, workspace, allowed_roots, runtime_env
        ) is None, command


def test_workspace_boundary_still_denies_rest_of_dev(tmp_path: Path):
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    # The sink is matched exactly: neighbouring devices and anything "under"
    # /dev/null must not ride along.
    for command in (
        "cat /dev/zero",
        "head -c 16 /dev/urandom",
        "cat /dev/null/passwd",
        "ls /dev",
        "echo leaked > /dev/sda",
    ):
        denial = agent_runtime._validate_workspace_tool_boundary(
            "Bash", {"command": command}, workspace, allowed_roots, runtime_env
        )
        assert denial is not None, command
        assert "outside workspace" in denial

    denial = agent_runtime._validate_workspace_tool_boundary(
        "Write", {"file_path": "/dev/sda"}, workspace, allowed_roots, runtime_env
    )
    assert denial is not None
    assert "outside workspace" in denial


def test_workspace_boundary_denies_paths_hidden_in_assignments(tmp_path: Path):
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    # A word does not have to start with "/" to carry an absolute path. Checking
    # the assignment is also what catches variable indirection: $FOO is opaque at
    # the use site, but the literal is visible where it is assigned.
    for command in (
        "dd if=/etc/shadow of=out.bin",
        "tar --file=/etc/passwd -x",
        "FOO=/etc/shadow cat $FOO",
        "PYTHONPATH=/opt/secret python run.py",
        "java -Dlog=/var/log/app.log -jar app.jar",
        "tool --define=key=/etc/shadow",
    ):
        denial = agent_runtime._validate_workspace_tool_boundary(
            "Bash", {"command": command}, workspace, allowed_roots, runtime_env
        )
        assert denial is not None, command
        assert "outside workspace" in denial


def test_workspace_boundary_assignment_check_does_not_overreach(tmp_path: Path):
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(
        workspace, {"enabled_roots": {}}, [str(scratch)]
    )

    for command in (
        # Relative values carry nothing for the boundary to check.
        "LANG=en_US.UTF-8 python run.py",
        "git log --pretty=format:%H",
        "awk -F=, '{print $1}' data.csv",
        # "=" reached through other punctuation is not an assignment; treating
        # sed scripts as one would deny a perfectly local command.
        "sed 's/a=/b/g' notes.md",
        # Assignments pointing at allowed roots must still pass, element by
        # element for joined path lists.
        f"MPLCONFIGDIR={scratch}/mpl python plot.py",
        f"tar --file={workspace}/out.tar -c data",
        f"PYTHONPATH={workspace}/lib:{scratch}/lib python run.py",
    ):
        assert agent_runtime._validate_workspace_tool_boundary(
            "Bash", {"command": command}, workspace, allowed_roots, runtime_env
        ) is None, command


def test_workspace_boundary_hook_allows_scratch_dir_from_config(tmp_path: Path, monkeypatch):
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    monkeypatch.setattr(
        agent_runtime,
        "get_settings",
        lambda: SimpleNamespace(dataagent_workspace_scratch_dirs=str(scratch)),
    )
    hooks = agent_runtime._build_workspace_boundary_hooks(
        workspace, {"enabled_roots": {}}, {"DATAAGENT_PYTHON_BIN": sys.executable}
    )
    hook = hooks["PreToolUse"][0].hooks[0]

    allowed = asyncio.run(
        hook(
            {"tool_name": "Write", "tool_input": {"file_path": f"{scratch}/step1.json"}},
            "tool-write-1",
            {"signal": None},
        )
    )
    denied = asyncio.run(
        hook(
            {"tool_name": "Write", "tool_input": {"file_path": str(tmp_path / "escape.json")}},
            "tool-write-2",
            {"signal": None},
        )
    )

    assert allowed == {"continue_": True, "suppressOutput": True}
    assert denied["decision"] == "block"


def _encode_claude_project_key(workspace: Path) -> str:
    # Same rule the Claude Code CLI uses for the per-project data dir name:
    # realpath(cwd) with every non-alphanumeric char replaced by "-".
    return re.sub(r"[^a-zA-Z0-9]", "-", str(workspace.resolve()))


def _claude_project_dir(home: Path, workspace: Path) -> Path:
    return home / ".claude" / "projects" / _encode_claude_project_key(workspace)


def test_workspace_boundary_allows_offloaded_tool_result_read(tmp_path: Path):
    home = tmp_path / "home"
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable, "HOME": str(home)}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    # Oversized tool results are offloaded to
    # <HOME>/.claude/projects/<encoded-cwd>/<session>/tool-results/<id>.{txt,json}
    # and the agent is pointed at that path; Read is allowed to view it directly.
    tool_results = _claude_project_dir(home, workspace) / "session-abc" / "tool-results"
    for name in ("toolu_01.txt", "toolu_02.json"):
        assert agent_runtime._validate_workspace_tool_boundary(
            "Read",
            {"file_path": str(tool_results / name)},
            workspace,
            allowed_roots,
            runtime_env,
        ) is None


def test_workspace_boundary_allows_bash_view_of_offloaded_tool_result(tmp_path: Path):
    home = tmp_path / "home"
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable, "HOME": str(home)}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    # The CLI's "Full output saved to: <path>" pointer does not say which tool to
    # use, so an agent that *views* the offloaded result with a read-only Bash
    # command (instead of Read) must not be denied as "outside workspace".
    tool_result_file = (
        _claude_project_dir(home, workspace) / "session-abc" / "tool-results" / "toolu_01.txt"
    )
    for command in (
        f"cat {tool_result_file}",
        f"head -n 50 {tool_result_file}",
        f"grep foo {tool_result_file}",
        f"tail -n 5 {tool_result_file} | head",
    ):
        assert agent_runtime._validate_workspace_tool_boundary(
            "Bash",
            {"command": command},
            workspace,
            allowed_roots,
            runtime_env,
        ) is None, f"expected read-only view {command!r} to be allowed"


def test_workspace_boundary_denies_bash_mutation_of_offloaded_tool_result(tmp_path: Path):
    home = tmp_path / "home"
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable, "HOME": str(home)}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    tool_result_file = (
        _claude_project_dir(home, workspace) / "session-abc" / "tool-results" / "toolu_01.txt"
    )
    outside_target = tmp_path / "outside.txt"

    # The offloaded-result allowance is read-only. Commands that would delete,
    # move, execute, or overwrite the file (which lives outside the workspace)
    # must not slip through the exception, and neither may a redirect target,
    # including the no-space forms shlex.split hides inside a glued token.
    for command in (
        f"rm {tool_result_file}",
        f"mv {tool_result_file} {workspace / 'copy.txt'}",
        f"python {tool_result_file}",
        f"tee {tool_result_file}",
        f"cat {tool_result_file} > {outside_target}",
        f"cat {tool_result_file} >{outside_target}",
        f"cat {tool_result_file} >>{outside_target}",
        f"cat {tool_result_file} 2>{outside_target}",
        f"cat foo >{tool_result_file}",
    ):
        denial = agent_runtime._validate_workspace_tool_boundary(
            "Bash",
            {"command": command},
            workspace,
            allowed_roots,
            runtime_env,
        )
        assert denial is not None, f"expected denial for {command!r}"
        assert "outside workspace" in denial


def test_workspace_boundary_denies_complex_bash_with_offloaded_tool_result(tmp_path: Path):
    home = tmp_path / "home"
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable, "HOME": str(home)}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    tool_result_file = (
        _claude_project_dir(home, workspace) / "session-abc" / "tool-results" / "toolu_01.txt"
    )

    for command in (
        f"cat {tool_result_file}\nrm {tool_result_file}",
        f"cat <(rm {tool_result_file})",
        f"cat <(python {tool_result_file})",
    ):
        denial = agent_runtime._validate_workspace_tool_boundary(
            "Bash",
            {"command": command},
            workspace,
            allowed_roots,
            runtime_env,
        )
        assert denial is not None, f"expected denial for {command!r}"
        assert "unsupported shell syntax" in denial


def test_workspace_boundary_denies_pager_view_of_offloaded_tool_result(tmp_path: Path):
    home = tmp_path / "home"
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable, "HOME": str(home)}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    tool_result_file = (
        _claude_project_dir(home, workspace) / "session-abc" / "tool-results" / "toolu_01.txt"
    )

    for command in (f"less {tool_result_file}", f"more {tool_result_file}"):
        denial = agent_runtime._validate_workspace_tool_boundary(
            "Bash",
            {"command": command},
            workspace,
            allowed_roots,
            runtime_env,
        )
        assert denial is not None, f"expected denial for {command!r}"
        assert "outside workspace" in denial


def test_workspace_boundary_denies_session_state_and_non_read_tools(tmp_path: Path):
    home = tmp_path / "home"
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable, "HOME": str(home)}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    project_dir = _claude_project_dir(home, workspace)
    session_dir = project_dir / "session-abc"
    tool_results = session_dir / "tool-results"

    # Read must NOT reach the surrounding per-project session state, only the
    # offloaded tool-result files themselves.
    denied_reads = [
        project_dir / "session-abc.jsonl",           # top-level transcript
        session_dir / "subagents" / "agent-1.jsonl",  # subagent transcript
        session_dir / "session.meta.json",            # session meta
        tool_results / "toolu_03.jsonl",               # right dir, wrong suffix
        tool_results,                                  # the dir itself, not a file
    ]
    for target in denied_reads:
        denial = agent_runtime._validate_workspace_tool_boundary(
            "Read", {"file_path": str(target)}, workspace, allowed_roots, runtime_env
        )
        assert denial is not None, f"expected Read denial for {target}"
        assert "outside workspace" in denial

    # The exception only covers Read and Bash on the offloaded file itself;
    # LS/Glob/Grep into the tool-results dir stay denied, and Bash into the
    # dir (rather than an exact offloaded file) stays denied too.
    for tool_name, payload in (
        ("LS", {"path": str(tool_results)}),
        ("Glob", {"path": str(tool_results), "pattern": "*.txt"}),
        ("Grep", {"path": str(tool_results)}),
        ("Bash", {"command": f"ls {tool_results}"}),
    ):
        denial = agent_runtime._validate_workspace_tool_boundary(
            tool_name, payload, workspace, allowed_roots, runtime_env
        )
        assert denial is not None, f"expected {tool_name} denial"
        assert "outside workspace" in denial


def test_workspace_boundary_denies_other_topic_and_credentials_under_home(tmp_path: Path):
    home = tmp_path / "home"
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {"DATAAGENT_PYTHON_BIN": sys.executable, "HOME": str(home)}
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    # The offloaded-result allowance is scoped to this workspace's encoded cwd, so
    # a sibling topic's tool results and shared credentials stay blocked.
    other_workspace = tmp_path / "runtime" / "topic_2" / "workspace"
    other_topic_file = (
        _claude_project_dir(home, other_workspace) / "session-x" / "tool-results" / "toolu_02.txt"
    )
    credentials = home / ".claude" / ".credentials.json"

    for target in (other_topic_file, credentials):
        denial = agent_runtime._validate_workspace_tool_boundary(
            "Read",
            {"file_path": str(target)},
            workspace,
            allowed_roots,
            runtime_env,
        )
        assert denial is not None
        assert "outside workspace" in denial


def test_workspace_boundary_honors_claude_config_dir_for_tool_results(tmp_path: Path):
    config_dir = tmp_path / "custom-config"
    home = tmp_path / "home"
    workspace = tmp_path / "runtime" / "topic_1" / "workspace"
    workspace.mkdir(parents=True)
    runtime_env = {
        "DATAAGENT_PYTHON_BIN": sys.executable,
        "HOME": str(home),
        "CLAUDE_CONFIG_DIR": str(config_dir),
    }
    allowed_roots = agent_runtime._build_workspace_allowed_roots(workspace, {"enabled_roots": {}})

    encoded = _encode_claude_project_key(workspace)
    persisted = config_dir / "projects" / encoded / "s1" / "tool-results" / "toolu_03.json"
    # CLAUDE_CONFIG_DIR wins, so the HOME-derived path is no longer the data dir.
    home_path = home / ".claude" / "projects" / encoded / "s1" / "tool-results" / "toolu_03.json"

    assert agent_runtime._validate_workspace_tool_boundary(
        "Read", {"file_path": str(persisted)}, workspace, allowed_roots, runtime_env
    ) is None
    denial = agent_runtime._validate_workspace_tool_boundary(
        "Read", {"file_path": str(home_path)}, workspace, allowed_roots, runtime_env
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


def test_build_system_prompt_declares_writable_scratch_dirs(monkeypatch):
    monkeypatch.setattr(
        agent_runtime,
        "get_settings",
        lambda: SimpleNamespace(dataagent_workspace_scratch_dirs="/tmp,/var/lib/dataagent/scratch"),
    )

    prompt = agent_runtime._build_system_prompt(None, {"enabled_folders": []})

    # The prompt must advertise exactly what the boundary hook allows, and keep the
    # deliverable path contract (workspace output/) intact.
    assert "可写临时目录：/tmp、/var/lib/dataagent/scratch" in prompt
    assert "`output/`" in prompt


def test_build_system_prompt_declares_no_scratch_dir_when_disabled(monkeypatch):
    monkeypatch.setattr(
        agent_runtime,
        "get_settings",
        lambda: SimpleNamespace(dataagent_workspace_scratch_dirs=""),
    )

    prompt = agent_runtime._build_system_prompt(None, {"enabled_folders": []})

    assert "可写临时目录：无" in prompt


def test_build_system_prompt_includes_methodology_and_non_negotiables():
    prompt = agent_runtime._build_system_prompt(None, {"enabled_folders": ["opendataworks-business-knowledge"]})

    required_fragments = [
        "企业级智能问数 Data Agent",
        "无论用户使用何种语言提问，必须始终使用简体中文回复",
        "不编造表、字段、指标、口径、数据结果",
        "不执行有副作用的操作",
        "文件读写只在当前会话工作区目录、以及「运行时上下文」声明的可写临时目录内进行",
        "最终交付文件必须写入工作区 `output/`",
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
