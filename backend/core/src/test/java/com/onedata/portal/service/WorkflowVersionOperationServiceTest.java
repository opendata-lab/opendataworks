package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionDeleteResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionErrorCodes;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackRequest;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowRuntimeSyncRecord;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowRuntimeSyncRecordMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowVersionOperationServiceTest {

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowVersion.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowPublishRecord.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowRuntimeSyncRecord.class);
    }

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private WorkflowPublishRecordMapper workflowPublishRecordMapper;

    @Mock
    private WorkflowRuntimeSyncRecordMapper workflowRuntimeSyncRecordMapper;

    @Mock
    private DataTaskService dataTaskService;

    @Mock
    private WorkflowService workflowService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private WorkflowVersionOperationService service;

    @BeforeEach
    void setUp() {
        service = new WorkflowVersionOperationService(
                workflowVersionMapper,
                dataWorkflowMapper,
                dataTaskMapper,
                workflowPublishRecordMapper,
                workflowRuntimeSyncRecordMapper,
                dataTaskService,
                workflowService,
                objectMapper);
    }

    @Test
    void compareShouldSwapLeftAndRightWhenLeftGreaterThanRight() {
        WorkflowVersion version1 = version(1L, 11L, 1, canonicalSnapshot("wf", "task_a"));
        WorkflowVersion version2 = version(2L, 11L, 2, canonicalSnapshot("wf2", "task_b"));

        when(workflowVersionMapper.selectById(1L)).thenReturn(version1);
        when(workflowVersionMapper.selectById(2L)).thenReturn(version2);

        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(2L);
        request.setRightVersionId(1L);

        WorkflowVersionCompareResponse response = service.compare(11L, request);

        assertEquals(1L, response.getLeftVersionId());
        assertEquals(2L, response.getRightVersionId());
        assertNotNull(response.getRawDiff());
        assertTrue(response.getRawDiff().contains("--- v1"));
        assertTrue(response.getRawDiff().contains("+++ v2"));
    }

    @Test
    void compareShouldFailWhenLeftEqualsRight() {
        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(2L);
        request.setRightVersionId(2L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.compare(11L, request));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_COMPARE_INVALID));
    }

    @Test
    void compareShouldTreatNullLeftAsEmptyBaseline() {
        WorkflowVersion rightVersion = version(2L, 11L, 2, canonicalSnapshot("wf2", "task_b"));
        when(workflowVersionMapper.selectById(2L)).thenReturn(rightVersion);

        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(null);
        request.setRightVersionId(2L);

        WorkflowVersionCompareResponse response = service.compare(11L, request);

        assertTrue(Boolean.TRUE.equals(response.getChanged()));
        assertTrue((response.getAdded().getTasks().size() + response.getAdded().getWorkflowFields().size()) > 0);
        assertEquals(0, response.getSummary().getRemoved());
        assertNotNull(response.getRawDiff());
        assertTrue(response.getRawDiff().contains("--- empty"));
    }

    @Test
    void compareShouldFailWhenVersionIsNotV3() {
        String legacyWithoutDefinition = "{\"schemaVersion\":2,\"workflow\":{\"workflowName\":\"wf\"},"
                + "\"tasks\":[{\"taskId\":1,\"taskName\":\"t1\"}],\"edges\":[],\"schedule\":{}}";
        WorkflowVersion v1 = versionWithSchema(31L, 11L, 1, legacyWithoutDefinition, 2);
        WorkflowVersion v2 = versionWithSchema(32L, 11L, 2, legacyWithoutDefinition, 2);
        when(workflowVersionMapper.selectById(31L)).thenReturn(v1);
        when(workflowVersionMapper.selectById(32L)).thenReturn(v2);

        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(31L);
        request.setRightVersionId(32L);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.compare(11L, request));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_COMPARE_ONLY_V3));
        assertTrue(ex.getMessage().contains("snapshotSchemaVersion=2"));
    }

    @Test
    void rollbackShouldFailForLegacySnapshot() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setWorkflowName("wf");

        WorkflowVersion legacyVersion = versionWithSchema(1L, 11L, 1,
                "{\"workflowId\":11,\"workflowName\":\"wf\",\"tasks\":[{\"taskId\":1}]}", 1);

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(1L)).thenReturn(legacyVersion);

        WorkflowVersionRollbackRequest request = new WorkflowVersionRollbackRequest();
        request.setOperator("tester");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.rollback(11L, 1L, request));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_ROLLBACK_ONLY_V3));
    }

    @Test
    void rollbackShouldFailWhenV3DefinitionMissingTaskId() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setWorkflowName("wf");
        workflow.setProjectCode(9527L);
        workflow.setDescription("desc");
        workflow.setGlobalParams("[]");
        workflow.setTaskGroupName("tg");

        Map<String, Object> definition = new LinkedHashMap<>();
        definition.put("schemaVersion", 3);
        Map<String, Object> process = new LinkedHashMap<>();
        process.put("name", "wf");
        process.put("taskGroupName", "tg");
        process.put("releaseState", "draft");
        definition.put("processDefinition", process);
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("code", 101L);
        task.put("taskCode", 101L);
        task.put("name", "task_a");
        task.put("taskName", "task_a");
        task.put("taskType", "SQL");
        task.put("nodeType", "SQL");
        task.put("taskParams", Collections.singletonMap("sql", "select 1"));
        task.put("inputTableIds", Collections.emptyList());
        task.put("outputTableIds", Collections.emptyList());
        // 故意缺少 xPlatformTaskMeta.taskId
        definition.put("taskDefinitionList", Collections.singletonList(task));
        definition.put("processTaskRelationList", Collections.singletonList(definitionRelationNode(0L, 101L)));
        definition.put("schedule", Collections.emptyMap());

        String definitionJson;
        try {
            definitionJson = objectMapper.writeValueAsString(definition);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        WorkflowVersion target = versionWithSchema(88L, 11L, 8, definitionJson, 3);

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(88L)).thenReturn(target);

        WorkflowVersionRollbackRequest request = new WorkflowVersionRollbackRequest();
        request.setOperator("tester");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.rollback(11L, 88L, request));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_ROLLBACK_TASK_ID_REQUIRED));
    }

    @Test
    void rollbackShouldRestoreTaskDolphinFlagFromSnapshot() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setWorkflowName("wf");
        workflow.setProjectCode(9527L);
        workflow.setDescription("desc");
        workflow.setGlobalParams("[]");
        workflow.setTaskGroupName("tg");
        workflow.setCurrentVersionId(99L);

        Map<String, Object> task = taskNode(10L, "task_a", "select 1", "mysql_ds",
                Collections.singletonList(1L), Collections.singletonList(2L));
        task.put("dolphinFlag", "NO");
        WorkflowVersion target = versionWithSchema(88L, 11L, 8,
                canonicalSnapshot("wf", Collections.singletonList(task), Collections.emptyList()), 3);

        DataTask existingTask = new DataTask();
        existingTask.setId(10L);
        existingTask.setTaskName("task_a");
        existingTask.setTaskCode("task_a");
        existingTask.setTaskType("batch");
        existingTask.setEngine("dolphin");
        existingTask.setDolphinNodeType("SQL");
        existingTask.setTaskSql("select 1");
        existingTask.setDatasourceName("mysql_ds");
        existingTask.setDatasourceType("MYSQL");
        existingTask.setTaskGroupName("tg-sql");
        existingTask.setDolphinFlag("YES");
        existingTask.setRetryTimes(1);
        existingTask.setRetryInterval(1);
        existingTask.setTimeoutSeconds(300);
        existingTask.setPriority(5);
        existingTask.setOwner("owner_a");
        existingTask.setDolphinProcessCode(1001L);
        existingTask.setDolphinTaskCode(10L);
        existingTask.setDolphinTaskVersion(1);

        DataWorkflow updatedWorkflow = new DataWorkflow();
        updatedWorkflow.setId(11L);
        updatedWorkflow.setCurrentVersionId(99L);

        WorkflowVersion newVersion = new WorkflowVersion();
        newVersion.setId(99L);
        newVersion.setVersionNo(9);

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(88L)).thenReturn(target);
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(existingTask));
        when(workflowService.updateWorkflow(eq(11L), any())).thenReturn(updatedWorkflow);
        when(workflowVersionMapper.selectById(99L)).thenReturn(newVersion);

        WorkflowVersionRollbackRequest request = new WorkflowVersionRollbackRequest();
        request.setOperator("tester");

        WorkflowVersionRollbackResponse response = service.rollback(11L, 88L, request);

        assertEquals(99L, response.getNewVersionId());
        ArgumentCaptor<DataTask> taskCaptor = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskService).update(taskCaptor.capture(), eq(Collections.singletonList(1L)), eq(Collections.singletonList(2L)));
        assertEquals("NO", taskCaptor.getValue().getDolphinFlag());
    }

    @Test
    void deleteShouldFailWhenTargetIsLatestSuccessfulPublishedVersion() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setCurrentVersionId(5L);
        WorkflowVersion target = version(4L, 11L, 4, canonicalSnapshot("wf", "task_a"));
        WorkflowPublishRecord latestSuccess = new WorkflowPublishRecord();
        latestSuccess.setId(99L);
        latestSuccess.setWorkflowId(11L);
        latestSuccess.setVersionId(4L);
        latestSuccess.setStatus("success");

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(4L)).thenReturn(target);
        when(workflowPublishRecordMapper.selectOne(any())).thenReturn(latestSuccess);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.deleteVersion(11L, 4L));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_DELETE_FORBIDDEN));
        verify(workflowVersionMapper, never()).delete(any());
    }

    @Test
    void deleteShouldFailWhenTargetIsCurrentVersion() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setCurrentVersionId(5L);
        WorkflowVersion target = version(5L, 11L, 5, canonicalSnapshot("wf", "task_a"));
        WorkflowPublishRecord latestSuccess = new WorkflowPublishRecord();
        latestSuccess.setId(102L);
        latestSuccess.setWorkflowId(11L);
        latestSuccess.setVersionId(4L);
        latestSuccess.setStatus("success");

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(5L)).thenReturn(target);
        when(workflowPublishRecordMapper.selectOne(any())).thenReturn(latestSuccess);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.deleteVersion(11L, 5L));
        assertTrue(ex.getMessage().contains(WorkflowVersionErrorCodes.VERSION_DELETE_FORBIDDEN));
        verify(workflowVersionMapper, never()).delete(any());
    }

    @Test
    void deleteShouldSucceedForDeletablePublishedHistoryVersion() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(11L);
        workflow.setCurrentVersionId(6L);
        WorkflowVersion target = version(4L, 11L, 4, canonicalSnapshot("wf", "task_a"));

        WorkflowPublishRecord latestSuccess = new WorkflowPublishRecord();
        latestSuccess.setId(101L);
        latestSuccess.setWorkflowId(11L);
        latestSuccess.setVersionId(6L);
        latestSuccess.setStatus("success");

        when(dataWorkflowMapper.selectById(11L)).thenReturn(workflow);
        when(workflowVersionMapper.selectById(4L)).thenReturn(target);
        when(workflowPublishRecordMapper.selectOne(any())).thenReturn(latestSuccess);
        when(workflowVersionMapper.delete(any())).thenReturn(1);

        WorkflowVersionDeleteResponse response = service.deleteVersion(11L, 4L);

        assertEquals(11L, response.getWorkflowId());
        assertEquals(4L, response.getDeletedVersionId());
        assertEquals(4, response.getDeletedVersionNo());
        verify(workflowVersionMapper).delete(any());
        verify(dataWorkflowMapper, never()).update(any(), any());
    }

    @Test
    void compareShouldDetectCoreDiffsAcrossMultipleWorkflowSaves() {
        final long workflowId = 11L;

        WorkflowVersion v1 = version(101L, workflowId, 1, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user", "select id,name from ods.user", "ods_ds",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v2 = version(102L, workflowId, 2, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user", "select id,name from ods.user", "ods_ds",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L)),
                        taskNode(3L, "agg_user", "insert into ads.user_cnt select count(*) from dwd.user", "ads_ds",
                                Arrays.asList(30L), Arrays.asList(40L))
                ),
                Arrays.asList(edgeNode(1L, 2L), edgeNode(2L, 3L))
        ));

        WorkflowVersion v3 = version(103L, workflowId, 3, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user", "select id,name from ods.user", "ods_ds",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v4 = version(104L, workflowId, 4, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user", "select id,name from ods.user where dt='${bizdate}'", "ods_ds",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v5 = version(105L, workflowId, 5, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user", "select id,name from ods.user where dt='${bizdate}'", "ods_ds_v2",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v6 = version(106L, workflowId, 6, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user_v2", "select id,name from ods.user where dt='${bizdate}'", "ods_ds_v2",
                                Arrays.asList(10L), Arrays.asList(20L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v7 = version(107L, workflowId, 7, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user_v2", "select id,name from ods.user where dt='${bizdate}'", "ods_ds_v2",
                                Arrays.asList(11L), Arrays.asList(21L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(1L, 2L))
        ));

        WorkflowVersion v8 = version(108L, workflowId, 8, canonicalSnapshot(
                "wf",
                Arrays.asList(
                        taskNode(1L, "extract_user_v2", "select id,name from ods.user where dt='${bizdate}'", "ods_ds_v2",
                                Arrays.asList(11L), Arrays.asList(21L)),
                        taskNode(2L, "load_dwd_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                                Arrays.asList(20L), Arrays.asList(30L))
                ),
                Collections.singletonList(edgeNode(2L, 1L))
        ));

        Map<Long, WorkflowVersion> versions = new LinkedHashMap<>();
        versions.put(v1.getId(), v1);
        versions.put(v2.getId(), v2);
        versions.put(v3.getId(), v3);
        versions.put(v4.getId(), v4);
        versions.put(v5.getId(), v5);
        versions.put(v6.getId(), v6);
        versions.put(v7.getId(), v7);
        versions.put(v8.getId(), v8);
        when(workflowVersionMapper.selectById(any())).thenAnswer(invocation -> {
            Long versionId = invocation.getArgument(0);
            return versions.get(versionId);
        });

        WorkflowVersionCompareResponse addTask = compare(workflowId, v1.getId(), v2.getId());
        assertListContains(addTask.getAdded().getTasks(), "agg_user", "新增任务应被识别");

        WorkflowVersionCompareResponse removeTask = compare(workflowId, v2.getId(), v3.getId());
        assertListContains(removeTask.getRemoved().getTasks(), "agg_user", "删除任务应被识别");

        WorkflowVersionCompareResponse modifySql = compare(workflowId, v3.getId(), v4.getId());
        assertListContains(modifySql.getModified().getTasks(), "extract_user", "任务 SQL 修改应被识别");

        WorkflowVersionCompareResponse modifyDatasource = compare(workflowId, v4.getId(), v5.getId());
        assertListContains(modifyDatasource.getModified().getTasks(), "extract_user", "任务数据源修改应被识别");

        WorkflowVersionCompareResponse modifyTaskName = compare(workflowId, v5.getId(), v6.getId());
        assertListContains(modifyTaskName.getModified().getTasks(), "extract_user_v2", "任务名称修改应被识别");

        WorkflowVersionCompareResponse modifyInputOutput = compare(workflowId, v6.getId(), v7.getId());
        assertListContains(modifyInputOutput.getModified().getTasks(), "extract_user_v2", "输入输出表修改应被识别");

        WorkflowVersionCompareResponse modifyRelation = compare(workflowId, v7.getId(), v8.getId());
        assertListContains(modifyRelation.getAdded().getEdges(), "2->1", "任务关系新增边应被识别");
        assertListContains(modifyRelation.getRemoved().getEdges(), "1->2", "任务关系删除边应被识别");
    }

    @Test
    void compareShouldDetectWorkflowAndScheduleDiffsAcrossMultipleWorkflowSaves() {
        final long workflowId = 22L;
        List<Map<String, Object>> tasks = Collections.singletonList(
                taskNode(1L, "extract_user", "select id,name from ods.user", "ods_ds",
                        Collections.singletonList(10L), Collections.singletonList(20L))
        );
        List<Map<String, Object>> edges = Collections.emptyList();

        WorkflowVersion v1 = version(201L, workflowId, 1, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v1",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-01\"}]", "tg-alpha"),
                tasks,
                edges,
                scheduleNode("0 0 1 * * ?", "Asia/Shanghai",
                        "2026-01-01 00:00:00", "2026-12-31 23:59:59",
                        "CONTINUE", "NONE", 101L,
                        "MEDIUM", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v2 = version(202L, workflowId, 2, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v1",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-alpha"),
                tasks,
                edges,
                scheduleNode("0 0 1 * * ?", "Asia/Shanghai",
                        "2026-01-01 00:00:00", "2026-12-31 23:59:59",
                        "CONTINUE", "NONE", 101L,
                        "MEDIUM", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v3 = version(203L, workflowId, 3, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v2",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-alpha"),
                tasks,
                edges,
                scheduleNode("0 0 1 * * ?", "Asia/Shanghai",
                        "2026-01-01 00:00:00", "2026-12-31 23:59:59",
                        "CONTINUE", "NONE", 101L,
                        "MEDIUM", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v4 = version(204L, workflowId, 4, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v2",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 0 1 * * ?", "Asia/Shanghai",
                        "2026-01-01 00:00:00", "2026-12-31 23:59:59",
                        "CONTINUE", "NONE", 101L,
                        "MEDIUM", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v5 = version(205L, workflowId, 5, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v2",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 30 2 * * ?", "UTC",
                        "2026-02-01 00:00:00", "2027-01-31 23:59:59",
                        "CONTINUE", "NONE", 101L,
                        "MEDIUM", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v6 = version(206L, workflowId, 6, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v2",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 30 2 * * ?", "UTC",
                        "2026-02-01 00:00:00", "2027-01-31 23:59:59",
                        "END", "FAILURE", 202L,
                        "HIGHEST", "default", "default",
                        1L, true)
        ));

        WorkflowVersion v7 = version(207L, workflowId, 7, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v2",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 30 2 * * ?", "UTC",
                        "2026-02-01 00:00:00", "2027-01-31 23:59:59",
                        "END", "FAILURE", 202L,
                        "HIGHEST", "high-mem", "tenant_a",
                        9L, false)
        ));

        WorkflowVersion v8 = version(208L, workflowId, 8, canonicalSnapshot(
                workflowNode("wf_schedule", null,
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 30 2 * * ?", "UTC",
                        "2026-02-01 00:00:00", null,
                        "END", "FAILURE", 202L,
                        "HIGHEST", "high-mem", "tenant_a",
                        9L, false)
        ));

        WorkflowVersion v9 = version(209L, workflowId, 9, canonicalSnapshot(
                workflowNode("wf_schedule", "desc-v3",
                        "[{\"prop\":\"bizdate\",\"value\":\"2026-01-02\"}]", "tg-beta"),
                tasks,
                edges,
                scheduleNode("0 30 2 * * ?", "UTC",
                        "2026-02-01 00:00:00", "2027-12-31 23:59:59",
                        "END", "FAILURE", 202L,
                        "HIGHEST", "high-mem", "tenant_a",
                        9L, false)
        ));

        Map<Long, WorkflowVersion> versions = new LinkedHashMap<>();
        versions.put(v1.getId(), v1);
        versions.put(v2.getId(), v2);
        versions.put(v3.getId(), v3);
        versions.put(v4.getId(), v4);
        versions.put(v5.getId(), v5);
        versions.put(v6.getId(), v6);
        versions.put(v7.getId(), v7);
        versions.put(v8.getId(), v8);
        versions.put(v9.getId(), v9);
        when(workflowVersionMapper.selectById(any())).thenAnswer(invocation -> {
            Long versionId = invocation.getArgument(0);
            return versions.get(versionId);
        });

        WorkflowVersionCompareResponse modifyGlobalParams = compare(workflowId, v1.getId(), v2.getId());
        assertListContains(modifyGlobalParams.getModified().getWorkflowFields(),
                "workflow.globalParams", "全局变量修改应被识别");
        assertListContains(modifyGlobalParams.getUnchanged().getTasks(),
                "extract_user", "仅改全局变量时任务应保持不变");

        WorkflowVersionCompareResponse modifyDescription = compare(workflowId, v2.getId(), v3.getId());
        assertListContains(modifyDescription.getModified().getWorkflowFields(),
                "workflow.description", "工作流描述修改应被识别");

        WorkflowVersionCompareResponse modifyTaskGroup = compare(workflowId, v3.getId(), v4.getId());
        assertListContains(modifyTaskGroup.getModified().getWorkflowFields(),
                "workflow.taskGroupName", "工作流任务组修改应被识别");

        WorkflowVersionCompareResponse modifyScheduleCore = compare(workflowId, v4.getId(), v5.getId());
        assertListContains(modifyScheduleCore.getModified().getSchedules(),
                "schedule.scheduleCron", "调度 cron 修改应被识别");
        assertListContains(modifyScheduleCore.getModified().getSchedules(),
                "schedule.scheduleTimezone", "调度时区修改应被识别");
        assertListContains(modifyScheduleCore.getModified().getSchedules(),
                "schedule.scheduleStartTime", "调度开始时间修改应被识别");
        assertListContains(modifyScheduleCore.getModified().getSchedules(),
                "schedule.scheduleEndTime", "调度结束时间修改应被识别");

        WorkflowVersionCompareResponse modifySchedulePolicy = compare(workflowId, v5.getId(), v6.getId());
        assertListContains(modifySchedulePolicy.getModified().getSchedules(),
                "schedule.scheduleFailureStrategy", "调度失败策略修改应被识别");
        assertListContains(modifySchedulePolicy.getModified().getSchedules(),
                "schedule.scheduleWarningType", "调度告警类型修改应被识别");
        assertListContains(modifySchedulePolicy.getModified().getSchedules(),
                "schedule.scheduleWarningGroupId", "调度告警组修改应被识别");
        assertListContains(modifySchedulePolicy.getModified().getSchedules(),
                "schedule.scheduleProcessInstancePriority", "调度优先级修改应被识别");

        WorkflowVersionCompareResponse modifyScheduleRuntime = compare(workflowId, v6.getId(), v7.getId());
        assertListContains(modifyScheduleRuntime.getModified().getSchedules(),
                "schedule.scheduleWorkerGroup", "调度 workerGroup 修改应被识别");
        assertListContains(modifyScheduleRuntime.getModified().getSchedules(),
                "schedule.scheduleTenantCode", "调度租户修改应被识别");
        assertListContains(modifyScheduleRuntime.getModified().getSchedules(),
                "schedule.scheduleEnvironmentCode", "调度环境修改应被识别");
        assertListContains(modifyScheduleRuntime.getModified().getSchedules(),
                "schedule.scheduleAutoOnline", "调度自动上线开关修改应被识别");

        WorkflowVersionCompareResponse removeWorkflowAndScheduleField = compare(workflowId, v7.getId(), v8.getId());
        assertListContains(removeWorkflowAndScheduleField.getRemoved().getWorkflowFields(),
                "workflow.description", "工作流描述删除应被识别");
        assertListContains(removeWorkflowAndScheduleField.getRemoved().getSchedules(),
                "schedule.scheduleEndTime", "调度结束时间删除应被识别");

        WorkflowVersionCompareResponse addWorkflowAndScheduleField = compare(workflowId, v8.getId(), v9.getId());
        assertListContains(addWorkflowAndScheduleField.getAdded().getWorkflowFields(),
                "workflow.description", "工作流描述新增应被识别");
        assertListContains(addWorkflowAndScheduleField.getAdded().getSchedules(),
                "schedule.scheduleEndTime", "调度结束时间新增应被识别");
    }

    @Test
    void compareShouldUseV3DefinitionDocument() {
        final long workflowId = 33L;

        String definitionV1 = platformDefinitionJson(
                "wf_def",
                "tg-alpha",
                "select id,name from ods.user",
                "0 0 1 * * ?",
                Collections.singletonList(definitionRelationNode(1L, 2L))
        );
        String definitionV2 = platformDefinitionJson(
                "wf_def",
                "tg-beta",
                "select id,name from ods.user where dt='${bizdate}'",
                "0 30 1 * * ?",
                Collections.singletonList(definitionRelationNode(2L, 1L))
        );

        WorkflowVersion v1 = version(301L, workflowId, 1, definitionV1);
        WorkflowVersion v2 = version(302L, workflowId, 2, definitionV2);

        when(workflowVersionMapper.selectById(301L)).thenReturn(v1);
        when(workflowVersionMapper.selectById(302L)).thenReturn(v2);

        WorkflowVersionCompareResponse response = compare(workflowId, 301L, 302L);
        assertTrue(Boolean.TRUE.equals(response.getChanged()));
        assertListContains(response.getModified().getTasks(), "extract_user_def",
                "应基于 definitionJson 识别任务 SQL 变更，而不是旧快照 tasks");
        assertListContains(response.getAdded().getEdges(), "2->1",
                "应基于 definitionJson 识别新增关系边");
        assertListContains(response.getRemoved().getEdges(), "1->2",
                "应基于 definitionJson 识别删除关系边");
        assertListContains(response.getModified().getWorkflowFields(), "workflow.taskGroupName",
                "应基于 definitionJson 识别 workflow 字段变更");
        assertListContains(response.getModified().getSchedules(), "schedule.scheduleCron",
                "应基于 V3 definition 识别调度字段变更");
    }

    @Test
    void compareShouldIgnoreTaskAndEdgeOrderInRawDiff() {
        final long workflowId = 34L;

        List<Map<String, Object>> leftTasks = Arrays.asList(
                taskNode(2L, "load_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                        Collections.singletonList(20L), Collections.singletonList(30L)),
                taskNode(1L, "extract_user", "select id,name from ods.user", "ods_ds",
                        Collections.singletonList(10L), Collections.singletonList(20L)),
                taskNode(3L, "agg_user", "insert into ads.user_cnt select count(*) from dwd.user", "ads_ds",
                        Collections.singletonList(30L), Collections.singletonList(40L))
        );
        List<Map<String, Object>> rightTasks = Arrays.asList(
                taskNode(1L, "extract_user", "select id,name from ods.user", "ods_ds",
                        Collections.singletonList(10L), Collections.singletonList(20L)),
                taskNode(3L, "agg_user", "insert into ads.user_cnt select count(*) from dwd.user", "ads_ds",
                        Collections.singletonList(30L), Collections.singletonList(40L)),
                taskNode(2L, "load_user", "insert into dwd.user select * from tmp.user", "dwd_ds",
                        Collections.singletonList(20L), Collections.singletonList(30L))
        );
        List<Map<String, Object>> leftEdges = Arrays.asList(edgeNode(2L, 3L), edgeNode(1L, 2L));
        List<Map<String, Object>> rightEdges = Arrays.asList(edgeNode(1L, 2L), edgeNode(2L, 3L));

        WorkflowVersion v1 = version(351L, workflowId, 1, canonicalSnapshot("wf_order", leftTasks, leftEdges));
        WorkflowVersion v2 = version(352L, workflowId, 2, canonicalSnapshot("wf_order", rightTasks, rightEdges));

        when(workflowVersionMapper.selectById(351L)).thenReturn(v1);
        when(workflowVersionMapper.selectById(352L)).thenReturn(v2);

        WorkflowVersionCompareResponse response = compare(workflowId, 351L, 352L);
        assertFalse(Boolean.TRUE.equals(response.getChanged()), "仅任务/边顺序变化时不应判定为内容变更");
        assertEquals(0, countRawDiffBodyChanges(response.getRawDiff()),
                "仅任务/边顺序变化时原始 JSON diff 不应出现增删行");
    }

    @Test
    void compareShouldIgnoreWorkflowStatusDiff() {
        final long workflowId = 35L;
        Map<String, Object> leftWorkflow = workflowNode("wf_status", "desc", "[]", "tg_alpha");
        leftWorkflow.put("status", "draft");
        Map<String, Object> rightWorkflow = workflowNode("wf_status", "desc", "[]", "tg_alpha");
        rightWorkflow.put("status", "online");
        List<Map<String, Object>> tasks = Collections.singletonList(
                taskNode(1L, "extract_user", "select 1", "default_ds",
                        Collections.singletonList(1L), Collections.singletonList(2L))
        );

        WorkflowVersion v1 = version(361L, workflowId, 1, canonicalSnapshot(leftWorkflow, tasks, Collections.emptyList(), new LinkedHashMap<>()));
        WorkflowVersion v2 = version(362L, workflowId, 2, canonicalSnapshot(rightWorkflow, tasks, Collections.emptyList(), new LinkedHashMap<>()));

        when(workflowVersionMapper.selectById(361L)).thenReturn(v1);
        when(workflowVersionMapper.selectById(362L)).thenReturn(v2);

        WorkflowVersionCompareResponse response = compare(workflowId, 361L, 362L);
        assertFalse(Boolean.TRUE.equals(response.getChanged()), "仅 workflow.status 变化时不应判定为内容变更");
        assertFalse(response.getModified().getWorkflowFields().stream().anyMatch(item -> item.contains("workflow.status")),
                "workflow.status 不应出现在结构化 diff 中");
        assertEquals(0, countRawDiffBodyChanges(response.getRawDiff()),
                "仅 workflow.status 变化时原始 JSON diff 不应出现增删行");
    }

    @Test
    void compareShouldDetectTaskRuntimeMetadataDiffs() {
        final long workflowId = 44L;
        String definitionV1 = metadataDefinitionJson(
                1L,
                "task_meta",
                1001L,
                1L,
                5L,
                1L,
                1L,
                60L);
        String definitionV2 = metadataDefinitionJson(
                1L,
                "task_meta",
                2002L,
                3L,
                9L,
                4L,
                6L,
                180L);

        WorkflowVersion v1 = version(401L, workflowId, 1, definitionV1);
        WorkflowVersion v2 = version(402L, workflowId, 2, definitionV2);

        when(workflowVersionMapper.selectById(401L)).thenReturn(v1);
        when(workflowVersionMapper.selectById(402L)).thenReturn(v2);

        WorkflowVersionCompareResponse response = compare(workflowId, 401L, 402L);
        assertTrue(Boolean.TRUE.equals(response.getChanged()));
        assertListContains(response.getModified().getTasks(), "dolphinTaskCode",
                "任务 dolphinTaskCode 变更应被识别");
        assertListContains(response.getModified().getTasks(), "dolphinTaskVersion",
                "任务 dolphinTaskVersion 变更应被识别");
        assertListContains(response.getModified().getTasks(), "priority",
                "任务 priority 变更应被识别");
        assertListContains(response.getModified().getTasks(), "retryTimes",
                "任务 retryTimes 变更应被识别");
        assertListContains(response.getModified().getTasks(), "retryInterval",
                "任务 retryInterval 变更应被识别");
        assertListContains(response.getModified().getTasks(), "timeoutSeconds",
                "任务 timeoutSeconds 变更应被识别");
    }

    @Test
    void compareShouldTreatAlignedTaskRuntimeMetadataAsNoDiff() {
        final long workflowId = 45L;
        String definitionV1 = metadataDefinitionJson(
                1L,
                "task_meta",
                1001L,
                1L,
                5L,
                1L,
                1L,
                60L);
        String definitionV2 = metadataDefinitionJson(
                1L,
                "task_meta",
                1001L,
                1L,
                5L,
                1L,
                1L,
                60L);

        WorkflowVersion v1 = version(411L, workflowId, 1, definitionV1);
        WorkflowVersion v2 = version(412L, workflowId, 2, definitionV2);

        when(workflowVersionMapper.selectById(411L)).thenReturn(v1);
        when(workflowVersionMapper.selectById(412L)).thenReturn(v2);

        WorkflowVersionCompareResponse response = compare(workflowId, 411L, 412L);
        assertFalse(Boolean.TRUE.equals(response.getChanged()), "元数据一致时不应产生差异");
        assertTrue(response.getModified().getTasks().isEmpty(), "元数据一致时任务修改列表应为空");
        assertTrue(response.getAdded().getTasks().isEmpty(), "元数据一致时不应出现新增任务");
        assertTrue(response.getRemoved().getTasks().isEmpty(), "元数据一致时不应出现删除任务");
        assertListContains(response.getUnchanged().getTasks(), "task_meta", "元数据一致时任务应归类为未变化");
    }

    private WorkflowVersion version(Long id, Long workflowId, Integer versionNo, String snapshot) {
        return versionWithSchema(id, workflowId, versionNo, snapshot, 3);
    }

    private WorkflowVersion versionWithSchema(Long id,
                                              Long workflowId,
                                              Integer versionNo,
                                              String snapshot,
                                              Integer schemaVersion) {
        WorkflowVersion version = new WorkflowVersion();
        version.setId(id);
        version.setWorkflowId(workflowId);
        version.setVersionNo(versionNo);
        version.setStructureSnapshot(snapshot);
        version.setSnapshotSchemaVersion(schemaVersion);
        return version;
    }

    private WorkflowVersionCompareResponse compare(Long workflowId, Long leftVersionId, Long rightVersionId) {
        WorkflowVersionCompareRequest request = new WorkflowVersionCompareRequest();
        request.setLeftVersionId(leftVersionId);
        request.setRightVersionId(rightVersionId);
        return service.compare(workflowId, request);
    }

    private void assertListContains(List<String> values, String expectedPart, String message) {
        assertTrue(values.stream().anyMatch(item -> item.contains(expectedPart)),
                message + "，实际内容: " + values);
    }

    private int countRawDiffBodyChanges(String rawDiff) {
        if (rawDiff == null) {
            return 0;
        }
        int count = 0;
        for (String line : rawDiff.split("\\R")) {
            if ((line.startsWith("+") && !line.startsWith("+++"))
                    || (line.startsWith("-") && !line.startsWith("---"))) {
                count++;
            }
        }
        return count;
    }

    private Map<String, Object> taskNode(Long taskId,
                                         String taskName,
                                         String taskSql,
                                         String datasourceName,
                                         List<Long> inputTableIds,
                                         List<Long> outputTableIds) {
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("taskId", taskId);
        node.put("taskName", taskName);
        node.put("taskSql", taskSql);
        node.put("datasourceName", datasourceName);
        node.put("dolphinFlag", "YES");
        node.put("inputTableIds", new ArrayList<>(inputTableIds));
        node.put("outputTableIds", new ArrayList<>(outputTableIds));
        return node;
    }

    private Map<String, Object> edgeNode(Long upstreamTaskId, Long downstreamTaskId) {
        Map<String, Object> edge = new LinkedHashMap<>();
        edge.put("upstreamTaskId", upstreamTaskId);
        edge.put("downstreamTaskId", downstreamTaskId);
        return edge;
    }

    private String canonicalSnapshot(String workflowName, String taskName) {
        return canonicalSnapshot(
                workflowName,
                Collections.singletonList(taskNode(
                        1L,
                        taskName,
                        "select 1",
                        "default_ds",
                        Collections.singletonList(1L),
                        Collections.singletonList(2L)
                )),
                Collections.emptyList()
        );
    }

    private String canonicalSnapshot(String workflowName,
                                     List<Map<String, Object>> tasks,
                                     List<Map<String, Object>> edges) {
        return canonicalSnapshot(workflowNode(workflowName, null, null, null), tasks, edges, new LinkedHashMap<>());
    }

    private String canonicalSnapshot(Map<String, Object> workflow,
                                     List<Map<String, Object>> tasks,
                                     List<Map<String, Object>> edges,
                                     Map<String, Object> schedule) {
        return buildDefinitionJsonFromCanonical(workflow, tasks, edges, schedule);
    }

    private String buildDefinitionJsonFromCanonical(Map<String, Object> workflow,
                                                    List<Map<String, Object>> tasks,
                                                    List<Map<String, Object>> edges,
                                                    Map<String, Object> schedule) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 3);

        Map<String, Object> process = new LinkedHashMap<>();
        process.put("name", workflow != null ? workflow.get("workflowName") : null);
        process.put("description", workflow != null ? workflow.get("description") : null);
        process.put("globalParams", workflow != null ? workflow.get("globalParams") : null);
        process.put("taskGroupName", workflow != null ? workflow.get("taskGroupName") : null);
        process.put("releaseState", workflow != null ? workflow.get("status") : null);
        root.put("processDefinition", process);

        List<Map<String, Object>> taskDefinitionList = new ArrayList<>();
        if (tasks != null) {
            for (Map<String, Object> task : tasks) {
                if (task == null) {
                    continue;
                }
                Long taskCode = toLong(task.get("taskId"));
                if (taskCode == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("code", taskCode);
                item.put("taskCode", taskCode);
                item.put("name", task.get("taskName"));
                item.put("taskName", task.get("taskName"));
                item.put("taskType", "SQL");
                item.put("nodeType", "SQL");
                item.put("flag", task.get("dolphinFlag"));
                item.put("inputTableIds", task.get("inputTableIds"));
                item.put("outputTableIds", task.get("outputTableIds"));

                Map<String, Object> taskParams = new LinkedHashMap<>();
                taskParams.put("sql", task.get("taskSql"));
                taskParams.put("rawScript", task.get("taskSql"));
                taskParams.put("datasourceName", task.get("datasourceName"));
                taskParams.put("type", "MYSQL");
                item.put("taskParams", taskParams);
                Map<String, Object> taskMeta = new LinkedHashMap<>();
                taskMeta.put("taskId", task.get("taskId"));
                taskMeta.put("platformTaskCode", task.get("taskCode"));
                taskMeta.put("entry", task.get("entry"));
                taskMeta.put("exit", task.get("exit"));
                taskMeta.put("nodeAttrs", task.get("nodeAttrs"));
                taskMeta.put("engine", "dolphin");
                taskMeta.put("platformTaskType", "batch");
                taskMeta.put("dolphinTaskCode", taskCode);
                taskMeta.put("dolphinTaskVersion", 1);
                item.put("xPlatformTaskMeta", taskMeta);
                taskDefinitionList.add(item);
            }
        }
        root.put("taskDefinitionList", taskDefinitionList);

        List<Map<String, Object>> relationList = new ArrayList<>();
        if (edges != null) {
            for (Map<String, Object> edge : edges) {
                if (edge == null) {
                    continue;
                }
                Long pre = toLong(edge.get("upstreamTaskId"));
                Long post = toLong(edge.get("downstreamTaskId"));
                if (pre == null || post == null) {
                    continue;
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("preTaskCode", pre);
                item.put("postTaskCode", post);
                relationList.add(item);
            }
        }
        root.put("processTaskRelationList", relationList);

        Map<String, Object> scheduleNode = new LinkedHashMap<>();
        if (schedule != null) {
            scheduleNode.put("crontab", schedule.get("scheduleCron"));
            scheduleNode.put("timezoneId", schedule.get("scheduleTimezone"));
            scheduleNode.put("startTime", schedule.get("scheduleStartTime"));
            scheduleNode.put("endTime", schedule.get("scheduleEndTime"));
            scheduleNode.put("failureStrategy", schedule.get("scheduleFailureStrategy"));
            scheduleNode.put("warningType", schedule.get("scheduleWarningType"));
            scheduleNode.put("warningGroupId", schedule.get("scheduleWarningGroupId"));
            scheduleNode.put("processInstancePriority", schedule.get("scheduleProcessInstancePriority"));
            scheduleNode.put("workerGroup", schedule.get("scheduleWorkerGroup"));
            scheduleNode.put("tenantCode", schedule.get("scheduleTenantCode"));
            scheduleNode.put("environmentCode", schedule.get("scheduleEnvironmentCode"));
            scheduleNode.put("autoOnline", schedule.get("scheduleAutoOnline"));
        }
        root.put("schedule", scheduleNode);

        Map<String, Object> workflowMeta = new LinkedHashMap<>();
        workflowMeta.put("publishStatus", workflow != null ? workflow.get("publishStatus") : null);
        root.put("xPlatformWorkflowMeta", workflowMeta);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("构建 canonical definitionJson 失败", ex);
        }
    }

    private Long toLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    private Map<String, Object> workflowNode(String workflowName,
                                             String description,
                                             String globalParams,
                                             String taskGroupName) {
        Map<String, Object> workflow = new LinkedHashMap<>();
        workflow.put("workflowName", workflowName);
        workflow.put("description", description);
        workflow.put("globalParams", globalParams);
        workflow.put("taskGroupName", taskGroupName);
        return workflow;
    }

    private Map<String, Object> workflowNodeWithDefinition(String workflowName, String definitionJson) {
        Map<String, Object> workflow = workflowNode(workflowName, null, null, null);
        workflow.put("definitionJson", definitionJson);
        return workflow;
    }

    private String metadataDefinitionJson(Long taskCode,
                                          String taskName,
                                          Long dolphinTaskCode,
                                          Long dolphinTaskVersion,
                                          Long taskPriority,
                                          Long retryTimes,
                                          Long retryInterval,
                                          Long timeoutSeconds) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> process = new LinkedHashMap<>();
        process.put("name", "wf_meta");
        process.put("description", "meta compare");
        process.put("globalParams", "[]");
        process.put("taskGroupName", "tg_meta");
        process.put("releaseState", "offline");
        root.put("processDefinition", process);

        Map<String, Object> task = new LinkedHashMap<>();
        task.put("code", taskCode);
        task.put("taskCode", taskCode);
        task.put("name", taskName);
        task.put("taskName", taskName);
        task.put("taskType", "SQL");
        task.put("nodeType", "SQL");
        task.put("taskPriority", taskPriority);
        task.put("failRetryTimes", retryTimes);
        task.put("failRetryInterval", retryInterval);
        task.put("timeout", timeoutSeconds);
        task.put("inputTableIds", Collections.singletonList(10L));
        task.put("outputTableIds", Collections.singletonList(20L));

        Map<String, Object> taskParams = new LinkedHashMap<>();
        taskParams.put("sql", "select 1");
        taskParams.put("rawScript", "select 1");
        taskParams.put("datasourceName", "meta_ds");
        taskParams.put("type", "MYSQL");
        task.put("taskParams", taskParams);

        Map<String, Object> taskMeta = new LinkedHashMap<>();
        taskMeta.put("taskId", taskCode);
        taskMeta.put("platformTaskCode", "meta_" + taskCode);
        taskMeta.put("entry", true);
        taskMeta.put("exit", true);
        taskMeta.put("engine", "dolphin");
        taskMeta.put("platformTaskType", "batch");
        taskMeta.put("dolphinTaskCode", dolphinTaskCode);
        taskMeta.put("dolphinTaskVersion", dolphinTaskVersion);
        task.put("xPlatformTaskMeta", taskMeta);

        root.put("taskDefinitionList", Collections.singletonList(task));
        root.put("processTaskRelationList", Collections.singletonList(definitionRelationNode(0L, taskCode)));
        root.put("schedule", Collections.emptyMap());
        root.put("schemaVersion", 3);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("构建 metadata definitionJson 失败", ex);
        }
    }

    private Map<String, Object> scheduleNode(String scheduleCron,
                                             String scheduleTimezone,
                                             String scheduleStartTime,
                                             String scheduleEndTime,
                                             String scheduleFailureStrategy,
                                             String scheduleWarningType,
                                             Long scheduleWarningGroupId,
                                             String scheduleProcessInstancePriority,
                                             String scheduleWorkerGroup,
                                             String scheduleTenantCode,
                                             Long scheduleEnvironmentCode,
                                             Boolean scheduleAutoOnline) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("scheduleCron", scheduleCron);
        schedule.put("scheduleTimezone", scheduleTimezone);
        schedule.put("scheduleStartTime", scheduleStartTime);
        schedule.put("scheduleEndTime", scheduleEndTime);
        schedule.put("scheduleFailureStrategy", scheduleFailureStrategy);
        schedule.put("scheduleWarningType", scheduleWarningType);
        schedule.put("scheduleWarningGroupId", scheduleWarningGroupId);
        schedule.put("scheduleProcessInstancePriority", scheduleProcessInstancePriority);
        schedule.put("scheduleWorkerGroup", scheduleWorkerGroup);
        schedule.put("scheduleTenantCode", scheduleTenantCode);
        schedule.put("scheduleEnvironmentCode", scheduleEnvironmentCode);
        schedule.put("scheduleAutoOnline", scheduleAutoOnline);
        return schedule;
    }

    private String platformDefinitionJson(String workflowName,
                                          String taskGroupName,
                                          String extractSql,
                                          String scheduleCron,
                                          List<Map<String, Object>> relations) {
        Map<String, Object> root = new LinkedHashMap<>();

        Map<String, Object> process = new LinkedHashMap<>();
        process.put("name", workflowName);
        process.put("description", "definition-source");
        process.put("globalParams", "[]");
        process.put("taskGroupName", taskGroupName);
        process.put("releaseState", "offline");
        root.put("processDefinition", process);

        List<Map<String, Object>> tasks = new ArrayList<>();
        tasks.add(definitionTaskNode(
                1L,
                "extract_user_def",
                extractSql,
                "ods_ds",
                Collections.singletonList(10L),
                Collections.singletonList(20L)
        ));
        tasks.add(definitionTaskNode(
                2L,
                "load_user_def",
                "insert into dwd.user select * from tmp.user",
                "dwd_ds",
                Collections.singletonList(20L),
                Collections.singletonList(30L)
        ));
        root.put("taskDefinitionList", tasks);
        root.put("processTaskRelationList", relations);

        Map<String, Object> schedule = new LinkedHashMap<>();
        schedule.put("crontab", scheduleCron);
        schedule.put("timezoneId", "Asia/Shanghai");
        schedule.put("startTime", "2026-01-01 00:00:00");
        schedule.put("endTime", "2026-12-31 23:59:59");
        schedule.put("failureStrategy", "CONTINUE");
        schedule.put("warningType", "NONE");
        schedule.put("warningGroupId", 101L);
        schedule.put("processInstancePriority", "MEDIUM");
        schedule.put("workerGroup", "default");
        schedule.put("tenantCode", "default");
        schedule.put("environmentCode", 1L);
        schedule.put("autoOnline", true);
        root.put("schedule", schedule);

        try {
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            throw new IllegalStateException("构建 definitionJson 失败", ex);
        }
    }

    private Map<String, Object> definitionTaskNode(Long code,
                                                   String name,
                                                   String sql,
                                                   String datasourceName,
                                                   List<Long> inputTableIds,
                                                   List<Long> outputTableIds) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("code", code);
        task.put("taskCode", code);
        task.put("name", name);
        task.put("taskName", name);
        task.put("taskType", "SQL");
        task.put("nodeType", "SQL");
        task.put("taskGroupName", "tg-sql");
        task.put("flag", "YES");
        task.put("taskPriority", 5);
        task.put("failRetryTimes", 1);
        task.put("failRetryInterval", 1);
        task.put("timeout", 300);
        task.put("inputTableIds", new ArrayList<>(inputTableIds));
        task.put("outputTableIds", new ArrayList<>(outputTableIds));

        Map<String, Object> taskParams = new LinkedHashMap<>();
        taskParams.put("sql", sql);
        taskParams.put("rawScript", sql);
        taskParams.put("datasourceName", datasourceName);
        taskParams.put("type", "MYSQL");
        task.put("taskParams", taskParams);
        return task;
    }

    private Map<String, Object> definitionRelationNode(Long preTaskCode, Long postTaskCode) {
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("preTaskCode", preTaskCode);
        relation.put("postTaskCode", postTaskCode);
        return relation;
    }
}
