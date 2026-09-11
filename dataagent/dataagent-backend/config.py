"""
DataAgent Backend 配置管理
支持环境变量和运行时动态更新
"""
from __future__ import annotations

import threading

from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    # ---- 服务 ----
    app_name: str = "dataagent-backend"
    host: str = "0.0.0.0"
    port: int = 8900
    debug: bool = False

    # ---- LLM Provider / Model ----
    llm_provider: str = ""  # anthropic | openrouter | anyrouter | anthropic_compatible
    claude_model: str = ""
    claude_max_tokens: int = 4096
    agent_timeout_seconds: int = 180
    agent_max_turns: int = 20
    agent_interactive_max_turns: int = 24
    agent_background_max_turns: int = 40
    agent_wait_timeout_seconds: int = 20
    agent_interactive_timeout_seconds: int = 360
    agent_background_timeout_seconds: int = 1800
    agent_interactive_idle_timeout_seconds: int = 90
    agent_background_idle_timeout_seconds: int = 300
    agent_interactive_sql_read_timeout_seconds: int = 300
    agent_background_sql_read_timeout_seconds: int = 900
    agent_sql_write_timeout_seconds: int = 60
    # Max bytes when buffering a single CLI stdout JSON message before decoding.
    # The SDK default is 1MB; large NL2SQL tool results / partial messages can
    # exceed it and trigger "JSON message exceeded maximum buffer size".
    agent_max_buffer_size_bytes: int = 10 * 1024 * 1024
    followup_suggestions_timeout_seconds: int = 20
    run_events_stream_poll_interval_seconds: int = 1
    run_events_stream_ping_seconds: int = 10
    task_max_concurrency: int = 8
    task_lease_ttl_seconds: int = 30
    task_heartbeat_seconds: int = 5
    task_recovery_scan_interval_seconds: int = 2
    task_recovery_batch_size: int = 20
    # Deprecated for permission/input waits. Confirmations and user-input cards
    # are parked durably in MySQL and no longer auto-deny by this timeout.
    task_permission_wait_seconds: int = 600
    schedule_scan_interval_seconds: int = 10
    schedule_scan_batch_size: int = 10
    schedule_lock_ttl_seconds: int = 60

    # ---- Anthropic 兼容认证 ----
    anthropic_api_key: str = ""
    anthropic_auth_token: str = ""
    anthropic_base_url: str = ""
    claude_cli_path: str = ""

    # ---- MySQL（会话存储 + MySQL 查询工具）----
    mysql_host: str = "localhost"
    mysql_port: int = 3306
    mysql_user: str = "dataagent"
    mysql_password: str = "dataagent123"
    mysql_database: str = "opendataworks"
    session_mysql_database: str = "dataagent"

    # ---- Redis（task 协调）----
    redis_host: str = "127.0.0.1"
    redis_port: int = 6379
    redis_password: str = ""
    redis_db: int = 0

    # ---- Doris（查询工具）----
    doris_host: str = "localhost"
    doris_port: int = 9030
    doris_user: str = "root"
    doris_password: str = ""
    doris_database: str = ""

    # ---- Skills ----
    skills_root_dir: str = ""
    skills_output_dir: str = "../.claude/skills/opendataworks-business-knowledge"
    dataagent_upload_max_bytes: int = 20 * 1024 * 1024
    dataagent_portal_mcp_enabled: bool = True
    dataagent_portal_mcp_base_url: str = ""
    dataagent_portal_mcp_token: str = ""
    dataagent_portal_mcp_token_header_name: str = "X-Portal-MCP-Token"
    # Claude Code 进程级 MCP_TOOL_TIMEOUT，由 runtime 转为毫秒。虽然配置名沿用
    # portal 前缀，该值也会作用于同一 CLI 进程中的其它 MCP server。180s 覆盖
    # portal_query_readonly 的 120s 契约上限，且低于交互 run 总预算 360s；若调到
    # 300s 以上，还必须同步配置并验证 CLAUDE_CODE_MCP_TOOL_IDLE_TIMEOUT。
    # 见 docs/design/2026-08-17-portal-mcp-streamable-http-design.md
    dataagent_portal_mcp_tool_timeout_seconds: int = 180

    # ---- Agent 交互能力 ----
    # AskUserQuestion 让 agent 通过选择卡片向用户提问。开启时会为每次运行安装
    # can_use_tool 回调，并以流式输入形态投递 prompt。置 false 后：AskUserQuestion 不再
    # 加入 allowed_tools，且在无写工具门控的会话中 can_use_tool 回到 None、prompt 回到
    # 纯字符串形态（更接近 v1.3.0 的调用形态）。写工具确认门控不受本开关影响。
    dataagent_ask_user_question_enabled: bool = True

    # ---- Agent runtime kind (data plane engine) ----
    # Which execution engine runs one NL2SQL turn. This is orthogonal to
    # dataagent_sandbox_mode: that one picks the isolation topology (in-process
    # vs. child container), this one picks the engine inside whichever topology
    # was selected. Deployment-level only; never resolved per request.
    #   pi_agent_core -> Node Pi Cell over stdio (default, dataagent-runtime-pi)
    #   claude_code   -> claude-agent-sdk
    dataagent_runtime_kind: str = "pi_agent_core"
    # Node binary and built Pi Cell entrypoint used when runtime kind is
    # pi_agent_core. Empty values fall back to `node` on PATH and the in-repo
    # dataagent-runtime-pi build output.
    dataagent_node_bin: str = ""
    dataagent_runtime_pi_dir: str = ""
    # Timeout and context governance settings for pi_agent_core runtime.
    # The three context_* names mirror the ``governance_settings`` wire contract
    # consumed by the Cell (src/protocol/frames.ts); keep them in lockstep.
    dataagent_run_idle_timeout_seconds: int = 300
    # Only reached when a task carries no timeout of its own, which a real task
    # always does: task_submission_service sets it from
    # agent_interactive_timeout_seconds (360) or agent_background_timeout_seconds
    # (1800), and task_executor prefers that over this. Tuning this number alone
    # changes nothing for an actual run — raise the interactive or background one.
    dataagent_run_total_timeout_seconds: int = 600
    dataagent_context_max_inline_result_bytes: int = 16 * 1024
    dataagent_context_protect_tail_turns: int = 6
    dataagent_context_max_context_tokens: int = 64_000
    # Compaction watermarks, as fractions of max_context_tokens. Pruning runs
    # only when the context crosses the high mark, because pi-ai caches the
    # prompt prefix and rewriting history on every turn invalidates it.
    dataagent_context_prune_high_watermark_ratio: float = 0.90
    dataagent_context_prune_target_ratio: float = 0.70
    # Prompt cache retention passed explicitly to the Cell: short | long | off.
    # DISABLE_PROMPT_CACHING never reached the Pi runtime, so caching was on
    # while the backend believed it was off.
    dataagent_pi_cache_retention: str = "short"

    # ---- Topic runtime root / sandbox ----
    # Filesystem root visible to the current process. Containerized backend
    # processes read the shared volume at /dataagent_runtime; local execution
    # leaves this empty and falls back to dataagent_host_root.
    dataagent_runtime_root: str = ""
    # Host-visible persistent root used by the sandbox runner when asking the
    # host Docker/Podman daemon to bind-mount topic subdirectories into child
    # containers.
    dataagent_host_root: str = "/dataagent_runtime"
    dataagent_sandbox_mode: str = ""
    dataagent_sandbox_runner_url: str = ""
    dataagent_sandbox_image: str = ""
    dataagent_sandbox_backend: str = "docker"
    dataagent_sandbox_network: str = ""
    # Per-task sandbox logs are written under <runtime_root>/<topic>/logs so they
    # sit next to the topic's workspace/ and home/ subdirs; no separate root.
    # Runtime isolation hardening for the child task container. The workspace
    # bind-mount is always the only writable host path; these tighten the rest.
    # read_only_rootfs locks the container root filesystem read-only so the
    # agent's Bash/Python cannot persist anything outside the bind-mounted
    # workspace; a writable tmpfs is mounted at /tmp for transient scratch.
    dataagent_sandbox_read_only_rootfs: bool = False
    dataagent_sandbox_tmpfs_size: str = "512m"
    # Directories the runtime boundary hook allows on top of the topic workspace
    # and the enabled Skill roots. Comma-separated absolute paths. Default "/tmp"
    # matches the writable tmpfs the sandbox mounts into the child container, so
    # transient scratch files stop being denied as "outside workspace". Read and
    # write share this one list; final deliverables still belong in the workspace
    # `output/` dir. Set to "" to restore workspace-only file access.
    dataagent_workspace_scratch_dirs: str = "/tmp"
    # Warm child container reuse. When the container backend is active, keep a
    # finished child alive for an idle window so same-conversation follow-ups
    # reuse it instead of paying full container/SDK cold-start each turn.
    # Set reuse_enabled to false to restore one-shot-per-task containers.
    dataagent_sandbox_reuse_enabled: bool = True
    dataagent_sandbox_idle_ttl_seconds: int = 600
    dataagent_sandbox_max_warm_containers: int = 32
    dataagent_sandbox_reaper_interval_seconds: int = 30

    # ---- 运行策略 ----
    max_few_shot_examples: int = 5
    max_schema_tables: int = 10
    max_business_rules: int = 5
    query_result_limit: int = 1000

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        extra = "ignore"


_settings = Settings()
_lock = threading.Lock()


def get_settings() -> Settings:
    return _settings


def update_settings(patch: dict) -> Settings:
    """运行时更新配置"""
    global _settings
    with _lock:
        current = _settings.model_dump()
        current.update({k: v for k, v in patch.items() if v is not None})
        _settings = Settings(**current)
    return _settings


def is_background_execution_mode(execution_mode) -> bool:
    """统一判定执行模式是否走后台（长）超时档位。

    ``auto`` 归入后台档，与 ``resolve_task_timeouts`` 的历史语义保持一致。
    """
    return str(execution_mode or "").strip().lower() in {"background", "auto"}


def resolve_sql_read_timeout_seconds(cfg: Settings, execution_mode) -> int:
    """按执行模式解析 SQL 只读查询超时（秒）。

    单一来源，供任务提交期的 ``resolve_task_timeouts`` 与运行期 env 装配共用，
    避免不同路径各自回落到不一致的短默认值。
    """
    if is_background_execution_mode(execution_mode):
        return int(getattr(cfg, "agent_background_sql_read_timeout_seconds", 0) or 900)
    return int(getattr(cfg, "agent_interactive_sql_read_timeout_seconds", 0) or 300)


SUPPORTED_RUNTIME_KINDS = ("claude_code", "pi_agent_core")
# Pi is what production runs, in the child-container topology. The default used
# to be claude_code, which meant a deployment that set nothing ran the engine
# nobody was using — and a local smoke that forgot the variable silently
# verified the wrong path.
DEFAULT_RUNTIME_KIND = "pi_agent_core"


def resolve_runtime_kind(cfg: Settings) -> str:
    """解析当前部署激活的执行引擎（数据面）。

    唯一解析入口。与 ``dataagent_sandbox_mode`` 正交：后者决定隔离拓扑（进程内 /
    子容器），本函数只决定在选定拓扑内部由哪个引擎执行一轮问答。部署级配置，
    不接受请求级切换。无法识别的取值回落到默认引擎而不是抛错，保证一个写错的
    环境变量不会让整个后端拒绝服务。
    """
    raw = str(getattr(cfg, "dataagent_runtime_kind", "") or "").strip().lower()
    if raw in SUPPORTED_RUNTIME_KINDS:
        return raw
    return DEFAULT_RUNTIME_KIND


def resolve_workspace_scratch_dirs(cfg: Settings) -> list[str]:
    """解析工作区边界之外仍允许读写的目录白名单。

    唯一解析入口，供边界钩子装配和系统提示词共用，避免两处对同一份配置给出不同结论。
    只接受绝对路径，拒绝根目录 ``/`` 和含 ``..`` 的路径，去掉尾部 ``/`` 后按顺序去重。
    非法项直接丢弃：配置写错只会收紧白名单，不会意外放开更多目录。
    """
    raw = str(getattr(cfg, "dataagent_workspace_scratch_dirs", "") or "")
    resolved: list[str] = []
    seen: set[str] = set()
    for item in raw.split(","):
        text = item.strip()
        if not text.startswith("/"):
            continue
        if any(part == ".." for part in text.split("/")):
            continue
        normalized = text.rstrip("/")
        if not normalized or normalized in seen:
            continue
        resolved.append(normalized)
        seen.add(normalized)
    return resolved
