from __future__ import annotations

import asyncio
import inspect
import json
import logging
import os
import re
import subprocess
from contextlib import asynccontextmanager
from dataclasses import asdict
from pathlib import Path
from typing import Any, AsyncIterator, Awaitable, Callable

from fastapi import FastAPI
from fastapi.responses import StreamingResponse

from config import get_settings
from core.task_executor import TaskExecutionInput, TaskExecutionResult, _execute_task_stream_local
from core.topic_workspace import resolve_topic_workspace, sanitize_topic_id

logger = logging.getLogger(__name__)


SANDBOX_CONTAINER_LABEL = "dataagent.sandbox.managed_by"
SANDBOX_CONTAINER_LABEL_VALUE = "dataagent-sandbox-runner"
SANDBOX_TASK_ID_LABEL = "dataagent.sandbox.task_id"
SANDBOX_TOPIC_ID_LABEL = "dataagent.sandbox.topic_id"


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    await _cleanup_stale_sandbox_containers()
    yield


app = FastAPI(
    title="DataAgent Sandbox Runner",
    description="Internal DataAgent sandbox execution runner",
    version="1.2.0",
    lifespan=lifespan,
)

CANCELLED_TASK_IDS: set[str] = set()
RUNNING_CONTAINERS: dict[str, tuple[str, str]] = {}
RUNNING_CONTAINERS_LOCK = asyncio.Lock()

_FORWARDED_ENV_PREFIXES = (
    "AGENT_",
    "ANTHROPIC_",
    "ANYROUTER_",
    "CLAUDE_",
    "DATAAGENT_PORTAL_",
    "DORIS_",
    "MYSQL_",
    "ODW_",
    "OPENAI_",
    "OPENROUTER_",
    "SESSION_",
)
_FORWARDED_ENV_KEYS = {
    "PATH",
    "TZ",
    "PYTHONPATH",
    "DATAAGENT_RUNTIME_PROJECT_CWD",
    "DATAAGENT_SKILLS_OUTPUT_DIR",
}


@app.get("/")
async def root():
    return {"service": "dataagent-sandbox-runner", "version": "1.2.0"}


@app.post("/internal/sandbox/runs")
async def run_sandbox_task(payload: dict[str, Any]):
    params = TaskExecutionInput(**payload)

    async def stream():
        queue: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue()

        async def emit(record: dict[str, Any]) -> None:
            await queue.put({"type": "record", "record": record})

        async def is_cancel_requested() -> bool:
            return params.task_id in CANCELLED_TASK_IDS

        async def execute() -> None:
            try:
                if _should_use_container_backend():
                    result = await _execute_task_stream_container(
                        params,
                        emit=emit,
                        is_cancel_requested=is_cancel_requested,
                    )
                else:
                    result = await _execute_task_stream_local(
                        params,
                        emit=emit,
                        is_cancel_requested=is_cancel_requested,
                    )
            except Exception as exc:
                logger.exception("sandbox runner task crashed task_id=%s", params.task_id)
                result = TaskExecutionResult(
                    task_status="error",
                    content=str(exc),
                    error={"code": "sandbox_runner_error", "message": str(exc)},
                    provider_id=params.provider_id,
                    model=params.model,
                )
            finally:
                CANCELLED_TASK_IDS.discard(params.task_id)

            await queue.put({"type": "result", "result": asdict(result)})
            await queue.put(None)

        task = asyncio.create_task(execute(), name=f"sandbox-runner-{params.task_id}")
        try:
            while True:
                item = await queue.get()
                if item is None:
                    break
                yield json.dumps(item, ensure_ascii=False) + "\n"
            await task
        finally:
            if not task.done():
                task.cancel()

    return StreamingResponse(stream(), media_type="application/x-ndjson")


@app.post("/internal/sandbox/runs/{task_id}/cancel")
async def cancel_sandbox_task(task_id: str, payload: dict[str, Any] | None = None):
    normalized_task_id = str(task_id or "")
    CANCELLED_TASK_IDS.add(normalized_task_id)
    async with RUNNING_CONTAINERS_LOCK:
        running = RUNNING_CONTAINERS.get(normalized_task_id)
    if running:
        backend, container_name = running
        await _kill_container(backend, container_name)
    return {"accepted": True, "task_id": task_id}


def _should_use_container_backend() -> bool:
    cfg = get_settings()
    backend = str(getattr(cfg, "dataagent_sandbox_backend", "") or "").strip().lower()
    image = str(getattr(cfg, "dataagent_sandbox_image", "") or "").strip()
    return backend in {"docker", "podman"} and bool(image)


def _safe_container_fragment(value: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "-", str(value or "").strip()).strip(".-")
    return safe or "task"


def _host_sandbox_root() -> Path:
    cfg = get_settings()
    raw = str(getattr(cfg, "dataagent_sandbox_host_root", "") or "").strip()
    if not raw:
        raw = str(getattr(cfg, "dataagent_sandbox_root", "") or "").strip()
    if raw:
        return Path(raw).expanduser().resolve()
    return resolve_topic_workspace("_placeholder_").parent


def _topic_host_workspace(topic_id: str) -> Path:
    return _host_sandbox_root() / sanitize_topic_id(topic_id)


def _build_child_env(*, skill_link_root: str) -> dict[str, str]:
    child_env = {
        key: str(value)
        for key, value in os.environ.items()
        if key in _FORWARDED_ENV_KEYS or any(key.startswith(prefix) for prefix in _FORWARDED_ENV_PREFIXES)
    }
    child_env.update(
        {
            "PYTHONUNBUFFERED": "1",
            "HOME": "/workspace",
            "PWD": "/workspace",
            "DATAAGENT_WORKSPACE_DIR": "/workspace",
            "DATAAGENT_WORKSPACE_PREPARED": "1",
            "DATAAGENT_SKILL_LINK_ROOT": skill_link_root,
            "DATAAGENT_SANDBOX_MODE": "",
            "DATAAGENT_SANDBOX_ROOT": "/workspace",
        }
    )
    return child_env


def _build_container_command(params: TaskExecutionInput) -> tuple[str, str, list[str]]:
    cfg = get_settings()
    backend = str(getattr(cfg, "dataagent_sandbox_backend", "") or "docker").strip().lower()
    image = str(getattr(cfg, "dataagent_sandbox_image", "") or "").strip()
    if backend not in {"docker", "podman"}:
        raise RuntimeError(f"unsupported sandbox backend: {backend}")
    if not image:
        raise RuntimeError("DATAAGENT_SANDBOX_IMAGE is required for container sandbox backend")

    topic_workspace = _topic_host_workspace(params.topic_id)
    topic_workspace.mkdir(parents=True, exist_ok=True)
    _ensure_topic_workspace_owner(topic_workspace)
    container_name = (
        f"dataagent-task-{_safe_container_fragment(params.topic_id)[:32]}-"
        f"{_safe_container_fragment(params.task_id)[:32]}"
    )

    host_skills_dir = str(getattr(cfg, "dataagent_sandbox_host_skills_dir", "") or "").strip()
    skill_link_root = "/skills" if host_skills_dir else "/app/.claude/skills"
    child_env = _build_child_env(skill_link_root=skill_link_root)

    command = [
        backend,
        "run",
        "--rm",
        "--interactive",
        "--name",
        container_name,
        "--label",
        f"{SANDBOX_CONTAINER_LABEL}={SANDBOX_CONTAINER_LABEL_VALUE}",
        "--label",
        f"{SANDBOX_TASK_ID_LABEL}={_safe_container_fragment(params.task_id)}",
        "--label",
        f"{SANDBOX_TOPIC_ID_LABEL}={sanitize_topic_id(params.topic_id)}",
        "--workdir",
        "/workspace",
        "--mount",
        f"type=bind,source={topic_workspace},target=/workspace",
    ]
    network = str(getattr(cfg, "dataagent_sandbox_network", "") or "").strip()
    if network:
        command.extend(["--network", network])
    uid = str(os.environ.get("DATAAGENT_RUNTIME_UID") or "").strip()
    gid = str(os.environ.get("DATAAGENT_RUNTIME_GID") or "").strip()
    if uid and gid:
        command.extend(["--user", f"{uid}:{gid}"])
    if host_skills_dir:
        command.extend(
            [
                "--mount",
                f"type=bind,source={Path(host_skills_dir).expanduser().resolve()},target=/skills,readonly",
            ]
        )
    for key, value in sorted(child_env.items()):
        command.extend(["--env", f"{key}={value}"])
    command.extend([image, "python", "/app/dataagent-backend/sandbox_task_main.py"])
    return backend, container_name, command


def _ensure_topic_workspace_owner(topic_workspace: Path) -> None:
    uid = str(os.environ.get("DATAAGENT_RUNTIME_UID") or "").strip()
    gid = str(os.environ.get("DATAAGENT_RUNTIME_GID") or "").strip()
    if not uid or not gid:
        return
    try:
        os.chown(topic_workspace, int(uid), int(gid))
        os.chmod(topic_workspace, 0o775)
    except PermissionError:
        logger.warning("cannot chown topic workspace path=%s uid=%s gid=%s", topic_workspace, uid, gid)
    except ValueError:
        logger.warning("invalid DATAAGENT_RUNTIME_UID/GID uid=%s gid=%s", uid, gid)


async def _execute_task_stream_container(
    params: TaskExecutionInput,
    *,
    emit: Callable[[dict[str, Any]], Awaitable[None]],
    is_cancel_requested: Callable[[], Awaitable[bool] | bool] | None = None,
) -> TaskExecutionResult:
    backend, container_name, command = _build_container_command(params)
    process = await asyncio.create_subprocess_exec(
        *command,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
    )
    stderr_task: asyncio.Task[Any] | None = None
    cancel_task: asyncio.Task[Any] | None = None
    result: TaskExecutionResult | None = None
    returncode: int | None = None
    try:
        await _send_payload_to_child(process, params)
        async with RUNNING_CONTAINERS_LOCK:
            RUNNING_CONTAINERS[params.task_id] = (backend, container_name)

        stderr_task = asyncio.create_task(_log_stderr(process.stderr, params.task_id))
        cancel_task = asyncio.create_task(_watch_cancel(params.task_id, backend, container_name, process, is_cancel_requested))
        assert process.stdout is not None
        async for raw in process.stdout:
            text = raw.decode("utf-8", errors="replace").strip()
            if not text:
                continue
            try:
                message = json.loads(text)
            except json.JSONDecodeError:
                logger.info("sandbox child stdout task_id=%s %s", params.task_id, text)
                continue
            message_type = str(message.get("type") or "")
            if message_type == "record":
                record = message.get("record") or {}
                if isinstance(record, dict):
                    await emit(record)
                continue
            if message_type == "result":
                result_payload = message.get("result") or {}
                if isinstance(result_payload, dict):
                    result = _result_from_payload(result_payload, params)
        returncode = await process.wait()
    finally:
        if process.returncode is None:
            await _kill_container(backend, container_name)
            try:
                await asyncio.wait_for(process.wait(), timeout=5)
            except asyncio.TimeoutError:
                logger.warning("sandbox process did not exit after kill task_id=%s", params.task_id)
        if cancel_task is not None:
            cancel_task.cancel()
            await _await_cancelled(cancel_task)
        if stderr_task is not None:
            await _await_cancelled(stderr_task)
        async with RUNNING_CONTAINERS_LOCK:
            RUNNING_CONTAINERS.pop(params.task_id, None)

    if result is not None:
        return result
    if params.task_id in CANCELLED_TASK_IDS:
        return TaskExecutionResult(
            task_status="suspended",
            content="task cancelled",
            error={"code": "cancelled", "message": "task cancelled"},
            provider_id=params.provider_id,
            model=params.model,
        )
    return TaskExecutionResult(
        task_status="error",
        content=f"sandbox container exited without a result: {returncode}",
        error={"code": "sandbox_container_no_result", "message": f"sandbox container exited without a result: {returncode}"},
        provider_id=params.provider_id,
        model=params.model,
    )


async def _send_payload_to_child(process: asyncio.subprocess.Process, params: TaskExecutionInput) -> None:
    if process.stdin is None:
        raise RuntimeError("sandbox container stdin is not available")
    process.stdin.write(json.dumps(asdict(params), ensure_ascii=False).encode("utf-8"))
    await process.stdin.drain()
    process.stdin.close()


async def _watch_cancel(
    task_id: str,
    backend: str,
    container_name: str,
    process: asyncio.subprocess.Process,
    is_cancel_requested: Callable[[], Awaitable[bool] | bool] | None,
) -> None:
    while process.returncode is None:
        if await _is_cancelled(task_id, is_cancel_requested):
            await _kill_container(backend, container_name)
            return
        await asyncio.sleep(0.25)


async def _is_cancelled(
    task_id: str,
    is_cancel_requested: Callable[[], Awaitable[bool] | bool] | None,
) -> bool:
    if task_id in CANCELLED_TASK_IDS:
        return True
    if is_cancel_requested is None:
        return False
    result = is_cancel_requested()
    if inspect.isawaitable(result):
        return bool(await result)
    return bool(result)


async def _kill_container(backend: str, container_name: str) -> None:
    try:
        process = await asyncio.create_subprocess_exec(
            backend,
            "kill",
            container_name,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        await process.wait()
    except FileNotFoundError:
        logger.warning("sandbox backend command not found: %s", backend)
    except Exception:
        logger.exception("failed to kill sandbox container name=%s", container_name)


async def _cleanup_stale_sandbox_containers() -> None:
    if not _should_use_container_backend():
        return
    cfg = get_settings()
    backend = str(getattr(cfg, "dataagent_sandbox_backend", "") or "docker").strip().lower()
    label_filter = f"label={SANDBOX_CONTAINER_LABEL}={SANDBOX_CONTAINER_LABEL_VALUE}"
    try:
        ps_process = await asyncio.create_subprocess_exec(
            backend,
            "ps",
            "-aq",
            "--filter",
            label_filter,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError:
        logger.warning("sandbox backend command not found during startup cleanup: %s", backend)
        return

    stdout, stderr = await ps_process.communicate()
    if ps_process.returncode != 0:
        logger.warning(
            "sandbox startup cleanup list failed backend=%s stderr=%s",
            backend,
            stderr.decode("utf-8", errors="replace").strip(),
        )
        return

    container_ids = [line.strip() for line in stdout.decode("utf-8", errors="replace").splitlines() if line.strip()]
    if not container_ids:
        return

    rm_process = await asyncio.create_subprocess_exec(
        backend,
        "rm",
        "-f",
        *container_ids,
        stdout=asyncio.subprocess.DEVNULL,
        stderr=asyncio.subprocess.PIPE,
    )
    _, rm_stderr = await rm_process.communicate()
    if rm_process.returncode == 0:
        logger.info("removed stale sandbox task containers count=%s", len(container_ids))
        return
    logger.warning(
        "sandbox startup cleanup remove failed backend=%s stderr=%s",
        backend,
        rm_stderr.decode("utf-8", errors="replace").strip(),
    )


async def _log_stderr(stream: asyncio.StreamReader | None, task_id: str) -> None:
    if stream is None:
        return
    async for raw in stream:
        text = raw.decode("utf-8", errors="replace").rstrip()
        if text:
            logger.info("sandbox child stderr task_id=%s %s", task_id, text)


async def _await_cancelled(task: asyncio.Task[Any]) -> None:
    try:
        await task
    except asyncio.CancelledError:
        pass


def _result_from_payload(payload: dict[str, Any], params: TaskExecutionInput) -> TaskExecutionResult:
    return TaskExecutionResult(
        task_status=str(payload.get("task_status") or "error"),
        content=str(payload.get("content") or ""),
        usage=payload.get("usage") if isinstance(payload.get("usage"), dict) else None,
        error=payload.get("error") if isinstance(payload.get("error"), dict) else None,
        provider_id=str(payload.get("provider_id") or params.provider_id),
        model=str(payload.get("model") or params.model),
        session_id=str(payload.get("session_id") or ""),
    )
