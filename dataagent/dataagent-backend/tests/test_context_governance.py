"""Governance settings are validated where they are resolved.

These values decide when the prompt cache is deliberately invalidated, so an
out-of-range ratio must fail at startup rather than quietly degrade every run.
"""

from __future__ import annotations

import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

BACKEND_ROOT = Path(__file__).resolve().parents[1]
if str(BACKEND_ROOT) not in sys.path:
    sys.path.insert(0, str(BACKEND_ROOT))

from core.context_governance import (  # noqa: E402
    InvalidContextGovernance,
    build_governance_settings,
    resolve_cache_retention,
    resolve_prune_watermarks,
)


def _cfg(**overrides):
    base = dict(
        dataagent_context_max_inline_result_bytes=16384,
        dataagent_context_protect_tail_turns=6,
        dataagent_context_max_context_tokens=64000,
        dataagent_context_prune_high_watermark_ratio=0.90,
        dataagent_context_prune_target_ratio=0.70,
        dataagent_pi_cache_retention="short",
    )
    base.update(overrides)
    return SimpleNamespace(**base)


def test_defaults_produce_a_complete_governance_section():
    settings = build_governance_settings(_cfg())
    assert settings["prune_high_watermark_ratio"] == 0.90
    assert settings["prune_target_ratio"] == 0.70
    assert settings["cache_retention"] == "short"


@pytest.mark.parametrize(
    "high,target",
    [(0.7, 0.9), (0.9, 0.9), (1.2, 0.7), (0.9, 0.0), (0.9, -0.1)],
)
def test_invalid_watermarks_are_rejected(high, target):
    """Inverted marks would compact up to a larger context than they started from."""
    cfg = _cfg(
        dataagent_context_prune_high_watermark_ratio=high,
        dataagent_context_prune_target_ratio=target,
    )
    with pytest.raises(InvalidContextGovernance):
        resolve_prune_watermarks(cfg)


def test_an_unknown_cache_retention_is_rejected():
    with pytest.raises(InvalidContextGovernance):
        resolve_cache_retention(_cfg(dataagent_pi_cache_retention="sometimes"))


def test_the_legacy_disable_flag_is_read_from_provider_env():
    """DISABLE_PROMPT_CACHING comes from build_provider_env, not from Settings.

    Reading it off cfg found nothing and silently ignored an operator who had
    switched caching off — the same class of miss as the flag never reaching the
    Pi runtime in the first place.
    """
    assert resolve_cache_retention(_cfg(), {"DISABLE_PROMPT_CACHING": "1"}) == "off"
    # Absent or empty leaves the configured value alone.
    assert resolve_cache_retention(_cfg(), {"DISABLE_PROMPT_CACHING": ""}) == "short"
    assert resolve_cache_retention(_cfg(), {}) == "short"


def test_an_explicit_retention_wins_over_the_legacy_flag():
    cfg = _cfg(dataagent_pi_cache_retention="long")
    assert resolve_cache_retention(cfg, {"DISABLE_PROMPT_CACHING": "1"}) == "long"
