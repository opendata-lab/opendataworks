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


def test_displaced_provenance_keeps_its_field_s_home():
    """Mirrors the TypeScript rule so a record reads the same on both sides.

    A fold takes result_ref for the stored copy and pushes the tool's own value
    to source_result_ref. Filing that under engine_details here while the
    producer keeps it at the top would split one fact across two shapes
    depending on which side you asked.
    """
    normalized = normalize_tool_output(
        {
            "output": {
                "content": [{"type": "text", "text": "digest"}],
                "details": {
                    "result_ref": "res_fold",
                    "source_result_ref": "res_source",
                    "original_bytes": 900,
                    "source_original_bytes": 100,
                    "stored_bytes": 400,
                },
            }
        }
    )

    meta = normalized["output_meta"]
    assert meta["result_ref"] == "res_fold"
    assert meta["source_result_ref"] == "res_source"
    assert meta["original_bytes"] == 900
    assert meta["source_original_bytes"] == 100
    assert meta["stored_bytes"] == 400
    assert "engine_details" not in meta


def test_a_source_prefix_over_an_unknown_field_is_still_namespaced():
    """The rule fires only when the suffix is a field we actually know.

    Otherwise any tool that happens to name something source_* would have it
    promoted to the top of the contract by accident.
    """
    normalized = normalize_tool_output(
        {"output": {"content": [], "details": {"source_type": "MYSQL"}}}
    )

    meta = normalized["output_meta"]
    assert meta["engine_details"]["source_type"] == "MYSQL"
    assert "source_type" not in {k for k in meta if k != "engine_details"}


def test_a_source_name_a_fold_could_not_have_written_stays_namespaced():
    """The prefix says nothing about who wrote it.

    source_error and source_count are fields a tool is free to invent, so
    promoting them would put a tool's own words where the contract promises
    fold provenance.
    """
    normalized = normalize_tool_output(
        {
            "output": {
                "content": [],
                "details": {
                    "source_error": "upstream said no",
                    "source_count": 3,
                    "source_result_ref": "res_a",
                },
            }
        }
    )

    meta = normalized["output_meta"]
    assert meta["engine_details"]["source_error"] == "upstream said no"
    assert meta["engine_details"]["source_count"] == 3
    assert "source_error" not in meta
    assert "source_count" not in meta
    # Only a field a fold actually claims counts as displaced provenance.
    assert meta["source_result_ref"] == "res_a"


def test_details_that_are_not_a_mapping_are_kept_whole():
    """Mirrors the TypeScript rule so neither side silently discards evidence."""
    normalized = normalize_tool_output(
        {"output": {"content": [], "details": "plain text"}}
    )

    assert normalized["output_meta"]["engine_details"]["raw_details"] == "plain text"


def test_the_fold_field_set_matches_the_typescript_producer():
    """The two sides keep separate copies of one list, so pin them together.

    stored_bytes existed in the TypeScript table and nowhere else until a review
    caught it. TypeScript now makes its own half a compile error; this is the
    half that crosses the language boundary, where no compiler can look.
    """
    import re
    from pathlib import Path

    from core.agent_record_compat import _FOLD_PROVENANCE_FIELDS

    producer = (
        Path(__file__).resolve().parents[2]
        / "dataagent-runtime-pi"
        / "src"
        / "kernel"
        / "event-normalizer.ts"
    )
    source = producer.read_text(encoding="utf-8")
    declared = re.search(
        r"FOLD_PROVENANCE_FIELDS = \[(.*?)\] as const", source, re.S
    )
    assert declared, "the producer no longer declares FOLD_PROVENANCE_FIELDS"

    assert set(re.findall(r'"(\w+)"', declared.group(1))) == set(_FOLD_PROVENANCE_FIELDS)


def test_the_two_readers_agree_on_a_matrix_of_wrapper_shapes():
    """Differential test, because separate suites hid a real divergence.

    Both sides had tests for non-object details and both passed, yet the fold
    path kept them and the plain unwrap dropped them — so one record meant two
    things depending on which reader saw it. Comparing outputs directly is the
    only check that would have caught it.
    """
    import json
    import os
    import subprocess
    from pathlib import Path

    runtime = Path(__file__).resolve().parents[2] / "dataagent-runtime-pi"
    if not (runtime / "dist" / "src" / "kernel" / "event-normalizer.js").exists():
        import pytest

        pytest.skip("pi runtime is not built")

    cases = [
        {"content": [], "details": {"exitCode": 0}},
        {"content": [], "details": {"result_ref": "a", "source_result_ref": "b"}},
        {"content": [], "details": {"source_error": "x", "source_count": 1}},
        {"content": [], "details": {"stored_bytes": 4, "original_bytes": 9}},
        {"content": [], "details": "plain text"},
        {"content": [], "details": [1, 2]},
        {"content": [], "details": 42},
        {"content": [], "details": True},
        {"content": [], "details": None},
        {"content": [{"type": "text", "text": "hi"}]},
    ]

    module = (runtime / "dist" / "src" / "kernel" / "event-normalizer.js").as_uri()
    script = (
        f"const {{unwrapToolResult}} = await import({module!r});"
        "console.log(JSON.stringify("
        "JSON.parse(process.env.CASES).map((c) => unwrapToolResult(c).output_meta)));"
    )
    completed = subprocess.run(
        ["node", "--input-type=module", "-e", script],
        cwd=runtime,
        capture_output=True,
        text=True,
        timeout=60,
        env={**os.environ, "CASES": json.dumps(cases)},
    )
    assert completed.returncode == 0, completed.stderr
    ts_metas = json.loads(completed.stdout)

    py_metas = [
        normalize_tool_output({"output": case}).get("output_meta") for case in cases
    ]

    for case, ts_meta, py_meta in zip(cases, ts_metas, py_metas):
        assert ts_meta == py_meta, f"readers disagree on {case!r}: {ts_meta!r} vs {py_meta!r}"
