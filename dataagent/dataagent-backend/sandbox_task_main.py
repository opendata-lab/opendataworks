from __future__ import annotations

import asyncio
import base64
import json
import logging
import os
import sys
from dataclasses import asdict
from pathlib import Path
from typing import Any

from core.task_executor import TaskExecutionInput, TaskExecutionResult, _execute_task_stream_local


logging.basicConfig(level=logging.INFO, stream=sys.stderr)
logger = logging.getLogger(__name__)


def _load_payload() -> dict[str, Any]:
    raw = str(os.environ.get("DATAAGENT_TASK_PAYLOAD_B64") or "").strip()
    if raw:
        return json.loads(base64.b64decode(raw).decode("utf-8"))
    stdin_payload = sys.stdin.buffer.read()
    if not stdin_payload:
        raise RuntimeError("task payload is required on stdin")
    return json.loads(stdin_payload.decode("utf-8"))


async def _main() -> int:
    params = TaskExecutionInput(**_load_payload())

    async def emit(record: dict[str, Any]) -> None:
        print(json.dumps({"type": "record", "record": record}, ensure_ascii=False), flush=True)

    try:
        result = await _execute_task_stream_local(
            params,
            emit=emit,
            is_cancel_requested=lambda: False,
            prepared_workspace_dir=Path.cwd(),
        )
    except Exception as exc:
        logger.exception("sandbox task crashed task_id=%s", params.task_id)
        result = TaskExecutionResult(
            task_status="error",
            content=str(exc),
            error={"code": "sandbox_task_error", "message": str(exc)},
            provider_id=params.provider_id,
            model=params.model,
        )

    print(json.dumps({"type": "result", "result": asdict(result)}, ensure_ascii=False), flush=True)
    return 0 if result.task_status != "error" else 1


if __name__ == "__main__":
    raise SystemExit(asyncio.run(_main()))
