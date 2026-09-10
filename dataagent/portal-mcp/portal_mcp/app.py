from __future__ import annotations

import contextlib
import json
from typing import Any, Literal

from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import BaseModel, ConfigDict, Field, model_validator
from pydantic.alias_generators import to_camel
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


class CreateTableColumnInput(BaseModel):
    model_config = ConfigDict(
        extra="forbid", str_strip_whitespace=True, alias_generator=to_camel, populate_by_name=True
    )

    column_name: str = Field(..., description="列名")
    data_type: str = Field(..., description="数据类型,如 BIGINT/VARCHAR/DECIMAL/DATETIME/STRING")
    type_params: str | None = Field(default=None, description="类型参数,如 '64'(VARCHAR)或 '18,2'(DECIMAL)")
    nullable: bool | None = Field(default=None, description="是否可空,默认可空")
    primary_key: bool | None = Field(default=None, description="是否为 KEY/主键列")
    partition_column: bool | None = Field(default=None, description="是否为分区列")
    default_value: str | None = Field(default=None, description="默认值")
    comment: str | None = Field(default=None, description="列注释")


class CreateTableInput(BaseModel):
    """建表入参,对齐平台建表规范(见 opendataworks-data-dev 技能 reference/40-ddl-standards.md)。表名由分层等组件生成。"""

    model_config = ConfigDict(
        extra="forbid", str_strip_whitespace=True, alias_generator=to_camel, populate_by_name=True
    )

    db_name: str = Field(..., description="目标数据库名")
    columns: list[CreateTableColumnInput] = Field(..., min_length=1, description="列定义(至少一列),每列建议带 comment")
    layer: str | None = Field(default=None, description="数仓分层:ods/dwd/dws/dim/ads,用于生成表名")
    business_domain: str | None = Field(default=None, description="业务域,用于生成表名")
    data_domain: str | None = Field(default=None, description="数据域,用于生成表名")
    custom_identifier: str | None = Field(default=None, description="自定义标识,用于生成表名")
    statistics_cycle: str | None = Field(default=None, description="统计周期,用于生成表名")
    update_type: str | None = Field(default=None, description="刷新方式,如 di(按日增量)/df(按日全量)")
    table_comment: str | None = Field(default=None, description="表注释")
    owner: str | None = Field(default=None, description="负责人,可选(默认取调用者身份)")
    table_model: str | None = Field(default=None, description="Doris 表模型:DUPLICATE(默认/明细)/AGGREGATE/UNIQUE")
    bucket_num: int | None = Field(default=None, ge=1, description="分桶数,默认 10")
    replica_num: int | None = Field(default=None, ge=1, description="副本数,默认 3(本地/测试用 1)")
    partition_column: str | None = Field(default=None, description="分区列(RANGE 分区)")
    distribution_columns: list[str] | None = Field(default=None, description="分桶键(DISTRIBUTED BY HASH)")
    key_columns: list[str] | None = Field(default=None, description="KEY 列(表模型 KEY(...),顺序敏感)")
    doris_cluster_id: int | None = Field(default=None, description="目标 Doris 集群/数据源 ID;执行建表必填,预览可省")
    sync_to_doris: bool | None = Field(default=None, description="是否在引擎执行建表;默认执行,置 false 仅登记元数据")
    doris_ddl: str | None = Field(default=None, description="高级:直接提供完整 Doris CREATE TABLE DDL")


class UpdateTableAttributesInput(BaseModel):
    model_config = ConfigDict(
        extra="forbid", str_strip_whitespace=True, alias_generator=to_camel, populate_by_name=True
    )

    layer: str | None = Field(default=None, description="数仓分层:ODS/DWD/DIM/DWS/ADS(清单外一律丢弃)")
    business_domain: str | None = Field(default=None, description="业务域编码(必须是平台已有编码)")
    data_domain: str | None = Field(default=None, description="数据域编码(必须存在且归属所选业务域)")


class UpdateTableFieldCommentInput(BaseModel):
    model_config = ConfigDict(
        extra="forbid", str_strip_whitespace=True, alias_generator=to_camel, populate_by_name=True
    )

    field_name: str | None = Field(default=None, description="字段名(与 field_id 二选一)")
    field_id: int | None = Field(default=None, description="字段 ID(与 field_name 二选一)")
    comment: str = Field(..., description="字段业务注释")


class UpdateTableFreshnessInput(BaseModel):
    """数据新鲜度契约,对齐平台 TableFreshnessRequest;mode 默认 column。"""

    model_config = ConfigDict(
        extra="forbid", str_strip_whitespace=True, alias_generator=to_camel, populate_by_name=True
    )

    mode: Literal["column", "custom_sql", "metadata"] | None = Field(default=None, description="取值方式,默认 column")
    loaded_at_field: str | None = Field(default=None, description="column 模式:取最大值的时间列(必须是真实字段)")
    loaded_at_query: str | None = Field(default=None, description="custom_sql 模式:返回最新时间的 SQL")
    filter_expr: str | None = Field(default=None, description="可选 WHERE 谓词")
    warn_after_count: int | None = Field(default=None, ge=1, description="预警阈值数量,默认 1")
    warn_after_period: Literal["minute", "hour", "day"] | None = Field(default=None, description="预警阈值单位,默认 day")
    error_after_count: int | None = Field(default=None, ge=1, description="过期阈值数量,默认 1")
    error_after_period: Literal["minute", "hour", "day"] | None = Field(default=None, description="过期阈值单位,默认 day")
    enabled: bool | None = Field(default=None, description="是否启用,默认 true")


class UpdateTableMetadataInput(BaseModel):
    """完善一张已存在表的元数据。用 table_id 或 database+table 定位;各段可选、独立应用。"""

    model_config = ConfigDict(
        extra="forbid", str_strip_whitespace=True, alias_generator=to_camel, populate_by_name=True
    )

    table_id: int | None = Field(default=None, description="表 ID(优先)")
    database: str | None = Field(default=None, description="库名(与 table 一起在缺 table_id 时定位)")
    table: str | None = Field(default=None, description="表名(与 database 一起在缺 table_id 时定位)")
    table_comment: str | None = Field(default=None, description="表业务描述")
    attributes: UpdateTableAttributesInput | None = Field(default=None, description="受控属性:分层/业务域/数据域")
    fields: list[UpdateTableFieldCommentInput] | None = Field(default=None, description="逐字段注释")
    freshness: UpdateTableFreshnessInput | None = Field(default=None, description="数据新鲜度契约")

    @model_validator(mode="after")
    def validate_locator(self) -> "UpdateTableMetadataInput":
        if self.table_id is None and (not self.database or not self.table):
            raise ValueError("table_id 或 database + table 至少提供一组")
        return self


class CreateTaskInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    task: dict[str, Any] = Field(..., description="任务定义(对齐平台 DataTask 字段,如 taskName/dolphinNodeType/taskSql/datasourceName)")
    input_table_ids: list[int] = Field(..., description="输入表 ID 列表(维护血缘);无输入表时显式传 []")
    output_table_ids: list[int] = Field(..., description="输出表 ID 列表(维护血缘);至少一个")


class UpdateTaskInput(BaseModel):
    model_config = ConfigDict(extra="forbid")

    task_id: int = Field(..., description="任务 ID")
    task: dict[str, Any] = Field(..., description="任务定义(仅 draft 状态可更新)")
    input_table_ids: list[int] | None = Field(
        default=None,
        description="输入表 ID 全量列表;省略表示保留原有输入血缘,传数组表示全量替换,传 [] 表示清空",
    )
    output_table_ids: list[int] | None = Field(
        default=None,
        description="输出表 ID 全量列表;省略表示保留原有输出血缘,传数组表示全量替换;不可清空",
    )


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
    preview_token: str | None = Field(default=None, description="发布预览返回的一次性凭证;deploy/online 必填,offline 可省略")

    @model_validator(mode="after")
    def validate_token_required(self) -> "PublishWorkflowInput":
        if self.operation in ("deploy", "online") and not self.preview_token:
            raise ValueError("deploy/online 操作必须提供 preview_token")
        return self


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
        name="portal_preview_create_table",
        annotations={"readOnlyHint": True, "destructiveHint": False, "idempotentHint": True, "openWorldHint": False},
    )
    async def portal_preview_create_table(params: CreateTableInput) -> dict[str, Any]:
        """Preview a new target table: returns the generated table name and normalized CREATE TABLE DDL without creating anything. Follow the data-dev skill DDL standards; use before portal_create_table."""
        return await service.preview_create_table(params.model_dump(by_alias=True, exclude_none=True))

    @mcp.tool(
        name="portal_create_table",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_create_table(params: CreateTableInput) -> dict[str, Any]:
        """Create a new target table: persist metadata and execute the DDL on the engine (Doris). High-risk and irreversible; requires dorisClusterId to execute. Preview first with portal_preview_create_table."""
        return await service.create_table(params.model_dump(by_alias=True, exclude_none=True))

    @mcp.tool(
        name="portal_update_table_metadata",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_update_table_metadata(params: UpdateTableMetadataInput) -> dict[str, Any]:
        """Complete the metadata of an existing table: table comment, controlled attributes (layer / business-domain / data-domain), per-field comments, and the data-freshness contract. Locate the table by table_id (preferred) or database + table. Discover weak/missing metadata first with portal_search_tables / portal_export_metadata, then read context with portal_get_table_ddl. Controlled values outside the platform lists are dropped server-side; freshness loaded_at_field must be a real column. Each section is applied independently and the result lists applied / skipped / failed."""
        return await service.update_table_metadata(params.model_dump(by_alias=True, exclude_none=True))

    @mcp.tool(
        name="portal_create_task",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_create_task(params: CreateTaskInput) -> dict[str, Any]:
        """Create a draft data-development task (e.g. a SQL task). Both input_table_ids and output_table_ids must be provided explicitly; pass [] for no inputs, and at least one output id."""
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
        """Update a draft task definition. Lineage lists are full replacements: omit a side to keep its existing lineage, pass a full array to replace it. Never pass a partial list — omitted table ids are deleted."""
        payload: dict[str, Any] = {"task": params.task}
        # 只把显式提供的血缘放进 payload。省略的字段必须在 JSON 中真正缺席，
        # 后端据此区分"保留原值"与"清空"；发成 [] 会被理解成清空该侧血缘。
        if "input_table_ids" in params.model_fields_set:
            payload["inputTableIds"] = params.input_table_ids
        if "output_table_ids" in params.model_fields_set:
            payload["outputTableIds"] = params.output_table_ids
        return await service.update_task(params.task_id, payload)

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
        return await service.create_workflow({"workflow": params.workflow})

    @mcp.tool(
        name="portal_update_workflow",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_update_workflow(params: UpdateWorkflowInput) -> dict[str, Any]:
        """Update a draft workflow's structure."""
        return await service.update_workflow(params.workflow_id, {"workflow": params.workflow})

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
        payload: dict[str, Any] = {"operation": params.operation}
        if params.preview_token:
            payload["previewToken"] = params.preview_token
        return await service.publish_workflow(params.workflow_id, payload)

    @mcp.tool(
        name="portal_upsert_schedule",
        annotations={"readOnlyHint": False, "destructiveHint": False, "idempotentHint": False, "openWorldHint": False},
    )
    async def portal_upsert_schedule(params: UpsertScheduleInput) -> dict[str, Any]:
        """Create or update a workflow's schedule configuration (cron/timezone/etc.)."""
        schedule = dict(params.schedule)
        schedule.pop("enabled", None)
        return await service.upsert_schedule(params.workflow_id, {"schedule": schedule})

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

    _inline_tool_schema_refs(mcp)
    return mcp


def _resolve_json_pointer(root: dict[str, Any], ref: str) -> Any:
    """Resolve a local JSON Pointer such as ``#/$defs/TableDdlInput``."""
    node: Any = root
    for raw in ref[2:].split("/"):
        # RFC 6901 escapes: ~1 is "/", ~0 is "~". Order matters.
        key = raw.replace("~1", "/").replace("~0", "~")
        if not isinstance(node, dict) or key not in node:
            return None
        node = node[key]
    return node


class SchemaInlineError(RuntimeError):
    """A tool schema has a shape this inliner refuses to rewrite."""


def _inline_refs(schema: Any, root: dict[str, Any], seen: frozenset[str] = frozenset()) -> Any:
    """Replace local ``$ref`` pointers with their definitions and drop ``$defs``.

    Deliberately narrow. This handles the one shape Pydantic emits — a plain
    ``{"$ref": "#/$defs/Name"}`` with no siblings, resolving to an object
    schema — and raises on anything else instead of guessing.

    A general JSON Schema inliner is not what this needs to be, and pretending
    otherwise is how it would go wrong quietly: under 2020-12 a ``$ref`` and its
    siblings are a conjunction, so merging them by dict-update lets a sibling
    ``minLength: 2`` silently relax a target's ``minLength: 5``. Recursive
    models, boolean schemas (``true``/``false`` are valid targets) and dangling
    pointers have the same problem — every "safe degradation" here would publish
    a schema that promises less than the model enforces.

    Failing at startup instead means a future model shape breaks the build, not
    a production run.
    """
    if isinstance(schema, list):
        return [_inline_refs(item, root, seen) for item in schema]
    if not isinstance(schema, dict):
        return schema

    ref = schema.get("$ref")
    if isinstance(ref, str):
        if not ref.startswith("#/"):
            raise SchemaInlineError(f"non-local $ref is not supported: {ref!r}")
        if ref in seen:
            raise SchemaInlineError(f"recursive $ref is not supported: {ref!r}")
        siblings = {key: value for key, value in schema.items() if key != "$ref"}
        if siblings:
            # Merging would change what the schema promises; see docstring.
            raise SchemaInlineError(
                f"$ref {ref!r} carries sibling keys {sorted(siblings)}; refusing to merge"
            )
        target = _resolve_json_pointer(root, ref)
        if not isinstance(target, dict):
            raise SchemaInlineError(
                f"$ref {ref!r} resolves to {type(target).__name__}, expected an object schema"
            )
        return _inline_refs(target, root, seen | {ref})

    return {
        key: _inline_refs(value, root, seen)
        for key, value in schema.items()
        if key != "$defs"
    }


def _inline_tool_schema_refs(mcp: FastMCP) -> None:
    """Publish tool schemas with no ``$ref`` indirection.

    FastMCP derives each tool's schema from its ``params: SomeModel`` signature,
    which puts the real fields under ``$defs`` and leaves ``properties.params``
    as a ``$ref``. Some clients rebuild the schema keeping only type/properties/
    required — pi-ai's non-strict Anthropic adapter does exactly this — which
    drops ``$defs`` and hands the model a pointer into nothing. The model then
    cannot see the field names and guesses them (``table_name`` instead of
    ``table``), and the server rejects the call under ``extra="forbid"``.

    Inlining here keeps every Pydantic guarantee intact — cross-field
    validators, aliases, ``extra="forbid"`` all still run on the real models —
    while making the published schema self-contained for every client, not just
    the one runtime that happens to compensate client-side.
    """
    for tool in mcp._tool_manager.list_tools():  # noqa: SLF001 - manager itself is private
        schema = tool.parameters
        if not isinstance(schema, dict) or "$defs" not in schema:
            continue
        inlined = _inline_refs(schema, schema)
        remaining = json.dumps(inlined)
        if '"$ref"' in remaining:
            # Dropping $defs while a pointer survives would publish exactly the
            # dangling reference this function exists to prevent.
            raise SchemaInlineError(f"tool {tool.name!r} still has a $ref after inlining")
        tool.parameters = inlined


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
