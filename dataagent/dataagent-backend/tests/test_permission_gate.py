"""Unit tests for session permission gating policy."""
from __future__ import annotations

from core import permission_gate as pg

PUBLISH = "mcp__portal__portal_publish_workflow"
SCHED_ONLINE = "mcp__portal__portal_workflow_schedule_online"
CREATE_TASK = "mcp__portal__portal_create_task"
ANALYZE = "mcp__portal__portal_analyze_sql"
READ = "mcp__portal__portal_search_tables"


def test_classification_by_bare_and_qualified_names() -> None:
    assert pg.is_high_risk_tool(PUBLISH)
    assert pg.is_high_risk_tool("portal_publish_workflow")
    assert pg.is_write_tool(CREATE_TASK)
    assert not pg.is_high_risk_tool(CREATE_TASK)
    assert not pg.is_write_tool(ANALYZE)
    assert not pg.is_write_tool(READ)


def test_bypass_never_confirms() -> None:
    for tool in (PUBLISH, CREATE_TASK, READ):
        assert pg.requires_confirmation(tool, "bypassPermissions") is False


def test_default_confirms_all_writes() -> None:
    assert pg.requires_confirmation(PUBLISH, "default") is True
    assert pg.requires_confirmation(CREATE_TASK, "default") is True
    assert pg.requires_confirmation(ANALYZE, "default") is False
    assert pg.requires_confirmation(READ, "default") is False


def test_accept_edits_confirms_only_high_risk() -> None:
    assert pg.requires_confirmation(PUBLISH, "acceptEdits") is True
    assert pg.requires_confirmation(SCHED_ONLINE, "acceptEdits") is True
    assert pg.requires_confirmation(CREATE_TASK, "acceptEdits") is False


def test_plan_denies_writes_and_never_confirms() -> None:
    # plan resolves write tools to outright denial, not confirmation.
    assert pg.requires_confirmation(PUBLISH, "plan") is False
    assert pg.plan_denies_tool(PUBLISH) is True
    assert pg.plan_denies_tool(CREATE_TASK) is True
    assert pg.plan_denies_tool(READ) is False


def test_legacy_mode_normalizes_to_default() -> None:
    # legacy 'inherit' / unknown -> default policy
    assert pg.requires_confirmation(CREATE_TASK, "inherit") is True
    assert pg.requires_confirmation(CREATE_TASK, "junk") is True
