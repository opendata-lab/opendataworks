"""What happens to a question or plan card nobody answers.

Engine-agnostic: the Claude Code and Pi paths both park a run on a durable
``question_request`` / ``permission_request`` record and wait for the user. Once
the deadline passes the run continues as the card recommended: each question
takes its recommended option (or is skipped when it has none), and a plan is
approved only when the agent opted into that (``plan_timeout_action=approve``).
The automatic answer goes through the same ``*_if_waiting`` append as a user's
reply, so whichever lands first wins and the other is a no-op.
"""
from __future__ import annotations

import time
from datetime import datetime, timedelta, timezone
from typing import Any

PLAN_TIMEOUT_ACTIONS = ("park", "approve")
AUTO_TIMEOUT_SOURCE = "auto_timeout"
_RECOMMENDED_SUFFIXES = ("（推荐）", "(推荐)", "(Recommended)", "(recommended)")


def normalize_plan_timeout_action(value: Any) -> str:
    text = str(value or "").strip().lower()
    return text if text in PLAN_TIMEOUT_ACTIONS else "park"


def is_recommended(option: Any) -> bool:
    if isinstance(option, dict):
        if option.get("recommended") is True:
            return True
        label = str(option.get("label") or "")
    else:
        label = str(option or "")
    return label.rstrip().endswith(_RECOMMENDED_SUFFIXES)


def _label(option: Any) -> str:
    return str(option.get("label") if isinstance(option, dict) else option or "").strip()


def recommended_answers(questions: list[Any]) -> list[dict[str, Any]]:
    """The answers a card falls back to: recommended picks, otherwise skipped."""
    answers = []
    for question in questions or []:
        if not isinstance(question, dict):
            continue
        options = question.get("options") if isinstance(question.get("options"), list) else []
        picks = [_label(option) for option in options if is_recommended(option) and _label(option)]
        if not question.get("multiSelect"):
            picks = picks[:1]
        answers.append(
            {
                "header": str(question.get("header") or ""),
                "question": str(question.get("question") or ""),
                "selected": picks,
                "other": "",
                "skipped": not picks,
                "auto": True,
            }
        )
    return answers


class InteractionDeadline:
    """A wait's deadline on the monotonic clock, plus its wall-clock form for the card."""

    def __init__(self, seconds: int) -> None:
        self.seconds = max(0, int(seconds or 0))
        self._at = time.monotonic() + self.seconds if self.seconds else None
        self.iso = (
            (datetime.now(timezone.utc) + timedelta(seconds=self.seconds)).isoformat(timespec="seconds")
            if self.seconds
            else ""
        )

    @property
    def expired(self) -> bool:
        return self._at is not None and time.monotonic() >= self._at


def question_deadline(cfg: Any) -> InteractionDeadline:
    return InteractionDeadline(int(getattr(cfg, "dataagent_interaction_timeout_seconds", 0) or 0))


def plan_deadline(cfg: Any, agent_snapshot: dict[str, Any] | None) -> InteractionDeadline:
    """A plan only has a deadline when the agent approves it on timeout; parked plans wait."""
    action = normalize_plan_timeout_action((agent_snapshot or {}).get("plan_timeout_action"))
    if action != "approve":
        return InteractionDeadline(0)
    return question_deadline(cfg)
