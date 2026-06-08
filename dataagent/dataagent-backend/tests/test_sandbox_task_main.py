from __future__ import annotations

import asyncio
import json
import sys
from pathlib import Path

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


def test_sandbox_task_main_uses_process_cwd_as_prepared_workspace(monkeypatch, tmp_path: Path, capsys):
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

    exit_code = asyncio.run(sandbox_task_main._main())

    assert exit_code == 0
    assert captured["task_id"] == "task-1"
    assert captured["prepared_workspace_dir"] == tmp_path
    lines = [json.loads(line) for line in capsys.readouterr().out.splitlines()]
    assert lines[0]["type"] == "record"
    assert lines[-1]["type"] == "result"
    assert lines[-1]["result"]["content"] == "sandbox-task-ok"
