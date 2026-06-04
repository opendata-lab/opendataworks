"""Contract test: backend SDK-record projection matches the shared golden fixtures.

The SDK-record -> rendered-block projection now lives in exactly two places:

* backend ``topic_task_store._project_sdk_records`` (history / eval evidence)
* frontend ``v2StreamParser.processV2Record`` (live streaming)

Both must turn the same ``records`` into the same canonical block list. This test
locks the backend side against ``dataagent/contracts/sdk-block-projection/cases.json``;
``sdkBlockProjection.contract.spec.js`` locks the frontend side against the same file.
"""
from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from core.topic_task_store import _project_sdk_records

_CASES_PATH = Path(__file__).resolve().parents[2] / "contracts" / "sdk-block-projection" / "cases.json"


def _load_cases() -> list[dict[str, Any]]:
    data = json.loads(_CASES_PATH.read_text(encoding="utf-8"))
    return list(data.get("cases") or [])


def _to_canonical(blocks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Normalize backend projection blocks to the shared canonical shape."""
    canonical: list[dict[str, Any]] = []
    for block in blocks:
        btype = block.get("type")
        if btype == "tool_use":
            canonical.append(
                {
                    "kind": "tool_use",
                    "tool_name": block.get("tool_name"),
                    "input": block.get("input"),
                    "output": block.get("output"),
                    "is_error": bool(block.get("is_error")),
                }
            )
        else:
            kind = "text" if btype == "main_text" else str(btype or "")
            text = str(block.get("text") or "")
            if not text.strip():
                continue
            canonical.append({"kind": kind, "text": text})
    return canonical


def test_contract_fixtures_exist() -> None:
    cases = _load_cases()
    assert cases, f"no projection contract cases found at {_CASES_PATH}"


def test_backend_projection_matches_golden_fixtures() -> None:
    for case in _load_cases():
        projected = _project_sdk_records(case["records"])["blocks"]
        assert _to_canonical(projected) == case["expected"], f"case mismatch: {case.get('name')}"
