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
from .scope_context import set_data_scope_header, set_operator_header
from .service import PortalToolService


class SearchTablesInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    database: str | None = Field(default=None, description="数据库名，可选")
    table: str | None = Field(default=None, description="表名，可选")
    keyword: str | None = Field(default=None, description="表注释、字段注释或字段名关键字，可选")
    table_limit: int = Field(default=12, ge=1, le=100, description="返回表数量上限")


class LineageInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    table: str | None = Field(default=None, description="表名，可选")
    db_name: str | None = Field(default=None, description="数据库名，可选")
    table_id: int | None = Field(default=None, description="表 ID，可选")
    depth: int | None = Field(default=None, ge=1, le=10, description="血缘深度，可选")

    @model_validator(mode="after")
    def validate_target(self) -> "LineageInput":
        if self.table_id is None and not self.table:
            raise ValueError("table 或 table_id 至少提供一个")
        return self


class ResolveDatasourceInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    database: str = Field(..., description="数据库名")
    preferred_engine: Literal["mysql", "doris"] | None = Field(default=None, description="期望引擎，可选")


class ExportMetadataInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    kind: Literal["tables", "lineage", "datasource"] = Field(..., description="导出类型")
    database: str | None = Field(default=None, description="数据库过滤条件，可选")


class TableDdlInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    database: str | None = Field(default=None, description="数据库名，可选")
    table: str | None = Field(default=None, description="表名，可选")
    table_id: int | None = Field(default=None, description="表 ID，可选")

    @model_validator(mode="after")
    def validate_locator(self) -> "TableDdlInput":
        if self.table_id is None and (not self.database or not self.table):
            raise ValueError("table_id 或 database + table 至少提供一组")
        return self


class QueryReadonlyInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    database: str = Field(..., description="数据库名")
    sql: str = Field(..., min_length=1, description="单条只读 SQL")
    preferred_engine: Literal["mysql", "doris"] | None = Field(default=None, description="期望引擎，可选")
    limit: int = Field(default=1000, ge=1, le=10000, description="结果返回上限")
    timeout_seconds: int = Field(default=30, ge=1, le=120, description="单次查询超时秒数")


# --- data development assistant: write tool inputs ---------------------------
# Nested task/workflow/schedule payloads are forwarded as-is to the backend
# agent API, which owns the authoritative field schema (mirrors the web DTOs).


class CreateTaskInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    task: dict[str, Any] = Field(..., description="任务定义(对齐平台 DataTask 字段,如 taskName/dolphinNodeType/taskSql/datasourceName)")
    input_table_ids: list[int] = Field(default_factory=list, description="输入表 ID 列表(维护血缘)")
    output_table_ids: list[int] = Field(default_factory=list, description="输出表 ID 列表(维护血缘)")


class UpdateTaskInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    task_id: int = Field(..., description="任务 ID")
    task: dict[str, Any] = Field(..., description="任务定义(仅 draft 状态可更新)")
    input_table_ids: list[int] = Field(default_factory=list)
    output_table_ids: list[int] = Field(default_factory=list)


class TaskIdInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    task_id: int = Field(..., description="任务 ID")


class ListInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    keyword: str | None = Field(default=None, description="名称/编码关键字,可选")
    status: str | None = Field(default=None, description="状态过滤,可选")
    limit: int = Field(default=50, ge=1, le=200, description="返回数量上限")


class CreateWorkflowInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workflow: dict[str, Any] = Field(..., description="工作流定义(对齐 WorkflowDefinitionRequest:workflowName/tasks/edges/globalParams 等)")


class UpdateWorkflowInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workflow_id: int = Field(..., description="工作流 ID")
    workflow: dict[str, Any] = Field(..., description="工作流结构(draft 状态)")


class WorkflowIdInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workflow_id: int = Field(..., description="工作流 ID")


class PublishWorkflowInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    workflow_id: int = Field(..., description="工作流 ID")
    operation: Literal["deploy", "online", "offline"] = Field(..., description="发布操作")
    preview_token: str = Field(..., min_length=1, description="发布预览返回的一次性凭证;deploy/online 必填,确保已先预览")


class UpsertScheduleInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workflow_id: int = Field(..., description="工作流 ID")
    schedule: dict[str, Any] = Field(..., description="调度配置(对齐 WorkflowScheduleRequest:scheduleCron/timezone 等)")


class ScheduleOnlineInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    workflow_id: int = Field(..., description="工作流 ID")
    preview_token: str = Field(..., min_length=1, description="发布预览返回的一次性凭证;调度上线必填")


class AnalyzeSqlInput(BaseModel):
    model_config = ConfigDict(extra="forbid", str_strip_whitespace=True)

    sql: str = Field(..., min_length=1, description="待分析 SQL")
    database: str | None = Field(default=None, description="数据库名,可选")
    cluster_id: int | None = Field(default=None, description="集群 ID,可选")


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
        reset_operator = set_operator_header(headers.get("x-agent-operator", ""))
        try:
            await self.app(scope, receive, send)
        finally:
            reset_operator()
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
        payload = params.model_dump(exclude_none=True)
        payload["tableLimit"] = payload.pop("table_limit")
        return await service.search_tables(payload)

    @mcp.tool(
        name="portal_get_lineage",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_get_lineage(params: LineageInput) -> dict[str, Any]:
        """Get lineage information for a table by table name or table id."""
        payload = params.model_dump(exclude_none=True)
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
        payload = params.model_dump(exclude_none=True)
        if "preferred_engine" in payload:
            payload["preferredEngine"] = payload.pop("preferred_engine")
        return await service.resolve_datasource(payload)

    @mcp.tool(
        name="portal_export_metadata",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_export_metadata(params: ExportMetadataInput) -> list[dict[str, Any]]:
        """Export metadata rows for tables, lineage, or datasource records."""
        return await service.export_metadata(params.model_dump(exclude_none=True))

    @mcp.tool(
        name="portal_get_table_ddl",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_get_table_ddl(params: TableDdlInput) -> dict[str, Any]:
        """Get live table DDL together with data-portal metadata summary."""
        payload = params.model_dump(exclude_none=True)
        if "table_id" in payload:
            payload["tableId"] = payload.pop("table_id")
        return await service.get_table_ddl(payload)

    @mcp.tool(
        name="portal_query_readonly",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_query_readonly(params: QueryReadonlyInput) -> dict[str, Any]:
        """Execute a single read-only SQL query through the backend read-only query path."""
        payload = params.model_dump(exclude_none=True)
        if "preferred_engine" in payload:
            payload["preferredEngine"] = payload.pop("preferred_engine")
        if "timeout_seconds" in payload:
            payload["timeoutSeconds"] = payload.pop("timeout_seconds")
        return await service.query_readonly(payload)

    # --- data development assistant: write tools -----------------------------

    @mcp.tool(
        name="portal_create_task",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_create_task(params: CreateTaskInput) -> dict[str, Any]:
        """Create a draft data-development task (e.g. a SQL task). Requires input/output table ids for lineage."""
        return await service.create_task(
            {
                "task": params.task,
                "inputTableIds": params.input_table_ids,
                "outputTableIds": params.output_table_ids,
            }
        )

    @mcp.tool(
        name="portal_update_task",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_update_task(params: UpdateTaskInput) -> dict[str, Any]:
        """Update a draft task definition."""
        return await service.update_task(
            params.task_id,
            {
                "task": params.task,
                "inputTableIds": params.input_table_ids,
                "outputTableIds": params.output_table_ids,
            },
        )

    @mcp.tool(
        name="portal_get_task",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_get_task(params: TaskIdInput) -> dict[str, Any]:
        """Get a task's detail by id."""
        return await service.get_task(params.task_id)

    @mcp.tool(
        name="portal_list_tasks",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_list_tasks(params: ListInput) -> dict[str, Any]:
        """List tasks with optional keyword/status filters."""
        return await service.list_tasks(params.model_dump(exclude_none=True))

    @mcp.tool(
        name="portal_create_workflow",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_create_workflow(params: CreateWorkflowInput) -> dict[str, Any]:
        """Create a draft workflow with its task bindings and edges."""
        return await service.create_workflow(params.workflow)

    @mcp.tool(
        name="portal_update_workflow",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_update_workflow(params: UpdateWorkflowInput) -> dict[str, Any]:
        """Update a draft workflow's structure."""
        return await service.update_workflow(params.workflow_id, params.workflow)

    @mcp.tool(
        name="portal_get_workflow",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_get_workflow(params: WorkflowIdInput) -> dict[str, Any]:
        """Get a workflow's detail (tasks, edges, latest instance)."""
        return await service.get_workflow(params.workflow_id)

    @mcp.tool(
        name="portal_list_workflows",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_list_workflows(params: ListInput) -> dict[str, Any]:
        """List workflows with optional keyword/status filters."""
        return await service.list_workflows(params.model_dump(exclude_none=True))

    @mcp.tool(
        name="portal_preview_publish",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_preview_publish(params: WorkflowIdInput) -> dict[str, Any]:
        """Preview a workflow publish: validation, diff, warnings, and a one-time preview_token used by publish/schedule-online."""
        return await service.preview_publish(params.workflow_id)

    @mcp.tool(
        name="portal_publish_workflow",
        annotations={"readOnlyHint": False, "destructiveHint": True, "idempotentHint": False, "openWorldHint": True},
    )
    async def portal_publish_workflow(params: PublishWorkflowInput) -> dict[str, Any]:
        """HIGH-RISK: deploy/online/offline a workflow to the scheduler. Requires user confirmation. Call portal_preview_publish first and pass its preview_token."""
        return await service.publish_workflow(
            params.workflow_id,
            {"operation": params.operation, "previewToken": params.preview_token},
        )

    @mcp.tool(
        name="portal_upsert_schedule",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_upsert_schedule(params: UpsertScheduleInput) -> dict[str, Any]:
        """Create or update a workflow's schedule configuration (cron/timezone/etc.)."""
        return await service.upsert_schedule(params.workflow_id, params.schedule)

    @mcp.tool(
        name="portal_workflow_schedule_online",
        annotations={"readOnlyHint": False, "destructiveHint": True, "idempotentHint": False, "openWorldHint": True},
    )
    async def portal_workflow_schedule_online(params: ScheduleOnlineInput) -> dict[str, Any]:
        """HIGH-RISK: enable a workflow's schedule so it triggers on cron. Requires user confirmation and a preview_token."""
        return await service.schedule_online(params.workflow_id, {"previewToken": params.preview_token})

    @mcp.tool(
        name="portal_workflow_schedule_offline",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_workflow_schedule_offline(params: WorkflowIdInput) -> dict[str, Any]:
        """Disable a workflow's schedule."""
        return await service.schedule_offline(params.workflow_id)

    @mcp.tool(
        name="portal_analyze_sql",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_analyze_sql(params: AnalyzeSqlInput) -> dict[str, Any]:
        """Analyze a SQL statement: input/output tables, operation type, and warnings (for SQL polish and lineage)."""
        payload = params.model_dump(exclude_none=True)
        if "cluster_id" in payload:
            payload["clusterId"] = payload.pop("cluster_id")
        return await service.analyze_sql(payload)

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
