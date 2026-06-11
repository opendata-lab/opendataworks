package com.onedata.portal.service;

import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataTaskServiceWorkflowMetadataTest {

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private TaskExecutionLogMapper executionLogMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private DataQueryService dataQueryService;

    @Mock
    private DorisClusterService dorisClusterService;

    @Mock
    private WorkflowService workflowService;

    @InjectMocks
    private DataTaskService dataTaskService;

    @Test
    void getByIdEnrichesWorkflowMetadata() {
        DataTask task = new DataTask();
        task.setId(1L);
        task.setTaskName("task-1");

        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setTaskId(1L);
        relation.setWorkflowId(10L);
        relation.setUpstreamTaskCount(2);
        relation.setDownstreamTaskCount(3);

        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(10L);
        workflow.setWorkflowName("workflow-10");

        when(dataTaskMapper.selectById(1L)).thenReturn(task);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(relation));
        when(dataWorkflowMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(workflow));

        DataTask result = dataTaskService.getById(1L);

        assertNotNull(result);
        assertEquals(10L, result.getWorkflowId());
        assertEquals("workflow-10", result.getWorkflowName());
        assertEquals(2, result.getUpstreamTaskCount());
        assertEquals(3, result.getDownstreamTaskCount());
    }

    @Test
    void updateClearingWorkflowIdRemovesRelationAndRefreshesPreviousWorkflow() {
        DataTask existing = new DataTask();
        existing.setId(1L);
        existing.setTaskName("existing");

        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setId(99L);
        relation.setTaskId(1L);
        relation.setWorkflowId(10L);

        DataTask updatePayload = new DataTask();
        updatePayload.setId(1L);
        updatePayload.setWorkflowId(null);

        when(dataTaskMapper.selectById(1L)).thenReturn(existing);
        when(workflowTaskRelationMapper.selectOne(any())).thenReturn(relation);
        when(dataTaskMapper.updateById(updatePayload)).thenReturn(1);
        when(dataLineageMapper.delete(any())).thenReturn(0);
        when(tableTaskRelationMapper.hardDeleteByTaskId(1L)).thenReturn(1);

        dataTaskService.update(updatePayload, null, null);

        verify(workflowTaskRelationMapper).hardDeleteByTaskId(1L);
        verify(workflowTaskRelationMapper, never()).insert(any());
        verify(workflowTaskRelationMapper, never()).updateById(any());
        verify(workflowService).refreshTaskRelations(10L);
    }

    @Test
    void createShouldIgnoreNullRowsWhenAllocatingNextTaskCode() {
        DataTask input = new DataTask();
        input.setTaskName("task-create-null-row");
        input.setTaskCode("task-create-null-row-code");
        input.setEngine("dolphin");
        input.setDolphinNodeType("SQL");
        input.setDatasourceName("ds_main");
        input.setDatasourceType("MYSQL");
        input.setTaskSql("insert into dwd.t1 select * from ods.t1");
        input.setOwner("tester");

        when(dataTaskMapper.selectCount(any())).thenReturn(0L);
        when(dataTaskMapper.selectOne(any())).thenReturn(null);
        when(dataTaskMapper.insert(any())).thenAnswer(invocation -> {
            DataTask task = invocation.getArgument(0);
            task.setId(101L);
            return 1;
        });

        DataTask persisted = new DataTask();
        persisted.setId(101L);
        persisted.setTaskName("task-create-null-row");
        persisted.setTaskCode("task-create-null-row-code");
        persisted.setEngine("dolphin");
        persisted.setDolphinNodeType("SQL");
        persisted.setDatasourceName("ds_main");
        persisted.setDatasourceType("MYSQL");
        persisted.setTaskSql("insert into dwd.t1 select * from ods.t1");
        persisted.setOwner("tester");
        when(dataTaskMapper.selectById(101L)).thenReturn(persisted);

        DataTask codeRecord = new DataTask();
        codeRecord.setDolphinTaskCode(1001L);
        when(dataTaskMapper.selectList(any())).thenReturn(Arrays.asList(null, codeRecord));
        when(dolphinSchedulerService.nextTaskCode()).thenReturn(2002L);

        DataTask result = dataTaskService.create(input, Collections.singletonList(11L), Collections.singletonList(12L));

        assertNotNull(result);
        assertEquals(101L, result.getId());
        verify(dolphinSchedulerService).alignSequenceWithExistingTasks(Collections.singletonList(1001L));
        verify(dolphinSchedulerService).nextTaskCode();
    }

    @Test
    void createSqlTaskWithoutDatasourceShouldSucceed() {
        DataTask input = new DataTask();
        input.setTaskName("task-create-sql-no-datasource");
        input.setTaskCode("task-create-sql-no-datasource-code");
        input.setEngine("dolphin");
        input.setDolphinNodeType("SQL");
        input.setDatasourceName(null);
        input.setDatasourceType(null);
        input.setTaskSql("insert into dwd.t2 select * from ods.t2");
        input.setOwner("tester");

        when(dataTaskMapper.selectCount(any())).thenReturn(0L);
        when(dataTaskMapper.selectOne(any())).thenReturn(null);
        when(dataTaskMapper.insert(any())).thenAnswer(invocation -> {
            DataTask task = invocation.getArgument(0);
            task.setId(201L);
            return 1;
        });
        DataTask persisted = new DataTask();
        persisted.setId(201L);
        persisted.setTaskName(input.getTaskName());
        persisted.setTaskCode(input.getTaskCode());
        persisted.setEngine("dolphin");
        persisted.setDolphinNodeType("SQL");
        persisted.setTaskSql(input.getTaskSql());
        persisted.setOwner(input.getOwner());
        when(dataTaskMapper.selectById(201L)).thenReturn(persisted);

        DataTask codeRecord = new DataTask();
        codeRecord.setDolphinTaskCode(3001L);
        when(dataTaskMapper.selectList(any())).thenReturn(Collections.singletonList(codeRecord));
        when(dolphinSchedulerService.nextTaskCode()).thenReturn(4002L);

        DataTask result = dataTaskService.create(input, Collections.singletonList(31L), Collections.singletonList(32L));

        assertNotNull(result);
        assertEquals(201L, result.getId());
    }

    @Test
    void createDataXTaskWithoutDatasourceShouldSucceed() {
        DataTask input = new DataTask();
        input.setTaskName("task-create-datax-no-datasource");
        input.setTaskCode("task-create-datax-no-datasource-code");
        input.setEngine("dolphin");
        input.setDolphinNodeType("DATAX");
        input.setDatasourceName(null);
        input.setTargetDatasourceName(null);
        input.setSourceTable("src_table");
        input.setTargetTable("tgt_table");
        input.setTaskSql("select 1");
        input.setOwner("tester");

        when(dataTaskMapper.selectCount(any())).thenReturn(0L);
        when(dataTaskMapper.selectOne(any())).thenReturn(null);
        when(dataTaskMapper.insert(any())).thenAnswer(invocation -> {
            DataTask task = invocation.getArgument(0);
            task.setId(202L);
            return 1;
        });
        DataTask persisted = new DataTask();
        persisted.setId(202L);
        persisted.setTaskName(input.getTaskName());
        persisted.setTaskCode(input.getTaskCode());
        persisted.setEngine("dolphin");
        persisted.setDolphinNodeType("DATAX");
        persisted.setSourceTable(input.getSourceTable());
        persisted.setTargetTable(input.getTargetTable());
        persisted.setTaskSql(input.getTaskSql());
        persisted.setOwner(input.getOwner());
        when(dataTaskMapper.selectById(202L)).thenReturn(persisted);

        DataTask codeRecord = new DataTask();
        codeRecord.setDolphinTaskCode(5001L);
        when(dataTaskMapper.selectList(any())).thenReturn(Collections.singletonList(codeRecord));
        when(dolphinSchedulerService.nextTaskCode()).thenReturn(6002L);

        DataTask result = dataTaskService.create(input, Collections.singletonList(41L), Collections.singletonList(42L));

        assertNotNull(result);
        assertEquals(202L, result.getId());
    }

    @Test
    void updateSqlTaskWithoutDatasourceShouldSucceed() {
        DataTask existing = new DataTask();
        existing.setId(301L);
        existing.setTaskName("task-update-sql-no-datasource");
        existing.setEngine("dolphin");
        existing.setDolphinNodeType("SQL");
        existing.setTaskSql("select 1");

        DataTask updatePayload = new DataTask();
        updatePayload.setId(301L);
        updatePayload.setTaskName("task-update-sql-no-datasource");
        updatePayload.setEngine("dolphin");
        updatePayload.setDolphinNodeType("SQL");
        updatePayload.setDatasourceName(null);
        updatePayload.setDatasourceType(null);
        updatePayload.setTaskSql("insert into dwd.t3 select * from ods.t3");

        DataTask persisted = new DataTask();
        persisted.setId(301L);
        persisted.setTaskName("task-update-sql-no-datasource");
        persisted.setEngine("dolphin");
        persisted.setDolphinNodeType("SQL");
        persisted.setTaskSql("insert into dwd.t3 select * from ods.t3");

        when(dataTaskMapper.selectById(301L)).thenReturn(existing, persisted, persisted);
        when(workflowTaskRelationMapper.selectOne(any())).thenReturn(null);
        when(dataTaskMapper.updateById(updatePayload)).thenReturn(1);
        when(dataLineageMapper.delete(any())).thenReturn(0);
        when(tableTaskRelationMapper.hardDeleteByTaskId(301L)).thenReturn(1);

        DataTask result = dataTaskService.update(updatePayload, null, null);

        assertNotNull(result);
        assertEquals(301L, result.getId());
    }

    @Test
    void publishShouldUseCatalogDatasourceTypeAndPersistCorrection() {
        DataTask target = new DataTask();
        target.setId(10L);
        target.setTaskName("task-publish-oceanbase");
        target.setTaskCode("task-publish-oceanbase-code");
        target.setEngine("dolphin");
        target.setDolphinNodeType("SQL");
        target.setDolphinTaskCode(1001L);
        target.setDolphinTaskVersion(1);
        target.setDatasourceName("oceanbase_prod");
        target.setDatasourceType("DORIS");
        target.setTaskSql("insert into dwd.t_order select * from ods.t_order");
        target.setPriority(5);
        target.setRetryTimes(1);
        target.setRetryInterval(1);
        target.setTimeoutSeconds(60);
        target.setDolphinFlag("YES");

        when(dataTaskMapper.selectById(10L)).thenReturn(target);
        when(dataTaskMapper.selectList(any())).thenReturn(Collections.singletonList(target));
        when(dataLineageMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listTaskGroups(null)).thenReturn(Collections.emptyList());

        DolphinDatasourceOption option = new DolphinDatasourceOption();
        option.setId(901L);
        option.setName("oceanbase_prod");
        option.setType("OCEANBASE");
        when(dolphinSchedulerService.listDatasources(null, null)).thenReturn(Collections.singletonList(option));
        when(dataTaskMapper.updateById(any(DataTask.class))).thenReturn(1);

        dataTaskService.publish(10L);

        ArgumentCaptor<String> datasourceTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(dolphinSchedulerService).buildTaskDefinition(
                anyLong(), anyInt(), anyString(), isNull(), anyString(),
                anyString(), anyInt(), anyInt(), anyInt(), eq("SQL"), eq(901L), datasourceTypeCaptor.capture(),
                any(), any(), any(), any(), any(), anyString(), any(), any());
        assertEquals("OCEANBASE", datasourceTypeCaptor.getValue());

        ArgumentCaptor<DataTask> updateCaptor = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskMapper, atLeastOnce()).updateById(updateCaptor.capture());
        boolean corrected = updateCaptor.getAllValues().stream()
                .filter(task -> task != null && Long.valueOf(10L).equals(task.getId()))
                .anyMatch(task -> "OCEANBASE".equals(task.getDatasourceType()));
        assertEquals(true, corrected);
    }

    @Test
    void deleteShouldNotOperateDolphinAndShouldRefreshLocalWorkflowMetadata() {
        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setTaskId(401L);
        relation.setWorkflowId(88L);
        when(workflowTaskRelationMapper.selectOne(any())).thenReturn(relation);

        DataTask task = new DataTask();
        task.setId(401L);
        task.setTaskName("task-delete-no-dolphin");
        task.setDolphinProcessCode(123456L);
        when(dataTaskMapper.selectById(401L)).thenReturn(task);

        when(dataLineageMapper.delete(any())).thenReturn(2);
        when(tableTaskRelationMapper.hardDeleteByTaskId(401L)).thenReturn(1);
        when(workflowTaskRelationMapper.hardDeleteByTaskId(401L)).thenReturn(1);
        when(dataTaskMapper.deleteById(401L)).thenReturn(1);

        dataTaskService.delete(401L);

        verify(workflowService).refreshTaskRelations(88L);
        verify(workflowService).normalizeAndPersistMetadata(88L, "system");
        verify(dolphinSchedulerService, never()).setWorkflowReleaseState(anyLong(), anyString());
        verify(dolphinSchedulerService, never()).deleteWorkflow(anyLong());
        verify(workflowTaskRelationMapper).hardDeleteByTaskId(401L);
    }
}
