package com.onedata.portal.service;

import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowExecutionServiceTest {

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;
    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;
    @Mock
    private TaskExecutionLogMapper taskExecutionLogMapper;
    @Mock
    private DolphinSchedulerService dolphinSchedulerService;
    @Mock
    private DolphinConfigService dolphinConfigService;
    @Mock
    private RuntimeBindingLock runtimeBindingLock;
    @Mock
    private WorkflowDefinitionAssembler workflowDefinitionAssembler;

    private WorkflowExecutionService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowExecutionService(
                dataWorkflowMapper,
                workflowTaskRelationMapper,
                taskExecutionLogMapper,
                dolphinSchedulerService,
                dolphinConfigService,
                runtimeBindingLock,
                workflowDefinitionAssembler);
    }

    @Test
    void executeWorkflowShouldCreateLogAndMarkRunningAfterDolphinStarts() {
        DataWorkflow workflow = onlineWorkflow();
        WorkflowTaskRelation relation = monitorRelation();
        AtomicReference<String> insertedStatus = new AtomicReference<>();
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(workflowTaskRelationMapper.selectOne(any())).thenReturn(relation);
        when(dolphinSchedulerService.startProcessInstance(2L, 5001L, null, "wf_order"))
                .thenReturn("pi-100");
        doAnswer(invocation -> {
            TaskExecutionLog inserted = invocation.getArgument(0);
            insertedStatus.set(inserted.getStatus());
            return 1;
        }).when(taskExecutionLogMapper).insert(any(TaskExecutionLog.class));

        String executionId = service.executeWorkflow(1L);

        assertEquals("pi-100", executionId);
        ArgumentCaptor<TaskExecutionLog> insertCaptor = ArgumentCaptor.forClass(TaskExecutionLog.class);
        verify(taskExecutionLogMapper).insert(insertCaptor.capture());
        TaskExecutionLog insertedLog = insertCaptor.getValue();
        assertEquals(Long.valueOf(101L), insertedLog.getTaskId());
        assertEquals("pending", insertedStatus.get());
        assertEquals("manual", insertedLog.getTriggerType());
        assertNotNull(insertedLog.getStartTime());

        ArgumentCaptor<TaskExecutionLog> updateCaptor = ArgumentCaptor.forClass(TaskExecutionLog.class);
        verify(taskExecutionLogMapper).updateById(updateCaptor.capture());
        TaskExecutionLog updatedLog = updateCaptor.getValue();
        assertSame(insertedLog, updatedLog);
        assertEquals("pi-100", updatedLog.getExecutionId());
        assertEquals("running", updatedLog.getStatus());
    }

    @Test
    void executeWorkflowShouldMarkLogFailedWhenDolphinStartFails() {
        DataWorkflow workflow = onlineWorkflow();
        RuntimeException failure = new RuntimeException("dolphin failed");
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(workflowTaskRelationMapper.selectOne(any())).thenReturn(monitorRelation());
        when(dolphinSchedulerService.startProcessInstance(2L, 5001L, null, "wf_order"))
                .thenThrow(failure);

        RuntimeException thrown = assertThrows(RuntimeException.class, () -> service.executeWorkflow(1L));

        assertSame(failure, thrown);
        ArgumentCaptor<TaskExecutionLog> updateCaptor = ArgumentCaptor.forClass(TaskExecutionLog.class);
        verify(taskExecutionLogMapper).updateById(updateCaptor.capture());
        TaskExecutionLog failedLog = updateCaptor.getValue();
        assertEquals("failed", failedLog.getStatus());
        assertEquals("dolphin failed", failedLog.getErrorMessage());
        assertNotNull(failedLog.getEndTime());
    }

    @Test
    void executeWorkflowShouldStartWithoutLogWhenNoRelationExists() {
        DataWorkflow workflow = onlineWorkflow();
        when(dataWorkflowMapper.selectById(1L)).thenReturn(workflow);
        when(workflowTaskRelationMapper.selectOne(any())).thenReturn(null);
        when(dolphinSchedulerService.startProcessInstance(2L, 5001L, null, "wf_order"))
                .thenReturn("pi-101");

        String executionId = service.executeWorkflow(1L);

        assertEquals("pi-101", executionId);
        verify(taskExecutionLogMapper, never()).insert(any());
        verify(taskExecutionLogMapper, never()).updateById(any());
    }

    @Test
    void backfillWorkflowShouldRejectMissingRequestBeforeCallingDolphin() {
        when(dataWorkflowMapper.selectById(1L)).thenReturn(onlineWorkflow());

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.backfillWorkflow(1L, null));

        assertEquals("补数参数不能为空", thrown.getMessage());
        verify(dolphinSchedulerService, never()).backfillProcessInstance(any(), any(), any());
    }

    @Test
    void backfillWorkflowShouldRejectFutureRangeBeforeWritingAnyLog() {
        WorkflowBackfillRequest request = new WorkflowBackfillRequest();
        request.setMode("range");
        request.setStartTime(LocalDate.now().minusDays(1).atStartOfDay()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        request.setEndTime(LocalDate.now().plusDays(1).atStartOfDay()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        when(dataWorkflowMapper.selectById(1L)).thenReturn(onlineWorkflow());

        IllegalArgumentException thrown =
                assertThrows(IllegalArgumentException.class, () -> service.backfillWorkflow(1L, request));

        assertTrue(thrown.getMessage().startsWith("补数时间不能晚于今天"));
        verify(taskExecutionLogMapper, never()).insert(any());
        verify(dolphinSchedulerService, never()).backfillProcessInstance(any(), any(), any());
    }

    @Test
    void backfillWorkflowShouldCreateLogAndTriggerDolphin() {
        WorkflowBackfillRequest request = new WorkflowBackfillRequest();
        request.setMode("range");
        request.setStartTime(LocalDate.now().minusDays(2).atStartOfDay()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        request.setEndTime(LocalDate.now().minusDays(1).atStartOfDay()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        when(dataWorkflowMapper.selectById(1L)).thenReturn(onlineWorkflow());
        when(workflowTaskRelationMapper.selectOne(any())).thenReturn(monitorRelation());
        when(dolphinSchedulerService.backfillProcessInstance(eq(2L), eq(5001L), eq(request)))
                .thenReturn("backfill-1");

        String triggerId = service.backfillWorkflow(1L, request);

        assertEquals("backfill-1", triggerId);
        ArgumentCaptor<TaskExecutionLog> insertCaptor = ArgumentCaptor.forClass(TaskExecutionLog.class);
        verify(taskExecutionLogMapper).insert(insertCaptor.capture());
        assertEquals("backfill", insertCaptor.getValue().getTriggerType());
        ArgumentCaptor<TaskExecutionLog> updateCaptor = ArgumentCaptor.forClass(TaskExecutionLog.class);
        verify(taskExecutionLogMapper).updateById(updateCaptor.capture());
        assertEquals("backfill-1", updateCaptor.getValue().getExecutionId());
        assertEquals("running", updateCaptor.getValue().getStatus());
    }

    private DataWorkflow onlineWorkflow() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(1L);
        workflow.setWorkflowName("wf_order");
        workflow.setWorkflowCode(5001L);
        workflow.setDolphinConfigId(2L);
        workflow.setStatus("online");
        return workflow;
    }

    private WorkflowTaskRelation monitorRelation() {
        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(1L);
        relation.setTaskId(101L);
        relation.setIsEntry(true);
        return relation;
    }
}
