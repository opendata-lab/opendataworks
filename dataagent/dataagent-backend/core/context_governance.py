"""Resolution and validation for context-governance settings.

These values reach the Cell through the ``cell.init`` frame and decide when the
prompt cache is deliberately invalidated, so a typo must surface at startup
rather than as a quietly degraded run.
"""

from __future__ import annotations

from typing import Any

CACHE_RETENTIONS = ("short", "long", "off")


class InvalidContextGovernance(ValueError):
    """A governance setting is outside its permitted range."""


def resolve_cache_retention(cfg: Any) -> str:
    """Explicit retention, with the legacy flag honoured only when it says off.

    ``DISABLE_PROMPT_CACHING`` is a Claude Code variable the Pi runtime never
    reads, so it silently did nothing there. It is respected here when set, but
    it cannot override an explicit retention.
    """
    configured = str(getattr(cfg, "dataagent_pi_cache_retention", "") or "").strip().lower()
    if configured:
        if configured not in CACHE_RETENTIONS:
            raise InvalidContextGovernance(
                f"DATAAGENT_PI_CACHE_RETENTION must be one of {CACHE_RETENTIONS}, got {configured!r}"
            )
        if configured != "short":
            return configured

    legacy = str(getattr(cfg, "disable_prompt_caching", "") or "").strip().lower()
    if legacy in {"1", "true", "yes"}:
        return "off"
    return configured or "short"


def resolve_prune_watermarks(cfg: Any) -> tuple[float, float]:
    """Return (high, target), validated as 0 < target < high <= 1.

    Inverting them would compact up to a larger context than it started from,
    and a high mark above 1 would never trigger at all.
    """
    # `or default` would silently accept 0.0 as "unset" and substitute the
    # default, turning an invalid configuration into a working one.
    raw_high = getattr(cfg, "dataagent_context_prune_high_watermark_ratio", None)
    raw_target = getattr(cfg, "dataagent_context_prune_target_ratio", None)
    high = float(0.90 if raw_high is None else raw_high)
    target = float(0.70 if raw_target is None else raw_target)
    if not (0 < target < high <= 1):
        raise InvalidContextGovernance(
            "context prune watermarks must satisfy 0 < target < high <= 1; "
            f"got target={target}, high={high}"
        )
    return high, target


def build_governance_settings(cfg: Any) -> dict[str, Any]:
    """Assemble the ``governance_settings`` section of the cell.init frame."""
    high, target = resolve_prune_watermarks(cfg)
    return {
        "max_inline_result_bytes": int(
            getattr(cfg, "dataagent_context_max_inline_result_bytes", 16 * 1024)
        ),
        "protect_tail_turns": int(getattr(cfg, "dataagent_context_protect_tail_turns", 6)),
        "max_context_tokens": int(getattr(cfg, "dataagent_context_max_context_tokens", 64_000)),
        "prune_high_watermark_ratio": high,
        "prune_target_ratio": target,
        "cache_retention": resolve_cache_retention(cfg),
    }
