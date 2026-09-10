from __future__ import annotations

import sys
from pathlib import Path

import httpx
import pytest
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client
from starlette.testclient import TestClient

SERVICE_ROOT = Path(__file__).resolve().parents[1]
if str(SERVICE_ROOT) not in sys.path:
    sys.path.insert(0, str(SERVICE_ROOT))

from portal_mcp.app import build_mcp_server, create_app
from portal_mcp.backend_client import BackendApiError
from portal_mcp.config import Settings
from portal_mcp.service import PortalToolService


class FakeBackendClient:
    def __init__(self):
        self.calls: list[tuple[str, dict]] = []

    async def inspect(self, **params):
        self.calls.append(("inspect", params))
        return {"kind": "metadata_snapshot", "database": params.get("database"), "table_count": 1, "tables": []}

    async def lineage(self, **params):
        self.calls.append(("lineage", params))
        return {"kind": "lineage_snapshot", "table": params.get("table"), "lineage": []}

    async def resolve_datasource(self, **params):
        self.calls.append(("resolve_datasource", params))
        return {"engine": "mysql", "database": params.get("database")}

    async def export_metadata(self, **params):
        self.calls.append(("export_metadata", params))
        return [{"kind": params.get("kind"), "db_name": params.get("database")}]

    async def get_table_ddl(self, **params):
        self.calls.append(("get_table_ddl", params))
        return {"kind": "table_ddl", "database": params.get("database"), "ddl": "CREATE TABLE demo (...)"}

    async def query_readonly(self, payload):
        self.calls.append(("query_readonly", payload))
        return {"kind": "query_result", "database": payload.get("database"), "rows": [{"value": 1}], "row_count": 1}


class FailingBackendClient(FakeBackendClient):
    async def query_readonly(self, payload):
        raise BackendApiError("backend rejected query", status_code=400)


def _settings() -> Settings:
    return Settings(
        backend_base_url="http://backend:8080/api",
        backend_service_token="service-token",
        backend_token_header_name="X-Agent-Service-Token",
        backend_timeout_seconds=30,
        frontdoor_token="portal-token",
        frontdoor_token_header_name="X-Portal-MCP-Token",
        host="0.0.0.0",
        port=8801,
        mcp_mount_path="/mcp",
    )


def test_health_does_not_require_frontdoor_token():
    app = create_app(settings=_settings(), backend_client=FakeBackendClient())
    with TestClient(app) as client:
        response = client.get("/health")

    assert response.status_code == 200
    assert response.json()["status"] == "ok"


def test_mcp_path_rejects_missing_frontdoor_token():
    app = create_app(settings=_settings(), backend_client=FakeBackendClient())
    with TestClient(app) as client:
        response = client.post("/mcp", json={})

    assert response.status_code == 401
    assert response.json()["message"] == "portal mcp token 无效"


def test_mcp_path_accepts_valid_frontdoor_token():
    app = create_app(settings=_settings(), backend_client=FakeBackendClient())
    with TestClient(app) as client:
        response = client.post("/mcp", headers={"X-Portal-MCP-Token": "portal-token"}, json={})

    assert response.status_code != 401


def test_build_mcp_server_is_stateless():
    # Stateless mode is what keeps the server from tracking/expiring per-client
    # sessions: with no server-side session there is no `Mcp-Session-Id` to go
    # stale, so the `MCP server "portal" session expired` 404/session-invalid path
    # cannot occur. Assert the flag on the real server object, not via an abstraction.
    mcp = build_mcp_server(PortalToolService(FakeBackendClient()))
    assert mcp.settings.stateless_http is True


def test_mcp_path_accepts_docker_hostname():
    # Regression: FastMCP 1.x DNS-rebinding protection defaults to
    # localhost-only and returns 421 for Host: portal-mcp:8801.
    # The fix disables that check so Claude CLI subprocesses can connect.
    app = create_app(settings=_settings(), backend_client=FakeBackendClient())
    with TestClient(app, base_url="http://portal-mcp:8801") as client:
        response = client.post(
            "/mcp/",
            headers={
                "X-Portal-MCP-Token": "portal-token",
                "Content-Type": "application/json",
                # Streamable HTTP requires the client to accept both; sending it
                # makes initialize return 200 so the no-session-id assertion below
                # is meaningful (a stateful server would emit Mcp-Session-Id here).
                "Accept": "application/json, text/event-stream",
            },
            json={
                "jsonrpc": "2.0",
                "id": 1,
                "method": "initialize",
                "params": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": {},
                    "clientInfo": {"name": "claude-code", "version": "1.0"},
                },
            },
        )

    assert response.status_code != 421, "MCP server must not reject Docker service hostname"
    assert response.status_code != 401
    # Stateless server must not hand out a session id on a successful initialize;
    # if it did, the client could later send a stale id and hit session-expired.
    assert response.status_code == 200
    assert "mcp-session-id" not in response.headers


@pytest.mark.anyio
async def test_all_tool_service_methods_return_backend_shapes():
    service = PortalToolService(FakeBackendClient())

    search = await service.search_tables({"database": "opendataworks", "table": "workflow_publish_record"})
    lineage = await service.get_lineage({"table": "ads_sales_di"})
    datasource = await service.resolve_datasource({"database": "opendataworks"})
    exported = await service.export_metadata({"kind": "tables", "database": "opendataworks"})
    ddl = await service.get_table_ddl({"database": "opendataworks", "table": "workflow_publish_record"})
    query = await service.query_readonly({"database": "opendataworks", "sql": "SELECT 1"})

    assert search["kind"] == "metadata_snapshot"
    assert lineage["kind"] == "lineage_snapshot"
    assert datasource["engine"] == "mysql"
    assert exported[0]["kind"] == "tables"
    assert ddl["kind"] == "table_ddl"
    assert query["kind"] == "query_result"
    assert query["row_count"] == 1


@pytest.mark.anyio
async def test_backend_error_is_mapped_to_runtime_error():
    service = PortalToolService(FailingBackendClient())

    with pytest.raises(RuntimeError, match="backend rejected query"):
        await service.query_readonly({"database": "opendataworks", "sql": "SELECT 1"})


async def _call_mcp_tool(app, *, tool_name: str, arguments: dict) -> tuple[object, list[str]]:
    transport = httpx.ASGITransport(app=app)
    async with app.router.lifespan_context(app):
        async with httpx.AsyncClient(
            transport=transport,
            base_url="http://localhost:8801",
            headers={"X-Portal-MCP-Token": "portal-token"},
        ) as http_client:
            async with streamable_http_client("http://localhost:8801/mcp/", http_client=http_client) as (read_stream, write_stream, _):
                async with ClientSession(read_stream, write_stream) as session:
                    await session.initialize()
                    tools = await session.list_tools()
                    result = await session.call_tool(tool_name, {"params": arguments})
                    return result, [item.name for item in tools.tools]


@pytest.mark.anyio
async def test_mcp_tools_are_exposed_and_parameter_mapping_is_preserved():
    backend = FakeBackendClient()

    _, tool_names = await _call_mcp_tool(
        create_app(settings=_settings(), backend_client=backend),
        tool_name="portal_search_tables",
        arguments={"database": "opendataworks", "keyword": "发布", "table_limit": 2},
    )
    # Read tools remain exposed (write tools from the data-dev surface are
    # additionally registered and covered in test_write_tools.py).
    assert {
        "portal_search_tables",
        "portal_get_lineage",
        "portal_resolve_datasource",
        "portal_export_metadata",
        "portal_get_table_ddl",
        "portal_query_readonly",
    }.issubset(set(tool_names))
    assert backend.calls[-1] == (
        "inspect",
        {"database": "opendataworks", "keyword": "发布", "tableLimit": 2},
    )

    await _call_mcp_tool(
        create_app(settings=_settings(), backend_client=backend),
        tool_name="portal_get_lineage",
        arguments={"table": "ads_sales_di", "db_name": "dw", "table_id": 7, "depth": 2},
    )
    assert backend.calls[-1] == (
        "lineage",
        {"table": "ads_sales_di", "dbName": "dw", "tableId": 7, "depth": 2},
    )

    await _call_mcp_tool(
        create_app(settings=_settings(), backend_client=backend),
        tool_name="portal_resolve_datasource",
        arguments={"database": "dw", "preferred_engine": "mysql"},
    )
    assert backend.calls[-1] == (
        "resolve_datasource",
        {"database": "dw", "preferredEngine": "mysql"},
    )

    await _call_mcp_tool(
        create_app(settings=_settings(), backend_client=backend),
        tool_name="portal_get_table_ddl",
        arguments={"table_id": 42},
    )
    assert backend.calls[-1] == (
        "get_table_ddl",
        {"tableId": 42},
    )

    result, _ = await _call_mcp_tool(
        create_app(settings=_settings(), backend_client=backend),
        tool_name="portal_query_readonly",
        arguments={
            "database": "dw",
            "sql": "SELECT 1",
            "preferred_engine": "doris",
            "limit": 5000,
            "timeout_seconds": 15,
        },
    )
    assert backend.calls[-1] == (
        "query_readonly",
        {
            "database": "dw",
            "sql": "SELECT 1",
            "preferredEngine": "doris",
            "limit": 5000,
            "timeoutSeconds": 15,
        },
    )
    assert result.structuredContent["kind"] == "query_result"


@pytest.mark.anyio
async def test_query_readonly_forwards_default_limit():
    backend = FakeBackendClient()

    result, _ = await _call_mcp_tool(
        create_app(settings=_settings(), backend_client=backend),
        tool_name="portal_query_readonly",
        arguments={
            "database": "dw",
            "sql": "SELECT 1",
        },
    )

    assert backend.calls[-1] == (
        "query_readonly",
        {
            "database": "dw",
            "sql": "SELECT 1",
            "limit": 1000,
            "timeoutSeconds": 30,
        },
    )
    assert result.structuredContent["kind"] == "query_result"


@pytest.mark.anyio
async def test_published_tool_schemas_contain_no_ref_indirection():
    """Every published schema must be self-contained.

    FastMCP derives schemas from `params: SomeModel` signatures, which parks the
    real fields under `$defs` and leaves `properties.params` as a `$ref`.
    Clients that rebuild the schema keeping only type/properties/required — the
    pi-ai non-strict Anthropic adapter does — drop `$defs` and hand the model a
    dangling pointer, so it cannot see field names and guesses them.
    """
    import json

    mcp = build_mcp_server(PortalToolService(FakeBackendClient()))
    tools = await mcp.list_tools()
    assert tools, "expected registered tools"

    offenders = []
    for tool in tools:
        text = json.dumps(tool.inputSchema)
        if '"$ref"' in text or "$defs" in tool.inputSchema:
            offenders.append(tool.name)
    assert not offenders, f"tools still publish $ref/$defs indirection: {offenders}"


@pytest.mark.anyio
async def test_inlined_schema_keeps_fields_and_extra_forbid():
    """Inlining must not weaken what the schema promises."""
    mcp = build_mcp_server(PortalToolService(FakeBackendClient()))
    tools = {tool.name: tool for tool in await mcp.list_tools()}

    params = tools["portal_get_table_ddl"].inputSchema["properties"]["params"]
    assert set(params["properties"]) == {"database", "table", "table_id"}
    # extra="forbid" survives as additionalProperties:false, which is what stops
    # a guessed field like table_name from being silently accepted.
    assert params["additionalProperties"] is False
    assert params["properties"]["database"]["description"]


@pytest.mark.anyio
async def test_published_schemas_have_no_ref_at_any_depth():
    """Recursively, not just at the top level.

    A dangling pointer nested inside array items or a sub-object is just as
    unusable to the model as one on the root.
    """
    import json

    mcp = build_mcp_server(PortalToolService(FakeBackendClient()))
    tools = await mcp.list_tools()
    assert tools

    offenders = [
        tool.name
        for tool in tools
        if '"$ref"' in json.dumps(tool.inputSchema) or '"$defs"' in json.dumps(tool.inputSchema)
    ]
    assert not offenders, f"tools still publish $ref/$defs: {offenders}"


@pytest.mark.anyio
async def test_inlined_schema_keeps_fields_and_extra_forbid():
    """Inlining must not weaken what the schema promises."""
    mcp = build_mcp_server(PortalToolService(FakeBackendClient()))
    tools = {tool.name: tool for tool in await mcp.list_tools()}

    params = tools["portal_get_table_ddl"].inputSchema["properties"]["params"]
    assert set(params["properties"]) == {"database", "table", "table_id"}
    # extra="forbid" survives as additionalProperties:false — this is what stops
    # a guessed field like table_name from being silently accepted.
    assert params["additionalProperties"] is False
    assert params["properties"]["database"]["description"]


@pytest.mark.anyio
async def test_nested_model_is_expanded_with_real_content():
    """Asserting 'no $ref' alone would also pass if the ref became {}."""
    mcp = build_mcp_server(PortalToolService(FakeBackendClient()))
    tools = {tool.name: tool for tool in await mcp.list_tools()}
    create = tools.get("portal_create_table")
    if create is None:
        pytest.skip("portal_create_table not registered")

    params = create.inputSchema["properties"]["params"]
    columns = params["properties"]["columns"]
    item = columns["items"] if "items" in columns else columns["anyOf"][0]["items"]
    assert item["properties"], "nested column model lost its fields"
    # Aliases must survive: the backend payload depends on camelCase names.
    assert "columnName" in item["properties"] or "column_name" in item["properties"]
    assert item.get("additionalProperties") is False


def test_inliner_refuses_ref_with_sibling_keys():
    """Under 2020-12 a $ref and its siblings are a conjunction.

    Merging by dict-update would let a sibling silently relax the target's
    constraint, so this shape must fail loudly instead.
    """
    from portal_mcp.app import SchemaInlineError, _inline_refs

    schema = {
        "$defs": {"S": {"type": "string", "minLength": 5}},
        "properties": {"x": {"$ref": "#/$defs/S", "minLength": 2}},
        "type": "object",
    }
    with pytest.raises(SchemaInlineError, match="sibling"):
        _inline_refs(schema, schema)


def test_inliner_refuses_recursive_and_unresolvable_refs():
    """Both would otherwise publish a schema promising less than the model."""
    from portal_mcp.app import SchemaInlineError, _inline_refs

    recursive = {
        "$defs": {"Node": {"type": "object", "properties": {"child": {"$ref": "#/$defs/Node"}}}},
        "properties": {"root": {"$ref": "#/$defs/Node"}},
        "type": "object",
    }
    with pytest.raises(SchemaInlineError, match="recursive"):
        _inline_refs(recursive, recursive)

    dangling = {"$defs": {}, "properties": {"x": {"$ref": "#/$defs/Missing"}}, "type": "object"}
    with pytest.raises(SchemaInlineError):
        _inline_refs(dangling, dangling)


def test_inliner_refuses_boolean_schema_target():
    """true/false are valid JSON Schema documents, but not a shape we rewrite."""
    from portal_mcp.app import SchemaInlineError, _inline_refs

    schema = {"$defs": {"B": True}, "properties": {"x": {"$ref": "#/$defs/B"}}, "type": "object"}
    with pytest.raises(SchemaInlineError, match="expected an object schema"):
        _inline_refs(schema, schema)


@pytest.mark.anyio
async def test_calls_still_enforce_pydantic_semantics_after_inlining():
    """The published schema is a separate object from the one that validates.

    Tool calls go through fn_metadata + the original model, so extra="forbid"
    and cross-field validators must still reject bad input.
    """
    mcp = build_mcp_server(PortalToolService(FakeBackendClient()))

    with pytest.raises(Exception):
        # table_name is not a field; extra="forbid" must reject it.
        await mcp.call_tool(
            "portal_get_table_ddl", {"params": {"database": "public", "table_name": "t"}}
        )

    with pytest.raises(Exception):
        # Cross-field validator: table alone is not a valid locator.
        await mcp.call_tool("portal_get_table_ddl", {"params": {"table": "t"}})
