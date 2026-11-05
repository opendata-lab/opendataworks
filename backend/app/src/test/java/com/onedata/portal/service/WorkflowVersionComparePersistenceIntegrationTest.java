package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackRequest;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "workflow.runtime-sync.enabled=false"
})
@DisplayName("工作流版本快照持久化集成测试")
class WorkflowVersionComparePersistenceIntegrationTest {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private WorkflowVersionOperationService workflowVersionOperationService;

    @Autowired
    private DataTaskService dataTaskService;

    @Autowired
    private DataTableMapper dataTableMapper;

    @Autowired
    private DataTaskMapper dataTaskMapper;

    @Autowired
    private DataWorkflowMapper dataWorkflowMapper;

    @Autowired
    private WorkflowVersionMapper workflowVersionMapper;

    @Test
    @DisplayName("内容未变化时保存不应新增版本")
    void saveWithoutContentChangeShouldNotCreateNewVersion() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String operator = "it-version-compare";

        Long tableA = createTable("it_vcmp_nc_a_" + suffix, "ods");
        Long tableB = createTable("it_vcmp_nc_b_" + suffix, "dwd");
        Long taskId = createSqlTask(
                "it_vcmp_nc_t1_" + suffix,
                "extract_nc_" + suffix,
                "insert into it_vcmp_nc_b_" + suffix + " select * from it_vcmp_nc_a_" + suffix,
                "doris_ds",
                Collections.singletonList(tableA),
                Collections.singletonList(tableB),
                operator
        );

        DataWorkflow workflow = workflowService.createWorkflow(buildWorkflowRequest(
                "it_workflow_nc_" + suffix,
                "desc",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha",
                operator,
                Collections.singletonList(taskId)
        ));
        Long workflowId = workflow.getId();
        Long versionIdBefore = requireCurrentVersionId(workflowId);

        workflowService.updateWorkflow(workflowId, buildWorkflowRequest(
                "it_workflow_nc_" + suffix,
                "desc",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha",
                operator,
                Collections.singletonList(taskId)
        ));

        Long versionIdAfter = requireCurrentVersionId(workflowId);
        assertEquals(versionIdBefore, versionIdAfter, "内容未变化时 currentVersionId 应保持不变");

        List<WorkflowVersion> versions = workflowVersionMapper.selectList(
                Wrappers.<WorkflowVersion>lambdaQuery()
                        .eq(WorkflowVersion::getWorkflowId, workflowId));
        assertEquals(1, versions.size(), "内容未变化时不应新增版本");
    }

    @Test
    @DisplayName("当前版本非V3时保存应强制生成V3基线版本")
    void saveShouldForceCreateV3BaselineWhenCurrentVersionIsLegacy() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String operator = "it-version-compare";

        Long tableA = createTable("it_vcmp_base_a_" + suffix, "ods");
        Long tableB = createTable("it_vcmp_base_b_" + suffix, "dwd");
        Long taskId = createSqlTask(
                "it_vcmp_base_t1_" + suffix,
                "extract_base_" + suffix,
                "insert into it_vcmp_base_b_" + suffix + " select * from it_vcmp_base_a_" + suffix,
                "doris_ds",
                Collections.singletonList(tableA),
                Collections.singletonList(tableB),
                operator
        );

        String workflowName = "it_workflow_base_" + suffix;
        DataWorkflow workflow = workflowService.createWorkflow(buildWorkflowRequest(
                workflowName,
                "desc-base",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha",
                operator,
                Collections.singletonList(taskId)
        ));
        Long workflowId = workflow.getId();
        Long versionIdBefore = requireCurrentVersionId(workflowId);
        WorkflowVersion beforeVersion = workflowVersionMapper.selectById(versionIdBefore);
        assertNotNull(beforeVersion);
        assertEquals(Integer.valueOf(3), beforeVersion.getSnapshotSchemaVersion(), "新建版本默认应为V3");

        beforeVersion.setSnapshotSchemaVersion(2);
        beforeVersion.setStructureSnapshot("{\"schemaVersion\":2,\"workflow\":{\"workflowName\":\"legacy\"},\"tasks\":[],\"edges\":[],\"schedule\":{}}");
        workflowVersionMapper.updateById(beforeVersion);

        workflowService.updateWorkflow(workflowId, buildWorkflowRequest(
                workflowName,
                "desc-base",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha",
                operator,
                Collections.singletonList(taskId)
        ));

        Long versionIdAfter = requireCurrentVersionId(workflowId);
        assertTrue(!versionIdBefore.equals(versionIdAfter), "当前版本非V3时应强制创建新版本");
        WorkflowVersion afterVersion = workflowVersionMapper.selectById(versionIdAfter);
        assertNotNull(afterVersion);
        assertEquals(Integer.valueOf(3), afterVersion.getSnapshotSchemaVersion(), "强制生成的基线版本应为V3");
    }

    @Test
    @DisplayName("回滚V3版本不应覆盖 workflow.syncSource")
    void rollbackShouldNotOverwriteSyncSource() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String operator = "it-version-compare";

        Long tableA = createTable("it_vcmp_sync_a_" + suffix, "ods");
        Long tableB = createTable("it_vcmp_sync_b_" + suffix, "dwd");
        Long taskId = createSqlTask(
                "it_vcmp_sync_t1_" + suffix,
                "extract_sync_" + suffix,
                "insert into it_vcmp_sync_b_" + suffix + " select * from it_vcmp_sync_a_" + suffix,
                "doris_ds",
                Collections.singletonList(tableA),
                Collections.singletonList(tableB),
                operator
        );

        String workflowName = "it_workflow_sync_" + suffix;
        DataWorkflow workflow = workflowService.createWorkflow(buildWorkflowRequest(
                workflowName,
                "desc-v1",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha",
                operator,
                Collections.singletonList(taskId)
        ));
        Long workflowId = workflow.getId();
        Long v1 = requireCurrentVersionId(workflowId);

        Long v2 = saveWorkflow(workflowId, workflowName, "desc-v2",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha", operator, Collections.singletonList(taskId));
        assertTrue(!v1.equals(v2), "内容变化后应创建新版本");

        dataWorkflowMapper.update(
                null,
                Wrappers.<DataWorkflow>lambdaUpdate()
                        .eq(DataWorkflow::getId, workflowId)
                        .set(DataWorkflow::getSyncSource, "runtime"));

        WorkflowVersionRollbackRequest rollbackRequest = new WorkflowVersionRollbackRequest();
        rollbackRequest.setOperator(operator);
        workflowVersionOperationService.rollback(workflowId, v1, rollbackRequest);

        DataWorkflow latest = dataWorkflowMapper.selectById(workflowId);
        assertNotNull(latest);
        assertEquals("runtime", latest.getSyncSource(), "回滚不应覆盖 syncSource");
    }

    @Test
    @DisplayName("单侧多次保存后版本对比应识别核心变更")
    void compareShouldRevealCoreChangesAcrossSingleSideMultipleSaves() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String operator = "it-version-compare";

        Long tableA = createTable("it_vcmp_a_" + suffix, "ods");
        Long tableB = createTable("it_vcmp_b_" + suffix, "dwd");
        Long tableC = createTable("it_vcmp_c_" + suffix, "dws");
        Long tableD = createTable("it_vcmp_d_" + suffix, "dws");

        Long task1Id = createSqlTask(
                "it_vcmp_t1_" + suffix,
                "extract_user_" + suffix,
                "select id,name from it_vcmp_a_" + suffix,
                "doris_ds",
                Collections.singletonList(tableA),
                Collections.singletonList(tableB),
                operator
        );
        Long task2Id = createSqlTask(
                "it_vcmp_t2_" + suffix,
                "load_user_" + suffix,
                "insert into it_vcmp_c_" + suffix + " select * from it_vcmp_b_" + suffix,
                "doris_ds",
                Collections.singletonList(tableB),
                Collections.singletonList(tableC),
                operator
        );
        Long task3Id = createSqlTask(
                "it_vcmp_t3_" + suffix,
                "agg_user_" + suffix,
                "insert into it_vcmp_d_" + suffix + " select count(*) from it_vcmp_c_" + suffix,
                "doris_ds",
                Collections.singletonList(tableC),
                Collections.singletonList(tableD),
                operator
        );

        String workflowName = "it_workflow_" + suffix;
        DataWorkflow createdWorkflow = workflowService.createWorkflow(buildWorkflowRequest(
                workflowName,
                "desc-v1",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha",
                operator,
                Arrays.asList(task1Id, task2Id)
        ));
        Long workflowId = createdWorkflow.getId();
        assertNotNull(workflowId);

        Long v1 = requireCurrentVersionId(workflowId);

        Long v2 = saveWorkflow(workflowId, workflowName, "desc-v1",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha", operator, Arrays.asList(task1Id, task2Id, task3Id));
        Long v3 = saveWorkflow(workflowId, workflowName, "desc-v1",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha", operator, Arrays.asList(task1Id, task2Id));

        updateSqlTask(task1Id,
                "extract_user_" + suffix,
                "select id,name from it_vcmp_a_" + suffix + " where dt='${bizdate}'",
                "doris_ds",
                Collections.singletonList(tableA),
                Collections.singletonList(tableB));
        Long v4 = saveWorkflow(workflowId, workflowName, "desc-v1",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha", operator, Arrays.asList(task1Id, task2Id));

        updateSqlTask(task1Id,
                "extract_user_" + suffix,
                "select id,name from it_vcmp_a_" + suffix + " where dt='${bizdate}'",
                "doris_ds_v2",
                Collections.singletonList(tableA),
                Collections.singletonList(tableB));
        Long v5 = saveWorkflow(workflowId, workflowName, "desc-v1",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha", operator, Arrays.asList(task1Id, task2Id));

        String renamedTask1 = "extract_user_v2_" + suffix;
        updateSqlTask(task1Id,
                renamedTask1,
                "select id,name from it_vcmp_a_" + suffix + " where dt='${bizdate}'",
                "doris_ds_v2",
                Collections.singletonList(tableA),
                Collections.singletonList(tableB));
        Long v6 = saveWorkflow(workflowId, workflowName, "desc-v1",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha", operator, Arrays.asList(task1Id, task2Id));

        updateSqlTask(task1Id,
                renamedTask1,
                "select id,name from it_vcmp_d_" + suffix + " where dt='${bizdate}'",
                "doris_ds_v2",
                Collections.singletonList(tableD),
                Collections.singletonList(tableB));
        Long v7 = saveWorkflow(workflowId, workflowName, "desc-v1",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha", operator, Arrays.asList(task1Id, task2Id));

        updateSqlTask(task2Id,
                "load_user_" + suffix,
                "insert into it_vcmp_d_" + suffix + " select * from it_vcmp_a_" + suffix,
                "doris_ds",
                Collections.singletonList(tableA),
                Collections.singletonList(tableD));
        Long v8 = saveWorkflow(workflowId, workflowName, "desc-v1",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha", operator, Arrays.asList(task1Id, task2Id));

        updateWorkflowSchedule(workflowId,
                "0 0 1 * * ?",
                "Asia/Shanghai",
                LocalDateTime.parse("2026-02-01T00:00:00"),
                LocalDateTime.parse("2026-12-31T23:59:59"),
                "CONTINUE",
                "NONE",
                101L,
                "MEDIUM",
                "default",
                "default",
                1L,
                true);
        Long v9 = saveWorkflow(workflowId, workflowName, "desc-v1",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-01\"}]",
                "tg-alpha", operator, Arrays.asList(task1Id, task2Id));

        updateWorkflowSchedule(workflowId,
                "0 30 2 * * ?",
                "UTC",
                LocalDateTime.parse("2026-03-01T00:00:00"),
                LocalDateTime.parse("2027-01-31T23:59:59"),
                "END",
                "FAILURE",
                202L,
                "HIGHEST",
                "high-mem",
                "tenant_a",
                9L,
                false);
        Long v10 = saveWorkflow(workflowId, workflowName, "desc-v2",
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-02\"}]",
                "tg-beta", operator, Arrays.asList(task1Id, task2Id));

        updateWorkflowSchedule(workflowId,
                "0 30 2 * * ?",
                "UTC",
                LocalDateTime.parse("2026-03-01T00:00:00"),
                null,
                "END",
                "FAILURE",
                202L,
                "HIGHEST",
                "high-mem",
                "tenant_a",
                9L,
                false);
        Long v11 = saveWorkflow(workflowId, workflowName, null,
                "[{\"prop\":\"bizdate\",\"value\":\"2026-02-02\"}]",
                "tg-beta", operator, Arrays.asList(task1Id, task2Id));

        WorkflowVersionCompareResponse addTask = compare(workflowId, v1, v2);
        assertListContains(addTask.getAdded().getTasks(), "agg_user_" + suffix, "新增任务应被识别");

        WorkflowVersionCompareResponse removeTask = compare(workflowId, v2, v3);
        assertListContains(removeTask.getRemoved().getTasks(), "agg_user_" + suffix, "删除任务应被识别");

        WorkflowVersionCompareResponse modifySql = compare(workflowId, v3, v4);
        assertListContains(modifySql.getModified().getTasks(), "extract_user_" + suffix, "SQL 修改应被识别");

        WorkflowVersionCompareResponse modifyDatasource = compare(workflowId, v4, v5);
        assertListContains(modifyDatasource.getModified().getTasks(),
                "extract_user_" + suffix, "任务数据源修改应被识别");

        WorkflowVersionCompareResponse modifyTaskName = compare(workflowId, v5, v6);
        assertListContains(modifyTaskName.getModified().getTasks(),
                renamedTask1, "任务名称修改应被识别");

        WorkflowVersionCompareResponse modifyInputOutput = compare(workflowId, v6, v7);
        assertListContains(modifyInputOutput.getModified().getTasks(),
                renamedTask1, "任务输入输出修改应被识别");

        WorkflowVersionCompareResponse modifyRelation = compare(workflowId, v7, v8);
        assertTrue(modifyRelation.getAdded().getEdges().stream()
                        .anyMatch(item -> item.contains("load_user_" + suffix)
                                && item.contains("-> " + renamedTask1 + "(")),
                "任务关系新增边应被识别: " + modifyRelation.getAdded().getEdges());
        assertTrue(modifyRelation.getRemoved().getEdges().stream()
                        .anyMatch(item -> item.contains(renamedTask1)
                                && item.contains("-> " + "load_user_" + suffix + "(")),
                "任务关系删除边应被识别: " + modifyRelation.getRemoved().getEdges());

        WorkflowVersionCompareResponse modifyWorkflowAndSchedule = compare(workflowId, v9, v10);
        assertListContains(modifyWorkflowAndSchedule.getModified().getWorkflowFields(),
                "workflow.globalParams", "全局变量修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getWorkflowFields(),
                "workflow.description", "工作流描述修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getWorkflowFields(),
                "workflow.taskGroupName", "工作流任务组修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleCron", "调度 cron 修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleTimezone", "调度时区修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleStartTime", "调度开始时间修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleEndTime", "调度结束时间修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleFailureStrategy", "调度失败策略修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleWarningType", "调度告警类型修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleWarningGroupId", "调度告警组修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleProcessInstancePriority", "调度优先级修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleWorkerGroup", "调度 workerGroup 修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleTenantCode", "调度租户修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleEnvironmentCode", "调度环境修改应被识别");
        assertListContains(modifyWorkflowAndSchedule.getModified().getSchedules(),
                "schedule.scheduleAutoOnline", "调度自动上线开关修改应被识别");

        WorkflowVersionCompareResponse removeWorkflowAndSchedule = compare(workflowId, v10, v11);
        assertListContains(removeWorkflowAndSchedule.getRemoved().getWorkflowFields(),
                "workflow.description", "工作流字段删除应被识别");
        assertListContains(removeWorkflowAndSchedule.getRemoved().getSchedules(),
                "schedule.scheduleEndTime", "调度字段删除应被识别");

        List<WorkflowVersion> versions = workflowVersionMapper.selectList(
                Wrappers.<WorkflowVersion>lambdaQuery()
                        .eq(WorkflowVersion::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowVersion::getVersionNo));
        assertEquals(11, versions.size(), "连续保存后版本数应递增");
    }

    private Long createTable(String tableName, String layer) {
        DataTable table = new DataTable();
        table.setTableName(tableName);
        table.setDbName("test_db");
        table.setLayer(layer);
        table.setTableComment("workflow-version-compare-it");
        table.setOwner("it-version-compare");
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
        task.setTaskDesc("workflow-version-compare-it");
        task.setPriority(5);
        task.setTimeoutSeconds(600);
        task.setRetryTimes(1);
        task.setRetryInterval(60);
        task.setOwner(owner);
        DataTask created = dataTaskService.create(task, inputTableIds, outputTableIds);
        assertNotNull(created.getId());
        return created.getId();
    }

    private void updateSqlTask(Long taskId,
                               String taskName,
                               String sql,
                               String datasourceName,
                               List<Long> inputTableIds,
                               List<Long> outputTableIds) {
        DataTask task = dataTaskMapper.selectById(taskId);
        assertNotNull(task);
        task.setTaskName(taskName);
        task.setTaskSql(sql);
        task.setDatasourceName(datasourceName);
        task.setDatasourceType("MYSQL");
        task.setDolphinNodeType("SQL");
        task.setEngine("dolphin");
        dataTaskService.update(task, inputTableIds, outputTableIds);
    }

    private void updateWorkflowSchedule(Long workflowId,
                                        String scheduleCron,
                                        String scheduleTimezone,
                                        LocalDateTime scheduleStartTime,
                                        LocalDateTime scheduleEndTime,
                                        String scheduleFailureStrategy,
                                        String scheduleWarningType,
                                        Long scheduleWarningGroupId,
                                        String scheduleProcessInstancePriority,
                                        String scheduleWorkerGroup,
                                        String scheduleTenantCode,
                                        Long scheduleEnvironmentCode,
                                        Boolean scheduleAutoOnline) {
        int updated = dataWorkflowMapper.update(
                null,
                Wrappers.<DataWorkflow>lambdaUpdate()
                        .eq(DataWorkflow::getId, workflowId)
                        .set(DataWorkflow::getScheduleCron, scheduleCron)
                        .set(DataWorkflow::getScheduleTimezone, scheduleTimezone)
                        .set(DataWorkflow::getScheduleStartTime, scheduleStartTime)
                        .set(DataWorkflow::getScheduleEndTime, scheduleEndTime)
                        .set(DataWorkflow::getScheduleFailureStrategy, scheduleFailureStrategy)
                        .set(DataWorkflow::getScheduleWarningType, scheduleWarningType)
                        .set(DataWorkflow::getScheduleWarningGroupId, scheduleWarningGroupId)
                        .set(DataWorkflow::getScheduleProcessInstancePriority, scheduleProcessInstancePriority)
                        .set(DataWorkflow::getScheduleWorkerGroup, scheduleWorkerGroup)
                        .set(DataWorkflow::getScheduleTenantCode, scheduleTenantCode)
                        .set(DataWorkflow::getScheduleEnvironmentCode, scheduleEnvironmentCode)
                        .set(DataWorkflow::getScheduleAutoOnline, scheduleAutoOnline));
        assertEquals(1, updated, "调度字段更新失败");
    }

    private Long saveWorkflow(Long workflowId,
                              String workflowName,
                              String description,
                              String globalParams,
                              String taskGroupName,
                              String operator,
                              List<Long> taskIds) {
        workflowService.updateWorkflow(workflowId, buildWorkflowRequest(
                workflowName,
                description,
                globalParams,
                taskGroupName,
                operator,
                taskIds));
        return requireCurrentVersionId(workflowId);
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
        request.setTriggerSource("it_workflow_save");
        request.setTasks(taskIds.stream().map(this::toBinding).collect(Collectors.toList()));
        return request;
    }

    private WorkflowTaskBinding toBinding(Long taskId) {
        WorkflowTaskBinding binding = new WorkflowTaskBinding();
        binding.setTaskId(taskId);
        return binding;
    }

    private Long requireCurrentVersionId(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        assertNotNull(workflow);
        assertNotNull(workflow.getCurrentVersionId());
        return workflow.getCurrentVersionId();
    }

    private WorkflowVersionCompareResponse compare(Long workflowId, Long leftVersionId, Long rightVersionId) {
        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(leftVersionId);
        request.setRightVersionId(rightVersionId);
        return workflowVersionOperationService.compare(workflowId, request);
    }

    private void assertListContains(List<String> values, String expectedPart, String message) {
        assertTrue(values.stream().anyMatch(item -> item.contains(expectedPart)),
                message + "，期待包含: " + expectedPart + "，实际: " + values);
    }
}
