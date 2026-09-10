"""The v1 contract binds real producers, not just documentation.

The vocabulary used to live in three places kept in sync by hand — the Pi
frames, the frontend reducer, and the Python projection. A typo produced a
record that stored fine and was then silently ignored by every consumer.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest
from jsonschema import Draft202012Validator

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

CONTRACT_DIR = BACKEND_ROOT.parents[0] / "contracts" / "agent-events" / "v1"

from core.task_status import (  # noqa: E402
    ACTIVE_TASK_STATUSES,
    TERMINAL_TASK_STATUSES,
)


def _schema(name: str) -> dict:
    return json.loads((CONTRACT_DIR / name).read_text(encoding="utf-8"))


@pytest.mark.parametrize(
    "name", ["neutral-event.schema.json", "tool-output.schema.json", "task-status.schema.json"]
)
def test_schemas_are_valid_json_schema(name):
    Draft202012Validator.check_schema(_schema(name))


def test_event_vocabulary_matches_the_pi_producer():
    """The producer's enum and the contract's must not drift.

    Reading the TypeScript keeps this honest without a build step: a type added
    on one side and forgotten on the other is exactly how the old three-way
    duplication went wrong.
    """
    frames = (
        BACKEND_ROOT.parents[0] / "dataagent-runtime-pi" / "src" / "protocol" / "frames.ts"
    ).read_text(encoding="utf-8")
    block = frames.split("export type AgentEventType", 1)[1].split(";", 1)[0]
    produced = set(part.strip().strip('|" ') for part in block.split("\n") if '"' in part)
    produced = {p for p in produced if p}

    declared = set(_schema("neutral-event.schema.json")["properties"]["type"]["enum"])
    assert produced == declared, f"producer {produced ^ declared} differs from contract"


def test_tool_progress_is_absent_from_the_contract():
    """It was removed on purpose; re-adding it needs a consumer first."""
    declared = set(_schema("neutral-event.schema.json")["properties"]["type"]["enum"])
    assert "tool.progress" not in declared


def test_task_status_schema_matches_the_python_vocabulary():
    schema = _schema("task-status.schema.json")["properties"]
    assert set(schema["task_status_terminal"]["enum"]) == set(TERMINAL_TASK_STATUSES)
    assert set(schema["task_status_active"]["enum"]) == set(ACTIVE_TASK_STATUSES)


def test_an_unwrapped_pi_tool_output_validates():
    payload = {
        "turn_id": "turn-1",
        "tool_call_id": "t1",
        "tool_name": "run_sql",
        "output": [{"type": "text", "text": '{"kind":"chart_spec"}'}],
        "output_meta": {"exit_code": 0},
        "is_error": False,
    }
    Draft202012Validator(_schema("tool-output.schema.json")).validate(payload)


def test_an_sdk_string_tool_output_validates():
    """The superset property: the Claude shape must stay legal."""
    Draft202012Validator(_schema("tool-output.schema.json")).validate(
        {"tool_call_id": "t1", "tool_name": "Bash", "output": "1", "is_error": False}
    )
