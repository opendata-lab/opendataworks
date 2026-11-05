package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

@ExtendWith(MockitoExtension.class)
class DolphinExportDefinitionParserTest {

    @Mock private DolphinOpenApiClient openApiClient;

    @Mock private DolphinSchedulerService dolphinSchedulerService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DolphinRuntimeDefinitionService service;

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.lenient()
                .when(
                        dolphinSchedulerService.getConfig(
                                org.mockito.ArgumentMatchers.nullable(Long.class)))
                .thenReturn(new com.onedata.portal.entity.DolphinConfig());
        service =
                new DolphinRuntimeDefinitionService(
                        openApiClient, dolphinSchedulerService, objectMapper);
    }

    @Test
    void shouldParseWorkflowDefinitionExportForDs34() {
        ObjectNode exported = objectMapper.createObjectNode();
        ObjectNode workflowDefinition = exported.putObject("workflowDefinition");
        workflowDefinition.put("code", 1001L);
        workflowDefinition.put("name", "wf_export_34");
        workflowDefinition.put("description", "desc_34");
        workflowDefinition.put("releaseState", "ONLINE");
        workflowDefinition.put("globalParams", "[{\"prop\":\"bizdate\",\"value\":\"2026-01-01\"}]");

        ArrayNode taskDefinitions = exported.putArray("taskDefinitionList");
        ObjectNode task = taskDefinitions.addObject();
        task.put("code", 2001L);
        task.put("name", "task_sql");
        task.put("taskType", "SQL");
        task.put("taskParams", "{\"sql\":\"select 1\",\"datasource\":10,\"type\":\"DORIS\"}");

        ArrayNode relations = exported.putArray("workflowTaskRelationList");
        ObjectNode relation = relations.addObject();
        relation.put("preTaskCode", 2001L);
        relation.put("postTaskCode", 2002L);

        ObjectNode schedule = exported.putObject("schedule");
        schedule.put("id", 301L);
        schedule.put("crontab", "0 0 1 * * ?");
        schedule.put("timezoneId", "Asia/Shanghai");
        schedule.put("releaseState", "ONLINE");

        when(openApiClient.exportDefinitionByCode(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(1001L)))
                .thenReturn(exported);

        RuntimeWorkflowDefinition definition = service.loadRuntimeDefinitionFromExport(1L, 1001L);

        assertEquals(1L, definition.getProjectCode());
        assertEquals(1001L, definition.getWorkflowCode());
        assertEquals("wf_export_34", definition.getWorkflowName());
        assertEquals("desc_34", definition.getDescription());
        assertEquals("ONLINE", definition.getReleaseState());
        assertEquals(1, definition.getTasks().size());
        assertEquals(1, definition.getExplicitEdges().size());
        assertNotNull(definition.getSchedule());
        assertEquals(301L, definition.getSchedule().getScheduleId());
        assertNotNull(definition.getRawDefinitionJson());
    }

    @Test
    void shouldParseProcessDefinitionExportForDs32() {
        ObjectNode exported = objectMapper.createObjectNode();
        ObjectNode processDefinition = exported.putObject("processDefinition");
        processDefinition.put("code", 1002L);
        processDefinition.put("name", "wf_export_32");
        processDefinition.put("description", "desc_32");
        processDefinition.put("releaseState", "OFFLINE");
        processDefinition.put("globalParams", "[{\"prop\":\"dt\",\"value\":\"2026-02-01\"}]");

        ArrayNode taskDefinitions = exported.putArray("taskDefinitionList");
        ObjectNode task = taskDefinitions.addObject();
        task.put("code", 2101L);
        task.put("name", "task_sql_32");
        task.put("taskType", "SQL");
        task.put("flag", "NO");
        task.put(
                "taskParams",
                "{\"sql\":\"select * from ods.t1\",\"datasource\":11,\"type\":\"MYSQL\"}");

        ArrayNode relations = exported.putArray("processTaskRelationList");
        ObjectNode relation = relations.addObject();
        relation.put("preTaskCode", 2101L);
        relation.put("postTaskCode", 2102L);

        when(openApiClient.exportDefinitionByCode(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(1002L)))
                .thenReturn(exported);

        RuntimeWorkflowDefinition definition = service.loadRuntimeDefinitionFromExport(1L, 1002L);

        assertEquals(1002L, definition.getWorkflowCode());
        assertEquals("wf_export_32", definition.getWorkflowName());
        assertEquals("desc_32", definition.getDescription());
        assertEquals("OFFLINE", definition.getReleaseState());
        assertEquals(1, definition.getTasks().size());
        RuntimeTaskDefinition taskDefinition = definition.getTasks().get(0);
        assertEquals(2101L, taskDefinition.getTaskCode());
        assertEquals("task_sql_32", taskDefinition.getTaskName());
        assertEquals("select * from ods.t1", taskDefinition.getSql());
        assertEquals("NO", taskDefinition.getFlag());
        assertEquals(1, definition.getExplicitEdges().size());
    }

    @Test
    void shouldResolveTaskGroupNameFromCatalogWhenTaskGroupIdOnly() {
        ObjectNode exported = objectMapper.createObjectNode();
        ObjectNode workflowDefinition = exported.putObject("workflowDefinition");
        workflowDefinition.put("code", 1007L);
        workflowDefinition.put("name", "wf_task_group_enrich");

        ArrayNode taskDefinitions = exported.putArray("taskDefinitionList");
        ObjectNode task = taskDefinitions.addObject();
        task.put("code", 3301L);
        task.put("name", "task_group_only_id");
        task.put("taskType", "SQL");
        task.put("taskGroupId", 71);
        task.put("taskParams", "{\"sql\":\"select 1\",\"datasource\":10,\"type\":\"MYSQL\"}");

        DolphinTaskGroupOption option = new DolphinTaskGroupOption();
        option.setId(71);
        option.setName("tg_alpha");

        when(openApiClient.exportDefinitionByCode(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(1007L)))
                .thenReturn(exported);
        when(dolphinSchedulerService.listTaskGroupsAtTarget(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(Collections.singletonList(option));

        RuntimeWorkflowDefinition definition = service.loadRuntimeDefinitionFromExport(1L, 1007L);

        assertEquals(1, definition.getTasks().size());
        assertEquals("tg_alpha", definition.getTasks().get(0).getTaskGroupName());
    }

    @Test
    void shouldFallbackToDefinitionTaskJsonWhenTaskDefinitionListMissing() {
        ObjectNode exported = objectMapper.createObjectNode();
        ObjectNode workflowDefinition = exported.putObject("workflowDefinition");
        workflowDefinition.put("code", 1003L);
        workflowDefinition.put("name", "wf_fallback");
        workflowDefinition.put(
                "taskDefinitionJson",
                "[{\"code\":2301,\"name\":\"task_from_inline\",\"taskType\":\"SQL\",\"taskParams\":\"{\\\"sql\\\":\\\"select"
                    + " 2\\\",\\\"datasource\\\":12,\\\"type\\\":\\\"MYSQL\\\"}\"}]");

        when(openApiClient.exportDefinitionByCode(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(1003L)))
                .thenReturn(exported);

        RuntimeWorkflowDefinition definition = service.loadRuntimeDefinitionFromExport(1L, 1003L);

        assertEquals(1, definition.getTasks().size());
        assertEquals("task_from_inline", definition.getTasks().get(0).getTaskName());
    }

    @Test
    void shouldThrowWhenExportPayloadIsEmpty() {
        when(openApiClient.exportDefinitionByCode(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(1004L)))
                .thenReturn(null);

        IllegalStateException ex =
                assertThrows(
                        IllegalStateException.class,
                        () -> service.loadRuntimeDefinitionFromExport(1L, 1004L));
        assertTrue(ex.getMessage().contains("导出工作流定义"));
    }

    @Test
    void shouldEnrichTaskMetadataBidirectionallyFromCatalog() {
        ObjectNode exported = objectMapper.createObjectNode();
        ObjectNode workflowDefinition = exported.putObject("workflowDefinition");
        workflowDefinition.put("code", 1010L);
        workflowDefinition.put("name", "wf_meta_enrich");

        ArrayNode taskDefinitions = exported.putArray("taskDefinitionList");

        ObjectNode fromNameTask = taskDefinitions.addObject();
        fromNameTask.put("code", 4101L);
        fromNameTask.put("name", "task_from_name");
        fromNameTask.put("taskType", "SQL");
        fromNameTask.put("taskPriority", "MEDIUM");
        fromNameTask.put("taskGroupName", "tg_alpha");
        fromNameTask.put("taskParams", "{\"sql\":\"select 1\",\"datasourceName\":\"ds_alpha\"}");

        ObjectNode fromIdTask = taskDefinitions.addObject();
        fromIdTask.put("code", 4102L);
        fromIdTask.put("name", "task_from_id");
        fromIdTask.put("taskType", "SQL");
        fromIdTask.put("taskPriority", "8");
        fromIdTask.put("taskGroupId", 2202);
        fromIdTask.put("taskParams", "{\"sql\":\"select 2\",\"datasource\":1102}");

        when(openApiClient.exportDefinitionByCode(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(1010L)))
                .thenReturn(exported);
        when(dolphinSchedulerService.listDatasourcesAtTarget(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.isNull(),
                        org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(
                        java.util.Arrays.asList(
                                datasourceOption(1101L, "ds_alpha", "MYSQL"),
                                datasourceOption(1102L, "ds_beta", "DORIS")));
        when(dolphinSchedulerService.listTaskGroupsAtTarget(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.isNull()))
                .thenReturn(
                        java.util.Arrays.asList(
                                taskGroupOption(2201, "tg_alpha"),
                                taskGroupOption(2202, "tg_beta")));

        RuntimeWorkflowDefinition definition = service.loadRuntimeDefinitionFromExport(1L, 1010L);

        assertEquals(2, definition.getTasks().size());
        RuntimeTaskDefinition taskFromName = definition.getTasks().get(0);
        assertEquals(1101L, taskFromName.getDatasourceId());
        assertEquals("ds_alpha", taskFromName.getDatasourceName());
        assertEquals("MYSQL", taskFromName.getDatasourceType());
        assertEquals(2201, taskFromName.getTaskGroupId());
        assertEquals("tg_alpha", taskFromName.getTaskGroupName());
        assertEquals("MEDIUM", taskFromName.getTaskPriority());

        RuntimeTaskDefinition taskFromId = definition.getTasks().get(1);
        assertEquals(1102L, taskFromId.getDatasourceId());
        assertEquals("ds_beta", taskFromId.getDatasourceName());
        assertEquals("DORIS", taskFromId.getDatasourceType());
        assertEquals(2202, taskFromId.getTaskGroupId());
        assertEquals("tg_beta", taskFromId.getTaskGroupName());
        assertEquals("HIGH", taskFromId.getTaskPriority());
    }

    @Test
    void shouldParseProcessTaskRelationListWhenLoadingRuntimeDefinition() {
        ObjectNode definition = objectMapper.createObjectNode();
        definition.put("code", 1005L);
        definition.put("name", "wf_runtime_process_relation");

        ArrayNode taskDefinitions = definition.putArray("taskDefinitionList");
        ObjectNode task1 = taskDefinitions.addObject();
        task1.put("code", 3101L);
        task1.put("name", "task_update");
        task1.put("taskType", "SQL");
        task1.put(
                "taskParams",
                "{\"sql\":\"UPDATE ods.t1 SET c1=1\",\"datasource\":10,\"type\":\"MYSQL\"}");

        ObjectNode task2 = taskDefinitions.addObject();
        task2.put("code", 3102L);
        task2.put("name", "task_insert");
        task2.put("taskType", "SQL");
        task2.put(
                "taskParams",
                "{\"sql\":\"INSERT INTO dws.t2 SELECT * FROM"
                    + " ods.t1\",\"datasource\":10,\"type\":\"MYSQL\"}");

        ArrayNode relations = definition.putArray("processTaskRelationList");
        ObjectNode relation = relations.addObject();
        relation.put("preTaskCode", 3101L);
        relation.put("postTaskCode", 3102L);

        when(openApiClient.getProcessDefinition(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(1005L)))
                .thenReturn(definition);

        RuntimeWorkflowDefinition runtime = service.loadRuntimeDefinition(1L, 1005L);

        assertEquals(2, runtime.getTasks().size());
        assertEquals(1, runtime.getExplicitEdges().size());
        RuntimeTaskEdge edge = runtime.getExplicitEdges().get(0);
        assertEquals(3101L, edge.getUpstreamTaskCode());
        assertEquals(3102L, edge.getDownstreamTaskCode());
    }

    @Test
    void shouldKeepEntryEdgeWhenParsingExportRelations() {
        ObjectNode exported = objectMapper.createObjectNode();
        ObjectNode workflowDefinition = exported.putObject("workflowDefinition");
        workflowDefinition.put("code", 1006L);
        workflowDefinition.put("name", "wf_entry_edge");

        ArrayNode taskDefinitions = exported.putArray("taskDefinitionList");
        ObjectNode task = taskDefinitions.addObject();
        task.put("code", 3201L);
        task.put("name", "task_head");
        task.put("taskType", "SQL");
        task.put(
                "taskParams",
                "{\"sql\":\"UPDATE ods.t1 SET c1=1\",\"datasource\":10,\"type\":\"MYSQL\"}");

        ArrayNode relations = exported.putArray("workflowTaskRelationList");
        ObjectNode entryRelation = relations.addObject();
        entryRelation.put("preTaskCode", 0L);
        entryRelation.put("postTaskCode", 3201L);

        when(openApiClient.exportDefinitionByCode(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq(1L),
                        org.mockito.ArgumentMatchers.eq(1006L)))
                .thenReturn(exported);

        RuntimeWorkflowDefinition definition = service.loadRuntimeDefinitionFromExport(1L, 1006L);

        assertEquals(1, definition.getExplicitEdges().size());
        RuntimeTaskEdge edge = definition.getExplicitEdges().get(0);
        assertEquals(0L, edge.getUpstreamTaskCode());
        assertEquals(3201L, edge.getDownstreamTaskCode());
    }

    private DolphinDatasourceOption datasourceOption(Long id, String name, String type) {
        DolphinDatasourceOption option = new DolphinDatasourceOption();
        option.setId(id);
        option.setName(name);
        option.setType(type);
        return option;
    }

    private DolphinTaskGroupOption taskGroupOption(Integer id, String name) {
        DolphinTaskGroupOption option = new DolphinTaskGroupOption();
        option.setId(id);
        option.setName(name);
        return option;
    }
}
