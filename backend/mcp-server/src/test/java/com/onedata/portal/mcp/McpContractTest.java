package com.onedata.portal.mcp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.onedata.auth.context.*;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.service.*;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;

class McpContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private McpToolSchemaRegistry schemas;
    private McpToolHandlers handlers;
    private final DataTaskService tasks = mock(DataTaskService.class);
    private final WorkflowPublishService publish = mock(WorkflowPublishService.class);
    private final DataQueryService queries = mock(DataQueryService.class);
    private final TableCreateService createTable = mock(TableCreateService.class);
    private final DataTableQueryService tables = mock(DataTableQueryService.class);
    private final WorkflowService workflows = mock(WorkflowService.class);

    @BeforeEach
    void setup() throws Exception {
        schemas = new McpToolSchemaRegistry(mapper);
        handlers =
                new McpToolHandlers(
                        schemas,
                        mapper,
                        tables,
                        mock(DataTableService.class),
                        mock(LineageService.class),
                        queries,
                        mock(DatasourceSubjectAccessService.class),
                        createTable,
                        tasks,
                        workflows,
                        publish,
                        mock(WorkflowScheduleService.class));
        PrincipalContextHolder.setContext(CurrentPrincipal.service(7L, "agent_x", "local:admin"));
    }

    @AfterEach
    void clear() {
        PrincipalContextHolder.clear();
    }

    @Test
    void registersExactly23FlatInlineSchemasWithCamelCaseInputs() {
        Set<String> names = new HashSet<>();
        for (JsonNode tool : schemas.tools()) {
            assertTrue(names.add(tool.path("name").asText()));
            assertFalse(tool.path("description").asText().isBlank());
            assertFalse(tool.path("description").asText().contains("preview_token"));
            assertFalse(tool.get("inputSchema").path("properties").has("params"));
            assertFalse(tool.get("inputSchema").path("additionalProperties").asBoolean(true));
            assertFalse(tool.toString().contains("$ref"));
            assertFalse(tool.toString().contains("$defs"));
            tool.get("inputSchema")
                    .get("properties")
                    .fieldNames()
                    .forEachRemaining(key -> assertFalse(key.contains("_"), key));
        }
        assertEquals(23, names.size());
    }

    @Test
    void rejectsOldWrappingNamesUnknownNestedFieldsAndScheduleState() {
        for (Map<String, Object> input :
                List.<Map<String, Object>>of(
                        Map.of("params", Map.of()), Map.of("workflow_id", 1), Map.of("unknown", 1)))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> schemas.validate("odw_list_workflows", mapper.valueToTree(input)));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        schemas.validate(
                                "odw_upsert_schedule",
                                mapper.valueToTree(
                                        Map.of(
                                                "workflowId",
                                                1,
                                                "schedule",
                                                Map.of("enabled", true)))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        schemas.validate(
                                "odw_create_task",
                                mapper.valueToTree(
                                        Map.of(
                                                "task",
                                                Map.of("unexpected", true),
                                                "inputTableIds",
                                                List.of(),
                                                "outputTableIds",
                                                List.of(1)))));
    }

    @Test
    void lineageOmissionEmptyAndNullAreDistinct() {
        schemas.validate(
                "odw_update_task", mapper.valueToTree(Map.of("taskId", 1, "task", Map.of())));
        schemas.validate(
                "odw_update_task",
                mapper.valueToTree(
                        Map.of("taskId", 1, "task", Map.of(), "inputTableIds", List.of())));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        schemas.validate(
                                "odw_update_task",
                                mapper.valueToTree(
                                        Map.of(
                                                "taskId",
                                                1,
                                                "task",
                                                Map.of(),
                                                "outputTableIds",
                                                List.of()))));
        Map<String, Object> args = new HashMap<>(Map.of("taskId", 1, "task", Map.of()));
        args.put("inputTableIds", null);
        assertThrows(
                IllegalArgumentException.class,
                () -> schemas.validate("odw_update_task", mapper.valueToTree(args)));
    }

    @Test
    void publishNeverImplicitlyConfirmsDiffOrRequiresPreviewToken() {
        when(publish.publish(eq(1L), any()))
                .thenReturn(new com.onedata.portal.entity.WorkflowPublishRecord());
        handlers.call("odw_publish_workflow", Map.of("workflowId", 1, "operation", "deploy"));
        var captured = ArgumentCaptor.forClass(WorkflowPublishRequest.class);
        verify(publish).publish(eq(1L), captured.capture());
        assertEquals(false, captured.getValue().getConfirmDiff());
        assertEquals("7", captured.getValue().getOperator());
        assertTrue(
                handlers.call(
                                "odw_publish_workflow",
                                Map.of(
                                        "workflowId",
                                        1,
                                        "operation",
                                        "deploy",
                                        "preview_token",
                                        "old"))
                        .isError());
        verifyNoMoreInteractions(publish);
    }

    @Test
    void dtoStructuredContentKeepsItsTopLevelFields() {
        var result = new com.onedata.portal.dto.ReadOnlyQueryResponse();
        result.setRows(List.of(Map.of("id", 1)));
        when(queries.readQuery(any())).thenReturn(result);
        var response =
                handlers.call(
                        "odw_query_readonly",
                        Map.of("database", "sample", "sql", "SELECT id FROM orders"));
        assertFalse(response.isError());
        var content = mapper.valueToTree(response.structuredContent());
        assertTrue(content.has("rows"));
        assertFalse(content.has("result"));
        assertEquals(1, content.path("rows").get(0).path("id").asInt());
    }

    @Test
    void oversizedWriteResultIsAnErrorAndDoesNotRepeatOperation() {
        // Read list can also exceed the transport budget; no rows are silently removed.
        var result =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<
                        com.onedata.portal.entity.DataWorkflow>();
        var workflow = new com.onedata.portal.entity.DataWorkflow();
        workflow.setWorkflowName("x".repeat(600000));
        result.setRecords(List.of(workflow));
        when(workflows.list(any())).thenReturn(result);
        var response = handlers.call("odw_list_workflows", Map.of());
        assertTrue(response.isError());
        verify(workflows, times(1)).list(any());
    }

    @Test
    void omittedArgumentsAreEmptyObjectsButRequiredArgumentsStillFail() {
        when(tables.inspect(any(), any(), any(), anyInt()))
                .thenReturn(new com.onedata.portal.dto.TableInspectionResponse());
        when(tasks.listTasks(any(), any(), anyInt())).thenReturn(List.of());
        when(workflows.list(any()))
                .thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>());
        for (String tool : List.of("odw_search_tables", "odw_list_tasks", "odw_list_workflows"))
            assertFalse(handlers.call(tool, null).isError(), tool);
        assertTrue(handlers.call("odw_get_task", null).isError());
        verify(tasks, never()).getTaskOrThrow(any());
    }

    @Test
    void tableBusinessOwnerIsExplicitOrDefaultsToAuthenticatedCaller() {
        when(createTable.preview(any()))
                .thenReturn(new com.onedata.portal.dto.TableDesignPreviewResponse());
        var columns = List.of(Map.of("columnName", "id", "dataType", "BIGINT"));
        assertFalse(
                handlers.call(
                                "odw_preview_create_table",
                                Map.of("dbName", "demo", "columns", columns, "owner", "data-team"))
                        .isError());
        assertFalse(
                handlers.call(
                                "odw_preview_create_table",
                                Map.of("dbName", "demo", "columns", columns))
                        .isError());
        var captured = ArgumentCaptor.forClass(com.onedata.portal.dto.TableCreateRequest.class);
        verify(createTable, times(2)).preview(captured.capture());
        assertEquals("data-team", captured.getAllValues().get(0).getOwner());
        assertEquals("7", captured.getAllValues().get(1).getOwner());
    }
}
