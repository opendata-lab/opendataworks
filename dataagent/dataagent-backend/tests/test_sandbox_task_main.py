from __future__ import annotations

import asyncio
import json
import logging
import os
import subprocess
import sys
from pathlib import Path

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

import sandbox_task_main
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


def test_serve_loop_processes_multiple_payloads_then_exits_on_eof(monkeypatch, tmp_path: Path):
    captured: list[str] = []

    async def fake_execute_and_emit(params):
        captured.append(params.task_id)
        return TaskExecutionResult(
            task_status="finished",
            content=f"warm-{params.task_id}",
            provider_id=params.provider_id,
            model=params.model,
        )

    async def fake_reader():
        reader = asyncio.StreamReader()
        first = json.dumps(_payload())
        second_payload = _payload()
        second_payload["task_id"] = "task-2"
        second = json.dumps(second_payload)
        reader.feed_data((first + "\n" + second + "\n").encode("utf-8"))
        reader.feed_eof()
        return reader

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(sandbox_task_main, "_execute_and_emit", fake_execute_and_emit)
    monkeypatch.setattr(sandbox_task_main, "_stdin_reader", fake_reader)

    exit_code = asyncio.run(sandbox_task_main._serve_loop())

    assert exit_code == 0
    assert captured == ["task-1", "task-2"]


def test_serve_loop_exits_on_idle_timeout(monkeypatch, tmp_path: Path):
    async def fake_reader():
        reader = asyncio.StreamReader()
        # never feeds data and never EOFs -> readline blocks until idle timeout
        return reader

    monkeypatch.setenv("DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT", "0.1")
    monkeypatch.setattr(sandbox_task_main, "_stdin_reader", fake_reader)

    exit_code = asyncio.run(sandbox_task_main._serve_loop())

    assert exit_code == 0


@pytest.mark.parametrize(
    ("configured_limit", "question_size"),
    [(1024, 256 * 1024), (12 * 1024 * 1024, 11 * 1024 * 1024)],
)
def test_serve_loop_reads_large_payload_and_followup_through_real_stdin(
    configured_limit, question_size,
):
    # Exercise the production reader and result protocol over an OS pipe. Only
    # model execution is stubbed; no provider or database is needed for framing.
    script = """
import asyncio
import sandbox_task_main
from core.task_executor import TaskExecutionResult

async def execute(params, **kwargs):
    await kwargs['emit']({'event_type': 'message_start'})
    return TaskExecutionResult(
        task_status='finished',
        content=f'{params.task_id}:{len(params.question)}',
        provider_id=params.provider_id,
        model=params.model,
    )

sandbox_task_main._execute_task_stream_local = execute
raise SystemExit(asyncio.run(sandbox_task_main._serve_loop()))
"""
    first = {**_payload(), "question": "x" * question_size}
    second = {**_payload(), "task_id": "task-2"}
    wire = "".join(json.dumps(payload) + "\n" for payload in (first, second))
    env = {
        **os.environ,
        "DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT": "10",
        "AGENT_MAX_BUFFER_SIZE_BYTES": str(configured_limit),
    }
    process = subprocess.run(
        [sys.executable, "-c", script],
        cwd=BACKEND_ROOT,
        env=env,
        input=wire,
        capture_output=True,
        text=True,
        timeout=30,
    )

    assert process.returncode == 0, process.stderr
    messages = [json.loads(line) for line in process.stdout.splitlines()]
    assert [message["type"] for message in messages] == [
        "record", "result", "record", "result",
    ]
    results = [message["result"] for message in messages if message["type"] == "result"]
    assert [result["task_status"] for result in results] == ["finished", "finished"]
    assert [result["content"] for result in results] == [
        f"task-1:{question_size}", "task-2:5",
    ]


def test_sandbox_task_main_uses_process_cwd_as_prepared_workspace(monkeypatch, tmp_path: Path, capsys, caplog):
    captured: dict[str, object] = {}

    async def fake_execute(params, *, emit, is_cancel_requested=None, prepared_workspace_dir=None):
        captured["task_id"] = params.task_id
        captured["prepared_workspace_dir"] = prepared_workspace_dir
        await emit({"record_type": "stream", "event_type": "message_start"})
        return TaskExecutionResult(
            task_status="finished",
            content="sandbox-task-ok",
            provider_id=params.provider_id,
            model=params.model,
            session_id="sdk-session-task",
        )

    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(sandbox_task_main, "_load_payload", _payload)
    monkeypatch.setattr(sandbox_task_main, "_execute_task_stream_local", fake_execute)
    caplog.set_level(logging.INFO, logger=sandbox_task_main.__name__)

    exit_code = asyncio.run(sandbox_task_main._main())

    assert exit_code == 0
    assert captured["task_id"] == "task-1"
    assert captured["prepared_workspace_dir"] == tmp_path
    lines = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert lines[0]["type"] == "record"
    assert lines[-1]["type"] == "result"
    assert lines[-1]["result"]["content"] == "sandbox-task-ok"
    assert "sandbox.task.start task_id=task-1" in caplog.text
    assert "sandbox.task.result task_id=task-1" in caplog.text
    assert "task_status=finished" in caplog.text
    assert "content_len=15" in caplog.text


def test_serve_loop_returns_clear_error_for_input_exceeding_configured_limit():
    process = subprocess.run(
        [sys.executable, "-c", "import asyncio, sandbox_task_main; raise SystemExit(asyncio.run(sandbox_task_main._serve_loop()))"],
        cwd=BACKEND_ROOT,
        env={**os.environ, "DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT": "10",
             "AGENT_MAX_BUFFER_SIZE_BYTES": str(1024 * 1024)},
        input=json.dumps({**_payload(), "question": "x" * (2 * 1024 * 1024)}) + "\n",
        capture_output=True, text=True, timeout=30,
    )
    assert process.returncode == 1
    messages = [json.loads(line) for line in process.stdout.splitlines()]
    assert len(messages) == 1
    assert messages[0]["type"] == "result"
    result = messages[0]["result"]
    assert result["task_status"] == "error"
    assert result["error"]["code"] == "sandbox_input_too_large"
    assert result["error"]["limit_bytes"] == 1024 * 1024
    assert "超过沙箱输入上限" in result["content"]
    assert "AGENT_MAX_BUFFER_SIZE_BYTES" in result["content"]
