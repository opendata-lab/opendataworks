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



def test_dataagent_has_only_session_database_and_explicit_wiring(render):
    services = render(MYSQL_DATABASE="platform_data", DATAAGENT_MYSQL_USER="agent_user",
                      DATAAGENT_MYSQL_PASSWORD="agent password", DATAAGENT_SESSION_MYSQL_DATABASE="agent_sessions")
    for name in ("dataagent-backend", "dataagent-sandbox-runner"):
        service = services[name]
        env = service["environment"]
        assert env["MYSQL_DATABASE"] == env["SESSION_MYSQL_DATABASE"] == "agent_sessions"
        assert env["MYSQL_USER"] == "agent_user" and env["MYSQL_PASSWORD"] == "agent password"
        assert not any(key.startswith(("ODW_", "DORIS_", "DATAAGENT_PORTAL_")) for key in env)
        assert "odw-mcp-server" not in service.get("depends_on", {})
        assert all("/dataagent/.claude/skills" not in volume.get("source", "") for volume in service.get("volumes", []))


def test_mcp_tokens_are_owned_by_opendataworks(render):
    services = render(AGENT_API_SERVICE_TOKEN="service-test", ODW_MCP_SERVER_TOKEN="mcp-test",
                      ODW_MCP_SERVER_TOKEN_HEADER_NAME="X-Test-MCP")
    env = services["odw-mcp-server"]["environment"]
    assert env["ODW_MCP_SERVER_BACKEND_SERVICE_TOKEN"] == services["backend"]["environment"]["AGENT_API_SERVICE_TOKEN"] == "service-test"
    assert env["ODW_MCP_SERVER_FRONTDOOR_TOKEN"] == "mcp-test"
    assert env["ODW_MCP_SERVER_FRONTDOOR_TOKEN_HEADER_NAME"] == "X-Test-MCP"



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
