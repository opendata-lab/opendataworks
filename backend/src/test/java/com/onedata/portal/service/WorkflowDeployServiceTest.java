package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowDeployServiceTest {

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, TableTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, DataTask.class);
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
    }

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private DataWorkflowMapper workflowMapper;

    private WorkflowDeployService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowDeployService(
                workflowTaskRelationMapper,
                dataTaskMapper,
                tableTaskRelationMapper,
                dolphinSchedulerService,
                workflowMapper,
                new ObjectMapper());
    }

    @Test
    void deployShouldFailFastWhenTaskRuntimeMetadataMissing() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(1L);
        workflow.setWorkflowName("wf_test");
        workflow.setProjectCode(11L);
        workflow.setDescription("workflow description");

        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(1L);
        relation.setTaskId(10L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(relation));

        DataTask task = new DataTask();
        task.setId(10L);
        task.setTaskName("task_missing_meta");
        task.setEngine("dolphin");
        task.setDolphinTaskCode(null);
        task.setDolphinTaskVersion(null);
        when(dataTaskMapper.selectBatchIds(anyList())).thenReturn(Collections.singletonList(task));
        when(dolphinSchedulerService.getProjectCode(true)).thenReturn(11L);

        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.deploy(workflow));
        assertTrue(ex.getMessage().contains("缺少 Dolphin 元数据"));
        verify(dolphinSchedulerService, never()).syncWorkflow(
                anyLong(),
                anyString(),
                anyString(),
                anyList(),
                anyList(),
                anyList(),
                any());
    }

    @Test
    void deployShouldBuildDolphinPayloadFromPersistedMetadata() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(1L);
        workflow.setWorkflowName("wf_test");
        workflow.setProjectCode(20001L);
        workflow.setDescription("workflow deploy description");
        workflow.setTaskGroupName("tg_default");
        workflow.setGlobalParams("[]");
        workflow.setDefinitionJson("{\"taskDefinitionList\":["
                + "{\"taskCode\":1001,\"taskGroupId\":77,\"taskParams\":{\"datasourceId\":901,\"datasourceType\":\"MYSQL\"}},"
                + "{\"taskCode\":2002,\"taskGroupId\":77,\"taskParams\":{\"datasourceId\":901,\"datasourceType\":\"MYSQL\"}}]}");

        WorkflowTaskRelation relA = new WorkflowTaskRelation();
        relA.setWorkflowId(1L);
        relA.setTaskId(10L);
        WorkflowTaskRelation relB = new WorkflowTaskRelation();
        relB.setWorkflowId(1L);
        relB.setTaskId(20L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Arrays.asList(relA, relB));

        DataTask taskA = new DataTask();
        taskA.setId(10L);
        taskA.setTaskName("task_a");
        taskA.setTaskDesc("desc-a");
        taskA.setTaskSql("INSERT INTO dwd_a SELECT * FROM ods_a");
        taskA.setEngine("dolphin");
        taskA.setDolphinNodeType("SQL");
        taskA.setDolphinTaskCode(1001L);
        taskA.setDolphinTaskVersion(3);
        taskA.setDatasourceName("ds_main");
        taskA.setDatasourceType("DORIS");
        taskA.setTaskGroupName("tg_sql");
        taskA.setDolphinFlag("NO");
        taskA.setPriority(8);
        taskA.setRetryTimes(4);
        taskA.setRetryInterval(6);
        taskA.setTimeoutSeconds(180);

        DataTask taskB = new DataTask();
        taskB.setId(20L);
        taskB.setTaskName("task_b");
        taskB.setTaskDesc("desc-b");
        taskB.setTaskSql("INSERT INTO dws_b SELECT * FROM dwd_a");
        taskB.setEngine("dolphin");
        taskB.setDolphinNodeType("SQL");
        taskB.setDolphinTaskCode(2002L);
        taskB.setDolphinTaskVersion(5);
        taskB.setDatasourceName("ds_main");
        taskB.setDatasourceType("DORIS");
        taskB.setTaskGroupName("tg_sql");
        taskB.setDolphinFlag("YES");
        taskB.setPriority(5);
        taskB.setRetryTimes(2);
        taskB.setRetryInterval(3);
        taskB.setTimeoutSeconds(90);

        when(dataTaskMapper.selectBatchIds(anyList())).thenReturn(Arrays.asList(taskA, taskB));

        TableTaskRelation relWriteA = new TableTaskRelation();
        relWriteA.setTaskId(10L);
        relWriteA.setTableId(501L);
        relWriteA.setRelationType("write");
        TableTaskRelation relReadB = new TableTaskRelation();
        relReadB.setTaskId(20L);
        relReadB.setTableId(501L);
        relReadB.setRelationType("read");
        when(tableTaskRelationMapper.selectList(any())).thenReturn(Arrays.asList(relWriteA, relReadB));

        when(dolphinSchedulerService.buildTaskDefinition(anyLong(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), anyString(), any(), anyString(), anyString(), any(), any()))
                .thenReturn(Collections.singletonMap("ok", true));
        when(dolphinSchedulerService.buildRelation(anyLong(), anyInt(), anyLong(), anyInt()))
                .thenAnswer(invocation -> dolphinRelation(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
        when(dolphinSchedulerService.buildLocation(anyLong(), anyInt(), anyInt()))
                .thenAnswer(invocation -> dolphinLocation(invocation.getArgument(0)));
        when(dolphinSchedulerService.syncWorkflow(
                anyLong(),
                anyString(),
                anyString(),
                anyList(),
                anyList(),
                anyList(),
                any()))
                .thenReturn(90001L);
        when(dolphinSchedulerService.getProjectCode(true)).thenReturn(11L);

        WorkflowDeployService.DeploymentResult result = service.deploy(workflow);
        assertEquals(90001L, result.getWorkflowCode());
        assertEquals(11L, result.getProjectCode());
        assertEquals(2, result.getTaskCount());

        ArgumentCaptor<Integer> retryTimesCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> retryIntervalCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Integer> timeoutCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<String> priorityCaptor = ArgumentCaptor.forClass(String.class);
        verify(dolphinSchedulerService).buildTaskDefinition(
                eq(1001L), eq(3), eq("task_a"), eq("desc-a"), anyString(),
                priorityCaptor.capture(),
                retryTimesCaptor.capture(),
                retryIntervalCaptor.capture(),
                timeoutCaptor.capture(),
                eq("SQL"), eq(901L), eq("MYSQL"), eq("NO"), eq(77), eq(null));
        assertEquals("HIGH", priorityCaptor.getValue());
        assertEquals(Integer.valueOf(4), retryTimesCaptor.getValue());
        assertEquals(Integer.valueOf(6), retryIntervalCaptor.getValue());
        assertEquals(Integer.valueOf(180), timeoutCaptor.getValue());

        verify(dolphinSchedulerService).buildRelation(1001L, 3, 2002L, 5);
        verify(dolphinSchedulerService).syncWorkflow(
                anyLong(),
                eq("wf_test"),
                eq("workflow deploy description"),
                anyList(),
                anyList(),
                anyList(),
                eq("[]"));
        verify(dolphinSchedulerService, never()).checkWorkflowExists(anyLong());
        verify(dolphinSchedulerService, never()).listDatasources(any(), any());
        verify(dolphinSchedulerService, never()).listTaskGroups(any());
    }

    @Test
    void deployShouldIgnoreStaleWorkflowCodeWhenWorkflowWasResetForFirstDeploy() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(1L);
        workflow.setWorkflowName("wf_switched");
        workflow.setProjectCode(22L);
        workflow.setWorkflowCode(5001L);
        workflow.setPublishStatus("never");
        workflow.setDescription("switched workflow");
        workflow.setGlobalParams("[]");

        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(1L);
        relation.setTaskId(10L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(relation));

        DataTask task = new DataTask();
        task.setId(10L);
        task.setTaskName("task_shell");
        task.setTaskDesc("desc");
        task.setTaskSql("select 1");
        task.setEngine("dolphin");
        task.setDolphinNodeType("SHELL");
        task.setDolphinTaskCode(1001L);
        task.setDolphinTaskVersion(1);
        task.setPriority(5);
        task.setRetryTimes(1);
        task.setRetryInterval(1);
        task.setTimeoutSeconds(60);
        when(dataTaskMapper.selectBatchIds(anyList())).thenReturn(Collections.singletonList(task));
        when(tableTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());

        when(dolphinSchedulerService.buildShellScript(anyString())).thenReturn("echo ok");
        when(dolphinSchedulerService.buildTaskDefinition(anyLong(), anyInt(), anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), anyString(), any(), any(), any(), any(), any()))
                .thenReturn(Collections.singletonMap("ok", true));
        when(dolphinSchedulerService.buildRelation(anyLong(), anyInt(), anyLong(), anyInt()))
                .thenAnswer(invocation -> dolphinRelation(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
        when(dolphinSchedulerService.buildLocation(anyLong(), anyInt(), anyInt()))
                .thenAnswer(invocation -> dolphinLocation(invocation.getArgument(0)));
        when(dolphinSchedulerService.getProjectCode(true)).thenReturn(22L);
        when(dolphinSchedulerService.syncWorkflow(
                anyLong(),
                anyString(),
                anyString(),
                anyList(),
                anyList(),
                anyList(),
                any()))
                .thenReturn(90002L);

        WorkflowDeployService.DeploymentResult result = service.deploy(workflow);

        assertEquals(90002L, result.getWorkflowCode());
        ArgumentCaptor<Long> workflowCodeCaptor = ArgumentCaptor.forClass(Long.class);
        verify(dolphinSchedulerService).syncWorkflow(
                workflowCodeCaptor.capture(),
                eq("wf_switched"),
                eq("switched workflow"),
                anyList(),
                anyList(),
                anyList(),
                eq("[]"));
        assertEquals(Long.valueOf(0L), workflowCodeCaptor.getValue());
        verify(dolphinSchedulerService, never()).checkWorkflowExists(anyLong());
    }

    private DolphinSchedulerService.TaskRelationPayload dolphinRelation(long preCode,
            int preVersion,
            long postCode,
            int postVersion) {
        DolphinSchedulerService.TaskRelationPayload relation = new DolphinSchedulerService.TaskRelationPayload();
        relation.setPreTaskCode(preCode);
        relation.setPreTaskVersion(preVersion);
        relation.setPostTaskCode(postCode);
        relation.setPostTaskVersion(postVersion);
        return relation;
    }

    private DolphinSchedulerService.TaskLocationPayload dolphinLocation(long taskCode) {
        DolphinSchedulerService.TaskLocationPayload payload = new DolphinSchedulerService.TaskLocationPayload();
        payload.setTaskCode(taskCode);
        payload.setX(0);
        payload.setY(0);
        return payload;
    }
}
