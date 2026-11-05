package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowPublishPreviewResponse;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "workflow.runtime-sync.enabled=false"
})
@DisplayName("工作流发布预检集成测试")
class WorkflowPublishPreviewIntegrationTest {

    @Autowired
    private WorkflowPublishService workflowPublishService;

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private DataTaskService dataTaskService;

    @Autowired
    private DataTableMapper dataTableMapper;

    @Autowired
    private DataTaskMapper dataTaskMapper;

    @Autowired
    private DataWorkflowMapper dataWorkflowMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private DolphinRuntimeDefinitionService runtimeDefinitionService;

    @Test
    @DisplayName("运行态仅有入口边时预检不应误报边变更")
    void previewShouldNotReportEntryEdgeNoiseWhenTaskRelationsAligned() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String operator = "it-publish-preview";

        Long sourceTable = createTable("it_pub_src_" + suffix, "ods");
        Long midTable = createTable("it_pub_mid_" + suffix, "dwd");
        Long sinkTable = createTable("it_pub_sink_" + suffix, "dws");

        Long taskAId = createSqlTask(
                "it_pub_task_a_" + suffix,
                "it_pub_task_a_" + suffix,
                "insert into it_pub_mid_" + suffix + " select * from it_pub_src_" + suffix,
                "doris_ds",
                Collections.singletonList(sourceTable),
                Collections.singletonList(midTable),
                operator);
        Long taskBId = createSqlTask(
                "it_pub_task_b_" + suffix,
                "it_pub_task_b_" + suffix,
                "insert into it_pub_sink_" + suffix + " select * from it_pub_mid_" + suffix,
                "doris_ds",
                Collections.singletonList(midTable),
                Collections.singletonList(sinkTable),
                operator);

        DataTask taskA = dataTaskMapper.selectById(taskAId);
        DataTask taskB = dataTaskMapper.selectById(taskBId);
        assertNotNull(taskA);
        assertNotNull(taskB);
        taskA.setDolphinTaskCode(71001L);
        taskA.setDolphinNodeType("SQL");
        taskA.setDatasourceType("MYSQL");
        taskA.setTaskGroupName("tg-alpha");
        taskB.setDolphinTaskCode(71002L);
        taskB.setDolphinNodeType("SQL");
        taskB.setDatasourceType("MYSQL");
        taskB.setTaskGroupName("tg-alpha");
        dataTaskMapper.updateById(taskA);
        dataTaskMapper.updateById(taskB);

        DataWorkflow workflow = workflowService.createWorkflow(buildWorkflowRequest(
                "it_pub_wf_" + suffix,
                "publish-preview-it",
                "[]",
                "tg-alpha",
                operator,
                Arrays.asList(taskAId, taskBId)));
        assertNotNull(workflow.getId());

        Long workflowCode = 880000L + Math.abs(suffix.hashCode() % 100000);
        dataWorkflowMapper.update(
                null,
                Wrappers.<DataWorkflow>lambdaUpdate()
                        .eq(DataWorkflow::getId, workflow.getId())
                        .set(DataWorkflow::getWorkflowCode, workflowCode)
                        .set(DataWorkflow::getPublishStatus, "published"));
        DataWorkflow latest = dataWorkflowMapper.selectById(workflow.getId());
        assertNotNull(latest);

        RuntimeWorkflowDefinition runtime = new RuntimeWorkflowDefinition();
        runtime.setProjectCode(latest.getProjectCode());
        runtime.setWorkflowCode(latest.getWorkflowCode());
        runtime.setWorkflowName(latest.getWorkflowName());
        runtime.setDescription(latest.getDescription());
        runtime.setGlobalParams(latest.getGlobalParams());
        runtime.setReleaseState(latest.getStatus());
        runtime.setTasks(Arrays.asList(
                runtimeTask(71001L,
                        taskA.getTaskName(),
                        taskA.getTaskSql(),
                        Collections.singletonList(sourceTable),
                        Collections.singletonList(midTable),
                        "tg-alpha"),
                runtimeTask(71002L,
                        taskB.getTaskName(),
                        taskB.getTaskSql(),
                        Collections.singletonList(midTable),
                        Collections.singletonList(sinkTable),
                        "tg-alpha")));
        runtime.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 71001L),
                new RuntimeTaskEdge(71001L, 71002L)));
        mockRuntimeDefinition(latest, runtime);

        WorkflowPublishPreviewResponse preview = workflowPublishService.previewPublish(latest.getId());
        assertTrue(preview.getErrors().isEmpty(), "预检不应报错");
        assertTrue(Boolean.TRUE.equals(preview.getCanPublish()), "预检应允许发布");
        assertNotNull(preview.getDiffSummary(), "差异摘要不能为空");
        assertTrue(preview.getDiffSummary().getEdgeAdded().isEmpty(), "入口边不应被识别为边新增");
        assertTrue(preview.getDiffSummary().getEdgeRemoved().isEmpty(), "入口边不应被识别为边删除");
        assertFalse(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()),
                "仅入口边差异不应标记为结构变更, diff=" + preview.getDiffSummary());
        assertFalse(Boolean.TRUE.equals(preview.getRequireConfirm()), "仅入口边差异不应要求确认");
    }

    @Test
    @DisplayName("仅运行态噪声字段变化时预检不应触发确认")
    void previewShouldIgnoreRuntimeManagedNoiseFieldsInIntegrationPath() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String operator = "it-publish-preview";

        Long sourceTable = createTable("it_pub_noise_src_" + suffix, "ods");
        Long sinkTable = createTable("it_pub_noise_sink_" + suffix, "dwd");

        Long taskId = createSqlTask(
                "it_pub_noise_task_" + suffix,
                "it_pub_noise_task_" + suffix,
                "insert into it_pub_noise_sink_" + suffix + " select * from it_pub_noise_src_" + suffix,
                "doris_ds",
                Collections.singletonList(sourceTable),
                Collections.singletonList(sinkTable),
                operator);

        DataTask task = dataTaskMapper.selectById(taskId);
        assertNotNull(task);
        task.setDolphinTaskCode(73001L);
        task.setDolphinNodeType("SQL");
        task.setDatasourceType("MYSQL");
        dataTaskMapper.updateById(task);

        DataWorkflow workflow = workflowService.createWorkflow(buildWorkflowRequest(
                "it_pub_noise_wf_" + suffix,
                "publish-preview-noise-it",
                "[]",
                "tg-alpha",
                operator,
                Collections.singletonList(taskId)));
        assertNotNull(workflow.getId());

        Long workflowCode = 990000L + Math.abs(suffix.hashCode() % 100000);
        dataWorkflowMapper.update(
                null,
                Wrappers.<DataWorkflow>lambdaUpdate()
                        .eq(DataWorkflow::getId, workflow.getId())
                        .set(DataWorkflow::getWorkflowCode, workflowCode)
                        .set(DataWorkflow::getPublishStatus, "published"));
        DataWorkflow latest = dataWorkflowMapper.selectById(workflow.getId());
        assertNotNull(latest);
        patchDefinitionRuntimeMetadata(latest, 73001L, 110L, 66);
        latest = dataWorkflowMapper.selectById(workflow.getId());
        assertNotNull(latest);

        RuntimeWorkflowDefinition runtime = new RuntimeWorkflowDefinition();
        runtime.setProjectCode(latest.getProjectCode());
        runtime.setWorkflowCode(latest.getWorkflowCode());
        runtime.setWorkflowName(latest.getWorkflowName());
        runtime.setDescription(latest.getDescription());
        runtime.setGlobalParams(latest.getGlobalParams());
        runtime.setReleaseState(latest.getStatus());
        RuntimeTaskDefinition runtimeTask = runtimeTask(
                73001L,
                task.getTaskName(),
                task.getTaskSql(),
                Collections.singletonList(sourceTable),
                Collections.singletonList(sinkTable),
                "tg-alpha");
        runtimeTask.setDatasourceId(110L);
        runtimeTask.setTaskGroupId(66);
        runtimeTask.setTaskPriority("MEDIUM");
        runtimeTask.setTaskVersion(2);
        runtime.setTasks(Collections.singletonList(runtimeTask));
        mockRuntimeDefinition(latest, runtime);

        WorkflowPublishPreviewResponse preview = workflowPublishService.previewPublish(latest.getId());
        assertTrue(preview.getErrors().isEmpty(), "预检不应报错");
        assertTrue(Boolean.TRUE.equals(preview.getCanPublish()), "预检应允许发布");
        assertNotNull(preview.getDiffSummary(), "差异摘要不能为空");
        assertTrue(preview.getDiffSummary().getTaskModified().isEmpty(),
                "运行态噪声字段不应触发任务差异, diff=" + preview.getDiffSummary());
        assertFalse(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()),
                "仅噪声字段变化不应要求确认, diff=" + preview.getDiffSummary());
        assertFalse(Boolean.TRUE.equals(preview.getRequireConfirm()), "仅噪声字段变化不应要求确认");
    }

    @Test
    @DisplayName("运行态无 scheduleId 时预检不应持续提示 schedule 差异")
    void previewShouldIgnoreScheduleDiffWhenRuntimeScheduleMissing() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String operator = "it-publish-preview";

        Long sourceTable = createTable("it_pub_no_schedule_src_" + suffix, "ods");
        Long sinkTable = createTable("it_pub_no_schedule_sink_" + suffix, "dwd");

        Long taskId = createSqlTask(
                "it_pub_no_schedule_task_" + suffix,
                "it_pub_no_schedule_task_" + suffix,
                "insert into it_pub_no_schedule_sink_" + suffix + " select * from it_pub_no_schedule_src_" + suffix,
                "doris_ds",
                Collections.singletonList(sourceTable),
                Collections.singletonList(sinkTable),
                operator);

        DataTask task = dataTaskMapper.selectById(taskId);
        assertNotNull(task);
        task.setDolphinTaskCode(74001L);
        task.setDolphinNodeType("SQL");
        task.setDatasourceType("MYSQL");
        task.setTaskGroupName("tg-alpha");
        dataTaskMapper.updateById(task);

        DataWorkflow workflow = workflowService.createWorkflow(buildWorkflowRequest(
                "it_pub_no_schedule_wf_" + suffix,
                "publish-preview-no-schedule-it",
                "[]",
                "tg-alpha",
                operator,
                Collections.singletonList(taskId)));
        assertNotNull(workflow.getId());

        Long workflowCode = 880000L + Math.abs(suffix.hashCode() % 100000);
        dataWorkflowMapper.update(
                null,
                Wrappers.<DataWorkflow>lambdaUpdate()
                        .eq(DataWorkflow::getId, workflow.getId())
                        .set(DataWorkflow::getPublishStatus, "published")
                        .set(DataWorkflow::getWorkflowCode, workflowCode)
                        .set(DataWorkflow::getScheduleCron, "0 0 1 * * ? *")
                        .set(DataWorkflow::getScheduleTimezone, "Asia/Shanghai")
                        .set(DataWorkflow::getScheduleFailureStrategy, "CONTINUE")
                        .set(DataWorkflow::getScheduleWarningType, "NONE")
                        .set(DataWorkflow::getScheduleWarningGroupId, 0L)
                        .set(DataWorkflow::getScheduleProcessInstancePriority, "MEDIUM")
                        .set(DataWorkflow::getScheduleWorkerGroup, "default")
                        .set(DataWorkflow::getScheduleTenantCode, "default")
                        .set(DataWorkflow::getScheduleEnvironmentCode, -1L)
                        .set(DataWorkflow::getDolphinScheduleId, null)
                        .set(DataWorkflow::getScheduleState, "draft"));
        DataWorkflow latest = dataWorkflowMapper.selectById(workflow.getId());
        assertNotNull(latest);

        RuntimeWorkflowDefinition runtime = new RuntimeWorkflowDefinition();
        runtime.setProjectCode(latest.getProjectCode());
        runtime.setWorkflowCode(latest.getWorkflowCode());
        runtime.setWorkflowName(latest.getWorkflowName());
        runtime.setDescription(latest.getDescription());
        runtime.setGlobalParams(latest.getGlobalParams());
        runtime.setReleaseState(latest.getStatus());
        runtime.setTasks(Collections.singletonList(
                runtimeTask(74001L,
                        task.getTaskName(),
                        task.getTaskSql(),
                        Collections.singletonList(sourceTable),
                        Collections.singletonList(sinkTable),
                        "tg-alpha")));
        runtime.setSchedule(null);
        mockRuntimeDefinition(latest, runtime);

        WorkflowPublishPreviewResponse preview = workflowPublishService.previewPublish(latest.getId());
        assertTrue(preview.getErrors().isEmpty(), "预检不应报错");
        assertTrue(Boolean.TRUE.equals(preview.getCanPublish()), "预检应允许发布");
        assertNotNull(preview.getDiffSummary(), "差异摘要不能为空");
        assertTrue(preview.getDiffSummary().getScheduleChanges().isEmpty(), "运行态无 scheduleId 时不应提示 schedule 差异");
        assertFalse(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()),
                "无真实差异时不应要求确认, diff=" + preview.getDiffSummary());
        assertFalse(Boolean.TRUE.equals(preview.getRequireConfirm()), "无真实差异时不应要求确认");
        assertTrue(preview.getRepairIssues().stream()
                .noneMatch(item -> item.getField() != null && item.getField().startsWith("schedule.")),
                "运行态无 scheduleId 时不应出现 schedule 修复提示");
    }

    private Long createTable(String tableName, String layer) {
        DataTable table = new DataTable();
        table.setTableName(tableName);
        table.setDbName("test_db");
        table.setLayer(layer);
        table.setTableComment("workflow-publish-preview-it");
        table.setOwner("it-publish-preview");
        table.setStatus("active");
        dataTableMapper.insert(table);
        assertNotNull(table.getId());
        return table.getId();
    }

    private Long createSqlTask(String taskCode,
            String taskName,
            String sql,
            String datasourceName,
            List<Long> inputTableIds,
            List<Long> outputTableIds,
            String owner) {
        DataTask task = new DataTask();
        task.setTaskCode(taskCode);
        task.setTaskName(taskName);
        task.setTaskType("batch");
        task.setEngine("dolphin");
        task.setDolphinNodeType("SQL");
        task.setDatasourceName(datasourceName);
        task.setDatasourceType("MYSQL");
        task.setTaskSql(sql);
        task.setTaskDesc("workflow-publish-preview-it");
        task.setPriority(5);
        task.setTimeoutSeconds(600);
        task.setRetryTimes(1);
        task.setRetryInterval(60);
        task.setOwner(owner);
        DataTask created = dataTaskService.create(task, inputTableIds, outputTableIds);
        assertNotNull(created.getId());
        return created.getId();
    }

    private WorkflowDefinitionRequest buildWorkflowRequest(String workflowName,
            String description,
            String globalParams,
            String taskGroupName,
            String operator,
            List<Long> taskIds) {
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest();
        request.setWorkflowName(workflowName);
        request.setDescription(description);
        request.setGlobalParams(globalParams);
        request.setTaskGroupName(taskGroupName);
        request.setDefinitionJson("{}");
        request.setProjectCode(9527L);
        request.setOperator(operator);
        request.setTriggerSource("it_publish_preview");
        request.setTasks(taskIds.stream().map(this::toBinding).collect(Collectors.toList()));
        return request;
    }

    private WorkflowTaskBinding toBinding(Long taskId) {
        WorkflowTaskBinding binding = new WorkflowTaskBinding();
        binding.setTaskId(taskId);
        return binding;
    }

    private RuntimeTaskDefinition runtimeTask(Long taskCode,
            String taskName,
            String sql,
            List<Long> inputTableIds,
            List<Long> outputTableIds,
            String taskGroupName) {
        RuntimeTaskDefinition task = new RuntimeTaskDefinition();
        task.setTaskCode(taskCode);
        task.setTaskName(taskName);
        task.setDescription("workflow-publish-preview-it");
        task.setNodeType("SQL");
        task.setSql(sql);
        task.setDatasourceName("doris_ds");
        task.setDatasourceType("MYSQL");
        task.setTaskGroupName(taskGroupName);
        task.setFlag("YES");
        task.setTaskPriority("MEDIUM");
        task.setRetryTimes(1);
        task.setRetryInterval(60);
        task.setTimeoutSeconds(600);
        task.setInputTableIds(inputTableIds);
        task.setOutputTableIds(outputTableIds);
        return task;
    }

    private void patchDefinitionRuntimeMetadata(DataWorkflow workflow,
            Long taskCode,
            Long datasourceId,
            Integer taskGroupId) {
        try {
            JsonNode rootNode = objectMapper.readTree(workflow.getDefinitionJson());
            if (!(rootNode instanceof ObjectNode)) {
                throw new IllegalStateException("definitionJson root must be object");
            }
            JsonNode taskListNode = rootNode.get("taskDefinitionList");
            if (!(taskListNode instanceof ArrayNode)) {
                throw new IllegalStateException("definitionJson must contain taskDefinitionList");
            }
            for (JsonNode taskNode : (ArrayNode) taskListNode) {
                if (!(taskNode instanceof ObjectNode)) {
                    continue;
                }
                ObjectNode taskObject = (ObjectNode) taskNode;
                long currentTaskCode = taskObject.path("taskCode").asLong(taskObject.path("code").asLong(-1));
                if (currentTaskCode != taskCode) {
                    continue;
                }
                JsonNode paramsNode = taskObject.get("taskParams");
                ObjectNode taskParams = paramsNode instanceof ObjectNode
                        ? (ObjectNode) paramsNode
                        : taskObject.putObject("taskParams");
                if (datasourceId != null) {
                    taskParams.put("datasourceId", datasourceId);
                    taskParams.put("datasource", datasourceId);
                }
                if (taskGroupId != null) {
                    taskObject.put("taskGroupId", taskGroupId);
                }
                String patchedDefinitionJson = objectMapper.writeValueAsString(rootNode);
                dataWorkflowMapper.update(
                        null,
                        Wrappers.<DataWorkflow>lambdaUpdate()
                                .eq(DataWorkflow::getId, workflow.getId())
                                .set(DataWorkflow::getDefinitionJson, patchedDefinitionJson));
                return;
            }
            throw new IllegalStateException("definitionJson taskCode not found: " + taskCode);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to patch workflow definition runtime metadata", ex);
        }
    }

    private void mockRuntimeDefinition(DataWorkflow workflow, RuntimeWorkflowDefinition runtime) {
        if (workflow.getDolphinConfigId() == null) {
            when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(
                    workflow.getProjectCode(),
                    workflow.getWorkflowCode())).thenReturn(runtime);
        } else {
            when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(
                    workflow.getDolphinConfigId(),
                    workflow.getProjectCode(),
                    workflow.getWorkflowCode())).thenReturn(runtime);
        }
    }
}
