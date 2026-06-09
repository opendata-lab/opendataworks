from __future__ import annotations

import asyncio
import hashlib
import inspect
import json
import logging
import os
import re
import shutil
import socket
import subprocess
import time
from contextlib import asynccontextmanager
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, AsyncIterator, Awaitable, Callable

from fastapi import FastAPI
from fastapi.responses import StreamingResponse

from config import get_settings
from core.agent_profile_service import normalize_agent_snapshot
from core.skill_admin_service import resolve_enabled_skill_runtime
from core.task_executor import TaskExecutionInput, TaskExecutionResult, _execute_task_stream_local
from core.topic_workspace import LOGS_DIRNAME, sanitize_topic_id

logger = logging.getLogger(__name__)


SANDBOX_CONTAINER_LABEL = "dataagent.sandbox.managed_by"
SANDBOX_CONTAINER_LABEL_VALUE = "dataagent-sandbox-runner"
SANDBOX_TASK_ID_LABEL = "dataagent.sandbox.task_id"
SANDBOX_TOPIC_ID_LABEL = "dataagent.sandbox.topic_id"

CHILD_WORKSPACE_ROOT = "/mnt/workspace"
CHILD_CLAUDE_HOME = "/mnt/home"
CHILD_SKILLS_ROOT = f"{CHILD_WORKSPACE_ROOT}/.claude/skills"
BACKEND_CODE_ROOT = "/opt/dataagent-backend"

# Mount point where the runner image/compose exposes the live skills root. The
# runner can read this directly, and its host source is what each child task
# container must bind-mount so children see live (and offline-package) skills.
RUNNER_SKILLS_MOUNT_TARGET = "/app/.claude/skills"

# Host source of the runner's own skills mount, auto-discovered at startup so
# child skill mounts are derived from the single DATAAGENT_SKILLS_DIR bind.
_AUTO_HOST_SKILLS_DIR: str | None = None


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncIterator[None]:
    await _cleanup_stale_sandbox_containers()
    global _AUTO_HOST_SKILLS_DIR
    _AUTO_HOST_SKILLS_DIR = await _discover_host_skills_dir()
    if _AUTO_HOST_SKILLS_DIR:
        logger.info("auto-discovered host skills dir for child mounts: %s", _AUTO_HOST_SKILLS_DIR)
    reaper_task: asyncio.Task[Any] | None = None
    if _should_reuse_containers():
        reaper_task = asyncio.create_task(_warm_pool_reaper(), name="sandbox-warm-pool-reaper")
        logger.info(
            "warm child container reuse enabled idle_ttl=%ss max=%s reaper_interval=%ss",
            _warm_idle_ttl(),
            _warm_max_containers(),
            _warm_reaper_interval(),
        )
    try:
        yield
    finally:
        if reaper_task is not None:
            reaper_task.cancel()
            await _await_cancelled(reaper_task)
        await _shutdown_warm_pool()


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
                if _should_reuse_containers():
                    result = await _execute_task_stream_warm(
                        params,
                        emit=emit,
                        is_cancel_requested=is_cancel_requested,
                    )
                elif _should_use_container_backend():
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


def _should_reuse_containers() -> bool:
    return _should_use_container_backend() and bool(getattr(get_settings(), "dataagent_sandbox_reuse_enabled", False))


def _warm_idle_ttl() -> int:
    return max(1, int(getattr(get_settings(), "dataagent_sandbox_idle_ttl_seconds", 600) or 600))


def _warm_max_containers() -> int:
    return max(1, int(getattr(get_settings(), "dataagent_sandbox_max_warm_containers", 32) or 32))


def _warm_reaper_interval() -> int:
    return max(1, int(getattr(get_settings(), "dataagent_sandbox_reaper_interval_seconds", 30) or 30))


async def _discover_host_skills_dir() -> str:
    """Resolve the host source of the runner's own ``/app/.claude/skills`` mount.

    The runner shares the host Docker socket, so it can inspect itself and read
    the host path backing its live skills bind. Child task containers then use
    that same host path as their skill bind-mount source, so they pick up
    live/offline-updated skills without operators having to configure a second
    host skills variable. Best-effort: any failure returns "".
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
    """Host source of DATAAGENT_SKILLS_DIR, auto-discovered from runner mount."""
    return _AUTO_HOST_SKILLS_DIR or ""


def _safe_container_fragment(value: str) -> str:
    safe = re.sub(r"[^A-Za-z0-9_.-]+", "-", str(value or "").strip()).strip(".-")
    return safe or "task"


def _host_sandbox_root() -> Path:
    cfg = get_settings()
    raw = str(getattr(cfg, "dataagent_host_root", "") or "").strip()
    return Path(raw or "/dataagent_runtime").expanduser().resolve()


def _topic_host_workspace(topic_id: str) -> Path:
    # Agent cwd bind source: <sandbox_root>/<topic>/workspace.
    return _host_sandbox_root() / sanitize_topic_id(topic_id) / "workspace"


def _topic_host_home(topic_id: str) -> Path:
    """Per-topic persisted Claude HOME on the host.

    Claude stores resume session transcripts under ``$HOME/.claude/projects``.
    A child-local tmpfs HOME would drop them when the container is recreated
    (idle TTL eviction, reuse disabled, restart), so follow-ups in the same
    conversation fail with "session not found". Persisting HOME per topic keeps
    resume working across the child container lifecycle.

    It is a sibling of ``workspace`` under the topic root
    (``<sandbox_root>/<topic>/home``), so it sits next to the topic's other
    files (easy to find, cleaned up with the topic) but is NOT inside the agent
    workspace bind. The child mounts it at the distinct path ``/mnt/home`` (not
    ``/mnt/workspace``), so HOME never equals cwd, project skill registration is
    unaffected, and the agent never sees session data in its workspace.
    """
    return _host_sandbox_root() / sanitize_topic_id(topic_id) / "home"


def _topic_host_logs(topic_id: str) -> Path:
    # Per-task sandbox logs live under <sandbox_root>/<topic>/logs, a sibling of
    # workspace/ and home/. Host-side only (never bind-mounted into the child).
    return _host_sandbox_root() / sanitize_topic_id(topic_id) / LOGS_DIRNAME


def _sandbox_task_log_path(params: TaskExecutionInput) -> Path:
    return _topic_host_logs(params.topic_id) / f"{_safe_container_fragment(params.task_id)}.log"


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
            "HOME": CHILD_CLAUDE_HOME,
            "DATAAGENT_SANDBOX_MODE": "",
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
            "host skills auto-discovery is unavailable when sandbox task enables skills; "
            "check DATAAGENT_SKILLS_DIR volume configuration and Docker/Podman inspect access"
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


def _build_container_command(
    params: TaskExecutionInput,
    *,
    container_name: str | None = None,
    task_id_label: str | None = None,
    extra_env: dict[str, str] | None = None,
) -> tuple[str, str, list[str]]:
    cfg = get_settings()
    backend = str(getattr(cfg, "dataagent_sandbox_backend", "") or "docker").strip().lower()
    image = str(getattr(cfg, "dataagent_sandbox_image", "") or "").strip()
    if backend not in {"docker", "podman"}:
        raise RuntimeError(f"unsupported sandbox backend: {backend}")
    if not image:
        raise RuntimeError("DATAAGENT_SANDBOX_IMAGE is required for container sandbox backend")

    topic_workspace = _topic_host_workspace(params.topic_id)
    topic_workspace.mkdir(parents=True, exist_ok=True)
    topic_home = _topic_host_home(params.topic_id)
    topic_home.mkdir(parents=True, exist_ok=True)
    enabled_folders = _enabled_skill_folders_for_task(params)
    skill_mounts = _build_skill_mounts(cfg, enabled_folders)
    _prepare_child_skill_mount_targets(topic_workspace, enabled_folders)
    _ensure_topic_workspace_owner(topic_workspace)
    _ensure_host_path_owner(topic_home)
    container_name = container_name or (
        f"dataagent-task-{_safe_container_fragment(params.topic_id)[:32]}-"
        f"{_safe_container_fragment(params.task_id)[:32]}"
    )
    task_label_value = task_id_label if task_id_label is not None else _safe_container_fragment(params.task_id)

    child_env = _build_child_env()
    if extra_env:
        child_env.update({str(key): str(value) for key, value in extra_env.items()})

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
        f"{SANDBOX_TASK_ID_LABEL}={task_label_value}",
        "--label",
        f"{SANDBOX_TOPIC_ID_LABEL}={sanitize_topic_id(params.topic_id)}",
        "--workdir",
        CHILD_WORKSPACE_ROOT,
        "--mount",
        f"type=bind,source={topic_workspace},target={CHILD_WORKSPACE_ROOT}",
        "--mount",
        f"type=bind,source={topic_home},target={CHILD_CLAUDE_HOME}",
    ]
    # Runtime isolation hardening. The workspace bind-mount, the per-topic HOME
    # bind-mount, and read-only skill mounts are the only host paths the child can
    # touch; block privilege escalation, and optionally lock the rest of the
    # container filesystem read-only so the agent's Bash/Python cannot persist
    # anything outside those binds (true runtime write isolation, independent of
    # the static PreToolUse boundary hook). Claude HOME is a per-topic persisted
    # bind-mount of <topic>/home (sibling of the workspace, not inside it),
    # mounted at the distinct path /mnt/home, so resume session transcripts under
    # $HOME/.claude/projects survive child container recreation while HOME never
    # equals cwd and the agent never sees session data in its workspace.
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


def _ensure_host_path_owner(path: Path) -> None:
    uid = str(os.environ.get("DATAAGENT_RUNTIME_UID") or "").strip()
    gid = str(os.environ.get("DATAAGENT_RUNTIME_GID") or "").strip()
    if not uid or not gid or not path.exists():
        return
    try:
        os.chown(path, int(uid), int(gid))
        os.chmod(path, 0o775)
    except PermissionError:
        logger.warning("cannot chown host path=%s uid=%s gid=%s", path, uid, gid)
    except ValueError:
        logger.warning("invalid DATAAGENT_RUNTIME_UID/GID uid=%s gid=%s", uid, gid)


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


# ---------------------------------------------------------------------------
# Warm child container pool
#
# When reuse is enabled, a child container becomes a long-lived serve loop and
# stays alive for an idle TTL after a task finishes. Same-conversation follow-ups
# whose container spec signature matches an idle warm child reuse it instead of
# paying full container/SDK cold-start each turn.
# ---------------------------------------------------------------------------


@dataclass
class WarmChild:
    backend: str
    container_name: str
    signature: str
    topic_id: str
    process: asyncio.subprocess.Process
    busy: bool = False
    healthy: bool = True
    last_used: float = field(default_factory=time.monotonic)
    current_task_id: str | None = None
    current_log_path: Path | None = None
    stderr_task: asyncio.Task[Any] | None = None


WARM_POOL: dict[str, WarmChild] = {}
WARM_POOL_LOCK = asyncio.Lock()
WARM_POOL_CONDITION = asyncio.Condition(WARM_POOL_LOCK)
WARM_POOL_CONDITION_LOOP: asyncio.AbstractEventLoop | None = None


def _warm_pool_condition() -> asyncio.Condition:
    global WARM_POOL_LOCK, WARM_POOL_CONDITION, WARM_POOL_CONDITION_LOOP
    loop = asyncio.get_running_loop()
    if WARM_POOL_CONDITION_LOOP is not loop:
        WARM_POOL_LOCK = asyncio.Lock()
        WARM_POOL_CONDITION = asyncio.Condition(WARM_POOL_LOCK)
        WARM_POOL_CONDITION_LOOP = loop
    return WARM_POOL_CONDITION


def _container_spec_signature(params: TaskExecutionInput) -> str:
    """Stable signature over the inputs that are fixed at ``docker run`` time.

    Per-task fields (provider, model, question, history) travel in the payload
    and are resolved inside the child per run, so they do not constrain reuse.
    """
    cfg = get_settings()
    backend = str(getattr(cfg, "dataagent_sandbox_backend", "") or "docker").strip().lower()
    image = str(getattr(cfg, "dataagent_sandbox_image", "") or "").strip()
    folders = sorted(_enabled_skill_folders_for_task(params))
    parts = [
        backend,
        image,
        str(_topic_host_workspace(params.topic_id)),
        "ro" if bool(getattr(cfg, "dataagent_sandbox_read_only_rootfs", False)) else "rw",
        str(getattr(cfg, "dataagent_sandbox_tmpfs_size", "") or ""),
        str(getattr(cfg, "dataagent_sandbox_network", "") or ""),
        str(os.environ.get("DATAAGENT_RUNTIME_UID") or ""),
        str(os.environ.get("DATAAGENT_RUNTIME_GID") or ""),
        *folders,
    ]
    digest = hashlib.sha256("\x1f".join(parts).encode("utf-8")).hexdigest()
    return digest[:16]


def _warm_container_name(topic_id: str, signature: str) -> str:
    return f"dataagent-warm-{_safe_container_fragment(topic_id)[:24]}-{signature[:12]}"


def _warm_child_alive(child: WarmChild) -> bool:
    return child.process.returncode is None


async def _execute_task_stream_warm(
    params: TaskExecutionInput,
    *,
    emit: Callable[[dict[str, Any]], Awaitable[None]],
    is_cancel_requested: Callable[[], Awaitable[bool] | bool] | None = None,
) -> TaskExecutionResult:
    child, reused = await _acquire_warm_child(params)
    try:
        return await _run_on_warm_child(
            child,
            params,
            reused=reused,
            emit=emit,
            is_cancel_requested=is_cancel_requested,
        )
    finally:
        await _release_warm_child(child, params.task_id)


async def _acquire_warm_child(params: TaskExecutionInput) -> tuple[WarmChild, bool]:
    signature = _container_spec_signature(params)
    condition = _warm_pool_condition()
    async with condition:
        while True:
            await _drop_dead_warm_children_locked()
            for child in WARM_POOL.values():
                if (
                    child.signature == signature
                    and child.topic_id == params.topic_id
                    and not child.busy
                    and _warm_child_alive(child)
                ):
                    child.busy = True
                    child.last_used = time.monotonic()
                    child.current_task_id = params.task_id
                    RUNNING_CONTAINERS[params.task_id] = (child.backend, child.container_name)
                    return child, True
            if any(
                child.topic_id == params.topic_id
                and child.busy
                and _warm_child_alive(child)
                for child in WARM_POOL.values()
            ):
                await condition.wait()
                continue
            await _evict_idle_same_topic_mismatch_locked(params.topic_id, signature)
            await _evict_idle_over_cap_locked()
            if len(WARM_POOL) < _warm_max_containers():
                child = await _start_warm_child(params, signature)
                child.busy = True
                child.current_task_id = params.task_id
                WARM_POOL[child.container_name] = child
                RUNNING_CONTAINERS[params.task_id] = (child.backend, child.container_name)
                return child, False
            await condition.wait()


async def _start_warm_child(params: TaskExecutionInput, signature: str) -> WarmChild:
    idle_ttl = _warm_idle_ttl()
    container_name = _warm_container_name(params.topic_id, signature)
    extra_env = {"DATAAGENT_SANDBOX_CHILD_IDLE_TIMEOUT": str(idle_ttl + 60)}
    backend, container_name, command = _build_container_command(
        params,
        container_name=container_name,
        task_id_label="warm",
        extra_env=extra_env,
    )
    stream_limit = max(1024 * 1024, int(getattr(get_settings(), "agent_max_buffer_size_bytes", 0) or 0))
    process = await asyncio.create_subprocess_exec(
        *command,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        limit=stream_limit,
    )
    child = WarmChild(
        backend=backend,
        container_name=container_name,
        signature=signature,
        topic_id=params.topic_id,
        process=process,
    )
    child.stderr_task = asyncio.create_task(_drain_warm_stderr(child))
    logger.info(
        "warm child started topic_id=%s container=%s signature=%s",
        params.topic_id,
        container_name,
        signature,
    )
    return child


async def _run_on_warm_child(
    child: WarmChild,
    params: TaskExecutionInput,
    *,
    reused: bool,
    emit: Callable[[dict[str, Any]], Awaitable[None]],
    is_cancel_requested: Callable[[], Awaitable[bool] | bool] | None = None,
) -> TaskExecutionResult:
    log_path = _sandbox_task_log_path(params)
    child.current_log_path = log_path
    _append_task_log(
        log_path,
        (
            f"task_start task_id={params.task_id} topic_id={params.topic_id} "
            f"backend={child.backend} container={child.container_name} "
            f"reused={reused} signature={child.signature}"
        ),
    )

    process = child.process
    if process.stdin is None or process.stdout is None:
        raise RuntimeError("warm child stdin/stdout is not available")

    async def _emit(record: dict[str, Any]) -> None:
        emitted = emit(record)
        if inspect.isawaitable(emitted):
            await emitted

    result: TaskExecutionResult | None = None
    cancel_task = asyncio.create_task(
        _watch_cancel_warm(child, params.task_id, is_cancel_requested)
    )
    try:
        process.stdin.write((json.dumps(asdict(params), ensure_ascii=False) + "\n").encode("utf-8"))
        await process.stdin.drain()
        async for raw in process.stdout:
            text = raw.decode("utf-8", errors="replace").strip()
            if not text:
                continue
            try:
                message = json.loads(text)
            except json.JSONDecodeError:
                logger.info("warm child stdout task_id=%s %s", params.task_id, text)
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
                break
            _append_task_log(log_path, f"stdout_protocol_unknown type={message_type} payload={_clip_log_text(message)}")
    except (BrokenPipeError, ConnectionResetError) as exc:
        _append_task_log(log_path, f"warm_child_pipe_error {exc}")
    finally:
        cancel_task.cancel()
        await _await_cancelled(cancel_task)
        child.current_log_path = None

    if result is not None:
        return result
    # No result line: the child stream ended (killed/cancelled/crashed). The
    # child can no longer be trusted for reuse, so mark it for removal on release.
    child.healthy = False
    if params.task_id in CANCELLED_TASK_IDS:
        _append_task_log(log_path, "result task_status=suspended error=task cancelled")
        return TaskExecutionResult(
            task_status="suspended",
            content="task cancelled",
            error={"code": "cancelled", "message": "task cancelled"},
            provider_id=params.provider_id,
            model=params.model,
        )
    error_message = "warm sandbox container exited without a result"
    _append_task_log(log_path, f"result task_status=error error={error_message}")
    return TaskExecutionResult(
        task_status="error",
        content=error_message,
        error={"code": "sandbox_container_no_result", "message": error_message},
        provider_id=params.provider_id,
        model=params.model,
    )


async def _watch_cancel_warm(
    child: WarmChild,
    task_id: str,
    is_cancel_requested: Callable[[], Awaitable[bool] | bool] | None,
) -> None:
    while _warm_child_alive(child):
        if await _is_cancelled(task_id, is_cancel_requested):
            await _kill_container(child.backend, child.container_name)
            return
        await asyncio.sleep(0.25)


async def _release_warm_child(child: WarmChild, task_id: str) -> None:
    dead: WarmChild | None = None
    condition = _warm_pool_condition()
    async with condition:
        RUNNING_CONTAINERS.pop(task_id, None)
        CANCELLED_TASK_IDS.discard(task_id)
        child.busy = False
        child.current_task_id = None
        child.last_used = time.monotonic()
        if not child.healthy or not _warm_child_alive(child):
            WARM_POOL.pop(child.container_name, None)
            dead = child
        condition.notify_all()
    if dead is not None:
        await _close_warm_child(dead)


async def _evict_idle_over_cap_locked() -> None:
    """Drop idle LRU warm children until under the cap. Caller holds the lock."""
    max_n = _warm_max_containers()
    while len(WARM_POOL) >= max_n:
        idle = [child for child in WARM_POOL.values() if not child.busy]
        if not idle:
            return
        victim = min(idle, key=lambda child: child.last_used)
        WARM_POOL.pop(victim.container_name, None)
        await _kill_container(victim.backend, victim.container_name)
        await _close_warm_child(victim)


async def _evict_idle_same_topic_mismatch_locked(topic_id: str, signature: str) -> None:
    """Keep at most one idle warm child per topic when the container spec changes."""
    victims = [
        child
        for child in WARM_POOL.values()
        if child.topic_id == topic_id
        and child.signature != signature
        and not child.busy
    ]
    for victim in victims:
        WARM_POOL.pop(victim.container_name, None)
        await _kill_container(victim.backend, victim.container_name)
        await _close_warm_child(victim)
    if victims:
        _warm_pool_condition().notify_all()


async def _drop_dead_warm_children_locked() -> None:
    dead = [child for child in WARM_POOL.values() if not _warm_child_alive(child)]
    for child in dead:
        WARM_POOL.pop(child.container_name, None)
        if child.current_task_id is not None:
            RUNNING_CONTAINERS.pop(child.current_task_id, None)
        await _close_warm_child(child)
    if dead:
        _warm_pool_condition().notify_all()


async def _close_warm_child(child: WarmChild) -> None:
    if child.stderr_task is not None:
        child.stderr_task.cancel()
        await _await_cancelled(child.stderr_task)
    process = child.process
    try:
        if process.stdin is not None and not process.stdin.is_closing():
            process.stdin.close()
    except Exception:
        pass
    if process.returncode is None:
        try:
            await asyncio.wait_for(process.wait(), timeout=5)
        except asyncio.TimeoutError:
            logger.warning("warm child did not exit after close container=%s", child.container_name)


async def _drain_warm_stderr(child: WarmChild) -> None:
    stream = child.process.stderr
    if stream is None:
        return
    async for raw in stream:
        text = raw.decode("utf-8", errors="replace").rstrip()
        if not text:
            continue
        logger.info("warm child stderr container=%s %s", child.container_name, text)
        _append_task_log(child.current_log_path, f"stderr {_clip_log_text(text)}")


async def _warm_pool_reaper() -> None:
    while True:
        await asyncio.sleep(_warm_reaper_interval())
        idle_ttl = _warm_idle_ttl()
        now = time.monotonic()
        expired: list[tuple[WarmChild, bool]] = []
        condition = _warm_pool_condition()
        async with condition:
            for name, child in list(WARM_POOL.items()):
                dead = not _warm_child_alive(child)
                idle_expired = (not child.busy) and (now - child.last_used > idle_ttl)
                if dead or idle_expired:
                    WARM_POOL.pop(name, None)
                    expired.append((child, dead))
            if expired:
                condition.notify_all()
        for child, dead in expired:
            if not dead:
                await _kill_container(child.backend, child.container_name)
                logger.info("reaped idle warm child container=%s", child.container_name)
            await _close_warm_child(child)


async def _shutdown_warm_pool() -> None:
    condition = _warm_pool_condition()
    async with condition:
        children = list(WARM_POOL.values())
        WARM_POOL.clear()
        condition.notify_all()
    for child in children:
        await _kill_container(child.backend, child.container_name)
        await _close_warm_child(child)


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
