"""The stdio frame limit must clear what the Cell is allowed to send.

A UI copy of a tool result can be up to STRUCTURED_OUTPUT_MAX_BYTES (512 KiB)
and travels in a single NDJSON frame. asyncio's reader defaults to 64 KiB, so
anything between the two killed the whole run with "Separator is not found, and
chunk exceed the limit" — every completed turn lost, and nothing in the failed
message explaining it.
"""
from __future__ import annotations

import asyncio
import json

import pytest

from core.pi_runtime import _STDIO_FRAME_LIMIT_BYTES


def _read_one_frame(payload_bytes: int, limit: int) -> dict:
    async def run() -> dict:
        script = (
            "import json,sys;"
            f"sys.stdout.write(json.dumps({{'type':'agent.event','payload':'x'*{payload_bytes}}}));"
            "sys.stdout.write('\\n');sys.stdout.flush()"
        )
        process = await asyncio.create_subprocess_exec(
            "python3",
            "-c",
            script,
            stdout=asyncio.subprocess.PIPE,
            limit=limit,
        )
        try:
            raw = await asyncio.wait_for(process.stdout.readline(), timeout=30)
            return json.loads(raw)
        finally:
            await process.wait()

    return asyncio.run(run())


def test_the_default_limit_is_the_one_that_broke_runs():
    """Pin the failure this guards against, so the guard is not cargo cult."""
    with pytest.raises(ValueError, match="chunk exceed the limit"):
        _read_one_frame(200 * 1024, limit=64 * 1024)


def test_a_frame_at_the_structured_output_ceiling_survives():
    # 512 KiB is what the Cell may send; the reader has to take it whole.
    frame = _read_one_frame(512 * 1024, limit=_STDIO_FRAME_LIMIT_BYTES)
    assert len(frame["payload"]) == 512 * 1024


def test_the_limit_clears_the_ceiling_with_room_for_encoding():
    # JSON escaping and the frame envelope both add to the payload, so equality
    # with the ceiling would be too tight.
    assert _STDIO_FRAME_LIMIT_BYTES > 512 * 1024 * 2


def test_the_limit_actually_reaches_the_subprocess():
    """The constant alone proves nothing — the call site has to pass it.

    Without this, deleting `limit=` from create_subprocess_exec leaves every
    other test in this file green while runs go back to dying at 64 KiB.
    """
    import inspect

    from core import pi_runtime

    source = inspect.getsource(pi_runtime.execute_pi_run)
    assert "limit=_STDIO_FRAME_LIMIT_BYTES" in source, (
        "create_subprocess_exec must be given the frame limit"
    )
