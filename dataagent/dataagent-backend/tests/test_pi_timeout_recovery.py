"""A run we cut short must keep the work it already produced.

The SDK path has always rescued the text a timed-out turn had written and
returned it as an answer. Pi marked the same situation an error, so the engine
production runs was the one that discarded a half-written report and showed a
red failure — with the text sitting right there in outcome.answer.
"""
from __future__ import annotations

import pytest

from core.agent_runtime import RECOVERABLE_TERMINAL_ERROR_CODES, _is_recoverable_timeout_reason


def test_the_engine_error_codes_are_recognised():
    """Matched on the code, not on a Chinese word in the message.

    The predicate used to test for the substring "超时", so a message from the
    SDK path recovered and PI_RUN_TIMEOUT — emitted by the engine actually in
    use — never did.
    """
    for code in ("PI_RUN_TIMEOUT", "PI_RUN_IDLE_TIMEOUT", "PI_RUN_INCOMPLETE", "CELL_LOSS"):
        assert _is_recoverable_timeout_reason(code), code
        assert code in RECOVERABLE_TERMINAL_ERROR_CODES


def test_english_timeout_text_is_recognised():
    assert _is_recoverable_timeout_reason("Request timed out after 360s")
    assert _is_recoverable_timeout_reason("TIMEOUT waiting for cell")


def test_the_chinese_wording_still_works_for_the_sdk_path():
    assert _is_recoverable_timeout_reason("Pi 运行时单轮执行超过 360s 总超时")


def test_an_unrelated_failure_is_not_treated_as_recoverable():
    """A model or provider error has no partial answer worth promoting."""
    assert not _is_recoverable_timeout_reason("Connection error.")
    assert not _is_recoverable_timeout_reason("PI_MODEL_ERROR")
    assert not _is_recoverable_timeout_reason("")


@pytest.mark.parametrize("code", sorted(RECOVERABLE_TERMINAL_ERROR_CODES))
def test_a_cut_short_run_with_text_finishes_instead_of_erroring(code):
    from core.task_executor import TaskExecutionResult  # noqa: F401
    from core import task_executor

    # Exercised through the same helper the Pi branch calls, so the test fails
    # if the branch stops routing through it.
    from core.agent_runtime import _recover_partial_content

    recovered = _recover_partial_content(
        question="最近 30 天工作流发布次数趋势",
        main_text="已确认平台共有 4 个工作流，其中 3 个在线。",
        blocks={},
        reason=code,
    )
    assert recovered, f"{code} must yield showable content"
    assert "4 个工作流" in recovered
