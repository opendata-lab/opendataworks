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



def test_dataagent_is_deployed_outside_this_stack(render):
    services = render()
    assert not any(name.startswith("dataagent") for name in services)
    assert not any("oda-" in service.get("image", "") for service in services.values())
    # Widget forwarding lives in the mounted nginx config, so the portal does not depend on DataAgent.
    frontend = services["frontend"]
    assert set(frontend.get("depends_on", {})) == {"backend"}
    assert any(volume.get("target") == "/etc/nginx/conf.d/default.conf" for volume in frontend["volumes"])


def test_mcp_is_served_by_backend_without_shared_forwarding_tokens(render):
    services = render(ODW_HTTP_ALLOWED_ORIGINS="https://portal.example",
                      ODW_HTTP_REQUIRE_PRIVATE_NETWORK="true",
                      ODW_HTTP_TRUSTED_PROXIES="10.0.0.2")
    assert "odw-mcp-server" not in services
    env = services["backend"]["environment"]
    assert "AGENT_API_SERVICE_TOKEN" not in env
    assert env["ODW_HTTP_ALLOWED_ORIGINS"] == "https://portal.example"
    assert env["ODW_HTTP_REQUIRE_PRIVATE_NETWORK"] == "true"
    assert env["ODW_HTTP_TRUSTED_PROXIES"] == "10.0.0.2"
    assert not any("8801" in str(port) for svc in services.values() for port in svc.get("ports", []))


def test_redis_is_not_part_of_portal_stack(render):
    services = render()
    assert "redis" not in services
    assert not any("redis" in service.get("image", "") for service in services.values())
    assert not any("6379" in str(port) for svc in services.values() for port in svc.get("ports", []))



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


def test_portal_proxy_does_not_turn_public_mcp_clients_into_private_peers():
    config = (ROOT / "deploy/docker/nginx/frontend.conf").read_text()
    assert re.search(r"location\s+=\s+/api/mcp\s*\{\s*return 404;\s*\}", config)
    assert re.search(r"location\s+\^~\s+/api/mcp/\s*\{\s*return 404;\s*\}", config)
    assert re.search(r"(?m)^    location /api/ \{", config)
