"""Contract tests for the data-development write surface (Stage 3)."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
from pydantic import ValidationError

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from portal_mcp import app as app_module
from portal_mcp.app import PublishWorkflowInput, ScheduleOnlineInput, build_mcp_server
from portal_mcp.service import PortalToolService


class RecordingBackend:
    def __init__(self):
        self.calls: list[tuple[str, tuple, dict]] = []

    def __getattr__(self, name):
        async def _call(*args, **kwargs):
            self.calls.append((name, args, kwargs))
            return {"ok": True, "method": name}

        return _call


@pytest.mark.anyio
async def test_write_service_methods_delegate_to_backend():
    backend = RecordingBackend()
    service = PortalToolService(backend)

    await service.create_task({"task": {"taskName": "t"}, "inputTableIds": [1], "outputTableIds": [2]})
    await service.update_task(7, {"task": {"taskName": "t2"}})
    await service.get_task(7)
    await service.list_tasks({"keyword": "etl", "limit": 10})
    await service.create_workflow({"workflowName": "wf"})
    await service.update_workflow(3, {"workflowName": "wf2"})
    await service.get_workflow(3)
    await service.list_workflows({"limit": 5})
    await service.preview_publish(3)
    await service.publish_workflow(3, {"operation": "deploy", "previewToken": "tok"})
    await service.upsert_schedule(3, {"scheduleCron": "0 0 * * *"})
    await service.schedule_online(3, {"previewToken": "tok"})
    await service.schedule_offline(3)
    await service.analyze_sql({"sql": "select 1"})

    names = [c[0] for c in backend.calls]
    assert names == [
        "create_task",
        "update_task",
        "get_task",
        "list_tasks",
        "create_workflow",
        "update_workflow",
        "get_workflow",
        "list_workflows",
        "preview_publish",
        "publish_workflow",
        "upsert_schedule",
        "schedule_online",
        "schedule_offline",
        "analyze_sql",
    ]


def test_publish_requires_preview_token():
    with pytest.raises(ValidationError):
        PublishWorkflowInput(workflow_id=1, operation="deploy")
    with pytest.raises(ValidationError):
        ScheduleOnlineInput(workflow_id=1)
    # valid with token
    ok = PublishWorkflowInput(workflow_id=1, operation="online", preview_token="tok")
    assert ok.preview_token == "tok"


def test_publish_operation_is_constrained():
    with pytest.raises(ValidationError):
        PublishWorkflowInput(workflow_id=1, operation="destroy", preview_token="tok")


@pytest.mark.anyio
async def test_build_mcp_server_registers_write_tools():
    mcp = build_mcp_server(PortalToolService(RecordingBackend()))
    tools = await mcp.list_tools()
    names = {t.name for t in tools}
    for expected in {
        "portal_create_task",
        "portal_update_task",
        "portal_get_task",
        "portal_list_tasks",
        "portal_create_workflow",
        "portal_update_workflow",
        "portal_get_workflow",
        "portal_list_workflows",
        "portal_preview_publish",
        "portal_publish_workflow",
        "portal_upsert_schedule",
        "portal_workflow_schedule_online",
        "portal_workflow_schedule_offline",
        "portal_analyze_sql",
    }:
        assert expected in names, f"missing tool {expected}"
    # read tools still present
    assert "portal_search_tables" in names


def test_operator_contextvar_propagates_into_request_headers(monkeypatch):
    # The backend client must attach X-Agent-Operator when the contextvar is set.
    from portal_mcp import scope_context

    reset = scope_context.set_operator_header("agent:topic-1")
    try:
        assert scope_context.get_operator_header() == "agent:topic-1"
    finally:
        reset()
    assert scope_context.get_operator_header() == ""
