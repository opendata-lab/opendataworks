"""Contract tests for the data-development write surface (Stage 3)."""
from __future__ import annotations

import sys
from pathlib import Path

import pytest
from pydantic import ValidationError

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from odw_mcp_server import app as app_module
from odw_mcp_server.app import PublishWorkflowInput, ScheduleOnlineInput, build_mcp_server
from odw_mcp_server.service import OdwToolService


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
    service = OdwToolService(backend)

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
    await service.upsert_schedule(3, {"scheduleCron": "0 0 * * *", "enabled": True})
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
    assert backend.calls[4] == ("create_workflow", ({"workflow": {"workflowName": "wf"}},), {})
    assert backend.calls[5] == ("update_workflow", (3, {"workflow": {"workflowName": "wf2"}}), {})
    assert backend.calls[10] == ("upsert_schedule", (3, {"schedule": {"scheduleCron": "0 0 * * *"}}), {})


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
    mcp = build_mcp_server(OdwToolService(RecordingBackend()))
    tools = await mcp.list_tools()
    names = {t.name for t in tools}
    for expected in {
        "odw_preview_create_table",
        "odw_create_table",
        "odw_create_task",
        "odw_update_task",
        "odw_get_task",
        "odw_list_tasks",
        "odw_create_workflow",
        "odw_update_workflow",
        "odw_get_workflow",
        "odw_list_workflows",
        "odw_preview_publish",
        "odw_publish_workflow",
        "odw_upsert_schedule",
        "odw_workflow_schedule_online",
        "odw_workflow_schedule_offline",
        "odw_analyze_sql",
        "odw_update_table_metadata",
    }:
        assert expected in names, f"missing tool {expected}"
    # read tools still present
    assert "odw_search_tables" in names


def test_create_table_input_serializes_to_camel_case():
    from odw_mcp_server.app import CreateTableInput

    inp = CreateTableInput(
        db_name="dwd",
        layer="dwd",
        update_type="di",
        table_model="DUPLICATE",
        bucket_num=10,
        replica_num=3,
        key_columns=["dt", "order_id"],
        distribution_columns=["order_id"],
        doris_cluster_id=5,
        columns=[{"column_name": "order_id", "data_type": "BIGINT", "comment": "订单ID"}],
    )
    payload = inp.model_dump(by_alias=True, exclude_none=True)

    # Backend (AgentTableCreateRequest / TableCreateRequest) expects camelCase.
    assert payload["dbName"] == "dwd"
    assert payload["bucketNum"] == 10
    assert payload["replicaNum"] == 3
    assert payload["keyColumns"] == ["dt", "order_id"]
    assert payload["distributionColumns"] == ["order_id"]
    assert payload["dorisClusterId"] == 5
    assert payload["columns"][0]["columnName"] == "order_id"
    assert payload["columns"][0]["dataType"] == "BIGINT"
    # snake_case must not leak into the backend payload.
    assert "db_name" not in payload
    assert "column_name" not in payload["columns"][0]


def test_create_table_requires_at_least_one_column():
    from odw_mcp_server.app import CreateTableInput

    with pytest.raises(ValidationError):
        CreateTableInput(db_name="dwd", columns=[])


@pytest.mark.anyio
async def test_create_table_service_methods_delegate_to_backend():
    backend = RecordingBackend()
    service = OdwToolService(backend)

    await service.preview_create_table({"dbName": "dwd", "columns": []})
    await service.create_table({"dbName": "dwd", "columns": [], "dorisClusterId": 5})

    names = [c[0] for c in backend.calls]
    assert names == ["preview_create_table", "create_table"]


def test_operator_contextvar_propagates_into_request_headers(monkeypatch):
    # The backend client must attach X-Agent-Operator when the contextvar is set.
    from odw_mcp_server import identity_context

    reset = identity_context.set_operator_header("agent:topic-1")
    try:
        assert identity_context.get_operator_header() == "agent:topic-1"
    finally:
        reset()
    assert identity_context.get_operator_header() == ""


async def _call_tool(mcp, name, arguments):
    """Invoke a registered MCP tool by name with raw arguments."""
    tools = {t.name: t for t in await mcp.list_tools()}
    assert name in tools, f"missing tool {name}"
    # Registered tools take a single `params` model, so raw args nest under it.
    return await mcp._tool_manager.call_tool(name, {"params": arguments})


@pytest.mark.anyio
async def test_update_task_omitting_lineage_leaves_keys_absent():
    """Omitted lineage must be truly absent from the JSON payload.

    The backend distinguishes null ("keep existing lineage") from [] ("clear
    that side"). Sending [] for an omitted field silently wipes lineage, which
    is the bug this contract prevents.
    """
    backend = RecordingBackend()
    mcp = build_mcp_server(OdwToolService(backend))

    await _call_tool(mcp, "odw_update_task", {"task_id": 7, "task": {"taskName": "t"}})

    name, args, _ = backend.calls[-1]
    assert name == "update_task"
    payload = args[1]
    assert "inputTableIds" not in payload
    assert "outputTableIds" not in payload


@pytest.mark.anyio
async def test_update_task_keeps_explicit_empty_list_as_clear_intent():
    backend = RecordingBackend()
    mcp = build_mcp_server(OdwToolService(backend))

    await _call_tool(
        mcp,
        "odw_update_task",
        {"task_id": 7, "task": {"taskName": "t"}, "input_table_ids": []},
    )

    payload = backend.calls[-1][1][1]
    assert payload["inputTableIds"] == []
    # the untouched side must stay absent, not become []
    assert "outputTableIds" not in payload


@pytest.mark.anyio
async def test_update_task_forwards_explicit_lists_as_full_replacement():
    backend = RecordingBackend()
    mcp = build_mcp_server(OdwToolService(backend))

    await _call_tool(
        mcp,
        "odw_update_task",
        {
            "task_id": 7,
            "task": {"taskName": "t"},
            "input_table_ids": [1, 2],
            "output_table_ids": [9],
        },
    )

    payload = backend.calls[-1][1][1]
    assert payload["inputTableIds"] == [1, 2]
    assert payload["outputTableIds"] == [9]


def test_update_task_lineage_fields_are_optional():
    from odw_mcp_server.app import UpdateTaskInput

    inp = UpdateTaskInput(task_id=7, task={"taskName": "t"})
    assert inp.input_table_ids is None
    assert inp.output_table_ids is None
    assert "input_table_ids" not in inp.model_fields_set


def test_create_task_requires_both_lineage_fields():
    from odw_mcp_server.app import CreateTaskInput

    # Creation has no prior lineage to fall back on, so both sides must be stated.
    with pytest.raises(ValidationError):
        CreateTaskInput(task={"taskName": "t"})
    with pytest.raises(ValidationError):
        CreateTaskInput(task={"taskName": "t"}, input_table_ids=[1])

    ok = CreateTaskInput(task={"taskName": "t"}, input_table_ids=[], output_table_ids=[9])
    assert ok.input_table_ids == []
    assert ok.output_table_ids == [9]


def test_update_table_metadata_requires_locator():
    from odw_mcp_server.app import UpdateTableMetadataInput

    # Neither table_id nor database+table -> invalid.
    with pytest.raises(ValidationError):
        UpdateTableMetadataInput(table_comment="x")
    with pytest.raises(ValidationError):
        UpdateTableMetadataInput(database="dwd", table_comment="x")  # table missing

    ok = UpdateTableMetadataInput(table_id=1, table_comment="订单明细")
    assert ok.table_id == 1
    ok2 = UpdateTableMetadataInput(database="dwd", table="dwd_order", table_comment="x")
    assert ok2.table == "dwd_order"


def test_update_table_metadata_serializes_to_camel_case():
    from odw_mcp_server.app import UpdateTableMetadataInput

    inp = UpdateTableMetadataInput(
        table_id=42,
        table_comment="订单明细表",
        attributes={"layer": "DWD", "business_domain": "TRADE", "data_domain": "ORDER"},
        fields=[{"field_name": "etl_time", "comment": "ETL 加载时间"}],
        freshness={
            "loaded_at_field": "etl_time",
            "warn_after_count": 1,
            "warn_after_period": "day",
            "error_after_count": 1,
            "error_after_period": "day",
        },
    )
    payload = inp.model_dump(by_alias=True, exclude_none=True)

    assert payload["tableId"] == 42
    assert payload["tableComment"] == "订单明细表"
    assert payload["attributes"]["businessDomain"] == "TRADE"
    assert payload["attributes"]["dataDomain"] == "ORDER"
    assert payload["fields"][0]["fieldName"] == "etl_time"
    assert payload["freshness"]["loadedAtField"] == "etl_time"
    assert payload["freshness"]["warnAfterCount"] == 1
    # snake_case must not leak into the backend payload
    assert "business_domain" not in payload["attributes"]
    assert "loaded_at_field" not in payload["freshness"]


@pytest.mark.anyio
async def test_update_table_metadata_tool_forwards_camel_payload():
    backend = RecordingBackend()
    mcp = build_mcp_server(OdwToolService(backend))

    await _call_tool(
        mcp,
        "odw_update_table_metadata",
        {
            "table_id": 7,
            "attributes": {"layer": "DWD"},
            "freshness": {"loaded_at_field": "etl_time"},
        },
    )

    name, args, _ = backend.calls[-1]
    assert name == "update_table_metadata"
    payload = args[0]
    assert payload["tableId"] == 7
    assert payload["attributes"]["layer"] == "DWD"
    assert payload["freshness"]["loadedAtField"] == "etl_time"
