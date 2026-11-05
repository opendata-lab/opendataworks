package com.onedata.portal.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.context.*;
import com.onedata.portal.dto.*;
import com.onedata.portal.dto.workflow.*;
import com.onedata.portal.service.*;

import io.modelcontextprotocol.spec.McpSchema;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class McpToolHandlers {
    private final McpToolSchemaRegistry schemas;
    private final ObjectMapper mapper;
    private final DataTableQueryService tables;
    private final DataTableService tableWrites;
    private final LineageService lineage;
    private final DataQueryService queries;
    private final DatasourceSubjectAccessService sources;
    private final TableCreateService createTable;
    private final DataTaskService tasks;
    private final WorkflowService workflows;
    private final WorkflowPublishService publish;
    private final WorkflowScheduleService schedules;

    public McpSchema.CallToolResult call(String name, Map<String, Object> input) {
        try {
            CurrentPrincipal principal = PrincipalContextHolder.require();
            if (principal.getAccountType() != CurrentPrincipal.AccountType.SERVICE)
                throw new IllegalArgumentException("需要服务账号身份");
            Map<String, Object> safeInput = input == null ? Map.of() : input;
            schemas.validate(name, mapper.valueToTree(safeInput));
            Map<String, Object> args = new LinkedHashMap<>(safeInput);
            args.remove("description");
            Object result = dispatch(name, args, principal.getUserId());
            String json = mapper.writeValueAsString(result);
            Object structured = mapper.convertValue(result, Object.class);
            var response =
                    McpSchema.CallToolResult.builder()
                            .addTextContent(json)
                            .structuredContent(
                                    structured instanceof Map
                                            ? structured
                                            : Map.of("result", structured))
                            .isError(false)
                            .build();
            if (mapper.writeValueAsBytes(response).length > 524288)
                return error("操作可能已经执行，但结果超过512KiB返回限制；请缩小范围或通过门户查看，不要自动重试。");
            return response;
        } catch (Exception e) {
            return error(
                    "工具调用失败："
                            + (e.getMessage() == null
                                            ? e.getClass().getSimpleName()
                                            : e.getMessage())
                                    .substring(
                                            0,
                                            Math.min(
                                                    1000,
                                                    e.getMessage() == null
                                                            ? e.getClass().getSimpleName().length()
                                                            : e.getMessage().length())));
        }
    }

    private McpSchema.CallToolResult error(String message) {
        return McpSchema.CallToolResult.builder().addTextContent(message).isError(true).build();
    }

    private Object dispatch(String name, Map<String, Object> a, String operator) {
        Long workflowId = id(a, "workflowId");
        return switch (name) {
            case "odw_search_tables" ->
                    tables.inspect(
                            text(a, "database"),
                            text(a, "table"),
                            text(a, "keyword"),
                            number(a, "tableLimit", 12));
            case "odw_get_lineage" ->
                    lineage.inspectLineage(
                            text(a, "table"),
                            text(a, "dbName"),
                            id(a, "tableId"),
                            a.containsKey("depth") ? number(a, "depth", 1) : null);
            case "odw_resolve_datasource" ->
                    sources.resolveDatasource(text(a, "database"), text(a, "preferredEngine"));
            case "odw_export_metadata" ->
                    switch (text(a, "kind")) {
                        case "tables" -> tables.exportTables(text(a, "database"));
                        case "lineage" -> lineage.exportLineage(text(a, "database"));
                        case "datasource" -> tables.exportDatasource(text(a, "database"));
                        default -> throw new IllegalArgumentException("无效导出类型");
                    };
            case "odw_get_table_ddl" ->
                    tables.ddl(text(a, "database"), text(a, "table"), id(a, "tableId"));
            case "odw_query_readonly" ->
                    queries.readQuery(mapper.convertValue(a, ReadOnlyQueryRequest.class));
            case "odw_analyze_sql" ->
                    queries.analyzeQuery(mapper.convertValue(a, SqlAnalyzeRequest.class));
            case "odw_preview_create_table", "odw_create_table" -> {
                TableCreateRequest request = mapper.convertValue(a, TableCreateRequest.class);
                if (request.getOwner() == null || request.getOwner().isBlank())
                    request.setOwner(operator);
                yield name.equals("odw_create_table")
                        ? createTable.create(request)
                        : createTable.preview(request);
            }
            case "odw_update_table_metadata" ->
                    tableWrites.completeMetadata(
                            mapper.convertValue(a, MetadataCompleteRequest.class), operator);
            case "odw_create_task" ->
                    tasks.createFromDefinition(
                            mapper.convertValue(a, TaskDefinitionRequest.class), operator);
            case "odw_update_task" -> {
                Map<String, Object> request = new LinkedHashMap<>(a);
                request.remove("taskId");
                yield tasks.updateFromDefinition(
                        id(a, "taskId"),
                        mapper.convertValue(request, TaskDefinitionRequest.class),
                        operator);
            }
            case "odw_get_task" -> tasks.getTaskOrThrow(id(a, "taskId"));
            case "odw_list_tasks" ->
                    tasks.listTasks(text(a, "keyword"), text(a, "status"), number(a, "limit", 50));
            case "odw_create_workflow", "odw_update_workflow" -> {
                var request =
                        mapper.convertValue(a.get("workflow"), WorkflowDefinitionRequest.class);
                request.setOperator(operator);
                yield name.equals("odw_create_workflow")
                        ? workflows.createWorkflow(request)
                        : workflows.updateWorkflow(workflowId, request);
            }
            case "odw_get_workflow" -> workflows.getDetail(workflowId);
            case "odw_list_workflows" -> {
                var request = new WorkflowQueryRequest();
                request.setPageNum(1);
                request.setPageSize(number(a, "limit", 50));
                request.setKeyword(text(a, "keyword"));
                request.setStatus(text(a, "status"));
                yield workflows.list(request);
            }
            case "odw_preview_publish" -> publish.previewPublish(workflowId);
            case "odw_publish_workflow" -> {
                var request = new WorkflowPublishRequest();
                request.setOperation(text(a, "operation"));
                request.setOperator(operator);
                request.setConfirmDiff(Boolean.TRUE.equals(a.get("confirmDiff")));
                yield publish.publish(workflowId, request);
            }
            case "odw_upsert_schedule" ->
                    schedules.upsertSchedule(
                            workflowId,
                            mapper.convertValue(a.get("schedule"), WorkflowScheduleRequest.class));
            case "odw_workflow_schedule_online" -> schedules.onlineSchedule(workflowId);
            case "odw_workflow_schedule_offline" -> schedules.offlineSchedule(workflowId);
            default -> throw new IllegalArgumentException("未知工具");
        };
    }

    private static String text(Map<String, Object> a, String key) {
        return (String) a.get(key);
    }

    private static Long id(Map<String, Object> a, String key) {
        return a.get(key) == null ? null : ((Number) a.get(key)).longValue();
    }

    private static int number(Map<String, Object> a, String key, int value) {
        return a.get(key) == null ? value : ((Number) a.get(key)).intValue();
    }
}
