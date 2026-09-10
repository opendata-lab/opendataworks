"""Legacy Pi tool outputs render like fresh ones.

Pi used to persist its raw {content, details} result as `output`. Those rows are
still stored, and replaying one verbatim shows the wrapper as JSON — the display
bug the producer fix removes only for new runs.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core.agent_record_compat import normalize_tool_output  # noqa: E402


def test_legacy_wrapper_is_split_into_output_and_meta():
    chart = json.dumps({"kind": "chart_spec", "chart_type": "bar"})
    out = normalize_tool_output(
        {
            "tool_call_id": "t1",
            "output": {"content": [{"type": "text", "text": chart}], "details": {"exitCode": 0}},
            "is_error": False,
        }
    )
    assert out["output"] == [{"type": "text", "text": chart}]
    assert out["output_meta"] == {"exit_code": 0}
    # Untouched fields survive.
    assert out["tool_call_id"] == "t1"


def test_normalization_is_idempotent():
    """It sits on the shared read path, so it must tolerate running twice."""
    once = normalize_tool_output(
        {"output": {"content": [{"type": "text", "text": "x"}], "details": {"bytes": 5}}}
    )
    assert normalize_tool_output(once) == once


def test_a_platform_structured_output_is_not_unwrapped():
    """`kind` at the top level is the renderer's signal; rewrapping would lose it."""
    payload = {"kind": "sql_execution", "rows": [{"a": 1}], "content": "unrelated"}
    assert normalize_tool_output({"output": payload})["output"] == payload


def test_producer_supplied_meta_wins_over_reconstruction():
    out = normalize_tool_output(
        {
            "output": {"content": [], "details": {"exitCode": 9}},
            "output_meta": {"exit_code": 0, "from_producer": True},
        }
    )
    assert out["output_meta"] == {"exit_code": 0, "from_producer": True}


def test_unknown_details_are_namespaced_not_dropped():
    out = normalize_tool_output({"output": {"content": [], "details": {"weird": 1}}})
    assert out["output_meta"]["engine_details"] == {"weird": 1}


def test_a_string_output_passes_through():
    """The SDK path stores a plain string and must not be touched."""
    assert normalize_tool_output({"output": "plain"})["output"] == "plain"
