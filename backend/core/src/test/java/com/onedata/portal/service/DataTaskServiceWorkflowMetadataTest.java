package com.onedata.portal.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.dolphin.DolphinTaskInstance;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.exception.BusinessException;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.service.lineage.TaskLineageConsistencyChecker;
import com.onedata.portal.service.lineage.TaskLineageWriteService;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataTaskServiceWorkflowMetadataTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataTask.class);
        TableInfoHelper.initTableInfo(assistant, DataLineage.class);
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, TableTaskRelation.class);
        TableInfoHelper.initTableInfo(assistant, TaskExecutionLog.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
    }

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

    @Mock
    private TaskLineageWriteService taskLineageWriteService;

    @Mock
    private TaskLineageConsistencyChecker taskLineageConsistencyChecker;

    @InjectMocks
    private DataTaskService dataTaskService;

    /**
     * 归档逻辑已收敛到 {@link DataTaskIdentityArchiver}，这里注入真实实现并复用 mock 的
     * {@link DataTaskMapper}，使删除/创建路径上的归档断言仍然直接作用于 mapper 调用。
     */
    @BeforeEach
    void wireIdentityArchiver() {
        ReflectionTestUtils.setField(dataTaskService, "dataTaskIdentityArchiver",
                new DataTaskIdentityArchiver(dataTaskMapper));
    }

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

    /**
     * 省略血缘参数时，update() 会读取库里的既有血缘作为最终值。
     * 已落库的任务必然带输出表（创建时就强制了），这里如实模拟。
     */
    private void stubExistingLineage(Long taskId, Long inputTableId, Long outputTableId) {
        DataLineage input = new DataLineage();
        input.setTaskId(taskId);
        input.setUpstreamTableId(inputTableId);
        input.setLineageType("input");
        DataLineage output = new DataLineage();
        output.setTaskId(taskId);
        output.setDownstreamTableId(outputTableId);
        output.setLineageType("output");
        when(dataLineageMapper.selectList(any())).thenReturn(
                Collections.singletonList(input),
                Collections.singletonList(output));
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
        stubExistingLineage(1L, 11L, 12L);

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
        verify(dataTaskMapper).selectDeletedByTaskCode("task-create-sql-no-datasource-code");
        verify(dataTaskMapper, never()).archiveUniqueIdentity(
                anyLong(), anyString(), anyString(), anyInt());
    }

    @Test
    void createShouldReleaseTaskCodeHeldByPreviouslyDeletedTask() {
        DataTask input = new DataTask();
        input.setTaskName("recreated-task");
        input.setTaskCode("reusable-task-code");
        input.setEngine("dolphin");
        input.setDolphinNodeType("SQL");
        input.setTaskSql("insert into dwd.t2 select * from ods.t2");
        input.setOwner("tester");
        input.setDolphinTaskCode(7001L);
        input.setDolphinTaskVersion(1);
        input.setDolphinFlag("YES");
        input.setPriority(5);
        input.setRetryTimes(1);
        input.setRetryInterval(1);
        input.setTimeoutSeconds(60);

        DataTask deletedTask = new DataTask();
        deletedTask.setId(77L);
        deletedTask.setTaskName("recreated-task");
        deletedTask.setTaskCode("reusable-task-code");
        deletedTask.setDeleted(1);

        when(dataTaskMapper.selectCount(any())).thenReturn(0L);
        when(dataTaskMapper.selectOne(any())).thenReturn(null);
        when(dataTaskMapper.selectDeletedByTaskCode("reusable-task-code")).thenReturn(deletedTask);
        when(dataTaskMapper.archiveUniqueIdentity(
                77L,
                "reusable-task-code__deleted_77",
                "recreated-task__deleted_77",
                1)).thenReturn(1);
        when(dataTaskMapper.insert(input)).thenAnswer(invocation -> {
            input.setId(301L);
            return 1;
        });
        when(dataTaskMapper.selectById(301L)).thenReturn(input);

        DataTask result = dataTaskService.create(
                input,
                Collections.emptyList(),
                Collections.singletonList(32L));

        assertEquals(301L, result.getId());
        verify(dataTaskMapper).archiveUniqueIdentity(
                77L,
                "reusable-task-code__deleted_77",
                "recreated-task__deleted_77",
                1);
        verify(dataTaskMapper).insert(input);
    }

    @Test
    void createShouldFailClearlyWhenDeletedTaskCodeCannotBeReleased() {
        DataTask input = new DataTask();
        input.setTaskName("blocked-recreated-task");
        input.setTaskCode("blocked-reusable-code");
        input.setEngine("dolphin");
        input.setDolphinNodeType("SQL");
        input.setTaskSql("insert into dwd.t2 select * from ods.t2");

        DataTask deletedTask = new DataTask();
        deletedTask.setId(78L);
        deletedTask.setTaskName("blocked-recreated-task");
        deletedTask.setTaskCode("blocked-reusable-code");
        deletedTask.setDeleted(1);

        when(dataTaskMapper.selectCount(any())).thenReturn(0L);
        when(dataTaskMapper.selectOne(any())).thenReturn(null);
        when(dataTaskMapper.selectDeletedByTaskCode("blocked-reusable-code")).thenReturn(deletedTask);
        when(dataTaskMapper.archiveUniqueIdentity(
                78L,
                "blocked-reusable-code__deleted_78",
                "blocked-recreated-task__deleted_78",
                1)).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> dataTaskService.create(
                input,
                Collections.emptyList(),
                Collections.singletonList(32L)));

        assertEquals("任务编码已存在: blocked-reusable-code", error.getMessage());
        verify(dataTaskMapper, never()).insert(any());
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
        stubExistingLineage(301L, 31L, 32L);

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
        task.setTaskCode("task-delete-no-dolphin");
        task.setDolphinProcessCode(123456L);
        when(dataTaskMapper.selectById(401L)).thenReturn(task);

        when(dataTaskMapper.archiveUniqueIdentity(
                401L,
                "task-delete-no-dolphin__deleted_401",
                "task-delete-no-dolphin__deleted_401",
                0)).thenReturn(1);
        when(workflowTaskRelationMapper.hardDeleteByTaskId(401L)).thenReturn(1);
        when(dataTaskMapper.deleteById(401L)).thenReturn(1);

        dataTaskService.delete(401L);

        verify(workflowService).refreshTaskRelations(88L);
        verify(workflowService).normalizeAndPersistMetadata(88L, "system");
        verify(dolphinSchedulerService, never()).setWorkflowReleaseState(anyLong(), anyString());
        verify(dolphinSchedulerService, never()).deleteWorkflow(anyLong());
        verify(workflowTaskRelationMapper).hardDeleteByTaskId(401L);
    }

    @Test
    void deletingRecreatedTaskShouldUseDistinctArchivedUniqueKeys() {
        String longIdentity = String.join("", Collections.nCopies(100, "x"));
        DataTask firstTask = new DataTask();
        firstTask.setId(501L);
        firstTask.setTaskName(longIdentity);
        firstTask.setTaskCode(longIdentity);

        DataTask recreatedTask = new DataTask();
        recreatedTask.setId(502L);
        recreatedTask.setTaskName(longIdentity);
        recreatedTask.setTaskCode(longIdentity);

        when(dataTaskMapper.selectById(501L)).thenReturn(firstTask);
        when(dataTaskMapper.selectById(502L)).thenReturn(recreatedTask);
        when(dataTaskMapper.archiveUniqueIdentity(anyLong(), anyString(), anyString(), anyInt())).thenReturn(1);
        when(dataTaskMapper.deleteById(anyLong())).thenReturn(1);

        dataTaskService.delete(501L);
        dataTaskService.delete(502L);

        ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(dataTaskMapper, times(2)).archiveUniqueIdentity(
                anyLong(), codeCaptor.capture(), nameCaptor.capture(), eq(0));

        assertNotEquals(codeCaptor.getAllValues().get(0), codeCaptor.getAllValues().get(1));
        assertNotEquals(nameCaptor.getAllValues().get(0), nameCaptor.getAllValues().get(1));
        assertTrue(codeCaptor.getAllValues().get(0).endsWith("__deleted_501"));
        assertTrue(codeCaptor.getAllValues().get(1).endsWith("__deleted_502"));
        assertTrue(nameCaptor.getAllValues().get(0).endsWith("__deleted_501"));
        assertTrue(nameCaptor.getAllValues().get(1).endsWith("__deleted_502"));
        assertEquals(100, codeCaptor.getAllValues().get(0).length());
        assertEquals(100, codeCaptor.getAllValues().get(1).length());
        assertEquals(100, nameCaptor.getAllValues().get(0).length());
        assertEquals(100, nameCaptor.getAllValues().get(1).length());
    }

    @Test
    void deleteShouldChooseAnotherArchiveCodeWhenFirstCandidateExists() {
        DataTask task = new DataTask();
        task.setId(601L);
        task.setTaskName("collision-task");
        task.setTaskCode("collision-code");

        when(dataTaskMapper.selectById(601L)).thenReturn(task);
        when(dataTaskMapper.countByTaskCodeIncludingDeleted("collision-code__deleted_601")).thenReturn(1L);
        when(dataTaskMapper.countByTaskCodeIncludingDeleted("collision-code__deleted_601_2")).thenReturn(0L);
        when(dataTaskMapper.archiveUniqueIdentity(
                601L,
                "collision-code__deleted_601_2",
                "collision-task__deleted_601",
                0)).thenReturn(1);
        when(dataTaskMapper.deleteById(601L)).thenReturn(1);

        dataTaskService.delete(601L);

        verify(dataTaskMapper).archiveUniqueIdentity(
                601L,
                "collision-code__deleted_601_2",
                "collision-task__deleted_601",
                0);
        verify(dataTaskMapper).deleteById(601L);
    }

    @Test
    void deleteShouldUseTaskFallbackForMissingNameAndCode() {
        DataTask task = new DataTask();
        task.setId(602L);

        when(dataTaskMapper.selectById(602L)).thenReturn(task);
        when(dataTaskMapper.archiveUniqueIdentity(
                602L,
                "task__deleted_602",
                "task__deleted_602",
                0)).thenReturn(1);
        when(dataTaskMapper.deleteById(602L)).thenReturn(1);

        dataTaskService.delete(602L);

        verify(dataTaskMapper).archiveUniqueIdentity(
                602L,
                "task__deleted_602",
                "task__deleted_602",
                0);
    }

    @Test
    void deleteShouldStopWhenTaskWasConcurrentlyArchived() {
        DataTask task = new DataTask();
        task.setId(603L);
        task.setTaskName("concurrent-task");
        task.setTaskCode("concurrent-code");

        when(dataTaskMapper.selectById(603L)).thenReturn(task);
        when(dataTaskMapper.archiveUniqueIdentity(
                603L,
                "concurrent-code__deleted_603",
                "concurrent-task__deleted_603",
                0)).thenReturn(0);

        dataTaskService.delete(603L);

        verify(dataTaskMapper, never()).deleteById(anyLong());
        verify(dataLineageMapper, never()).delete(any());
        verify(workflowTaskRelationMapper, never()).hardDeleteByTaskId(anyLong());
    }

    @Test
    void listMapsDifferentTaskInstanceStatesWithinOneWorkflowWithoutPerTaskRequests() {
        DataTask firstTask = deployedTask(1L, "first", 1001L);
        DataTask secondTask = deployedTask(2L, "second", 1002L);
        mockTaskPage(Arrays.asList(firstTask, secondTask));

        WorkflowTaskRelation firstRelation = relation(1L, 10L);
        WorkflowTaskRelation secondRelation = relation(2L, 10L);
        when(workflowTaskRelationMapper.selectList(any()))
                .thenReturn(Arrays.asList(firstRelation, secondRelation));

        DataWorkflow workflow = deployedWorkflow(10L, 20L, 3001L);
        when(dataWorkflowMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(workflow));
        when(executionLogMapper.selectList(any())).thenReturn(Collections.emptyList());

        WorkflowInstanceSummary workflowInstance = WorkflowInstanceSummary.builder()
                .instanceId(9001L)
                .state("FAILURE")
                .commandType("SCHEDULER")
                .startTime("2026-07-31 09:00:00")
                .endTime("2026-07-31 09:05:00")
                .build();
        when(dolphinSchedulerService.listWorkflowInstances(20L, 3001L, 1))
                .thenReturn(Collections.singletonList(workflowInstance));

        DolphinTaskInstance firstInstance = taskInstance(8001L, 1001L, "SUCCESS");
        DolphinTaskInstance secondInstance = taskInstance(8002L, 1002L, "FAILURE");
        when(dolphinSchedulerService.listTaskInstances(20L, 9001L))
                .thenReturn(Arrays.asList(firstInstance, secondInstance));

        Page<DataTask> result = dataTaskService.list(
                1, 20, null, null, null, null, null, null, null);

        assertEquals("success", result.getRecords().get(0).getExecutionStatus().getStatus());
        assertEquals("failed", result.getRecords().get(1).getExecutionStatus().getStatus());
        assertEquals("schedule", result.getRecords().get(0).getExecutionStatus().getTriggerType());
        verify(dolphinSchedulerService, times(1)).listWorkflowInstances(20L, 3001L, 1);
        verify(dolphinSchedulerService, times(1)).listTaskInstances(20L, 9001L);
    }

    @Test
    void listMarksMissingTaskWaitingForActiveWorkflow() {
        assertMissingTaskStatus("RUNNING_EXECUTION", "waiting");
    }

    @Test
    void listMarksMissingTaskNotRunForTerminalWorkflow() {
        assertMissingTaskStatus("SUCCESS", "not_run");
    }

    @Test
    void listMarksTaskStatusUnavailableWhenTaskInstanceQueryFails() {
        DataTask task = deployedTask(4L, "unavailable", 1004L);
        mockTaskPage(Collections.singletonList(task));
        when(workflowTaskRelationMapper.selectList(any()))
                .thenReturn(Collections.singletonList(relation(4L, 12L)));
        DataWorkflow workflow = deployedWorkflow(12L, 22L, 3003L);
        when(dataWorkflowMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(workflow));
        when(executionLogMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listWorkflowInstances(22L, 3003L, 1))
                .thenReturn(Collections.singletonList(WorkflowInstanceSummary.builder()
                        .instanceId(9003L)
                        .state("RUNNING_EXECUTION")
                        .build()));
        when(dolphinSchedulerService.listTaskInstances(22L, 9003L))
                .thenThrow(new RuntimeException("offline"));

        Page<DataTask> result = dataTaskService.list(
                1, 20, null, null, null, null, null, null, null);

        assertEquals("unavailable", result.getRecords().get(0).getExecutionStatus().getStatus());
    }

    private void assertMissingTaskStatus(String workflowState, String expectedStatus) {
        DataTask task = deployedTask(3L, "missing", 1003L);
        mockTaskPage(Collections.singletonList(task));
        when(workflowTaskRelationMapper.selectList(any()))
                .thenReturn(Collections.singletonList(relation(3L, 11L)));
        DataWorkflow workflow = deployedWorkflow(11L, 21L, 3002L);
        when(dataWorkflowMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(workflow));
        when(executionLogMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dolphinSchedulerService.listWorkflowInstances(21L, 3002L, 1))
                .thenReturn(Collections.singletonList(WorkflowInstanceSummary.builder()
                        .instanceId(9002L)
                        .state(workflowState)
                        .commandType("START_PROCESS")
                        .startTime("2026-07-31 10:00:00")
                        .build()));
        when(dolphinSchedulerService.listTaskInstances(21L, 9002L))
                .thenReturn(Collections.emptyList());

        Page<DataTask> result = dataTaskService.list(
                1, 20, null, null, null, null, null, null, null);

        assertEquals(expectedStatus, result.getRecords().get(0).getExecutionStatus().getStatus());
    }

    @SuppressWarnings("unchecked")
    private void mockTaskPage(List<DataTask> tasks) {
        when(dataTaskMapper.selectPage(any(Page.class), any())).thenAnswer(invocation -> {
            Page<DataTask> page = invocation.getArgument(0);
            page.setRecords(tasks);
            page.setTotal(tasks.size());
            return page;
        });
    }

    private DataTask deployedTask(Long id, String name, Long taskCode) {
        DataTask task = new DataTask();
        task.setId(id);
        task.setTaskName(name);
        task.setDolphinTaskCode(taskCode);
        return task;
    }

    private WorkflowTaskRelation relation(Long taskId, Long workflowId) {
        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setTaskId(taskId);
        relation.setWorkflowId(workflowId);
        relation.setUpstreamTaskCount(0);
        relation.setDownstreamTaskCount(0);
        return relation;
    }

    private DataWorkflow deployedWorkflow(Long id, Long configId, Long workflowCode) {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(id);
        workflow.setDolphinConfigId(configId);
        workflow.setWorkflowCode(workflowCode);
        workflow.setWorkflowName("workflow-" + id);
        return workflow;
    }

    private DolphinTaskInstance taskInstance(Long instanceId, Long taskCode, String state) {
        DolphinTaskInstance instance = new DolphinTaskInstance();
        instance.setId(instanceId);
        instance.setTaskCode(taskCode);
        instance.setState(state);
        instance.setStartTime("2026-07-31 09:00:00");
        instance.setEndTime("2026-07-31 09:01:00");
        return instance;
    }
}
