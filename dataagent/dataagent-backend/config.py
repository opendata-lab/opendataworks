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
    task_max_concurrency: int = 4
    task_lease_ttl_seconds: int = 30
    task_heartbeat_seconds: int = 5
    task_recovery_scan_interval_seconds: int = 2
    task_recovery_batch_size: int = 20
    # Max time a run waits at a permission confirmation before timing out (deny).
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

    # ---- Topic runtime root / sandbox ----
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
