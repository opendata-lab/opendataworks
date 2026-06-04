from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

from fastapi.testclient import TestClient

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

import sandbox_runner_main
from config import get_settings, update_settings
from core.task_executor import TaskExecutionInput
from core.task_executor import TaskExecutionResult


def _payload() -> dict:
    return {
        "task_id": "task-1",
        "topic_id": "topic-1",
        "question": "hello",
        "history": [],
        "resume_session_id": None,
        "provider_id": "openrouter",
        "model": "anthropic/claude-sonnet-4.5",
        "database_hint": None,
        "debug": False,
        "timeout_seconds": 60,
        "sql_read_timeout_seconds": 30,
        "sql_write_timeout_seconds": 30,
        "execution_mode": "background",
        "agent_snapshot": None,
    }


def test_sandbox_runner_streams_records_and_result(monkeypatch):
    async def fake_execute(params, *, emit, is_cancel_requested=None):
        assert params.topic_id == "topic-1"
        await emit({"record_type": "event", "event_type": "DEBUG", "data": {"status": "runner"}})
        return TaskExecutionResult(
            task_status="finished",
            content="runner-api-ok",
            provider_id="openrouter",
            model="anthropic/claude-sonnet-4.5",
            session_id="sdk-session-api",
        )

    monkeypatch.setattr(sandbox_runner_main, "_execute_task_stream_local", fake_execute)

    client = TestClient(sandbox_runner_main.app)
    with client.stream("POST", "/internal/sandbox/runs", json=_payload()) as response:
        assert response.status_code == 200
        lines = [json.loads(line) for line in response.iter_lines() if line]

    assert lines[0]["type"] == "record"
    assert lines[0]["record"]["data"]["status"] == "runner"
    assert lines[-1]["type"] == "result"
    assert lines[-1]["result"]["content"] == "runner-api-ok"
    assert lines[-1]["result"]["session_id"] == "sdk-session-api"


def test_sandbox_runner_cancel_endpoint_marks_task_cancelled():
    sandbox_runner_main.CANCELLED_TASK_IDS.clear()
    client = TestClient(sandbox_runner_main.app)

    response = client.post("/internal/sandbox/runs/task-1/cancel", json={"task_id": "task-1"})

    assert response.status_code == 200
    assert response.json() == {"accepted": True, "task_id": "task-1"}
    assert "task-1" in sandbox_runner_main.CANCELLED_TASK_IDS


def test_sandbox_runner_container_command_mounts_only_topic_workspace(monkeypatch, tmp_path: Path):
    settings = get_settings()
    originals = {
        "dataagent_sandbox_backend": settings.dataagent_sandbox_backend,
        "dataagent_sandbox_image": settings.dataagent_sandbox_image,
        "dataagent_sandbox_host_root": settings.dataagent_sandbox_host_root,
        "dataagent_sandbox_root": settings.dataagent_sandbox_root,
        "dataagent_sandbox_network": settings.dataagent_sandbox_network,
        "dataagent_sandbox_host_skills_dir": settings.dataagent_sandbox_host_skills_dir,
    }
    host_root = tmp_path / "topics"
    update_settings(
        {
            "dataagent_sandbox_backend": "docker",
            "dataagent_sandbox_image": "opendataworks-dataagent-runner:test",
            "dataagent_sandbox_host_root": str(host_root),
            "dataagent_sandbox_root": str(tmp_path / "container-topics"),
            "dataagent_sandbox_network": "container:opendataworks-dataagent-sandbox-runner",
            "dataagent_sandbox_host_skills_dir": "",
        }
    )
    monkeypatch.setenv("MYSQL_HOST", "mysql")
    monkeypatch.setenv("DATAAGENT_RUNTIME_UID", "1000")
    monkeypatch.setenv("DATAAGENT_RUNTIME_GID", "1000")
    try:
        backend, container_name, command = sandbox_runner_main._build_container_command(TaskExecutionInput(**_payload()))
    finally:
        update_settings(originals)

    assert backend == "docker"
    assert container_name.startswith("dataagent-task-topic-1-task-1")
    assert host_root / "topic-1" == tmp_path / "topics" / "topic-1"
    assert (host_root / "topic-1").is_dir()
    assert f"type=bind,source={host_root / 'topic-1'},target=/workspace" in command
    assert f"type=bind,source={host_root},target=/workspace" not in command
    assert "--network" in command
    assert "container:opendataworks-dataagent-sandbox-runner" in command
    assert "--interactive" in command
    assert f"{sandbox_runner_main.SANDBOX_CONTAINER_LABEL}={sandbox_runner_main.SANDBOX_CONTAINER_LABEL_VALUE}" in command
    assert f"{sandbox_runner_main.SANDBOX_TASK_ID_LABEL}=task-1" in command
    assert f"{sandbox_runner_main.SANDBOX_TOPIC_ID_LABEL}=topic-1" in command
    assert "--user" in command
    assert "1000:1000" in command
    assert "opendataworks-dataagent-runner:test" in command
    assert "python" in command
    assert "/app/dataagent-backend/sandbox_task_main.py" in command
    assert not any("/var/run/docker.sock" in arg for arg in command)

    env_values = [command[index + 1] for index, item in enumerate(command) if item == "--env"]
    assert "HOME=/workspace" in env_values
    assert "PWD=/workspace" in env_values
    assert "DATAAGENT_WORKSPACE_DIR=/workspace" in env_values
    assert "DATAAGENT_WORKSPACE_PREPARED=1" in env_values
    assert "DATAAGENT_SKILL_LINK_ROOT=/app/.claude/skills" in env_values
    assert not any(value.startswith("DATAAGENT_TASK_PAYLOAD_B64=") for value in env_values)


def test_sandbox_runner_startup_cleanup_removes_labeled_stale_containers(monkeypatch):
    settings = get_settings()
    originals = {
        "dataagent_sandbox_backend": settings.dataagent_sandbox_backend,
        "dataagent_sandbox_image": settings.dataagent_sandbox_image,
    }
    update_settings(
        {
            "dataagent_sandbox_backend": "docker",
            "dataagent_sandbox_image": "opendataworks-dataagent-runner:test",
        }
    )
    calls: list[tuple[str, ...]] = []

    class FakeProcess:
        def __init__(self, returncode: int, stdout: bytes = b"", stderr: bytes = b""):
            self.returncode = returncode
            self._stdout = stdout
            self._stderr = stderr

        async def communicate(self):
            return self._stdout, self._stderr

    async def fake_exec(*args, **kwargs):
        calls.append(tuple(str(arg) for arg in args))
        if args[:2] == ("docker", "ps"):
            return FakeProcess(0, stdout=b"container-a\ncontainer-b\n")
        if args[:2] == ("docker", "rm"):
            return FakeProcess(0)
        raise AssertionError(f"unexpected subprocess args: {args}")

    monkeypatch.setattr(sandbox_runner_main.asyncio, "create_subprocess_exec", fake_exec)
    try:
        asyncio.run(sandbox_runner_main._cleanup_stale_sandbox_containers())
    finally:
        update_settings(originals)

    assert calls[0] == (
        "docker",
        "ps",
        "-aq",
        "--filter",
        f"label={sandbox_runner_main.SANDBOX_CONTAINER_LABEL}={sandbox_runner_main.SANDBOX_CONTAINER_LABEL_VALUE}",
    )
    assert calls[1] == ("docker", "rm", "-f", "container-a", "container-b")
