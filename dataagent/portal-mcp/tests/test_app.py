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

from portal_mcp.app import create_app
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


def test_mcp_path_accepts_docker_hostname():
    # Regression: FastMCP 1.x DNS-rebinding protection defaults to
    # localhost-only and returns 421 for Host: portal-mcp:8801.
    # The fix disables that check so Claude CLI subprocesses can connect.
    app = create_app(settings=_settings(), backend_client=FakeBackendClient())
    with TestClient(app, base_url="http://portal-mcp:8801") as client:
        response = client.post(
            "/mcp/",
            headers={"X-Portal-MCP-Token": "portal-token", "Content-Type": "application/json"},
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
    assert set(tool_names) == {
        "portal_search_tables",
        "portal_get_lineage",
        "portal_resolve_datasource",
        "portal_export_metadata",
        "portal_get_table_ddl",
        "portal_query_readonly",
    }
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
async def test_display_description_is_accepted_but_not_forwarded():
    # The display-only description must be accepted by the strict (extra="forbid")
    # schema yet stripped from the payload sent to the backend.
    backend = FakeBackendClient()

    await _call_mcp_tool(
        create_app(settings=_settings(), backend_client=backend),
        tool_name="portal_search_tables",
        arguments={"keyword": "发布", "description": "搜索发布相关表"},
    )
    assert backend.calls[-1] == ("inspect", {"keyword": "发布", "tableLimit": 12})

    result, _ = await _call_mcp_tool(
        create_app(settings=_settings(), backend_client=backend),
        tool_name="portal_query_readonly",
        arguments={"database": "dw", "sql": "SELECT 1", "description": "执行只读查询"},
    )
    assert backend.calls[-1] == (
        "query_readonly",
        {"database": "dw", "sql": "SELECT 1", "limit": 1000, "timeoutSeconds": 30},
    )
    assert result.structuredContent["kind"] == "query_result"
