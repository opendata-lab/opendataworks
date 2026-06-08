from __future__ import annotations

import asyncio
import inspect
import json
import logging
import os
import re
import shutil
import socket
import subprocess
from contextlib import asynccontextmanager
from dataclasses import asdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, AsyncIterator, Awaitable, Callable

from fastapi import FastAPI
from fastapi.responses import StreamingResponse

from config import get_settings
from core.agent_profile_service import normalize_agent_snapshot
from core.skill_admin_service import resolve_enabled_skill_runtime
from core.task_executor import TaskExecutionInput, TaskExecutionResult, _execute_task_stream_local
from core.topic_workspace import resolve_topic_workspace, sanitize_topic_id

logger = logging.getLogger(__name__)


SANDBOX_CONTAINER_LABEL = "dataagent.sandbox.managed_by"
SANDBOX_CONTAINER_LABEL_VALUE = "dataagent-sandbox-runner"
SANDBOX_TASK_ID_LABEL = "dataagent.sandbox.task_id"
SANDBOX_TOPIC_ID_LABEL = "dataagent.sandbox.topic_id"

CHILD_APP_ROOT = "/app"
CHILD_SKILLS_ROOT = "/app/.claude/skills"
BACKEND_CODE_ROOT = "/opt/dataagent-backend"

# Mount point where the runner image/compose exposes the live skills root. The
# runner can read this directly, and its host source is what each child task
# container must bind-mount so children see live (and offline-package) skills.
RUNNER_SKILLS_MOUNT_TARGET = "/app/.claude/skills"

# Host source of the runner's own skills mount, auto-discovered at startup so the
# child skill mounts work by default without setting DATAAGENT_SANDBOX_HOST_SKILLS_DIR.
_AUTO_HOST_SKILLS_DIR: str | None = None


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    await _cleanup_stale_sandbox_containers()
    global _AUTO_HOST_SKILLS_DIR
    _AUTO_HOST_SKILLS_DIR = await _discover_host_skills_dir()
    if _AUTO_HOST_SKILLS_DIR:
        logger.info("auto-discovered host skills dir for child mounts: %s", _AUTO_HOST_SKILLS_DIR)
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
    "SKILLS_ROOT_DIR",
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


async def _discover_host_skills_dir() -> str:
    """Resolve the host source of the runner's own ``/app/.claude/skills`` mount.

    The runner shares the host Docker socket, so it can inspect itself and read
    the host path backing its live skills bind. Child task containers then use
    that same host path as their skill bind-mount source, so they pick up
    live/offline-updated skills without operators having to set
    ``DATAAGENT_SANDBOX_HOST_SKILLS_DIR`` to a path that also happens to be
    visible inside the runner. Best-effort: any failure returns "".
    """
    if not _should_use_container_backend():
        return ""
    cfg = get_settings()
    backend = str(getattr(cfg, "dataagent_sandbox_backend", "") or "docker").strip().lower()
    self_id = socket.gethostname().strip()
    if not self_id:
        return ""
    template = (
        '{{range .Mounts}}{{if eq .Destination "'
        + RUNNER_SKILLS_MOUNT_TARGET
        + '"}}{{.Source}}{{end}}{{end}}'
    )
    try:
        proc = await asyncio.create_subprocess_exec(
            backend,
            "inspect",
            "--format",
            template,
            self_id,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
    except FileNotFoundError:
        logger.warning("sandbox backend command not found during skills discovery: %s", backend)
        return ""
    stdout, stderr = await proc.communicate()
    if proc.returncode != 0:
        logger.warning(
            "host skills auto-discovery failed backend=%s stderr=%s",
            backend,
            stderr.decode("utf-8", errors="replace").strip(),
        )
        return ""
    source = stdout.decode("utf-8", errors="replace").strip()
    if not source or not Path(source).is_absolute():
        return ""
    return source


def _resolve_host_skills_dir(cfg: Any) -> str:
    """Explicit override wins; otherwise use the auto-discovered runner mount."""
    explicit = str(getattr(cfg, "dataagent_sandbox_host_skills_dir", "") or "").strip()
    if explicit:
        return explicit
    return _AUTO_HOST_SKILLS_DIR or ""


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


def _sandbox_task_log_path(params: TaskExecutionInput) -> Path:
    cfg = get_settings()
    raw_root = str(getattr(cfg, "dataagent_sandbox_log_dir", "") or "/workspaces/.sandbox-logs").strip()
    root = Path(raw_root).expanduser()
    if not root.is_absolute():
        root = _host_sandbox_root() / root
    return root / sanitize_topic_id(params.topic_id) / f"{_safe_container_fragment(params.task_id)}.log"


def _append_task_log(log_path: Path | None, line: str) -> None:
    if log_path is None:
        return
    try:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(f"{timestamp} {str(line or '').rstrip()}\n")
    except Exception:
        logger.warning("failed to append sandbox task log path=%s", log_path, exc_info=True)


def _clip_log_text(value: Any, limit: int = 4000) -> str:
    text = str(value or "").replace("\r", "\\r").replace("\n", "\\n")
    if len(text) <= limit:
        return text
    return f"{text[:limit]}... [truncated {len(text) - limit} chars]"


def _build_child_env() -> dict[str, str]:
    child_env = {
        key: str(value)
        for key, value in os.environ.items()
        if key in _FORWARDED_ENV_KEYS or any(key.startswith(prefix) for prefix in _FORWARDED_ENV_PREFIXES)
    }
    child_env.update(
        {
            "PYTHONUNBUFFERED": "1",
            "HOME": CHILD_APP_ROOT,
            "PWD": CHILD_APP_ROOT,
            "DATAAGENT_WORKSPACE_DIR": CHILD_APP_ROOT,
            "DATAAGENT_WORKSPACE_PREPARED": "1",
            "DATAAGENT_SANDBOX_MODE": "",
            "DATAAGENT_SANDBOX_ROOT": CHILD_APP_ROOT,
            "SKILLS_ROOT_DIR": CHILD_SKILLS_ROOT,
        }
    )
    return child_env


def _dedupe_skill_folders(values: Any) -> list[str]:
    if values is None:
        return []
    if isinstance(values, str):
        values = [values]
    result: list[str] = []
    seen: set[str] = set()
    iterable = values if isinstance(values, (list, tuple, set)) else []
    for value in iterable:
        folder = str(value or "").strip()
        if not folder or folder in seen:
            continue
        result.append(folder)
        seen.add(folder)
    return result


def _enabled_skill_folders_for_task(params: TaskExecutionInput) -> list[str]:
    if params.agent_snapshot is not None:
        snapshot = normalize_agent_snapshot(params.agent_snapshot)
        return _dedupe_skill_folders(snapshot.get("skill_folders"))
    runtime = resolve_enabled_skill_runtime()
    return _dedupe_skill_folders(runtime.get("enabled_folders"))


def _validate_skill_folder_name(folder: str) -> str:
    value = str(folder or "").strip()
    if not value or Path(value).name != value or "/" in value or "\\" in value:
        raise RuntimeError(f"invalid enabled skill folder: {folder}")
    return value


def _build_skill_mounts(cfg: Any, enabled_folders: list[str]) -> list[tuple[str, str]]:
    if not enabled_folders:
        return []
    host_source_root = _resolve_host_skills_dir(cfg)
    if not host_source_root:
        raise RuntimeError(
            "DATAAGENT_SANDBOX_HOST_SKILLS_DIR is required when sandbox task enables skills "
            "and host skills auto-discovery is unavailable"
        )
    # host_root is the child bind-mount source, resolved by the host docker daemon.
    # It is generally NOT visible inside this runner container (the runner sees the
    # same skills at RUNNER_SKILLS_MOUNT_TARGET), so it must not be resolved against
    # the runner filesystem. Validate folder/SKILL.md existence against a
    # runner-visible root: the explicit host path if it happens to be visible
    # (same-path mount), otherwise the runner's own live skills mount.
    host_root = Path(host_source_root).expanduser()
    validation_root = host_root if host_root.is_dir() else Path(RUNNER_SKILLS_MOUNT_TARGET)
    if not validation_root.is_dir():
        raise RuntimeError(
            f"skills root not visible to runner for validation: host={host_root} "
            f"runner_mount={RUNNER_SKILLS_MOUNT_TARGET}"
        )
    validation_root = validation_root.resolve()

    mounts: list[tuple[str, str]] = []
    for raw_folder in enabled_folders:
        folder = _validate_skill_folder_name(raw_folder)
        check = (validation_root / folder).resolve()
        if not (check == validation_root or check.is_relative_to(validation_root)):
            raise RuntimeError(f"enabled skill folder escapes skills root: {folder}")
        if not check.is_dir():
            raise RuntimeError(f"enabled skill folder not found: {folder}")
        if not (check / "SKILL.md").is_file():
            raise RuntimeError(f"enabled skill missing SKILL.md: {folder}")
        mounts.append((folder, str(host_root / folder)))
    return mounts


def _prepare_child_skill_mount_targets(topic_workspace: Path, enabled_folders: list[str]) -> None:
    skills_dir = topic_workspace / ".claude" / "skills"
    skills_dir.mkdir(parents=True, exist_ok=True)
    enabled_set = set(enabled_folders)
    for existing in skills_dir.iterdir():
        if existing.name in enabled_set:
            continue
        if existing.is_symlink() or existing.is_file():
            existing.unlink()
        elif existing.is_dir():
            shutil.rmtree(existing)
    for folder in enabled_folders:
        target = skills_dir / folder
        if target.is_symlink() or target.is_file():
            target.unlink()
        elif target.exists() and target.is_dir():
            shutil.rmtree(target)
        target.mkdir(parents=True, exist_ok=True)


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
    enabled_folders = _enabled_skill_folders_for_task(params)
    skill_mounts = _build_skill_mounts(cfg, enabled_folders)
    _prepare_child_skill_mount_targets(topic_workspace, enabled_folders)
    _ensure_topic_workspace_owner(topic_workspace)
    container_name = (
        f"dataagent-task-{_safe_container_fragment(params.topic_id)[:32]}-"
        f"{_safe_container_fragment(params.task_id)[:32]}"
    )

    child_env = _build_child_env()

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
        CHILD_APP_ROOT,
        "--mount",
        f"type=bind,source={topic_workspace},target={CHILD_APP_ROOT}",
    ]
    # Runtime isolation hardening. The workspace bind-mount (and read-only skill
    # mounts) are the only host paths the child can touch; block privilege
    # escalation, and optionally lock the rest of the container filesystem
    # read-only so the agent's Bash/Python cannot persist anything outside the
    # bind-mounted workspace (true runtime write isolation, independent of the
    # static PreToolUse boundary hook). A writable tmpfs at /tmp covers transient
    # scratch; HOME/PWD already point at the writable workspace mount.
    command.extend(["--security-opt", "no-new-privileges"])
    if bool(getattr(cfg, "dataagent_sandbox_read_only_rootfs", False)):
        tmpfs_size = str(getattr(cfg, "dataagent_sandbox_tmpfs_size", "") or "512m").strip()
        command.append("--read-only")
        command.extend(["--tmpfs", f"/tmp:rw,nosuid,nodev,size={tmpfs_size}"])
    network = str(getattr(cfg, "dataagent_sandbox_network", "") or "").strip()
    if network:
        command.extend(["--network", network])
    uid = str(os.environ.get("DATAAGENT_RUNTIME_UID") or "").strip()
    gid = str(os.environ.get("DATAAGENT_RUNTIME_GID") or "").strip()
    if uid and gid:
        command.extend(["--user", f"{uid}:{gid}"])
    for folder, source in skill_mounts:
        command.extend(["--mount", f"type=bind,source={source},target={CHILD_SKILLS_ROOT}/{folder},readonly"])
    for key, value in sorted(child_env.items()):
        command.extend(["--env", f"{key}={value}"])
    command.extend([image, "python", f"{BACKEND_CODE_ROOT}/sandbox_task_main.py"])
    return backend, container_name, command


def _ensure_topic_workspace_owner(topic_workspace: Path) -> None:
    uid = str(os.environ.get("DATAAGENT_RUNTIME_UID") or "").strip()
    gid = str(os.environ.get("DATAAGENT_RUNTIME_GID") or "").strip()
    if not uid or not gid:
        return
    try:
        numeric_uid = int(uid)
        numeric_gid = int(gid)
        for path in (topic_workspace, topic_workspace / ".claude", topic_workspace / ".claude" / "skills"):
            if not path.exists():
                continue
            os.chown(path, numeric_uid, numeric_gid)
            os.chmod(path, 0o775)
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
    log_path = _sandbox_task_log_path(params)
    _append_task_log(
        log_path,
        (
            f"task_start task_id={params.task_id} topic_id={params.topic_id} "
            f"backend={backend} container={container_name}"
        ),
    )
    stream_limit = max(1024 * 1024, int(getattr(get_settings(), "agent_max_buffer_size_bytes", 0) or 0))
    process = await asyncio.create_subprocess_exec(
        *command,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        limit=stream_limit,
    )
    stderr_task: asyncio.Task[Any] | None = None
    cancel_task: asyncio.Task[Any] | None = None
    result: TaskExecutionResult | None = None
    returncode: int | None = None

    async def _emit(record: dict[str, Any]) -> None:
        emitted = emit(record)
        if inspect.isawaitable(emitted):
            await emitted

    try:
        await _send_payload_to_child(process, params)
        async with RUNNING_CONTAINERS_LOCK:
            RUNNING_CONTAINERS[params.task_id] = (backend, container_name)

        stderr_task = asyncio.create_task(_log_stderr(process.stderr, params.task_id, log_path=log_path))
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
                _append_task_log(log_path, f"stdout {_clip_log_text(text)}")
                continue
            message_type = str(message.get("type") or "")
            if message_type == "record":
                record = message.get("record") or {}
                if isinstance(record, dict):
                    await _emit(record)
                continue
            if message_type == "result":
                result_payload = message.get("result") or {}
                if isinstance(result_payload, dict):
                    result = _result_from_payload(result_payload, params)
                    _append_task_log(
                        log_path,
                        (
                            f"result task_status={result.task_status} "
                            f"error={_clip_log_text(result.error)} "
                            f"content={_clip_log_text(result.content, 1000)}"
                        ),
                    )
                continue
            _append_task_log(log_path, f"stdout_protocol_unknown type={message_type} payload={_clip_log_text(message)}")
        returncode = await process.wait()
        _append_task_log(log_path, f"returncode={returncode}")
    finally:
        if process.returncode is None:
            await _kill_container(backend, container_name)
            _append_task_log(log_path, "container_killed reason=runner_cleanup")
            try:
                await asyncio.wait_for(process.wait(), timeout=5)
            except asyncio.TimeoutError:
                logger.warning("sandbox process did not exit after kill task_id=%s", params.task_id)
                _append_task_log(log_path, "container_kill_timeout")
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
        _append_task_log(log_path, "result task_status=suspended error=task cancelled")
        return TaskExecutionResult(
            task_status="suspended",
            content="task cancelled",
            error={"code": "cancelled", "message": "task cancelled"},
            provider_id=params.provider_id,
            model=params.model,
        )
    error_message = f"sandbox container exited without a result: {returncode}"
    _append_task_log(log_path, f"result task_status=error error={error_message}")
    return TaskExecutionResult(
        task_status="error",
        content=error_message,
        error={"code": "sandbox_container_no_result", "message": error_message},
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


async def _log_stderr(stream: asyncio.StreamReader | None, task_id: str, *, log_path: Path | None = None) -> None:
    if stream is None:
        return
    async for raw in stream:
        text = raw.decode("utf-8", errors="replace").rstrip()
        if text:
            logger.info("sandbox child stderr task_id=%s %s", task_id, text)
            _append_task_log(log_path, f"stderr {_clip_log_text(text)}")


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
