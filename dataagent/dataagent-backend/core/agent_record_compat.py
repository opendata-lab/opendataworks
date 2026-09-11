"""Read-time compatibility for tool outputs written before the unwrap.

Pi used to persist its raw ``{content, details}`` tool result as ``output``.
Records written that way are still in ``da_agent_sdk_record``, and replaying
them would render the wrapper as JSON — the very display bug the producer fix
removes for new runs.

Normalizing on read rather than migrating the rows keeps the repair reversible:
the stored evidence is untouched, and a mistake here is a code fix rather than a
second data migration.
"""

from __future__ import annotations

from typing import Any

# Mirrors DETAIL_FIELD_ALIASES in
# dataagent-runtime-pi/src/kernel/event-normalizer.ts. The two must agree, or a
# legacy record and a fresh one render with different metadata keys.
_DETAIL_FIELD_ALIASES = {
    "exitCode": "exit_code",
    "exit_code": "exit_code",
    "bytes": "byte_count",
    "byte_count": "byte_count",
    "truncated": "truncated",
    "count": "count",
    "folded": "model_context_folded",
    "model_context_folded": "model_context_folded",
    "result_ref": "result_ref",
    "storage_path": "storage_path",
    "original_bytes": "original_bytes",
    # Fold provenance arrives nested, so it cannot collide with a tool's own
    # fields and needs no arbitration.
    "dataagent_fold": "fold",
    "stored_bytes": "stored_bytes",
    "skill_name": "skill_name",
    "root_path": "root_path",
    "denied": "denied",
    "error": "error",
}


def _looks_like_legacy_wrapper(value: Any) -> bool:
    """A raw pi AgentToolResult, not a payload that merely has a content field."""
    return (
        isinstance(value, dict)
        and ("content" in value or "details" in value)
        # A platform structured output carries `kind` at the top level and must
        # be passed through, not unwrapped.
        and "kind" not in value
    )


def normalize_tool_output(data: dict[str, Any]) -> dict[str, Any]:
    """Return ``data`` with any legacy ``output`` wrapper split into siblings.

    Idempotent: a record already carrying an unwrapped ``output`` is returned
    unchanged, so this can sit on both the SSE and the history read path without
    converting twice.
    """
    output = data.get("output")
    if not _looks_like_legacy_wrapper(output):
        return data

    meta: dict[str, Any] = {}
    engine_details: dict[str, Any] = {}
    details = output.get("details")
    if isinstance(details, dict):
        for key, value in details.items():
            alias = _DETAIL_FIELD_ALIASES.get(key)
            if alias:
                meta[alias] = value
            else:
                engine_details[key] = value
    elif details is not None:
        # Mirrors the TypeScript rule: a tool's details are typed as anything,
        # and a string or list is evidence too. Keep it whole rather than
        # dropping it for not being a mapping.
        engine_details["raw_details"] = details
    if engine_details:
        meta["engine_details"] = engine_details

    normalized = dict(data)
    normalized["output"] = output.get("content")
    # An existing output_meta wins: it came from the producer, which knows more
    # than this reconstruction does.
    if meta and not normalized.get("output_meta"):
        normalized["output_meta"] = meta
    return normalized
