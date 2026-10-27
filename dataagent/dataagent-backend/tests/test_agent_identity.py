from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
import json
import os
import subprocess
import sys
import threading

import httpx
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from core.agent_identity import _identity, current_headers, identity_headers, set_execution_identity
from core.tool_http_channel import ToolHttpChannel, channel_url


def test_identity_requires_user_and_agent():
    with pytest.raises(ValueError):
        identity_headers("", "agent-a")
    with pytest.raises(ValueError):
        identity_headers("local:alice", "")
    assert identity_headers("local:alice", "agent-a")["X-Agent-User-Id"] == "local:alice"
    assert identity_headers("oidc:subject", "agent-a")["X-Agent-User-Id"] == "oidc:subject"


def test_run_identity_is_restored():
    token = set_execution_identity(SimpleNamespace(auth_user_id="local:alice", agent_snapshot={"agent_id": "agent-a"}))
    try:
        assert current_headers()["X-Agent-Id"] == "agent-a"
    finally:
        _identity.reset(token)
    assert current_headers() == {}


def test_cli_channel_pins_identity_and_hides_service_secret(monkeypatch):
    captured = []
    class Upstream(BaseHTTPRequestHandler):
        def do_GET(self):
            captured.append(dict(self.headers))
            payload = json.dumps({"kind": "metadata_snapshot"}).encode()
            self.send_response(200)
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        def log_message(self, *_args):
            pass
    server = ThreadingHTTPServer(("127.0.0.1", 0), Upstream)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setenv("ODW_BACKEND_BASE_URL", f"http://127.0.0.1:{server.server_port}/api/v1/ai")
    monkeypatch.setenv("ODW_AGENT_SERVICE_TOKEN", "server-only-secret")
    token = set_execution_identity(SimpleNamespace(auth_user_id="local:alice", agent_snapshot={"agent_id": "agent-a"}))
    try:
        with ToolHttpChannel() as channel:
            url = channel_url()
            response = httpx.get(url + "/metadata/inspect", headers={"X-Agent-User-Id": "local:mallory", "X-Agent-Id": "agent-b"})
            assert response.status_code == 200
            cli = Path(__file__).resolve().parents[2] / ".claude/skills/opendataworks-platform-tools/bin/odw-cli"
            env = {**os.environ, "ODW_BACKEND_BASE_URL": url}
            env.pop("ODW_AGENT_SERVICE_TOKEN", None)
            result = subprocess.run(["sh", str(cli), "inspect"], env=env, capture_output=True, text=True)
            assert result.returncode == 0, result.stderr
            assert "server-only-secret" not in result.stdout
        for headers in captured:
            assert headers["X-Agent-User-Id"] == "local:alice"
            assert headers["X-Agent-Id"] == "agent-a"
            assert "X-Agent-Independent" not in headers
            assert headers["X-Agent-Service-Token"] == "server-only-secret"
        assert channel.server.fileno() == -1
    finally:
        _identity.reset(token)
        server.shutdown()
        server.server_close()
        thread.join()


def test_public_task_input_cannot_claim_a_schedule_source():
    from models.schemas import CreateTaskRequest
    task = CreateTaskRequest(topic_id="topic-a", message_type="text", message_content="query",
                             source_schedule_id="forged-schedule", source_schedule_log_id="forged-log")
    assert "source_schedule_id" not in task.model_dump()
    assert "source_schedule_log_id" not in task.model_dump()


def test_model_environment_clears_parent_service_and_database_secrets(monkeypatch):
    import os
    from config import get_settings
    from core.agent_runtime import _build_runtime_env
    for key in ("ODW_AGENT_SERVICE_TOKEN", "MYSQL_PASSWORD", "PORTAL_MCP_FRONTDOOR_TOKEN", "DATAAGENT_PORTAL_MCP_TOKEN"):
        monkeypatch.setenv(key, "parent-private-secret")
    env = _build_runtime_env(get_settings(), {})
    merged = {**os.environ, **env}
    for key in ("ODW_AGENT_SERVICE_TOKEN", "MYSQL_PASSWORD", "PORTAL_MCP_FRONTDOOR_TOKEN", "DATAAGENT_PORTAL_MCP_TOKEN"):
        assert merged[key] == ""
