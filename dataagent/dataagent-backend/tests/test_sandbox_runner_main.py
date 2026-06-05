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


def _agent_snapshot(skill_folders: list[str] | None = None) -> dict:
    return {
        "agent_id": "agent-custom",
        "name": "自定义智能体",
        "permission_mode": "default",
        "allowed_tools": ["Read"],
        "mcp_server_ids": [],
        "skill_folders": skill_folders or [],
        "max_turns": 0,
        "env_vars": {},
        "is_default": False,
    }


def _payload(agent_snapshot: dict | None = None) -> dict:
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
        "agent_snapshot": agent_snapshot,
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
        backend, container_name, command = sandbox_runner_main._build_container_command(
            TaskExecutionInput(**_payload(agent_snapshot=_agent_snapshot([])))
        )
    finally:
        update_settings(originals)

    assert backend == "docker"
    assert container_name.startswith("dataagent-task-topic-1-task-1")
    assert host_root / "topic-1" == tmp_path / "topics" / "topic-1"
    assert (host_root / "topic-1").is_dir()
    assert (host_root / "topic-1" / ".claude" / "skills").is_dir()
    assert f"type=bind,source={host_root / 'topic-1'},target=/app" in command
    assert f"type=bind,source={host_root},target=/app" not in command
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
    assert "/opt/dataagent-backend/sandbox_task_main.py" in command
    assert not any("/var/run/docker.sock" in arg for arg in command)
    assert not any("target=/skills" in arg for arg in command)
    assert not any("target=/workspace" in arg for arg in command)

    env_values = [command[index + 1] for index, item in enumerate(command) if item == "--env"]
    assert "HOME=/app" in env_values
    assert "PWD=/app" in env_values
    assert "DATAAGENT_WORKSPACE_DIR=/app" in env_values
    assert "DATAAGENT_WORKSPACE_PREPARED=1" in env_values
    assert "DATAAGENT_SANDBOX_ROOT=/app" in env_values
    assert "SKILLS_ROOT_DIR=/app/.claude/skills" in env_values
    assert not any(value.startswith("DATAAGENT_SKILL_LINK_ROOT=") for value in env_values)
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


def test_sandbox_runner_container_process_uses_configured_stream_limit(monkeypatch):
    settings = get_settings()
    originals = {
        "agent_max_buffer_size_bytes": settings.agent_max_buffer_size_bytes,
    }
    emitted: list[dict] = []
    sandbox_runner_main.CANCELLED_TASK_IDS.discard("task-1")

    update_settings({"agent_max_buffer_size_bytes": 4 * 1024 * 1024})
    child_code = """
import json
import sys

sys.stdin.read()
large_payload = "x" * (96 * 1024)
print(json.dumps({
    "type": "record",
    "record": {
        "record_type": "event",
        "event_type": "DEBUG",
        "data": {"payload": large_payload},
    },
}), flush=True)
print(json.dumps({
    "type": "result",
    "result": {
        "task_status": "finished",
        "content": "large-line-ok",
        "provider_id": "openrouter",
        "model": "anthropic/claude-sonnet-4.5",
    },
}), flush=True)
"""
    monkeypatch.setattr(
        sandbox_runner_main,
        "_build_container_command",
        lambda params: ("docker", "container-1", [sys.executable, "-c", child_code]),
    )
    kill_calls: list[tuple[str, str]] = []

    async def fake_kill_container(backend: str, container_name: str) -> None:
        kill_calls.append((backend, container_name))

    monkeypatch.setattr(sandbox_runner_main, "_kill_container", fake_kill_container)

    async def emit(record: dict) -> None:
        emitted.append(record)

    try:
        result = asyncio.run(
            sandbox_runner_main._execute_task_stream_container(
                TaskExecutionInput(**_payload()),
                emit=emit,
                is_cancel_requested=lambda: False,
            )
        )
    finally:
        sandbox_runner_main.CANCELLED_TASK_IDS.discard("task-1")
        update_settings(originals)

    assert result.task_status == "finished"
    assert result.content == "large-line-ok"
    assert emitted[0]["data"]["payload"] == "x" * (96 * 1024)
    assert kill_calls == []


def test_resolve_host_skills_dir_uses_explicit_setting_only(monkeypatch):
    settings = get_settings()
    original = settings.dataagent_sandbox_host_skills_dir
    try:
        update_settings({"dataagent_sandbox_host_skills_dir": ""})
        assert sandbox_runner_main._resolve_host_skills_dir(get_settings()) == ""
        update_settings({"dataagent_sandbox_host_skills_dir": "/explicit/skills"})
        assert sandbox_runner_main._resolve_host_skills_dir(get_settings()) == "/explicit/skills"
    finally:
        update_settings({"dataagent_sandbox_host_skills_dir": original})


def test_sandbox_runner_mounts_only_enabled_agent_skills_into_child(monkeypatch, tmp_path: Path):
    settings = get_settings()
    originals = {
        "dataagent_sandbox_backend": settings.dataagent_sandbox_backend,
        "dataagent_sandbox_image": settings.dataagent_sandbox_image,
        "dataagent_sandbox_host_root": settings.dataagent_sandbox_host_root,
        "dataagent_sandbox_root": settings.dataagent_sandbox_root,
        "dataagent_sandbox_host_skills_dir": settings.dataagent_sandbox_host_skills_dir,
    }
    host_root = tmp_path / "topics"
    host_skills = tmp_path / "offline" / "skills"
    skill_dir = host_skills / "platform-imported-skill"
    skill_dir.mkdir(parents=True)
    (skill_dir / "SKILL.md").write_text("# imported\n", encoding="utf-8")
    unused_skill = host_skills / "unused-skill"
    unused_skill.mkdir(parents=True)
    (unused_skill / "SKILL.md").write_text("# unused\n", encoding="utf-8")
    update_settings(
        {
            "dataagent_sandbox_backend": "docker",
            "dataagent_sandbox_image": "opendataworks-dataagent-runner:test",
            "dataagent_sandbox_host_root": str(host_root),
            "dataagent_sandbox_root": str(tmp_path / "container-topics"),
            "dataagent_sandbox_host_skills_dir": str(host_skills),
        }
    )
    try:
        _, _, command = sandbox_runner_main._build_container_command(
            TaskExecutionInput(**_payload(agent_snapshot=_agent_snapshot(["platform-imported-skill"])))
        )
    finally:
        update_settings(originals)

    assert (
        f"type=bind,source={skill_dir.resolve()},target=/app/.claude/skills/platform-imported-skill,readonly"
        in command
    )
    assert not any("unused-skill" in item for item in command)
    assert not any(f"source={host_skills.resolve()},target=/app/.claude/skills" in item for item in command)
    env_values = [command[index + 1] for index, item in enumerate(command) if item == "--env"]
    assert "SKILLS_ROOT_DIR=/app/.claude/skills" in env_values


def test_sandbox_runner_requires_host_skills_dir_when_agent_enables_skills(monkeypatch, tmp_path: Path):
    settings = get_settings()
    originals = {
        "dataagent_sandbox_backend": settings.dataagent_sandbox_backend,
        "dataagent_sandbox_image": settings.dataagent_sandbox_image,
        "dataagent_sandbox_host_root": settings.dataagent_sandbox_host_root,
        "dataagent_sandbox_host_skills_dir": settings.dataagent_sandbox_host_skills_dir,
    }
    update_settings(
        {
            "dataagent_sandbox_backend": "docker",
            "dataagent_sandbox_image": "opendataworks-dataagent-runner:test",
            "dataagent_sandbox_host_root": str(tmp_path / "topics"),
            "dataagent_sandbox_host_skills_dir": "",
        }
    )
    try:
        try:
            sandbox_runner_main._build_container_command(
                TaskExecutionInput(**_payload(agent_snapshot=_agent_snapshot(["platform-imported-skill"])))
            )
        except RuntimeError as exc:
            assert "DATAAGENT_SANDBOX_HOST_SKILLS_DIR" in str(exc)
        else:
            raise AssertionError("expected missing host skills dir to fail")
    finally:
        update_settings(originals)


def test_sandbox_runner_requires_enabled_skill_folder_to_exist(monkeypatch, tmp_path: Path):
    settings = get_settings()
    originals = {
        "dataagent_sandbox_backend": settings.dataagent_sandbox_backend,
        "dataagent_sandbox_image": settings.dataagent_sandbox_image,
        "dataagent_sandbox_host_root": settings.dataagent_sandbox_host_root,
        "dataagent_sandbox_host_skills_dir": settings.dataagent_sandbox_host_skills_dir,
    }
    host_skills = tmp_path / "skills"
    host_skills.mkdir()
    update_settings(
        {
            "dataagent_sandbox_backend": "docker",
            "dataagent_sandbox_image": "opendataworks-dataagent-runner:test",
            "dataagent_sandbox_host_root": str(tmp_path / "topics"),
            "dataagent_sandbox_host_skills_dir": str(host_skills),
        }
    )
    try:
        try:
            sandbox_runner_main._build_container_command(
                TaskExecutionInput(**_payload(agent_snapshot=_agent_snapshot(["missing-skill"])))
            )
        except RuntimeError as exc:
            assert "enabled skill folder not found: missing-skill" in str(exc)
        else:
            raise AssertionError("expected missing enabled skill to fail")
    finally:
        update_settings(originals)


def test_discover_host_skills_dir_reads_runner_mount_source(monkeypatch):
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
        returncode = 0

        async def communicate(self):
            return b"/srv/offline/skills\n", b""

    async def fake_exec(*args, **kwargs):
        calls.append(tuple(str(arg) for arg in args))
        return FakeProcess()

    monkeypatch.setattr(sandbox_runner_main.socket, "gethostname", lambda: "runner-cid")
    monkeypatch.setattr(sandbox_runner_main.asyncio, "create_subprocess_exec", fake_exec)
    try:
        resolved = asyncio.run(sandbox_runner_main._discover_host_skills_dir())
    finally:
        update_settings(originals)

    assert resolved == "/srv/offline/skills"
    assert calls[0][1] == "inspect"
    assert sandbox_runner_main.RUNNER_SKILLS_MOUNT_TARGET in calls[0][3]
    assert calls[0][-1] == "runner-cid"


def test_resolve_host_skills_dir_prefers_explicit_then_auto(monkeypatch):
    settings = get_settings()
    original = settings.dataagent_sandbox_host_skills_dir
    monkeypatch.setattr(sandbox_runner_main, "_AUTO_HOST_SKILLS_DIR", "/auto/skills")
    try:
        update_settings({"dataagent_sandbox_host_skills_dir": ""})
        assert sandbox_runner_main._resolve_host_skills_dir(get_settings()) == "/auto/skills"
        update_settings({"dataagent_sandbox_host_skills_dir": "/explicit/skills"})
        assert sandbox_runner_main._resolve_host_skills_dir(get_settings()) == "/explicit/skills"
    finally:
        update_settings({"dataagent_sandbox_host_skills_dir": original})


def test_sandbox_runner_validates_against_runner_mount_when_host_not_visible(monkeypatch, tmp_path: Path):
    """The host skills path is the child bind source resolved by the host docker
    daemon and is not visible inside the runner; validation must fall back to the
    runner's own live skills mount while the child still binds the host path."""
    settings = get_settings()
    originals = {
        "dataagent_sandbox_backend": settings.dataagent_sandbox_backend,
        "dataagent_sandbox_image": settings.dataagent_sandbox_image,
        "dataagent_sandbox_host_root": settings.dataagent_sandbox_host_root,
        "dataagent_sandbox_root": settings.dataagent_sandbox_root,
        "dataagent_sandbox_host_skills_dir": settings.dataagent_sandbox_host_skills_dir,
    }
    # The host path the child must bind-mount from (not present inside this process,
    # mirroring a host path that the runner container cannot see).
    host_skills = "/host/only/offline/skills"
    # The runner's own live skills mount, which the validation should use instead.
    runner_mount = tmp_path / "runner-skills"
    skill_dir = runner_mount / "platform-imported-skill"
    skill_dir.mkdir(parents=True)
    (skill_dir / "SKILL.md").write_text("# imported\n", encoding="utf-8")
    monkeypatch.setattr(sandbox_runner_main, "RUNNER_SKILLS_MOUNT_TARGET", str(runner_mount))
    update_settings(
        {
            "dataagent_sandbox_backend": "docker",
            "dataagent_sandbox_image": "opendataworks-dataagent-runner:test",
            "dataagent_sandbox_host_root": str(tmp_path / "topics"),
            "dataagent_sandbox_root": str(tmp_path / "container-topics"),
            "dataagent_sandbox_host_skills_dir": host_skills,
        }
    )
    try:
        _, _, command = sandbox_runner_main._build_container_command(
            TaskExecutionInput(**_payload(agent_snapshot=_agent_snapshot(["platform-imported-skill"])))
        )
    finally:
        update_settings(originals)

    assert (
        f"type=bind,source={host_skills}/platform-imported-skill,"
        "target=/app/.claude/skills/platform-imported-skill,readonly"
        in command
    )
