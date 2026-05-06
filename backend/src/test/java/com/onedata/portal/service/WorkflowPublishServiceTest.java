package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.onedata.portal.dto.workflow.WorkflowPublishPreviewResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRepairRequest;
import com.onedata.portal.dto.workflow.WorkflowPublishRepairResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffFieldChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeRelationChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowSchedule;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowPublishServiceTest {

    @Mock
    private WorkflowPublishRecordMapper publishRecordMapper;

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private DolphinRuntimeDefinitionService runtimeDefinitionService;

    @Mock
    private WorkflowRuntimeDiffService runtimeDiffService;

    @Mock
    private WorkflowDeployService workflowDeployService;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks
    private WorkflowPublishService service;

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, TableTaskRelation.class);
    }

    @BeforeEach
    void setUp() {
        WorkflowRuntimeDiffService.RuntimeSnapshot snapshot = new WorkflowRuntimeDiffService.RuntimeSnapshot();
        snapshot.setSnapshotJson("{}");
        snapshot.setSnapshotHash("hash");
        snapshot.setSnapshotNode(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        lenient().when(runtimeDiffService.buildSnapshot(any(), any())).thenReturn(snapshot);
    }

    @Test
    void publishDeployShouldRequireConfirmWhenDiffExists() {
        DataWorkflow workflow = workflow(1L, null, 101L);
        WorkflowVersion version = version(101L, 1);
        mockPreviewInputs(workflow);

        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(workflowService.syncCurrentVersion(1L, "tester", "publish_auto_save")).thenReturn(workflow);
        when(workflowVersionMapper.selectById(101L)).thenReturn(version);
        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(changedDiff());

        WorkflowPublishRequest request = new WorkflowPublishRequest();
        request.setOperation("deploy");
        request.setRequireApproval(false);
        request.setOperator("tester");
        request.setConfirmDiff(false);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.publish(1L, request));
        assertTrue(ex.getMessage().contains("PUBLISH_DIFF_CONFIRM_REQUIRED"));
        verify(workflowDeployService, never()).deploy(any());
    }

    @Test
    void publishDeployShouldSucceedWhenDiffConfirmed() {
        DataWorkflow workflow = workflow(1L, null, 101L);
        WorkflowVersion version = version(101L, 2);
        mockPreviewInputs(workflow);

        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(workflowService.syncCurrentVersion(1L, "tester", "publish_auto_save")).thenReturn(workflow);
        when(workflowVersionMapper.selectById(101L)).thenReturn(version);
        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(changedDiff());

        WorkflowDeployService.DeploymentResult result =
                new WorkflowDeployService.DeploymentResult(90001L, 11L, 1, false);
        when(workflowDeployService.deploy(eq(workflow))).thenReturn(result);

        WorkflowPublishRequest request = new WorkflowPublishRequest();
        request.setOperation("deploy");
        request.setRequireApproval(false);
        request.setOperator("tester");
        request.setConfirmDiff(true);

        WorkflowPublishRecord record = service.publish(1L, request);
        assertEquals("success", record.getStatus());
        assertEquals(90001L, record.getEngineWorkflowCode());
    }

    @Test
    void publishDeployShouldUseSyncedCurrentVersionAsPublishVersion() {
        DataWorkflow storedWorkflow = workflow(1L, null, 101L);
        DataWorkflow syncedWorkflow = workflow(1L, null, 202L);
        WorkflowVersion version = version(202L, 4);
        mockPreviewInputs(syncedWorkflow);

        when(dataWorkflowMapper.selectById(1L)).thenReturn(storedWorkflow);
        when(workflowService.syncCurrentVersion(1L, "tester", "publish_auto_save")).thenReturn(syncedWorkflow);
        when(workflowVersionMapper.selectById(202L)).thenReturn(version);
        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(noiseOnlyDiff());

        WorkflowDeployService.DeploymentResult result =
                new WorkflowDeployService.DeploymentResult(90002L, 11L, 1, false);
        when(workflowDeployService.deploy(eq(syncedWorkflow))).thenReturn(result);

        WorkflowPublishRequest request = new WorkflowPublishRequest();
        request.setOperation("deploy");
        request.setRequireApproval(false);
        request.setOperator("tester");
        request.setConfirmDiff(false);

        WorkflowPublishRecord record = service.publish(1L, request);
        assertEquals("success", record.getStatus());
        assertEquals(202L, record.getVersionId());
        verify(workflowService).syncCurrentVersion(1L, "tester", "publish_auto_save");
        verify(workflowVersionMapper).selectById(202L);
        verify(workflowVersionMapper, never()).selectById(101L);
        verify(workflowDeployService).deploy(eq(syncedWorkflow));
    }

    @Test
    void previewPublishShouldTreatNeverPublishedWorkflowAsFirstDeployEvenWithStaleWorkflowCode() {
        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setPublishStatus("never");
        mockPreviewInputs(workflow);

        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        WorkflowPublishPreviewResponse preview = service.previewPublish(1L);

        assertTrue(Boolean.TRUE.equals(preview.getCanPublish()));
        assertTrue(preview.getWarnings().stream()
                .anyMatch(issue -> "PUBLISH_FIRST_DEPLOY".equals(issue.getCode())));
        verify(runtimeDefinitionService, never()).loadRuntimeDefinitionFromExport(any(Long.class), any(Long.class));
        verify(runtimeDefinitionService, never()).loadRuntimeDefinitionFromExport(
                any(Long.class), any(Long.class), any(Long.class));
    }

    @Test
    void publishOnlineShouldUseLastPublishedVersionWhenRequestVersionAbsent() {
        DataWorkflow workflow = workflow(1L, 90001L, 101L);
        workflow.setLastPublishedVersionId(88L);
        workflow.setStatus("offline");
        WorkflowVersion publishedVersion = version(88L, 3);

        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(88L)).thenReturn(publishedVersion);

        WorkflowPublishRequest request = new WorkflowPublishRequest();
        request.setOperation("online");
        request.setRequireApproval(false);
        request.setOperator("tester");

        WorkflowPublishRecord record = service.publish(1L, request);

        assertEquals("success", record.getStatus());
        assertEquals(88L, record.getVersionId());
        verify(workflowVersionMapper, times(1)).selectById(88L);
        verify(workflowVersionMapper, never()).selectById(101L);
    }

    @Test
    void previewPublishShouldExposeReadableDiffAcrossWorkflowScheduleTasksAndEdges() {
        WorkflowPublishService previewService = buildPreviewServiceWithRealDiff();

        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setWorkflowName("wf_platform");
        workflow.setDescription("platform description");
        workflow.setGlobalParams("[{\"prop\":\"k\",\"value\":\"platform\"}]");
        workflow.setDolphinScheduleId(900L);
        workflow.setScheduleState("ONLINE");
        workflow.setScheduleCron("0 0 1 * * ? *");
        workflow.setScheduleTimezone("Asia/Shanghai");
        workflow.setScheduleStartTime(LocalDateTime.of(2026, 2, 24, 1, 0, 0));
        workflow.setScheduleEndTime(LocalDateTime.of(2026, 8, 24, 1, 0, 0));
        workflow.setScheduleWorkerGroup("wg_platform");
        workflow.setScheduleTenantCode("tenant_platform");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        WorkflowTaskRelation relA = new WorkflowTaskRelation();
        relA.setWorkflowId(workflow.getId());
        relA.setTaskId(10L);
        WorkflowTaskRelation relB = new WorkflowTaskRelation();
        relB.setWorkflowId(workflow.getId());
        relB.setTaskId(20L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Arrays.asList(relA, relB));

        DataTask taskA = new DataTask();
        taskA.setId(10L);
        taskA.setTaskName("platform_task_a");
        taskA.setTaskSql("INSERT INTO dwd.order_user SELECT * FROM ods.order_src");
        taskA.setTaskDesc("task-a");
        taskA.setDolphinNodeType("SQL");
        taskA.setDolphinTaskCode(1001L);
        taskA.setDatasourceName("doris_ds");
        taskA.setDatasourceType("DORIS");

        DataTask taskB = new DataTask();
        taskB.setId(20L);
        taskB.setTaskName("platform_task_b");
        taskB.setTaskSql("INSERT INTO dws.order_user_di SELECT * FROM dwd.order_user");
        taskB.setTaskDesc("task-b");
        taskB.setDolphinNodeType("SQL");
        taskB.setDolphinTaskCode(2002L);
        taskB.setDatasourceName("doris_ds");
        taskB.setDatasourceType("DORIS");
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Arrays.asList(taskA, taskB));

        TableTaskRelation relReadA = tableTaskRelation(10L, 401L, "read");
        TableTaskRelation relReadB = tableTaskRelation(20L, 501L, "read");
        TableTaskRelation relWriteA = tableTaskRelation(10L, 501L, "write");
        TableTaskRelation relWriteB = tableTaskRelation(20L, 601L, "write");
        when(tableTaskRelationMapper.selectList(any()))
                .thenReturn(Arrays.asList(relReadA, relReadB), Arrays.asList(relWriteA, relWriteB));

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setWorkflowName("wf_runtime");
        runtimeDefinition.setDescription("runtime description");
        runtimeDefinition.setGlobalParams("[{\"prop\":\"k\",\"value\":\"runtime\"}]");
        runtimeDefinition.setSchedule(runtimeSchedule(
                "0 15 2 * * ? *",
                "UTC",
                "wg_runtime",
                "tenant_runtime"));
        runtimeDefinition.setTasks(Arrays.asList(
                runtimeTask(1001L,
                        "platform_task_a",
                        "INSERT INTO dwd.order_user SELECT user_id FROM ods.order_src",
                        Arrays.asList(401L),
                        Arrays.asList(501L)),
                runtimeTask(3003L,
                        "runtime_task_c",
                        "INSERT INTO ads.order_user SELECT * FROM dwd.order_user",
                        Arrays.asList(501L),
                        Arrays.asList(701L))));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L))
                .thenReturn(runtimeDefinition);

        WorkflowPublishPreviewResponse preview = previewService.previewPublish(1L);

        assertTrue(preview.getErrors().isEmpty(), "预检不应返回错误");
        assertTrue(Boolean.TRUE.equals(preview.getCanPublish()), "预检应允许发布");
        assertTrue(Boolean.TRUE.equals(preview.getRequireConfirm()), "有差异时应要求确认");
        assertNotNull(preview.getDiffSummary(), "差异摘要不能为空");
        assertTrue(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()), "应识别到变更");

        assertTrue(preview.getDiffSummary().getWorkflowFieldChanges().stream()
                .anyMatch(item -> item.contains("workflow.workflowName")
                        && item.contains("wf_runtime")
                        && item.contains("wf_platform")));
        assertTrue(preview.getDiffSummary().getWorkflowFieldChanges().stream()
                .anyMatch(item -> item.contains("workflow.description")));
        assertTrue(preview.getDiffSummary().getWorkflowFieldChanges().stream()
                .anyMatch(item -> item.contains("workflow.globalParams")));

        assertTrue(preview.getDiffSummary().getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.crontab")));
        assertTrue(preview.getDiffSummary().getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.timezoneId")));
        assertTrue(preview.getDiffSummary().getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.workerGroup")));
        assertTrue(preview.getDiffSummary().getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.tenantCode")));

        assertTrue(preview.getDiffSummary().getTaskModified().stream()
                .anyMatch(item -> item.contains("platform_task_a") && item.contains("sql")));
        assertTrue(preview.getDiffSummary().getTaskAdded().stream()
                .anyMatch(item -> item.contains("platform_task_b") && item.contains("taskCode=2002")));
        assertTrue(preview.getDiffSummary().getTaskRemoved().stream()
                .anyMatch(item -> item.contains("runtime_task_c") && item.contains("taskCode=3003")));

        assertTrue(preview.getDiffSummary().getEdgeAdded().stream()
                .anyMatch(item -> item.contains("platform_task_a")
                        && item.contains("platform_task_b")
                        && item.contains("1001->2002")));
        assertTrue(preview.getDiffSummary().getEdgeRemoved().stream()
                .anyMatch(item -> item.contains("platform_task_a")
                        && item.contains("runtime_task_c")
                        && item.contains("1001->3003")));
        assertFalse(preview.getDiffSummary().getEdgeAdded().isEmpty(), "应识别边新增");
        assertFalse(preview.getDiffSummary().getEdgeRemoved().isEmpty(), "应识别边删除");
    }

    @Test
    void previewPublishShouldIgnoreRuntimeManagedNoiseFields() {
        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setWorkflowName("wf_platform");
        workflow.setDescription("platform description");
        workflow.setGlobalParams("[{\"prop\":\"k\",\"value\":\"platform\"}]");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        mockPreviewInputs(workflow);

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setWorkflowName("wf_platform");
        runtimeDefinition.setDescription("platform description");
        runtimeDefinition.setGlobalParams("[{\"prop\":\"k\",\"value\":\"platform\"}]");
        runtimeDefinition.setSchedule(runtimeSchedule(
                "0 0 1 * * ? *",
                "Asia/Shanghai",
                "default",
                "default"));
        runtimeDefinition.setTasks(Collections.singletonList(
                runtimeTask(10001L,
                        "task_a",
                        "INSERT INTO dws.t1 SELECT * FROM ods.t1",
                        Collections.emptyList(),
                        Collections.emptyList())));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L))
                .thenReturn(runtimeDefinition);

        RuntimeDiffSummary diff = new RuntimeDiffSummary();
        diff.setChanged(true);

        RuntimeTaskChange modified = new RuntimeTaskChange();
        modified.setTaskCode(10001L);
        modified.setTaskName("task_a");
        modified.setFieldChanges(Arrays.asList(
                fieldChange("task.datasourceId", "10", null),
                fieldChange("task.datasourceName", "doris_ds", "doris_ds_platform"),
                fieldChange("task.inputTableIds", "[]", "[1,2]"),
                fieldChange("task.outputTableIds", "[]", "[3]"),
                fieldChange("task.taskGroupId", "0", null),
                fieldChange("task.taskGroupName", null, "tg-default"),
                fieldChange("task.taskPriority", "MEDIUM", "LOW"),
                fieldChange("task.taskVersion", "2", "1")));
        diff.setTaskModified(Collections.singletonList(modified));
        diff.setScheduleChanges(Arrays.asList(
                fieldChange("schedule.scheduleId", "901", null),
                fieldChange("schedule.releaseState", "ONLINE", "OFFLINE"),
                fieldChange("schedule.failureStrategy", "CONTINUE", null),
                fieldChange("schedule.warningType", "NONE", null),
                fieldChange("schedule.warningGroupId", "0", null),
                fieldChange("schedule.processInstancePriority", "MEDIUM", null),
                fieldChange("schedule.environmentCode", "-1", null),
                fieldChange("schedule.workerGroup", "default", null),
                fieldChange("schedule.tenantCode", "default", null)));
        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(diff);

        WorkflowPublishPreviewResponse preview = service.previewPublish(1L);

        assertNotNull(preview.getDiffSummary());
        assertTrue(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()), "非噪声调度字段应触发变更确认");
        assertFalse(preview.getDiffSummary().getTaskModified().isEmpty(), "taskPriority 变更应保留");
        assertTrue(preview.getDiffSummary().getTaskModified().stream()
                .flatMap(item -> item.getFieldChanges() == null
                        ? java.util.stream.Stream.empty()
                        : item.getFieldChanges().stream())
                .anyMatch(item -> "task.datasourceId".equals(item.getField())), "应保留 task.datasourceId 差异");
        assertTrue(preview.getDiffSummary().getTaskModified().stream()
                .flatMap(item -> item.getFieldChanges() == null
                        ? java.util.stream.Stream.empty()
                        : item.getFieldChanges().stream())
                .anyMatch(item -> "task.datasourceName".equals(item.getField())), "应保留 task.datasourceName 差异");
        assertTrue(preview.getDiffSummary().getTaskModified().stream()
                .flatMap(item -> item.getFieldChanges() == null
                        ? java.util.stream.Stream.empty()
                        : item.getFieldChanges().stream())
                .anyMatch(item -> "task.taskGroupId".equals(item.getField())), "应保留 task.taskGroupId 差异");
        assertTrue(preview.getDiffSummary().getTaskModified().stream()
                .flatMap(item -> item.getFieldChanges() == null
                        ? java.util.stream.Stream.empty()
                        : item.getFieldChanges().stream())
                .anyMatch(item -> "task.taskGroupName".equals(item.getField())), "应保留 task.taskGroupName 差异");
        assertTrue(preview.getDiffSummary().getTaskModified().stream()
                .flatMap(item -> item.getFieldChanges() == null
                        ? java.util.stream.Stream.empty()
                        : item.getFieldChanges().stream())
                .anyMatch(item -> "task.taskPriority".equals(item.getField())), "应保留 task.taskPriority 差异");
        assertFalse(preview.getDiffSummary().getScheduleChanges().isEmpty(), "调度差异应保留并提示修复");
        assertTrue(preview.getDiffSummary().getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.failureStrategy")));
        assertTrue(preview.getDiffSummary().getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.scheduleId")), "应保留 schedule.scheduleId 差异");
        assertTrue(preview.getRepairIssues().isEmpty(), "普通发布差异应走发布确认，不应触发元数据修复步骤");
        assertTrue(Boolean.TRUE.equals(preview.getRequireConfirm()), "存在调度差异时应要求确认");
    }

    @Test
    void previewPublishShouldKeepSqlDiffForConfirmButNotTreatItAsRepairIssue() {
        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setWorkflowName("wf_platform");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        mockPreviewInputs(workflow);

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setWorkflowName("wf_platform");
        runtimeDefinition.setTasks(Collections.singletonList(
                runtimeTask(10001L,
                        "task_a",
                        "INSERT INTO dws.t1 SELECT * FROM ods.runtime_t1",
                        Collections.emptyList(),
                        Collections.emptyList())));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L))
                .thenReturn(runtimeDefinition);

        RuntimeDiffSummary diff = new RuntimeDiffSummary();
        diff.setChanged(true);
        RuntimeTaskChange modified = new RuntimeTaskChange();
        modified.setTaskCode(10001L);
        modified.setTaskName("task_a");
        modified.setFieldChanges(Collections.singletonList(
                fieldChange("task.sql",
                        "INSERT INTO dws.t1 SELECT * FROM ods.runtime_t1",
                        "INSERT INTO dws.t1 SELECT * FROM ods.t1")));
        diff.setTaskModified(Collections.singletonList(modified));
        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(diff);

        WorkflowPublishPreviewResponse preview = service.previewPublish(1L);

        assertTrue(Boolean.TRUE.equals(preview.getRequireConfirm()), "SQL 变更仍应进入发布确认");
        assertTrue(preview.getRepairIssues().isEmpty(), "SQL 变更不应触发元数据修复");
        assertTrue(preview.getDiffSummary().getTaskModified().stream()
                .flatMap(item -> item.getFieldChanges() == null
                        ? java.util.stream.Stream.empty()
                        : item.getFieldChanges().stream())
                .anyMatch(item -> "task.sql".equals(item.getField())), "SQL 变更应保留在发布确认中");
    }

    @Test
    void previewPublishShouldTreatOnlyNoiseDiffAsNoChange() {
        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setWorkflowName("wf_platform");
        workflow.setDescription("platform description");
        workflow.setGlobalParams("[{\"prop\":\"k\",\"value\":\"platform\"}]");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        mockPreviewInputs(workflow);

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setWorkflowName("wf_platform");
        runtimeDefinition.setDescription("platform description");
        runtimeDefinition.setGlobalParams("[{\"prop\":\"k\",\"value\":\"platform\"}]");
        runtimeDefinition.setSchedule(runtimeSchedule(
                "0 0 1 * * ? *",
                "Asia/Shanghai",
                "default",
                "default"));
        runtimeDefinition.setTasks(Collections.singletonList(
                runtimeTask(10001L,
                        "task_a",
                        "INSERT INTO dws.t1 SELECT * FROM ods.t1",
                        Collections.emptyList(),
                        Collections.emptyList())));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L))
                .thenReturn(runtimeDefinition);

        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(noiseOnlyDiff());

        WorkflowPublishPreviewResponse preview = service.previewPublish(1L);
        assertNotNull(preview.getDiffSummary());
        assertFalse(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()), "仅噪声字段不应视为变更");
        assertFalse(Boolean.TRUE.equals(preview.getRequireConfirm()), "仅噪声字段不应要求确认发布");
        assertTrue(preview.getDiffSummary().getTaskModified().isEmpty(), "噪声任务字段应被过滤");
        assertTrue(preview.getDiffSummary().getScheduleChanges().isEmpty(), "噪声调度字段应被过滤");
        assertTrue(preview.getRepairIssues().isEmpty(), "仅噪声字段时不应产生修复提示");
    }

    @Test
    void previewPublishShouldIgnoreScheduleChangesWhenBeforeAfterEqual() {
        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setWorkflowName("wf_platform");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        mockPreviewInputs(workflow);

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setWorkflowName("wf_platform");
        runtimeDefinition.setTasks(Collections.singletonList(
                runtimeTask(10001L, "task_a", "select 1", Collections.emptyList(), Collections.emptyList())));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L))
                .thenReturn(runtimeDefinition);

        RuntimeDiffSummary diff = new RuntimeDiffSummary();
        diff.setChanged(true);
        diff.setScheduleChanges(Collections.singletonList(
                fieldChange("schedule.failureStrategy", null, null)));
        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(diff);

        WorkflowPublishPreviewResponse preview = service.previewPublish(1L);
        assertNotNull(preview.getDiffSummary());
        assertTrue(preview.getDiffSummary().getScheduleChanges().isEmpty(), "before/after 一致的字段不应保留");
        assertFalse(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()), "无真实差异时不应标记变更");
        assertFalse(Boolean.TRUE.equals(preview.getRequireConfirm()), "无真实差异时不应要求确认");
        assertTrue(preview.getRepairIssues().isEmpty(), "无真实差异时不应产生修复提示");
    }

    @Test
    void previewPublishShouldIgnoreScheduleDiffWhenRuntimeScheduleMissing() {
        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setWorkflowName("wf_platform");
        workflow.setScheduleCron("0 0 1 * * ? *");
        workflow.setScheduleTimezone("Asia/Shanghai");
        workflow.setScheduleFailureStrategy("CONTINUE");
        workflow.setScheduleWarningType("NONE");
        workflow.setScheduleWarningGroupId(0L);
        workflow.setScheduleProcessInstancePriority("MEDIUM");
        workflow.setScheduleWorkerGroup("default");
        workflow.setScheduleTenantCode("default");
        workflow.setScheduleEnvironmentCode(-1L);
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        mockPreviewInputs(workflow);

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setWorkflowName("wf_platform");
        runtimeDefinition.setDescription(workflow.getDescription());
        runtimeDefinition.setGlobalParams(workflow.getGlobalParams());
        runtimeDefinition.setTasks(Collections.singletonList(
                runtimeTask(10001L, "task_a", "INSERT INTO dws.t1 SELECT * FROM ods.t1",
                        Collections.emptyList(), Collections.emptyList())));
        runtimeDefinition.setSchedule(null);
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L))
                .thenReturn(runtimeDefinition);

        RuntimeDiffSummary diff = new RuntimeDiffSummary();
        diff.setChanged(true);
        diff.setScheduleChanges(Arrays.asList(
                fieldChange("schedule.crontab", null, "0 0 1 * * ? *"),
                fieldChange("schedule.workerGroup", null, "default"),
                fieldChange("schedule.tenantCode", null, "default")));
        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(diff);

        WorkflowPublishPreviewResponse preview = service.previewPublish(1L);
        assertNotNull(preview.getDiffSummary());
        assertTrue(preview.getDiffSummary().getScheduleChanges().isEmpty(), "运行态无调度时不应提示 schedule 差异");
        assertFalse(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()), "仅 schedule 空跑差异不应标记变更");
        assertFalse(Boolean.TRUE.equals(preview.getRequireConfirm()), "仅 schedule 空跑差异不应要求确认");
        assertTrue(preview.getRepairIssues().stream().noneMatch(item -> item.getField() != null
                && item.getField().startsWith("schedule.")), "运行态无调度时不应出现 schedule 修复提示");
    }

    @Test
    void previewPublishShouldAlignRuntimeEntryEdgesBeforeDiff() {
        WorkflowPublishService previewService = buildPreviewServiceWithRealDiff();
        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setWorkflowName("wf_platform");
        workflow.setDefinitionJson("{\"taskDefinitionList\":["
                + "{\"taskCode\":1001,\"taskParams\":{\"datasourceId\":10}},"
                + "{\"taskCode\":2002,\"taskParams\":{\"datasourceId\":10}}]}");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        WorkflowTaskRelation relA = new WorkflowTaskRelation();
        relA.setWorkflowId(workflow.getId());
        relA.setTaskId(10L);
        WorkflowTaskRelation relB = new WorkflowTaskRelation();
        relB.setWorkflowId(workflow.getId());
        relB.setTaskId(20L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Arrays.asList(relA, relB));

        DataTask taskA = new DataTask();
        taskA.setId(10L);
        taskA.setTaskName("task_a");
        taskA.setTaskSql("INSERT INTO dwd.order_user SELECT * FROM ods.order_src");
        taskA.setTaskDesc("task-a");
        taskA.setDolphinNodeType("SQL");
        taskA.setDolphinTaskCode(1001L);
        taskA.setDatasourceName("doris_ds");
        taskA.setDatasourceType("DORIS");

        DataTask taskB = new DataTask();
        taskB.setId(20L);
        taskB.setTaskName("task_b");
        taskB.setTaskSql("INSERT INTO dws.order_user_di SELECT * FROM dwd.order_user");
        taskB.setTaskDesc("task-b");
        taskB.setDolphinNodeType("SQL");
        taskB.setDolphinTaskCode(2002L);
        taskB.setDatasourceName("doris_ds");
        taskB.setDatasourceType("DORIS");
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Arrays.asList(taskA, taskB));

        TableTaskRelation relReadA = tableTaskRelation(10L, 401L, "read");
        TableTaskRelation relReadB = tableTaskRelation(20L, 501L, "read");
        TableTaskRelation relWriteA = tableTaskRelation(10L, 501L, "write");
        TableTaskRelation relWriteB = tableTaskRelation(20L, 601L, "write");
        when(tableTaskRelationMapper.selectList(any()))
                .thenReturn(Arrays.asList(relReadA, relReadB), Arrays.asList(relWriteA, relWriteB));

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setWorkflowName("wf_platform");
        runtimeDefinition.setReleaseState("draft");
        RuntimeTaskDefinition runtimeTaskA = runtimeTask(1001L,
                "task_a",
                "INSERT INTO dwd.order_user SELECT * FROM ods.order_src",
                Arrays.asList(401L),
                Arrays.asList(501L));
        runtimeTaskA.setDescription("task-a");
        runtimeTaskA.setDatasourceName("doris_ds");
        runtimeTaskA.setDatasourceType("DORIS");
        RuntimeTaskDefinition runtimeTaskB = runtimeTask(2002L,
                "task_b",
                "INSERT INTO dws.order_user_di SELECT * FROM dwd.order_user",
                Arrays.asList(501L),
                Arrays.asList(601L));
        runtimeTaskB.setDescription("task-b");
        runtimeTaskB.setDatasourceName("doris_ds");
        runtimeTaskB.setDatasourceType("DORIS");
        runtimeDefinition.setTasks(Arrays.asList(
                runtimeTaskA,
                runtimeTaskB));
        runtimeDefinition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 1001L),
                new RuntimeTaskEdge(1001L, 2002L)));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L))
                .thenReturn(runtimeDefinition);

        WorkflowPublishPreviewResponse preview = previewService.previewPublish(1L);
        assertNotNull(preview.getDiffSummary());
        assertTrue(preview.getDiffSummary().getEdgeAdded().isEmpty(), "仅入口边差异不应产生边新增");
        assertTrue(preview.getDiffSummary().getEdgeRemoved().isEmpty(), "仅入口边差异不应产生边删除");
        assertFalse(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()), "仅入口边差异不应触发结构变更");
        assertFalse(Boolean.TRUE.equals(preview.getRequireConfirm()), "仅入口边差异不应要求确认");
    }

    @Test
    void previewPublishShouldNotSilenceUnexpectedEntryEdgeDiff() {
        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setWorkflowName("wf_platform");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        mockPreviewInputs(workflow);

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setWorkflowName("wf_platform");
        runtimeDefinition.setTasks(Collections.singletonList(
                runtimeTask(10001L, "task_a", "select 1", Collections.emptyList(), Collections.emptyList())));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L))
                .thenReturn(runtimeDefinition);

        RuntimeDiffSummary diff = new RuntimeDiffSummary();
        diff.setChanged(true);
        RuntimeRelationChange entryEdge = new RuntimeRelationChange();
        entryEdge.setPreTaskCode(0L);
        entryEdge.setPostTaskCode(10001L);
        entryEdge.setEntryEdge(true);
        diff.setEdgeRemoved(Collections.singletonList(entryEdge));
        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(diff);

        WorkflowPublishPreviewResponse preview = service.previewPublish(1L);
        assertNotNull(preview.getDiffSummary());
        assertFalse(preview.getDiffSummary().getEdgeRemoved().isEmpty(), "异常入口边差异不应被静默吞掉");
        assertTrue(Boolean.TRUE.equals(preview.getDiffSummary().getChanged()), "存在边差异时应标记变更");
        assertTrue(Boolean.TRUE.equals(preview.getRequireConfirm()), "存在边差异时应要求确认");
    }

    @Test
    void publishDeployShouldProceedWithoutConfirmWhenOnlyNoiseDiffExists() {
        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        WorkflowVersion version = version(101L, 2);
        mockPreviewInputs(workflow);
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(workflowService.syncCurrentVersion(1L, "tester", "publish_auto_save")).thenReturn(workflow);
        when(workflowVersionMapper.selectById(101L)).thenReturn(version);

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setWorkflowName("wf_test");
        runtimeDefinition.setDescription("desc");
        runtimeDefinition.setGlobalParams("[]");
        runtimeDefinition.setSchedule(runtimeSchedule(
                "0 0 1 * * ? *",
                "Asia/Shanghai",
                "default",
                "default"));
        runtimeDefinition.setTasks(Collections.singletonList(
                runtimeTask(10001L,
                        "task_a",
                        "INSERT INTO dws.t1 SELECT * FROM ods.t1",
                        Collections.emptyList(),
                        Collections.emptyList())));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L))
                .thenReturn(runtimeDefinition);

        when(runtimeDiffService.buildDiff(any(), any())).thenReturn(noiseOnlyDiff());

        WorkflowDeployService.DeploymentResult result =
                new WorkflowDeployService.DeploymentResult(90001L, 11L, 1, false);
        when(workflowDeployService.deploy(eq(workflow))).thenReturn(result);

        WorkflowPublishRequest request = new WorkflowPublishRequest();
        request.setOperation("deploy");
        request.setRequireApproval(false);
        request.setOperator("tester");
        request.setConfirmDiff(false);

        WorkflowPublishRecord record = service.publish(1L, request);
        assertEquals("success", record.getStatus());
        assertEquals(90001L, record.getEngineWorkflowCode());
        verify(workflowDeployService, times(1)).deploy(eq(workflow));
    }

    @Test
    void repairPublishMetadataShouldUpdateWorkflowAndTaskRuntimeFields() {
        DataWorkflow workflow = workflow(1L, 5001L, 101L);
        workflow.setDolphinScheduleId(null);
        workflow.setScheduleCron("0 0 1 * * ? *");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        RuntimeWorkflowDefinition runtimeDefinition = new RuntimeWorkflowDefinition();
        runtimeDefinition.setProjectCode(11L);
        runtimeDefinition.setWorkflowCode(5001L);
        runtimeDefinition.setSchedule(runtimeSchedule(
                "0 15 2 * * ? *",
                "UTC",
                "wg_runtime",
                "tenant_runtime"));
        RuntimeTaskDefinition runtimeTask = runtimeTask(10001L,
                "task_a",
                "INSERT INTO dws.t1 SELECT * FROM ods.t1",
                Collections.emptyList(),
                Collections.emptyList());
        runtimeTask.setTaskVersion(3);
        runtimeTask.setTaskGroupName("group_runtime");
        runtimeDefinition.setTasks(Collections.singletonList(runtimeTask));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(11L, 5001L)).thenReturn(runtimeDefinition);

        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(1L);
        relation.setTaskId(10L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(relation));

        DataTask task = new DataTask();
        task.setId(10L);
        task.setDolphinTaskCode(10001L);
        task.setDolphinTaskVersion(1);
        task.setDatasourceName("doris_old");
        task.setDatasourceType("MYSQL");
        task.setTaskGroupName("group_old");
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(task));

        WorkflowPublishRepairRequest request = new WorkflowPublishRepairRequest();
        request.setOperator("tester");
        WorkflowPublishRepairResponse response = service.repairPublishMetadata(1L, request);

        assertTrue(Boolean.TRUE.equals(response.getRepaired()));
        assertEquals(1, response.getUpdatedTaskCount());
        assertTrue(response.getUpdatedWorkflowFields().contains("schedule.crontab"));
        verify(dataWorkflowMapper, times(1)).updateById(any(DataWorkflow.class));
        verify(dataTaskMapper, times(1)).updateById(any(DataTask.class));
    }

    @Test
    void repairPublishMetadataShouldResolveIdsFromCatalogWhenFirstDeploy() {
        WorkflowPublishService publishService = buildPreviewServiceWithRealDiff();
        DataWorkflow workflow = workflow(1L, null, 101L);
        workflow.setDefinitionJson("{\"taskDefinitionList\":[{\"taskCode\":10001,\"taskGroupName\":\"tg_a\","
                + "\"taskParams\":{\"datasourceName\":\"ds_a\",\"type\":\"MYSQL\"}}]}");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        DolphinDatasourceOption datasourceOption = new DolphinDatasourceOption();
        datasourceOption.setId(501L);
        datasourceOption.setName("ds_a");
        datasourceOption.setType("MYSQL");
        when(dolphinSchedulerService.listDatasources(null, null))
                .thenReturn(Collections.singletonList(datasourceOption));

        DolphinTaskGroupOption groupOption = new DolphinTaskGroupOption();
        groupOption.setId(71);
        groupOption.setName("tg_a");
        when(dolphinSchedulerService.listTaskGroups(null))
                .thenReturn(Collections.singletonList(groupOption));

        WorkflowPublishRepairRequest request = new WorkflowPublishRepairRequest();
        request.setOperator("tester");
        WorkflowPublishRepairResponse response = publishService.repairPublishMetadata(1L, request);

        assertTrue(Boolean.TRUE.equals(response.getRepaired()));
        assertTrue(response.getUpdatedWorkflowFields().contains("definition.taskDefinitionList"));
        assertTrue(workflow.getDefinitionJson().contains("\"datasourceId\":501"));
        assertTrue(workflow.getDefinitionJson().contains("\"taskGroupId\":71"));
        verify(dataWorkflowMapper, times(1)).updateById(any(DataWorkflow.class));
        verify(workflowService, times(1)).normalizeAndPersistMetadata(1L, "tester");
    }

    @Test
    void repairPublishMetadataShouldOverwriteWrongDatasourceTypeFromCatalog() {
        WorkflowPublishService publishService = buildPreviewServiceWithRealDiff();
        DataWorkflow workflow = workflow(1L, null, 101L);
        workflow.setDefinitionJson("{\"taskDefinitionList\":[{\"taskCode\":10001,\"taskGroupId\":71,"
                + "\"taskParams\":{\"datasourceId\":501,\"datasource\":501,\"datasourceName\":\"ds_a\","
                + "\"datasourceType\":\"DORIS\",\"type\":\"DORIS\"}}]}");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);

        DolphinDatasourceOption datasourceOption = new DolphinDatasourceOption();
        datasourceOption.setId(501L);
        datasourceOption.setName("ds_a");
        datasourceOption.setType("OCEANBASE");
        when(dolphinSchedulerService.listDatasources(null, null))
                .thenReturn(Collections.singletonList(datasourceOption));
        when(dolphinSchedulerService.listTaskGroups(null))
                .thenReturn(Collections.singletonList(new DolphinTaskGroupOption()));

        WorkflowPublishRepairRequest request = new WorkflowPublishRepairRequest();
        request.setOperator("tester");
        WorkflowPublishRepairResponse response = publishService.repairPublishMetadata(1L, request);

        assertTrue(Boolean.TRUE.equals(response.getRepaired()));
        assertTrue(workflow.getDefinitionJson().contains("\"datasourceType\":\"OCEANBASE\""));
        assertTrue(workflow.getDefinitionJson().contains("\"type\":\"OCEANBASE\""));
        verify(dataWorkflowMapper, times(1)).updateById(any(DataWorkflow.class));
        verify(workflowService, times(1)).normalizeAndPersistMetadata(1L, "tester");
    }

    @Test
    void repairPublishMetadataShouldFailFastWhenCatalogUnavailable() {
        WorkflowPublishService publishService = buildPreviewServiceWithRealDiff();
        DataWorkflow workflow = workflow(1L, null, 101L);
        workflow.setDefinitionJson("{\"taskDefinitionList\":[{\"taskCode\":10001,\"taskGroupName\":\"tg_a\","
                + "\"taskParams\":{\"datasourceName\":\"ds_a\",\"type\":\"MYSQL\"}}]}");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(dolphinSchedulerService.listDatasources(null, null))
                .thenThrow(new RuntimeException("401 Unauthorized"));

        WorkflowPublishRepairRequest request = new WorkflowPublishRepairRequest();
        request.setOperator("tester");
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> publishService.repairPublishMetadata(1L, request));

        assertTrue(exception.getMessage().contains("无法读取 Dolphin 数据源目录"));
        verify(workflowService, never()).normalizeAndPersistMetadata(1L, "tester");
    }

    @Test
    void repairPublishMetadataShouldFailFastWhenDatasourceCatalogEmpty() {
        WorkflowPublishService publishService = buildPreviewServiceWithRealDiff();
        DataWorkflow workflow = workflow(1L, null, 101L);
        workflow.setDefinitionJson("{\"taskDefinitionList\":[{\"taskCode\":10001,\"taskGroupName\":\"tg_a\","
                + "\"taskParams\":{\"datasourceName\":\"ds_a\",\"type\":\"MYSQL\"}}]}");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(dolphinSchedulerService.listDatasources(null, null))
                .thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listTaskGroups(null))
                .thenReturn(Collections.singletonList(new DolphinTaskGroupOption()));

        WorkflowPublishRepairRequest request = new WorkflowPublishRepairRequest();
        request.setOperator("tester");
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> publishService.repairPublishMetadata(1L, request));

        assertTrue(exception.getMessage().contains("Dolphin 数据源目录为空"));
        verify(workflowService, never()).normalizeAndPersistMetadata(1L, "tester");
    }

    @Test
    void repairPublishMetadataShouldFailWhenBlockingIdsStillMissing() {
        WorkflowPublishService publishService = buildPreviewServiceWithRealDiff();
        DataWorkflow workflow = workflow(1L, null, 101L);
        workflow.setDefinitionJson("{\"taskDefinitionList\":[{\"taskCode\":10001,\"taskGroupName\":\"tg_missing\","
                + "\"taskParams\":{\"datasourceName\":\"ds_missing\",\"type\":\"MYSQL\"}}]}");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        mockPreviewInputs(workflow);

        DolphinDatasourceOption datasourceOption = new DolphinDatasourceOption();
        datasourceOption.setId(501L);
        datasourceOption.setName("ds_other");
        datasourceOption.setType("MYSQL");
        when(dolphinSchedulerService.listDatasources(null, null))
                .thenReturn(Collections.singletonList(datasourceOption));

        DolphinTaskGroupOption groupOption = new DolphinTaskGroupOption();
        groupOption.setId(71);
        groupOption.setName("tg_other");
        when(dolphinSchedulerService.listTaskGroups(null))
                .thenReturn(Collections.singletonList(groupOption));

        WorkflowPublishRepairRequest request = new WorkflowPublishRepairRequest();
        request.setOperator("tester");
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> publishService.repairPublishMetadata(1L, request));

        assertTrue(exception.getMessage().contains("元数据修复未完成"));
        assertTrue(exception.getMessage().contains("datasourceId"));
        verify(workflowService, times(1)).normalizeAndPersistMetadata(1L, "tester");
    }

    @Test
    void previewPublishShouldExposeRepairIssueWhenTaskIdsMissing() {
        WorkflowPublishService previewService = buildPreviewServiceWithRealDiff();
        DataWorkflow workflow = workflow(1L, null, 101L);
        workflow.setDefinitionJson("{\"taskDefinitionList\":[{\"taskCode\":10001,\"taskGroupName\":\"tg_a\","
                + "\"taskParams\":{\"datasourceName\":\"ds_a\",\"type\":\"MYSQL\"}}]}");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        mockPreviewInputs(workflow);

        WorkflowPublishPreviewResponse preview = previewService.previewPublish(1L);
        assertTrue(Boolean.TRUE.equals(preview.getCanPublish()));
        assertTrue(preview.getErrors().isEmpty());
        assertTrue(preview.getRepairIssues().stream()
                .anyMatch(item -> "task.datasourceId".equals(item.getField())));
    }

    private WorkflowPublishService buildPreviewServiceWithRealDiff() {
        return new WorkflowPublishService(
                publishRecordMapper,
                workflowVersionMapper,
                dataWorkflowMapper,
                dataTaskMapper,
                workflowTaskRelationMapper,
                tableTaskRelationMapper,
                runtimeDefinitionService,
                new WorkflowRuntimeDiffService(new com.fasterxml.jackson.databind.ObjectMapper()),
                workflowDeployService,
                dolphinSchedulerService,
                workflowService,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    private void mockPreviewInputs(DataWorkflow workflow) {
        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(workflow.getId());
        relation.setTaskId(10L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(relation));

        DataTask task = new DataTask();
        task.setId(10L);
        task.setTaskName("task_a");
        task.setTaskSql("INSERT INTO dws.t1 SELECT * FROM ods.t1");
        task.setDolphinNodeType("SQL");
        task.setTaskDesc("desc");
        task.setDolphinTaskCode(10001L);
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(task));

        when(tableTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
    }

    private DataWorkflow workflow(Long id, Long workflowCode, Long currentVersionId) {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(id);
        workflow.setWorkflowName("wf_test");
        workflow.setProjectCode(11L);
        workflow.setWorkflowCode(workflowCode);
        workflow.setCurrentVersionId(currentVersionId);
        workflow.setStatus("draft");
        return workflow;
    }

    private WorkflowVersion version(Long id, Integer versionNo) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(id);
        version.setVersionNo(versionNo);
        return version;
    }

    private com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary changedDiff() {
        com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary diff =
                new com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary();
        diff.setChanged(true);
        com.onedata.portal.dto.workflow.runtime.RuntimeTaskChange taskChange =
                new com.onedata.portal.dto.workflow.runtime.RuntimeTaskChange();
        taskChange.setTaskCode(10001L);
        taskChange.setTaskName("task_a");
        diff.setTaskAdded(Collections.singletonList(taskChange));
        return diff;
    }

    private RuntimeDiffSummary noiseOnlyDiff() {
        RuntimeDiffSummary diff = new RuntimeDiffSummary();
        diff.setChanged(true);
        RuntimeTaskChange modified = new RuntimeTaskChange();
        modified.setTaskCode(10001L);
        modified.setTaskName("task_a");
        modified.setFieldChanges(Arrays.asList(
                fieldChange("task.inputTableIds", "[]", "[1]"),
                fieldChange("task.outputTableIds", "[]", "[2]"),
                fieldChange("task.taskVersion", "1", "2")));
        diff.setTaskModified(Collections.singletonList(modified));
        diff.setScheduleChanges(Arrays.asList(
                fieldChange("schedule.releaseState", "OFFLINE", "ONLINE")));
        return diff;
    }

    private RuntimeDiffFieldChange fieldChange(String field, String before, String after) {
        RuntimeDiffFieldChange change = new RuntimeDiffFieldChange();
        change.setField(field);
        change.setBefore(before);
        change.setAfter(after);
        return change;
    }

    private TableTaskRelation tableTaskRelation(Long taskId, Long tableId, String relationType) {
        TableTaskRelation relation = new TableTaskRelation();
        relation.setTaskId(taskId);
        relation.setTableId(tableId);
        relation.setRelationType(relationType);
        return relation;
    }

    private RuntimeWorkflowSchedule runtimeSchedule(String cron, String timezone, String workerGroup, String tenantCode) {
        RuntimeWorkflowSchedule schedule = new RuntimeWorkflowSchedule();
        schedule.setScheduleId(901L);
        schedule.setReleaseState("ONLINE");
        schedule.setCrontab(cron);
        schedule.setTimezoneId(timezone);
        schedule.setStartTime("2026-02-24 02:15:00");
        schedule.setEndTime("2026-08-24 02:15:00");
        schedule.setWorkerGroup(workerGroup);
        schedule.setTenantCode(tenantCode);
        return schedule;
    }

    private RuntimeTaskDefinition runtimeTask(Long code,
            String name,
            String sql,
            List<Long> inputTableIds,
            List<Long> outputTableIds) {
        RuntimeTaskDefinition task = new RuntimeTaskDefinition();
        task.setTaskCode(code);
        task.setTaskName(name);
        task.setNodeType("SQL");
        task.setDatasourceId(10L);
        task.setDatasourceName("doris_ds");
        task.setDatasourceType("DORIS");
        task.setSql(sql);
        task.setInputTableIds(inputTableIds);
        task.setOutputTableIds(outputTableIds);
        return task;
    }
}
