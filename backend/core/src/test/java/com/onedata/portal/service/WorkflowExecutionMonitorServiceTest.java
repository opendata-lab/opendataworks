package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.onedata.portal.dto.dolphin.DolphinTaskInstance;
import com.onedata.portal.dto.execution.WorkflowExecutionPage;
import com.onedata.portal.dto.execution.WorkflowInstanceExecution;
import com.onedata.portal.dto.execution.WorkflowTaskInstanceExecution;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionMonitorServiceTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, TaskExecutionLog.class);
    }

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;
    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;
    @Mock
    private DataTaskMapper dataTaskMapper;
    @Mock
    private TaskExecutionLogMapper executionLogMapper;
    @Mock
    private DolphinSchedulerService dolphinSchedulerService;
    @Mock
    private WorkflowInstanceCacheService workflowInstanceCacheService;

    private WorkflowExecutionMonitorService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowExecutionMonitorService(
                dataWorkflowMapper,
                workflowTaskRelationMapper,
                dataTaskMapper,
                executionLogMapper,
                dolphinSchedulerService,
                workflowInstanceCacheService);
    }

    @Test
    void includesScheduledInstancesDeduplicatesPlatformTriggerAndKeepsPreSubmitFailure() {
        DataWorkflow workflow = workflow();
        when(dataWorkflowMapper.selectList(any())).thenReturn(Collections.singletonList(workflow));
        when(workflowTaskRelationMapper.selectList(any()))
                .thenReturn(Collections.singletonList(relation()));

        TaskExecutionLog matchedPlatformLog = localLog(1L, "9001", "running", "manual");
        TaskExecutionLog preSubmitFailure = localLog(2L, null, "failed", "manual");
        preSubmitFailure.setErrorMessage("submit failed");
        when(executionLogMapper.selectList(any()))
                .thenReturn(Arrays.asList(matchedPlatformLog, preSubmitFailure));

        WorkflowInstanceSummary platformInstance = workflowInstance(9001L, "SUCCESS", "START_PROCESS");
        WorkflowInstanceSummary scheduledInstance = workflowInstance(9002L, "SUCCESS", "SCHEDULER");
        when(dolphinSchedulerService.listRecentProjectInstances(20L, 50))
                .thenReturn(Arrays.asList(platformInstance, scheduledInstance));

        WorkflowExecutionPage page = service.listWorkflowInstances(null, null, false, 1, 20);

        assertEquals(3, page.getTotal());
        assertEquals(3L, page.getStatistics().get("totalExecutions"));
        assertEquals(1, page.getRecords().stream()
                .filter(item -> Long.valueOf(9001L).equals(item.getInstanceId()))
                .count());

        WorkflowInstanceExecution scheduled = findByInstanceId(page.getRecords(), 9002L);
        assertEquals("dolphin", scheduled.getSource());
        assertEquals("schedule", scheduled.getTriggerType());

        WorkflowInstanceExecution platform = findByInstanceId(page.getRecords(), 9001L);
        assertEquals("platform", platform.getSource());
        assertEquals("manual", platform.getTriggerType());

        WorkflowInstanceExecution failure = page.getRecords().stream()
                .filter(item -> Long.valueOf(2L).equals(item.getLocalExecutionLogId()))
                .findFirst()
                .orElseThrow(AssertionError::new);
        assertNull(failure.getInstanceId());
        assertFalse(failure.getExpandable());
        assertEquals("submit failed", failure.getErrorMessage());
        // 全局路径只读不写：缓存由 WorkflowExecutionSyncJob 维护。
        verify(workflowInstanceCacheService, never()).replaceCache(any(), any());
    }

    @Test
    void queriesDolphinOncePerConfigRegardlessOfWorkflowCount() {
        DataWorkflow first = workflow();
        DataWorkflow second = workflow();
        second.setId(11L);
        second.setWorkflowName("workflow-11");
        second.setWorkflowCode(3002L);
        when(dataWorkflowMapper.selectList(any())).thenReturn(Arrays.asList(first, second));
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listRecentProjectInstances(20L, 50))
                .thenReturn(Arrays.asList(
                        instanceOf(3001L, 9001L, "SUCCESS", "SCHEDULER"),
                        instanceOf(3002L, 9002L, "SUCCESS", "SCHEDULER")));

        WorkflowExecutionPage page = service.listWorkflowInstances(null, null, false, 1, 20);

        assertEquals(2, page.getTotal());
        assertEquals("workflow-10", findByInstanceId(page.getRecords(), 9001L).getWorkflowName());
        assertEquals("workflow-11", findByInstanceId(page.getRecords(), 9002L).getWorkflowName());
        verify(dolphinSchedulerService, times(1)).listRecentProjectInstances(20L, 50);
        verify(dolphinSchedulerService, never()).listWorkflowInstances(any(), any(), anyInt());
    }

    @Test
    void dropsProjectInstancesThatBelongToUnmanagedWorkflows() {
        DataWorkflow workflow = workflow();
        when(dataWorkflowMapper.selectList(any())).thenReturn(Collections.singletonList(workflow));
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listRecentProjectInstances(20L, 50))
                .thenReturn(Arrays.asList(
                        instanceOf(3001L, 9001L, "SUCCESS", "SCHEDULER"),
                        instanceOf(8888L, 9999L, "SUCCESS", "SCHEDULER")));

        WorkflowExecutionPage page = service.listWorkflowInstances(null, null, false, 1, 20);

        assertEquals(1, page.getTotal());
        assertEquals(9001L, page.getRecords().get(0).getInstanceId());
    }

    @Test
    void fallsBackToCrossWorkflowCacheWhenProjectQueryFails() {
        DataWorkflow workflow = workflow();
        when(dataWorkflowMapper.selectList(any())).thenReturn(Collections.singletonList(workflow));
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listRecentProjectInstances(20L, 50))
                .thenThrow(new RuntimeException("offline"));
        when(workflowInstanceCacheService.listRecentAcrossWorkflows(50))
                .thenReturn(Collections.singletonList(cachedInstance()));

        WorkflowExecutionPage page = service.listWorkflowInstances(null, "running", false, 1, 10);

        assertEquals(1, page.getTotal());
        assertEquals("cache", page.getRecords().get(0).getExecutionSource());
        assertEquals("schedule", page.getRecords().get(0).getTriggerType());
    }

    @Test
    void fallsBackToWorkflowCacheWhenDolphinIsUnavailable() {
        DataWorkflow workflow = workflow();
        when(dataWorkflowMapper.selectList(any())).thenReturn(Collections.singletonList(workflow));
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listWorkflowInstances(20L, 3001L, 100))
                .thenThrow(new RuntimeException("offline"));
        when(workflowInstanceCacheService.listRecent(10L, 100))
                .thenReturn(Collections.singletonList(cachedInstance()));

        WorkflowExecutionPage page = service.listWorkflowInstances(10L, "running", true, 1, 10);

        assertEquals(1, page.getTotal());
        assertEquals("cache", page.getRecords().get(0).getExecutionSource());
        assertEquals("schedule", page.getRecords().get(0).getTriggerType());
    }

    @Test
    void carriesScheduleTimeAndDolphinDeepLink() {
        DataWorkflow workflow = workflow();
        workflow.setProjectCode(777L);
        when(dataWorkflowMapper.selectList(any())).thenReturn(Collections.singletonList(workflow));
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listRecentProjectInstances(20L, 50))
                .thenReturn(Collections.singletonList(WorkflowInstanceSummary.builder()
                        .instanceId(9001L)
                        .workflowCode(3001L)
                        .state("SUCCESS")
                        .commandType("COMPLEMENT_DATA")
                        .scheduleTime("2026-07-20 00:00:00")
                        .startTime("2026-07-31 10:00:00")
                        .endTime("2026-07-31 10:05:00")
                        .build()));
        when(dolphinSchedulerService.getWebuiBaseUrl(20L)).thenReturn("http://ds.local/");

        WorkflowExecutionPage page = service.listWorkflowInstances(null, null, false, 1, 10);

        WorkflowInstanceExecution record = page.getRecords().get(0);
        assertEquals(LocalDateTime.of(2026, 7, 20, 0, 0), record.getScheduleTime());
        assertEquals("backfill", record.getTriggerType());
        assertEquals("http://ds.local/ui/projects/777/workflow/instances/9001?code=3001",
                record.getDolphinInstanceUrl());
    }

    @Test
    void leavesDeepLinkNullWhenWebuiIsNotConfigured() {
        DataWorkflow workflow = workflow();
        workflow.setProjectCode(777L);
        when(dataWorkflowMapper.selectList(any())).thenReturn(Collections.singletonList(workflow));
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listRecentProjectInstances(20L, 50))
                .thenReturn(Collections.singletonList(
                        instanceOf(3001L, 9001L, "SUCCESS", "SCHEDULER")));
        when(dolphinSchedulerService.getWebuiBaseUrl(20L)).thenReturn(null);

        WorkflowExecutionPage page = service.listWorkflowInstances(null, null, false, 1, 10);

        assertNull(page.getRecords().get(0).getDolphinInstanceUrl());
    }

    @Test
    void expandsDolphinTaskInstancesAndMapsPlatformTaskId() {
        DataWorkflow workflow = workflow();
        when(dataWorkflowMapper.selectById(10L)).thenReturn(workflow);
        when(workflowTaskRelationMapper.selectList(any()))
                .thenReturn(Collections.singletonList(relation()));

        DataTask task = new DataTask();
        task.setId(1L);
        task.setTaskName("platform-task");
        task.setDolphinTaskCode(7001L);
        when(dataTaskMapper.selectBatchIds(any()))
                .thenReturn(Collections.singletonList(task));

        DolphinTaskInstance instance = new DolphinTaskInstance();
        instance.setId(8001L);
        instance.setTaskCode(7001L);
        instance.setName("dolphin-task");
        instance.setState("FAILURE");
        instance.setHost("worker-1");
        instance.setRetryTimes(2);
        instance.setStartTime("2026-07-31 10:00:00");
        instance.setEndTime("2026-07-31 10:01:00");
        when(dolphinSchedulerService.listTaskInstances(20L, 9001L))
                .thenReturn(Collections.singletonList(instance));

        List<WorkflowTaskInstanceExecution> result = service.listTaskInstances(10L, 9001L);

        assertEquals(1, result.size());
        assertEquals(1L, result.get(0).getPlatformTaskId());
        assertEquals("platform-task", result.get(0).getTaskName());
        assertEquals("failed", result.get(0).getStatus());
        assertEquals(60, result.get(0).getDurationSeconds());
        assertTrue(result.get(0).getRetryTimes() == 2);
    }

    private DataWorkflow workflow() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(10L);
        workflow.setWorkflowName("workflow-10");
        workflow.setWorkflowCode(3001L);
        workflow.setDolphinConfigId(20L);
        return workflow;
    }

    private WorkflowTaskRelation relation() {
        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(10L);
        relation.setTaskId(1L);
        return relation;
    }

    private TaskExecutionLog localLog(Long id, String executionId, String status, String triggerType) {
        TaskExecutionLog log = new TaskExecutionLog();
        log.setId(id);
        log.setTaskId(1L);
        log.setExecutionId(executionId);
        log.setStatus(status);
        log.setTriggerType(triggerType);
        log.setStartTime(LocalDateTime.of(2026, 7, 31, 10, id.intValue()));
        return log;
    }

    private WorkflowInstanceSummary workflowInstance(Long id, String state, String commandType) {
        return instanceOf(3001L, id, state, commandType);
    }

    private WorkflowInstanceSummary instanceOf(Long workflowCode, Long id, String state, String commandType) {
        return WorkflowInstanceSummary.builder()
                .instanceId(id)
                .workflowCode(workflowCode)
                .state(state)
                .commandType(commandType)
                .startTime("2026-07-31 10:00:00")
                .endTime("2026-07-31 10:05:00")
                .build();
    }

    private WorkflowInstanceCache cachedInstance() {
        WorkflowInstanceCache cache = new WorkflowInstanceCache();
        cache.setWorkflowId(10L);
        cache.setInstanceId(9100L);
        cache.setState("RUNNING_EXECUTION");
        cache.setTriggerType("SCHEDULER");
        cache.setStartTime(Date.from(
                LocalDateTime.of(2026, 7, 31, 10, 0)
                        .atZone(ZoneId.systemDefault())
                        .toInstant()));
        return cache;
    }

    private WorkflowInstanceExecution findByInstanceId(List<WorkflowInstanceExecution> records, Long id) {
        return records.stream()
                .filter(item -> id.equals(item.getInstanceId()))
                .findFirst()
                .orElseThrow(AssertionError::new);
    }
}
