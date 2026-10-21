"""Exercise real Compose interpolation and the resulting application settings.

No services are started. Isolated env files keep workstation credentials out of
assertion output and prove that a fresh deployment needs no copied tuning knobs.
"""
from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

from dotenv import dotenv_values
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "dataagent" / "dataagent-backend"))
from config import Settings, resolve_run_limits


@pytest.fixture(scope="module")
def compose_command():
    # Parsing config does not require a daemon or a running container.
    if shutil.which("docker-compose"):
        return ["docker-compose"]
    for runtime in ("docker", "podman"):
        if shutil.which(runtime):
            probe = subprocess.run([runtime, "compose", "version"], capture_output=True)
            if probe.returncode == 0:
                return [runtime, "compose"]
    pytest.skip("Compose CLI required for deployment contract validation")


@pytest.fixture(params=["dev", "prod"])
def render(tmp_path, request, compose_command):
    source = ROOT / "deploy" / f"docker-compose.{request.param}.yml"
    target = tmp_path / source.name
    target.write_text(source.read_text())
    base = dict(dotenv_values(ROOT / "deploy" / ".env.example"))
    inherited = os.environ.copy()
    # Explicit shell variables outrank --env-file; do not inherit workstation overrides.
    for key in set(re.findall(r"\$\{([A-Z][A-Z0-9_]*)", source.read_text())) | set(base):
        inherited.pop(key, None)

    def run(**overrides):
        values = {**base, **overrides}
        env_file = tmp_path / ".env"
        env_file.write_text("\n".join(f"{key}={json.dumps(str(value))}" for key, value in values.items()) + "\n")
        result = subprocess.run(
            [*compose_command, "--env-file", str(env_file), "-f", str(target),
             "config", "--format", "json"],
            cwd=tmp_path, env=inherited, capture_output=True, text=True, check=True,
        )
        return json.loads(result.stdout)["services"]

    return run


def settings(service):
    return Settings(_env_file=None, **{key.lower(): value for key, value in service["environment"].items()})


def test_minimal_template_keeps_application_defaults_and_internal_routes(render):
    services = render()
    backend = services["backend"]["environment"]
    assert backend["SPRING_DATASOURCE_USERNAME"] == backend["MYSQL_USER"]
    assert backend["SPRING_DATASOURCE_PASSWORD"] == backend["MYSQL_PASSWORD"]
    assert backend["SPRING_DATASOURCE_URL"].startswith("jdbc:mysql://mysql:3306/opendataworks?")
    for name in ("dataagent-backend", "dataagent-sandbox-runner"):
        cfg = settings(services[name])
        assert cfg.mysql_host == "mysql" and cfg.mysql_port == 3306
        assert cfg.mysql_user == "dataagent" and cfg.session_mysql_database == "dataagent"
        assert cfg.dataagent_runtime_kind == "pi_agent_core"
        assert cfg.task_max_concurrency == 8
        assert cfg.task_heartbeat_seconds < cfg.task_lease_ttl_seconds
        assert resolve_run_limits(cfg, "auto")["max_tool_calls"] == 200
        assert cfg.dataagent_sandbox_reuse_enabled
    env = services["dataagent-backend"]["environment"]
    assert env["REDIS_HOST"] == "redis"
    assert env["ODW_BACKEND_BASE_URL"] == "http://backend:8080/api/v1/ai"
    assert env["DATAAGENT_PORTAL_MCP_BASE_URL"] == "http://portal-mcp:8801/mcp/"
    assert "environment" not in services["frontend"]
    assert "environment" not in services["dataagent-frontend"]


def test_nondefault_credentials_follow_roles_and_shared_tokens(render):
    services = render(MYSQL_USER="portal_user", MYSQL_PASSWORD="portal password",
                      MYSQL_DATABASE="portal_data", DATAAGENT_MYSQL_USER="agent_user",
                      DATAAGENT_MYSQL_PASSWORD="agent password", AGENT_API_SERVICE_TOKEN="service-test",
                      PORTAL_MCP_TOKEN="mcp-test", PORTAL_MCP_TOKEN_HEADER_NAME="X-Test-MCP")
    main = services["backend"]["environment"]
    mysql = services["mysql"]["environment"]
    portal = services["portal-mcp"]["environment"]
    assert main["SPRING_DATASOURCE_PASSWORD"] == mysql["MYSQL_PASSWORD"] == "portal password"
    assert main["SPRING_DATASOURCE_USERNAME"] == mysql["MYSQL_USER"] == "portal_user"
    assert "/portal_data?" in main["SPRING_DATASOURCE_URL"]
    for name in ("dataagent-backend", "dataagent-sandbox-runner"):
        agent = services[name]["environment"]
        assert agent["MYSQL_USER"] == "agent_user"
        assert agent["MYSQL_PASSWORD"] == "agent password"
        assert agent["MYSQL_DATABASE"] == "portal_data"
        assert agent["ODW_AGENT_SERVICE_TOKEN"] == main["AGENT_API_SERVICE_TOKEN"] == portal["PORTAL_MCP_BACKEND_SERVICE_TOKEN"]
        assert agent["DATAAGENT_PORTAL_MCP_TOKEN"] == portal["PORTAL_MCP_FRONTDOOR_TOKEN"] == "mcp-test"
        assert agent["DATAAGENT_PORTAL_MCP_TOKEN_HEADER_NAME"] == portal["PORTAL_MCP_FRONTDOOR_TOKEN_HEADER_NAME"] == "X-Test-MCP"


def test_documented_external_spring_database_remains_an_explicit_override(render):
    url = "jdbc:mysql://external-db:3307/external?useSSL=true"
    services = render(SPRING_DATASOURCE_URL=url, SPRING_DATASOURCE_USERNAME="external-user",
                      SPRING_DATASOURCE_PASSWORD="external-password")
    env = services["backend"]["environment"]
    assert env["SPRING_DATASOURCE_URL"] == url
    assert env["SPRING_DATASOURCE_USERNAME"] == "external-user"
    assert env["SPRING_DATASOURCE_PASSWORD"] == "external-password"
    assert settings(services["dataagent-backend"]).mysql_host == "mysql"


def test_native_advanced_settings_and_deployment_switches_still_work(render):
    services = render(TASK_MAX_CONCURRENCY="12", SCHEDULE_SCAN_INTERVAL_SECONDS="20",
                      DATAAGENT_SANDBOX_MAX_WARM_CONTAINERS="16", DATAAGENT_SANDBOX_REUSE_ENABLED="false",
                      DATAAGENT_RUNTIME_KIND="claude_code", DATAAGENT_SANDBOX_MODE="container",
                      DATAAGENT_RUNTIME_UID="1200", DATAAGENT_RUNTIME_GID="1201",
                      DATAAGENT_HOST_ROOT="/tmp/odw-runtime-custom", DATAAGENT_SKILLS_DIR="/tmp/odw-skills-custom",
                      OPENDATAWORKS_DATAAGENT_RUNNER_IMAGE="test-runner:custom",
                      PORTAL_MCP_MOUNT_PATH="/custom-mcp", PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS="900")
    cfg = settings(services["dataagent-backend"])
    assert cfg.task_max_concurrency == 12 and cfg.schedule_scan_interval_seconds == 20
    assert cfg.dataagent_sandbox_mode == "container" and cfg.dataagent_runtime_kind == "claude_code"
    runner = services["dataagent-sandbox-runner"]
    cfg = settings(runner)
    assert cfg.dataagent_sandbox_mode == ""  # child execution must not delegate recursively
    assert cfg.dataagent_sandbox_max_warm_containers == 16 and not cfg.dataagent_sandbox_reuse_enabled
    assert runner["image"] == runner["environment"]["DATAAGENT_SANDBOX_IMAGE"] == "test-runner:custom"
    assert runner["environment"]["DATAAGENT_RUNTIME_UID"] == "1200"
    assert services["dataagent-backend"]["user"] == "1200:1201"
    assert cfg.dataagent_host_root == "/tmp/odw-runtime-custom"
    for name in ("dataagent-backend", "dataagent-sandbox-runner"):
        mounts = {item["target"]: item["source"] for item in services[name]["volumes"]}
        assert mounts["/dataagent_runtime"] == "/tmp/odw-runtime-custom"
        assert mounts["/app/.claude/skills"] == "/tmp/odw-skills-custom"
    assert cfg.dataagent_portal_mcp_base_url == "http://portal-mcp:8801/custom-mcp/"
    assert services["portal-mcp"]["environment"]["PORTAL_MCP_KEEPALIVE_TIMEOUT_SECONDS"] == "900"


def test_mysql_probe_uses_the_container_password_after_customization(render, tmp_path):
    service = render(MYSQL_ROOT_PASSWORD="changed root password")["mysql"]
    kind, command = service["healthcheck"]["test"]
    assert kind == "CMD-SHELL"
    # Compose escapes the dollar in config; the container shell gets a single dollar.
    command = command.replace("$$", "$")
    executable = tmp_path / "mysqladmin"
    executable.write_text('#!/bin/sh\nprintf "%s" "$MYSQL_PWD"\n')
    executable.chmod(0o755)
    result = subprocess.run(["sh", "-c", command], capture_output=True, text=True, check=True,
                            env={**os.environ, **service["environment"], "PATH": f"{tmp_path}:{os.environ['PATH']}"})
    assert result.stdout == "changed root password"
