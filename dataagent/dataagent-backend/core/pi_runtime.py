"""Pi data plane adapter — runs one NL2SQL turn on a Node Pi Cell over stdio.

Sits under ``task_executor._execute_task_stream_local``, alongside the
claude-agent-sdk path, and reuses everything that path already prepared:
provider/model selection, system prompt, skill runtime, workspace, runtime env.
Only the engine differs.

Deliberately *not* a network service. The Cell is a child process reached over
stdin/stdout, so there is no port to expose, no shared secret to distribute, and
no second copy of the event log to keep durable — events land in
``da_agent_sdk_record`` through :class:`core.pi_event_writer.PiEventWriter`, the
same table the SDK path writes to.

Protocol (newline-delimited JSON, one frame per line):

  Gateway -> Cell   ``cell.init`` (once)   ``run.cancel``   ``cell.shutdown``
  Cell -> Gateway   ``cell.ready``   ``run.event``   ``run.settled``   ``protocol.error``

Every frame is ``{"protocol_version": 1, "type": ..., "payload": {...}}``. The
payload of ``run.event`` *is* the neutral AgentEvent — not a wrapper around it.
Both sides must agree on that; a cross-process contract test pins it.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import shutil
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Awaitable, Callable, Optional

logger = logging.getLogger(__name__)

PROTOCOL_VERSION = 1

# Grace period between asking the Cell to stop and killing it. One turn of the
# agent loop is far shorter than this; anything still running is wedged.
CANCEL_GRACE_SECONDS = 2.0
# The Cell answers cell.init as soon as its module graph is loaded.
HANDSHAKE_TIMEOUT_SECONDS = 10.0
# How often the cancel flag is polled while events stream.
CANCEL_POLL_INTERVAL_SECONDS = 1.0

TERMINAL_EVENT_TYPES = frozenset({"run.completed", "run.failed", "run.cancelled", "run.suspended"})


class PiRuntimeUnavailable(RuntimeError):
    """The Pi Cell entrypoint or its Node runtime is missing."""


@dataclass
class PiRunContext:
    """Everything the Cell needs for one run, assembled by the control plane."""

    task_id: str
    topic_id: str
    provider_id: str
    model: str
    system_prompt: str
    messages: list[dict[str, str]]
    project_cwd: Path
    boundary_policy: dict[str, Any]
    history: list[dict[str, str]] = field(default_factory=list)
    prompt: str = ""
    runtime_env: dict[str, str] = field(default_factory=dict)
    provider_env: dict[str, str] = field(default_factory=dict)
    skills: list[dict[str, str]] = field(default_factory=list)
    mcp_servers: list[dict[str, Any]] = field(default_factory=list)
    total_timeout_seconds: int = 600
    idle_timeout_seconds: int = 300
    max_turns: int = 30
    max_tool_calls: int = 50
    # Context governance, assembled by core.context_governance so the wire
    # contract has one producer. Field names mirror ``governance_settings`` in
    # ``src/protocol/frames.ts``; a rename on either side has to move in
    # lockstep or the Cell silently falls back to its own defaults.
    governance_settings: dict[str, Any] = field(default_factory=dict)

    def to_init_payload(self) -> dict[str, Any]:
        """Serialize for the ``cell.init`` frame.

        Provider credentials are deliberately absent: they travel through the
        child's environment instead, so they never appear on stdio, in a log
        line, or in any persisted record.
        """
        prompt_val = self.prompt
        history_val = list(self.history)
        if not prompt_val and self.messages:
            prompt_val = str(self.messages[-1].get("content") or "").strip()
            if not history_val:
                history_val = self.messages[:-1]

        return {
            "run_id": self.task_id,
            "task_id": self.task_id,
            "topic_id": self.topic_id,
            "system_prompt": self.system_prompt,
            "messages": self.messages,
            "history": history_val,
            "prompt": prompt_val,
            "model": {"provider_id": self.provider_id, "model_id": self.model},
            "workspace": {"project_cwd": str(self.project_cwd)},
            "boundary_policy": self.boundary_policy,
            "skills": self.skills,
            "mcp_servers": self.mcp_servers,
            "runtime_env": self.runtime_env,
            "limits": {
                "total_timeout_seconds": self.total_timeout_seconds,
                "idle_timeout_seconds": self.idle_timeout_seconds,
                "max_turns": self.max_turns,
                "max_tool_calls": self.max_tool_calls,
            },
            "governance_settings": dict(self.governance_settings),
        }


def resolve_cell_command(cfg: Any = None) -> list[str]:
    """Locate the Node binary and the built Cell entrypoint.

    Raises :class:`PiRuntimeUnavailable` with an actionable message rather than
    letting a spawn failure surface as an opaque OSError, mirroring how the SDK
    path reports ``sdk_not_installed``.
    """
    node_bin = str(getattr(cfg, "dataagent_node_bin", "") or "").strip()
    node_bin = node_bin or os.environ.get("DATAAGENT_NODE_BIN", "").strip() or shutil.which("node") or ""
    if not node_bin:
        raise PiRuntimeUnavailable("找不到 Node 运行时：请设置 DATAAGENT_NODE_BIN 或将 node 加入 PATH")

    pi_dir = str(getattr(cfg, "dataagent_runtime_pi_dir", "") or "").strip()
    pi_dir = pi_dir or os.environ.get("DATAAGENT_RUNTIME_PI_DIR", "").strip()
    if pi_dir:
        root = Path(pi_dir)
    else:
        # dataagent-backend/core/pi_runtime.py -> dataagent/dataagent-runtime-pi
        root = Path(__file__).resolve().parents[2] / "dataagent-runtime-pi"

    entrypoint = root / "dist" / "src" / "main.js"
    if not entrypoint.exists():
        raise PiRuntimeUnavailable(
            f"Pi Cell 入口不存在：{entrypoint}（请先在 {root} 执行 npm ci && npm run build）"
        )
    return [node_bin, str(entrypoint)]


def _frame(frame_type: str, payload: dict[str, Any] | None = None) -> str:
    return json.dumps(
        {"protocol_version": PROTOCOL_VERSION, "type": frame_type, "payload": payload or {}},
        separators=(",", ":"),
        ensure_ascii=False,
    )


class _CellChannel:
    """stdio framing for one Cell child process."""

    def __init__(self, process: asyncio.subprocess.Process, task_id: str) -> None:
        self._process = process
        self._task_id = task_id

    async def send(self, frame_type: str, payload: dict[str, Any] | None = None) -> None:
        stdin = self._process.stdin
        if stdin is None or stdin.is_closing():
            return
        try:
            stdin.write((_frame(frame_type, payload) + "\n").encode("utf-8"))
            await stdin.drain()
        except (BrokenPipeError, ConnectionResetError):
            # The child is gone; the stdout reader will surface the loss.
            logger.warning("pi_cell.stdin_closed task_id=%s frame=%s", self._task_id, frame_type)

    async def read_frame(self) -> Optional[dict[str, Any]]:
        """Next protocol frame, or ``None`` at end of stream.

        A blank line is skipped rather than treated as EOF, and a line that is
        not valid JSON is logged and skipped rather than tearing the channel
        down: the child is still alive and its next line may well be fine.
        Losing the channel on one stray line would strand a healthy run.
        """
        stdout = self._process.stdout
        if stdout is None:
            return None
        while True:
            raw = await stdout.readline()
            if not raw:
                return None
            line = raw.decode("utf-8", errors="replace").strip()
            if not line:
                continue
            try:
                parsed = json.loads(line)
            except json.JSONDecodeError:
                logger.warning("pi_cell.non_json_line task_id=%s line=%.200s", self._task_id, line)
                continue
            if not isinstance(parsed, dict):
                logger.warning("pi_cell.non_object_frame task_id=%s line=%.200s", self._task_id, line)
                continue
            return parsed


async def _drain_stderr(process: asyncio.subprocess.Process, task_id: str) -> None:
    """Mirror the child's stderr into the task log; never touches the protocol."""
    stderr = process.stderr
    if stderr is None:
        return
    try:
        while True:
            raw = await stderr.readline()
            if not raw:
                return
            logger.info("pi_cell.stderr task_id=%s %s", task_id, raw.decode("utf-8", errors="replace").rstrip())
    except Exception:
        logger.debug("pi_cell.stderr_reader_stopped task_id=%s", task_id, exc_info=True)


async def _terminate(process: asyncio.subprocess.Process, task_id: str) -> None:
    """Ensure the child is gone.

    Always called, including on the success path: a Cell that has settled its
    run but not exited would otherwise linger as an orphan holding the
    workspace open.
    """
    if process.returncode is not None:
        return
    try:
        process.terminate()
    except ProcessLookupError:
        return
    try:
        await asyncio.wait_for(process.wait(), timeout=CANCEL_GRACE_SECONDS)
    except asyncio.TimeoutError:
        logger.warning("pi_cell.kill_after_grace task_id=%s", task_id)
        try:
            process.kill()
        except ProcessLookupError:
            pass
        try:
            await asyncio.wait_for(process.wait(), timeout=CANCEL_GRACE_SECONDS)
        except asyncio.TimeoutError:
            logger.error("pi_cell.kill_timeout task_id=%s", task_id)


@dataclass
class PiRunOutcome:
    """Engine-level result, translated to TaskExecutionResult by the caller."""

    terminal_status: str  # "success" | "failed" | "cancelled"
    answer: str = ""
    error_code: str = ""
    error_message: str = ""
    usage: dict[str, Any] | None = None
    last_sequence: int = 0


async def execute_pi_run(
    ctx: PiRunContext,
    *,
    writer: Any,
    cancel_reason: Callable[[], Awaitable[Any]] | None = None,
    cell_command: list[str] | None = None,
    child_env: dict[str, str] | None = None,
) -> PiRunOutcome:
    """Run one turn on a Pi Cell and return its outcome.

    ``writer`` receives every neutral event (a :class:`PiEventWriter`).
    ``cell_command`` is injectable so a contract test can drive a stub Cell over
    the real protocol instead of mocking the channel away.
    """
    command = cell_command or resolve_cell_command()

    env = dict(child_env) if child_env is not None else dict(os.environ)
    env.update(ctx.runtime_env)
    env.update(ctx.provider_env)
    env.setdefault("NODE_ENV", "production")

    process = await asyncio.create_subprocess_exec(
        *command,
        stdin=asyncio.subprocess.PIPE,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        cwd=str(ctx.project_cwd),
        env=env,
    )
    channel = _CellChannel(process, ctx.task_id)
    stderr_task = asyncio.create_task(_drain_stderr(process, ctx.task_id))

    started_at = time.monotonic()
    deadline = started_at + max(1, ctx.total_timeout_seconds)
    last_activity = started_at
    highest_sequence = 0
    answer_parts: list[str] = []
    usage: dict[str, Any] | None = None
    terminal: PiRunOutcome | None = None
    cancel_sent = False
    cancel_deadline: float | None = None

    try:
        await channel.send("cell.init", ctx.to_init_payload())

        while True:
            now = time.monotonic()
            if now >= deadline:
                terminal = PiRunOutcome(
                    terminal_status="failed",
                    answer="".join(answer_parts),
                    error_code="PI_RUN_TIMEOUT",
                    error_message=f"Pi 运行时单轮执行超过 {ctx.total_timeout_seconds}s 总超时",
                    last_sequence=highest_sequence,
                )
                break
            # A cancel the Cell does not honour must not leave the user waiting
            # out the rest of the run timeout. Once asked to stop it gets a
            # grace period, then the run is settled as cancelled — which is what
            # actually happened — and the finally block kills the child.
            if cancel_deadline is not None and now >= cancel_deadline:
                terminal = PiRunOutcome(
                    terminal_status="cancelled",
                    answer="".join(answer_parts),
                    usage=usage,
                    last_sequence=highest_sequence,
                )
                break
            if (
                cancel_deadline is None
                and ctx.idle_timeout_seconds > 0
                and (now - last_activity) >= ctx.idle_timeout_seconds
            ):
                terminal = PiRunOutcome(
                    terminal_status="failed",
                    answer="".join(answer_parts),
                    error_code="PI_RUN_IDLE_TIMEOUT",
                    error_message=f"Pi 运行时 {ctx.idle_timeout_seconds}s 内无任何事件或工具输出（任务已执行 {int(now - started_at)}s）",
                    last_sequence=highest_sequence,
                )
                break

            if cancel_reason is not None and not cancel_sent:
                reason = await cancel_reason()
                if reason:
                    cancel_sent = True
                    cancel_deadline = time.monotonic() + CANCEL_GRACE_SECONDS
                    await channel.send("run.cancel", {"reason": str(reason)})

            # Bounded wait so the cancel flag and both timeouts stay live even
            # while the Cell is quiet.
            budget = min(
                CANCEL_POLL_INTERVAL_SECONDS,
                max(0.05, deadline - now),
                # Keep the grace period accurate rather than rounding it up to
                # the next poll tick.
                max(0.05, cancel_deadline - now) if cancel_deadline is not None else CANCEL_POLL_INTERVAL_SECONDS,
            )
            try:
                frame = await asyncio.wait_for(channel.read_frame(), timeout=budget)
            except asyncio.TimeoutError:
                continue

            if frame is None:
                # stdout closed: the child exited without settling the run.
                terminal = PiRunOutcome(
                    terminal_status="cancelled" if cancel_sent else "failed",
                    answer="".join(answer_parts),
                    error_code="" if cancel_sent else "CELL_LOSS",
                    error_message="" if cancel_sent else "Pi Cell 子进程在产出终态前退出",
                    last_sequence=highest_sequence,
                )
                break

            last_activity = time.monotonic()
            ftype = str(frame.get("type") or "")
            payload = frame.get("payload") if isinstance(frame.get("payload"), dict) else {}

            if ftype == "run.event":
                event = payload
                sequence = event.get("sequence")
                if isinstance(sequence, int):
                    highest_sequence = max(highest_sequence, sequence)
                writer.ingest(event)

                event_type = str(event.get("type") or "")
                event_payload = event.get("payload") if isinstance(event.get("payload"), dict) else {}
                if event_type == "content.delta" and str(event_payload.get("kind") or "") != "reasoning":
                    answer_parts.append(str(event_payload.get("delta") or ""))
                elif event_type == "usage.updated":
                    raw_usage = event_payload.get("usage")
                    if isinstance(raw_usage, dict):
                        usage = raw_usage
                elif event_type in TERMINAL_EVENT_TYPES:
                    terminal = _outcome_from_terminal_event(
                        event_type, event_payload, "".join(answer_parts), usage, highest_sequence
                    )
                    break

            elif ftype == "cell.ready":
                logger.info("pi_cell.ready task_id=%s manifest=%s", ctx.task_id, payload.get("manifest"))

            elif ftype == "protocol.error":
                logger.error("pi_cell.protocol_error task_id=%s payload=%s", ctx.task_id, payload)

            elif ftype == "run.settled":
                # Belt and braces: the Cell should always emit a terminal event
                # before settling, but a settle without one must not hang us.
                if terminal is None:
                    status = str(payload.get("terminal_status") or "failed")
                    terminal = PiRunOutcome(
                        terminal_status="success" if status == "success" else status,
                        answer="".join(answer_parts),
                        error_code="" if status == "success" else "PI_SETTLED_WITHOUT_TERMINAL_EVENT",
                        error_message=str(payload.get("error") or ""),
                        usage=usage,
                        last_sequence=highest_sequence,
                    )
                break

        if terminal is None:
            terminal = PiRunOutcome(
                terminal_status="failed",
                answer="".join(answer_parts),
                error_code="PI_RUN_INCOMPLETE",
                error_message="Pi 运行时未产出终态",
                last_sequence=highest_sequence,
            )

        # A run cut short by us still owes the record stream a terminal event,
        # otherwise history replay shows a turn that never ends.
        if terminal.error_code in {"PI_RUN_TIMEOUT", "PI_RUN_IDLE_TIMEOUT", "CELL_LOSS", "PI_RUN_INCOMPLETE"}:
            writer.ingest(
                {
                    "event_id": f"ev-{ctx.task_id}-terminal",
                    "run_id": ctx.task_id,
                    "task_id": ctx.task_id,
                    "task_attempt_id": ctx.task_id,
                    "sequence": highest_sequence + 1,
                    "type": "run.failed",
                    "payload": {"error_code": terminal.error_code, "message": terminal.error_message},
                }
            )
        return terminal

    finally:
        await channel.send("cell.shutdown", {})
        await _terminate(process, ctx.task_id)
        stderr_task.cancel()
        try:
            await stderr_task
        except (asyncio.CancelledError, Exception):  # noqa: B014 - cleanup must not raise
            pass


def _outcome_from_terminal_event(
    event_type: str,
    payload: dict[str, Any],
    answer: str,
    usage: dict[str, Any] | None,
    last_sequence: int,
) -> PiRunOutcome:
    if event_type == "run.completed":
        return PiRunOutcome(
            terminal_status="success",
            answer=str(payload.get("answer") or answer),
            usage=usage,
            last_sequence=last_sequence,
        )
    if event_type == "run.cancelled":
        return PiRunOutcome(terminal_status="cancelled", answer=answer, usage=usage, last_sequence=last_sequence)
    return PiRunOutcome(
        terminal_status="failed",
        answer=answer,
        error_code=str(payload.get("error_code") or "PI_EXECUTION_ERROR"),
        error_message=str(payload.get("message") or "Pi 运行时执行失败"),
        usage=usage,
        last_sequence=last_sequence,
    )
