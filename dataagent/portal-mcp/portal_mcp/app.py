from __future__ import annotations

import contextlib
from typing import Any, Literal

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import BaseModel, ConfigDict, Field, model_validator
from starlette.applications import Starlette
from starlette.responses import JSONResponse
from starlette.routing import Mount, Route

from .backend_client import BackendApiClient
from .config import Settings, load_settings
from .scope_context import set_data_scope_header
from .service import PortalToolService


class PortalToolInput(BaseModel):
    """Shared base for portal tool inputs.

    Carries an optional, display-only ``description`` so the agent can attach a
    short natural-language summary of the call's intent. It is surfaced by the
    frontend tool-trace ("执行工具：…") and intentionally never forwarded to the
    backend, so ``to_payload`` strips it from the outgoing request.
    """

    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    description: str | None = Field(
        default=None,
        description="本次工具调用的简短中文意图说明，仅用于前端展示，例如“搜索订单相关表”；不参与查询逻辑。",
    )

    def to_payload(self) -> dict[str, Any]:
        return self.model_dump(exclude_none=True, exclude={"description"})


class SearchTablesInput(PortalToolInput):
    database: str | None = Field(default=None, description="数据库名，可选")
    table: str | None = Field(default=None, description="表名，可选")
    keyword: str | None = Field(default=None, description="表注释、字段注释或字段名关键字，可选")
    table_limit: int = Field(default=12, ge=1, le=100, description="返回表数量上限")


class LineageInput(PortalToolInput):
    table: str | None = Field(default=None, description="表名，可选")
    db_name: str | None = Field(default=None, description="数据库名，可选")
    table_id: int | None = Field(default=None, description="表 ID，可选")
    depth: int | None = Field(default=None, ge=1, le=10, description="血缘深度，可选")

    @model_validator(mode="after")
    def validate_target(self) -> "LineageInput":
        if self.table_id is None and not self.table:
            raise ValueError("table 或 table_id 至少提供一个")
        return self


class ResolveDatasourceInput(PortalToolInput):
    database: str = Field(..., description="数据库名")
    preferred_engine: Literal["mysql", "doris"] | None = Field(default=None, description="期望引擎，可选")


class ExportMetadataInput(PortalToolInput):
    kind: Literal["tables", "lineage", "datasource"] = Field(..., description="导出类型")
    database: str | None = Field(default=None, description="数据库过滤条件，可选")


class TableDdlInput(PortalToolInput):
    database: str | None = Field(default=None, description="数据库名，可选")
    table: str | None = Field(default=None, description="表名，可选")
    table_id: int | None = Field(default=None, description="表 ID，可选")

    @model_validator(mode="after")
    def validate_locator(self) -> "TableDdlInput":
        if self.table_id is None and (not self.database or not self.table):
            raise ValueError("table_id 或 database + table 至少提供一组")
        return self


class QueryReadonlyInput(PortalToolInput):
    database: str = Field(..., description="数据库名")
    sql: str = Field(..., min_length=1, description="单条只读 SQL")
    preferred_engine: Literal["mysql", "doris"] | None = Field(default=None, description="期望引擎，可选")
    limit: int = Field(default=1000, ge=1, le=10000, description="结果返回上限")
    timeout_seconds: int = Field(default=30, ge=1, le=120, description="单次查询超时秒数")


class FrontDoorTokenMiddleware:
    def __init__(self, app, settings: Settings):
        self.app = app
        self.settings = settings

    async def __call__(self, scope, receive, send):
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return

        path = str(scope.get("path") or "")
        method = str(scope.get("method") or "GET").upper()
        if method == "OPTIONS" or path == "/health" or not path.startswith(self.settings.mcp_mount_path):
            await self.app(scope, receive, send)
            return

        if not self.settings.frontdoor_token:
            await _send_json(scope, receive, send, 503, {"success": False, "message": "portal mcp frontdoor token 未配置"})
            return

        headers = {
            key.decode("latin-1").lower(): value.decode("latin-1")
            for key, value in scope.get("headers", [])
        }
        actual = headers.get(self.settings.frontdoor_token_header_name.lower(), "").strip()
        if actual != self.settings.frontdoor_token:
            await _send_json(scope, receive, send, 401, {"success": False, "message": "portal mcp token 无效"})
            return

        reset_scope = set_data_scope_header(headers.get("x-agent-data-scope", ""))
        try:
            await self.app(scope, receive, send)
        finally:
            reset_scope()


def build_mcp_server(service: PortalToolService) -> FastMCP:
    mcp = FastMCP("portal-mcp", json_response=True)
    mcp.settings.streamable_http_path = "/"
    # DNS-rebinding protection defaults to localhost-only in FastMCP 1.x, which
    # rejects requests from the Claude CLI subprocess using the Docker service
    # hostname (e.g. Host: portal-mcp:8801) with HTTP 421. The service is
    # already protected by FrontDoorTokenMiddleware, so disable it here.
    mcp.settings.transport_security = TransportSecuritySettings(enable_dns_rebinding_protection=False)
    # Stateless mode: each request is handled independently with no session-ID
    # handshake required. Correct for a read-only tool server.
    mcp.settings.stateless_http = True

    @mcp.tool(
        name="portal_search_tables",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_search_tables(params: SearchTablesInput) -> dict[str, Any]:
        """Search data-portal tables by database, table name, or comment keyword."""
        payload = params.to_payload()
        payload["tableLimit"] = payload.pop("table_limit")
        return await service.search_tables(payload)

    @mcp.tool(
        name="portal_get_lineage",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_get_lineage(params: LineageInput) -> dict[str, Any]:
        """Get lineage information for a table by table name or table id."""
        payload = params.to_payload()
        if "db_name" in payload:
            payload["dbName"] = payload.pop("db_name")
        if "table_id" in payload:
            payload["tableId"] = payload.pop("table_id")
        return await service.get_lineage(payload)

    @mcp.tool(
        name="portal_resolve_datasource",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_resolve_datasource(params: ResolveDatasourceInput) -> dict[str, Any]:
        """Resolve datasource summary and routing metadata for a database."""
        payload = params.to_payload()
        if "preferred_engine" in payload:
            payload["preferredEngine"] = payload.pop("preferred_engine")
        return await service.resolve_datasource(payload)

    @mcp.tool(
        name="portal_export_metadata",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_export_metadata(params: ExportMetadataInput) -> list[dict[str, Any]]:
        """Export metadata rows for tables, lineage, or datasource records."""
        return await service.export_metadata(params.to_payload())

    @mcp.tool(
        name="portal_get_table_ddl",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_get_table_ddl(params: TableDdlInput) -> dict[str, Any]:
        """Get live table DDL together with data-portal metadata summary."""
        payload = params.to_payload()
        if "table_id" in payload:
            payload["tableId"] = payload.pop("table_id")
        return await service.get_table_ddl(payload)

    @mcp.tool(
        name="portal_query_readonly",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_query_readonly(params: QueryReadonlyInput) -> dict[str, Any]:
        """Execute a single read-only SQL query through the backend read-only query path."""
        payload = params.to_payload()
        if "preferred_engine" in payload:
            payload["preferredEngine"] = payload.pop("preferred_engine")
        if "timeout_seconds" in payload:
            payload["timeoutSeconds"] = payload.pop("timeout_seconds")
        return await service.query_readonly(payload)

    return mcp


def create_app(
    settings: Settings | None = None,
    backend_client: BackendApiClient | None = None,
) -> Starlette:
    effective_settings = settings or load_settings()
    client = backend_client or BackendApiClient(effective_settings)
    service = PortalToolService(client)
    mcp = build_mcp_server(service)

    @contextlib.asynccontextmanager
    async def lifespan(app: Starlette):
        async with mcp.session_manager.run():
            yield

    async def health(_request):
        return JSONResponse({"status": "ok", "service": "portal-mcp"})

    app = Starlette(
        routes=[
            Route("/health", endpoint=health),
            Mount(effective_settings.mcp_mount_path, app=mcp.streamable_http_app()),
        ],
        lifespan=lifespan,
    )
    app.add_middleware(FrontDoorTokenMiddleware, settings=effective_settings)
    return app


app = create_app()


async def _send_json(scope, receive, send, status_code: int, payload: dict[str, Any]) -> None:
    body = JSONResponse(payload, status_code=status_code)
    await body(scope, receive, send)
