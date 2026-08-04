package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowExportJsonResponse;
import com.onedata.portal.dto.workflow.WorkflowImportCommitRequest;
import com.onedata.portal.dto.workflow.WorkflowImportCommitResponse;
import com.onedata.portal.dto.workflow.WorkflowImportPreviewRequest;
import com.onedata.portal.dto.workflow.WorkflowImportPreviewResponse;
import com.onedata.portal.dto.workflow.runtime.DolphinRuntimeWorkflowOption;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowDefinitionLifecycleServiceTest {

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataTask.class);
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowVersion.class);
    }

    @Mock
    private DolphinRuntimeDefinitionService runtimeDefinitionService;

    @Mock
    private SqlTableMatcherService sqlTableMatcherService;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private DataTaskService dataTaskService;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    @Mock
    private WorkflowDefinitionAssembler workflowDefinitionAssembler;

    private WorkflowDefinitionLifecycleService service;

    @Mock
    private com.onedata.portal.service.lineage.TaskLineageConsistencyChecker lineageConsistencyChecker;

    private static final Long TARGET_DOLPHIN_CONFIG_ID = 7L;
    private static final Long TARGET_PROJECT_CODE = 5001L;

    @BeforeEach
    void setUp() {
        service = new WorkflowDefinitionLifecycleService(
                runtimeDefinitionService,
                sqlTableMatcherService,
                dolphinSchedulerService,
                dataTaskService,
                workflowService,
                workflowDefinitionAssembler,
                lineageConsistencyChecker,
                dataWorkflowMapper,
                dataTaskMapper,
                workflowVersionMapper,
                new com.fasterxml.jackson.databind.ObjectMapper());
    }

    /** 目标 Dolphin 环境可解析出项目编码，运行态归属判定才能继续。 */
    private void stubTargetProject() {
        when(dolphinSchedulerService.findProjectCode(TARGET_DOLPHIN_CONFIG_ID)).thenReturn(TARGET_PROJECT_CODE);
    }

    /** 不关联既有运行态时，定义 JSON 会过一次运行态清理；这里让它原样返回以便断言内容。 */
    private void stubRuntimeBindingReset() {
        when(workflowDefinitionAssembler.refreshRuntimeBindings(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void previewShouldRequireRelationDecisionWhenDeclaredMismatch() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        RuntimeTaskDefinition task2 = sqlTask(2L, "t_load", "SQL_B");
        definition.setTasks(Arrays.asList(task1, task2));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 2L),
                new RuntimeTaskEdge(2L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL"))).thenReturn(analyze(101L, 102L));

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        WorkflowImportPreviewResponse response = service.preview(request);

        assertTrue(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(Boolean.TRUE.equals(response.getRelationDecisionRequired()));
        assertNotNull(response.getRelationCompareDetail());
        assertFalse(response.getRelationCompareDetail().getOnlyInDeclared().isEmpty());
        assertFalse(response.getRelationCompareDetail().getOnlyInInferred().isEmpty());
    }

    @Test
    void previewShouldFailWhenTaskHasNoOutputTable() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_only_input", "SQL_A");
        definition.setTasks(Collections.singletonList(task1));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(11L, null));

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        WorkflowImportPreviewResponse response = service.preview(request);

        assertFalse(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(response.getErrors().stream().anyMatch(item -> item.contains("输出表")));
    }

    @Test
    void commitShouldFailWhenRelationDecisionMissingUnderMismatch() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        RuntimeTaskDefinition task2 = sqlTask(2L, "t_load", "SQL_B");
        definition.setTasks(Arrays.asList(task1, task2));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 2L),
                new RuntimeTaskEdge(2L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL"))).thenReturn(analyze(101L, 102L));

        WorkflowImportCommitRequest request = new WorkflowImportCommitRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        request.setOperator("tester");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.commit(request));
        assertTrue(ex.getMessage().contains("relationDecision"));
    }

    @Test
    void commitShouldCreateWorkflowWhenPreviewPassed() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        task1.setFlag("NO");
        RuntimeTaskDefinition task2 = sqlTask(2L, "t_load", "SQL_B");
        definition.setTasks(Arrays.asList(task1, task2));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 1L),
                new RuntimeTaskEdge(1L, 2L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL"))).thenReturn(analyze(101L, 102L));
        when(dataTaskMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dataWorkflowMapper.selectOne(any())).thenReturn(null);

        stubRuntimeBindingReset();

        AtomicLong taskIdSequence = new AtomicLong(10L);
        when(dataTaskService.create(any(), any(), any())).thenAnswer(invocation -> {
            DataTask payload = invocation.getArgument(0);
            DataTask persisted = new DataTask();
            persisted.setId(taskIdSequence.incrementAndGet());
            persisted.setTaskName(payload.getTaskName());
            persisted.setTaskCode(payload.getTaskCode());
            return persisted;
        });

        DataWorkflow createdWorkflow = new DataWorkflow();
        createdWorkflow.setId(77L);
        createdWorkflow.setWorkflowName("wf_import_demo");
        createdWorkflow.setCurrentVersionId(900L);
        when(workflowService.createWorkflow(any())).thenReturn(createdWorkflow);

        WorkflowVersion version = new WorkflowVersion();
        version.setId(900L);
        version.setVersionNo(3);
        when(workflowVersionMapper.selectById(900L)).thenReturn(version);

        WorkflowImportCommitRequest request = new WorkflowImportCommitRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        request.setOperator("tester");
        WorkflowImportCommitResponse response = service.commit(request);

        assertEquals(77L, response.getWorkflowId());
        assertEquals(900L, response.getVersionId());
        assertEquals(3, response.getVersionNo());
        assertEquals(2, response.getCreatedTaskCount());
        assertEquals("INFERRED", response.getAppliedRelationDecision());

        ArgumentCaptor<WorkflowDefinitionRequest> requestCaptor = ArgumentCaptor.forClass(WorkflowDefinitionRequest.class);
        verify(workflowService).createWorkflow(requestCaptor.capture());
        WorkflowDefinitionRequest captured = requestCaptor.getValue();
        assertEquals("import_file", captured.getTriggerSource());
        assertNotNull(captured.getDefinitionJson());
        assertTrue(captured.getDefinitionJson().contains("processTaskRelationList"));
        assertTrue(captured.getDefinitionJson().contains("\"flag\":\"NO\""));
    }

    @Test
    void commitShouldAvoidTaskCodeOwnedByLogicallyDeletedTask() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition task = sqlTask(1L, "t_extract", "SQL_A");
        definition.setTasks(Collections.singletonList(task));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(dataTaskMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dataTaskMapper.countByTaskCodeIncludingDeleted("wf_imp_t_extract_1")).thenReturn(1L);
        when(dataWorkflowMapper.selectOne(any())).thenReturn(null);

        DataTask persistedTask = new DataTask();
        persistedTask.setId(11L);
        persistedTask.setTaskName("t_extract");
        persistedTask.setTaskCode("wf_imp_t_extract_1_2");
        stubRuntimeBindingReset();
        when(dataTaskService.create(any(), any(), any())).thenReturn(persistedTask);

        DataWorkflow createdWorkflow = new DataWorkflow();
        createdWorkflow.setId(77L);
        createdWorkflow.setWorkflowName("wf_import_demo");
        createdWorkflow.setCurrentVersionId(900L);
        when(workflowService.createWorkflow(any())).thenReturn(createdWorkflow);

        WorkflowVersion version = new WorkflowVersion();
        version.setId(900L);
        version.setVersionNo(3);
        when(workflowVersionMapper.selectById(900L)).thenReturn(version);

        WorkflowImportCommitRequest request = new WorkflowImportCommitRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        request.setOperator("tester");

        WorkflowImportCommitResponse response = service.commit(request);

        assertEquals(77L, response.getWorkflowId());
        ArgumentCaptor<DataTask> taskCaptor = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskService).create(taskCaptor.capture(), any(), any());
        assertEquals("wf_imp_t_extract_1_2", taskCaptor.getValue().getTaskCode());
        verify(dataTaskMapper).countByTaskCodeIncludingDeleted("wf_imp_t_extract_1");
        verify(dataTaskMapper).countByTaskCodeIncludingDeleted("wf_imp_t_extract_1_2");
    }

    @Test
    void commitShouldKeepImportTaskMetadataRawThenNormalizePersist() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition task = sqlTask(11L, "t_raw_meta", "SQL_A");
        task.setTaskPriority(null);
        task.setRetryTimes(null);
        task.setRetryInterval(null);
        task.setTimeoutSeconds(null);
        task.setTaskVersion(null);
        task.setFlag("NO");
        definition.setTasks(Collections.singletonList(task));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 11L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(dataTaskMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dataWorkflowMapper.selectOne(any())).thenReturn(null);

        DataTask persistedTask = new DataTask();
        persistedTask.setId(1001L);
        persistedTask.setTaskName("t_raw_meta");
        persistedTask.setTaskCode("wf_imp_t_raw_meta_11");
        stubRuntimeBindingReset();
        when(dataTaskService.create(any(), any(), any())).thenReturn(persistedTask);

        DataWorkflow createdWorkflow = new DataWorkflow();
        createdWorkflow.setId(77L);
        createdWorkflow.setWorkflowName("wf_import_demo");
        createdWorkflow.setCurrentVersionId(900L);
        when(workflowService.createWorkflow(any())).thenReturn(createdWorkflow);

        WorkflowVersion version = new WorkflowVersion();
        version.setId(900L);
        version.setVersionNo(3);
        when(workflowVersionMapper.selectById(900L)).thenReturn(version);

        WorkflowImportCommitRequest request = new WorkflowImportCommitRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        request.setOperator("tester");

        WorkflowImportCommitResponse response = service.commit(request);
        assertEquals(77L, response.getWorkflowId());

        ArgumentCaptor<DataTask> taskCaptor = ArgumentCaptor.forClass(DataTask.class);
        verify(dataTaskService).create(taskCaptor.capture(), any(), any());
        DataTask capturedTask = taskCaptor.getValue();
        assertEquals("SQL", capturedTask.getDolphinNodeType());
        assertNull(capturedTask.getPriority(), "导入阶段不应在 create 之前补 priority 默认值");
        assertNull(capturedTask.getRetryTimes(), "导入阶段不应在 create 之前补 retryTimes 默认值");
        assertNull(capturedTask.getRetryInterval(), "导入阶段不应在 create 之前补 retryInterval 默认值");
        assertNull(capturedTask.getTimeoutSeconds(), "导入阶段不应在 create 之前补 timeoutSeconds 默认值");
        assertNull(capturedTask.getDolphinTaskVersion(), "导入阶段不应在 create 之前补 dolphinTaskVersion 默认值");
        assertEquals("NO", capturedTask.getDolphinFlag(), "导入阶段应保留 Dolphin flag");

        verify(workflowService).normalizeAndPersistMetadata(77L, "tester");
    }

    @Test
    void previewShouldLoadDefinitionFromDolphinSource() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setWorkflowName("wf_from_dolphin");
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        RuntimeTaskDefinition task2 = sqlTask(2L, "t_load", "SQL_B");
        definition.setTasks(Arrays.asList(task1, task2));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 1L),
                new RuntimeTaskEdge(1L, 2L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 2001L)).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL"))).thenReturn(analyze(101L, 102L));
        when(dataWorkflowMapper.selectOne(any())).thenReturn(null);

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setSourceType("dolphin");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        request.setProjectCode(1L);
        request.setWorkflowCode(2001L);
        request.setWorkflowName("wf_from_dolphin_imported");
        WorkflowImportPreviewResponse response = service.preview(request);

        assertTrue(Boolean.TRUE.equals(response.getCanImport()));
        assertEquals("wf_from_dolphin_imported", response.getWorkflowName());
    }

    @Test
    void commitShouldUseDolphinImportTriggerAndCustomName() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setWorkflowName("wf_from_dolphin");
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        RuntimeTaskDefinition task2 = sqlTask(2L, "t_load", "SQL_B");
        definition.setTasks(Arrays.asList(task1, task2));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 1L),
                new RuntimeTaskEdge(1L, 2L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 3001L)).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL"))).thenReturn(analyze(101L, 102L));
        when(dataTaskMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(dataWorkflowMapper.selectOne(any())).thenReturn(null);

        stubRuntimeBindingReset();

        AtomicLong taskIdSequence = new AtomicLong(20L);
        when(dataTaskService.create(any(), any(), any())).thenAnswer(invocation -> {
            DataTask payload = invocation.getArgument(0);
            DataTask persisted = new DataTask();
            persisted.setId(taskIdSequence.incrementAndGet());
            persisted.setTaskName(payload.getTaskName());
            persisted.setTaskCode(payload.getTaskCode());
            return persisted;
        });

        DataWorkflow createdWorkflow = new DataWorkflow();
        createdWorkflow.setId(99L);
        createdWorkflow.setCurrentVersionId(901L);
        when(workflowService.createWorkflow(any())).thenReturn(createdWorkflow);

        WorkflowVersion version = new WorkflowVersion();
        version.setId(901L);
        version.setVersionNo(4);
        when(workflowVersionMapper.selectById(901L)).thenReturn(version);

        WorkflowImportCommitRequest request = new WorkflowImportCommitRequest();
        request.setSourceType("dolphin");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        request.setProjectCode(1L);
        request.setWorkflowCode(3001L);
        request.setWorkflowName("wf_custom_import_name");
        request.setOperator("tester");
        WorkflowImportCommitResponse response = service.commit(request);

        assertEquals(99L, response.getWorkflowId());
        ArgumentCaptor<WorkflowDefinitionRequest> requestCaptor = ArgumentCaptor.forClass(WorkflowDefinitionRequest.class);
        verify(workflowService).createWorkflow(requestCaptor.capture());
        WorkflowDefinitionRequest captured = requestCaptor.getValue();
        assertEquals("wf_custom_import_name", captured.getWorkflowName());
        assertEquals("import_dolphin", captured.getTriggerSource());

        ArgumentCaptor<DataWorkflow> workflowCaptor = ArgumentCaptor.forClass(DataWorkflow.class);
        verify(dataWorkflowMapper).updateById(workflowCaptor.capture());
        DataWorkflow updated = workflowCaptor.getValue();
        assertNull(updated.getWorkflowCode());
        assertEquals("import", updated.getSyncSource());
    }

    @Test
    void exportShouldReturnWorkflowDefinitionJson() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(12L);
        workflow.setWorkflowName("wf_export_demo");

        when(dataWorkflowMapper.selectById(12L)).thenReturn(workflow);
        when(workflowService.buildDefinitionJsonForExport(12L)).thenReturn("{\"ok\":true}");

        WorkflowExportJsonResponse response = service.exportJson(12L);

        assertEquals("wf_export_demo.json", response.getFileName());
        assertEquals("{\"ok\":true}", response.getContent());
    }

    @Test
    void previewShouldFailWhenDolphinImportWorkflowNameConflict() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setWorkflowName("wf_conflict");
        RuntimeTaskDefinition task1 = sqlTask(1L, "t_extract", "SQL_A");
        definition.setTasks(Collections.singletonList(task1));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 4001L)).thenReturn(definition);
        DataWorkflow existing = new DataWorkflow();
        existing.setId(123L);
        existing.setWorkflowName("wf_conflict");
        when(dataWorkflowMapper.selectOne(any())).thenReturn(existing);

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setSourceType("dolphin");
        request.setProjectCode(1L);
        request.setWorkflowCode(4001L);
        WorkflowImportPreviewResponse response = service.preview(request);

        assertFalse(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(response.getErrors().stream().anyMatch(item -> item.contains("工作流名称已存在")));
    }

    @Test
    void previewShouldFailWhenDolphinConfigMissing() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setTasks(Collections.singletonList(sqlTask(1L, "t_extract", "SQL_A")));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        WorkflowImportPreviewResponse response = service.preview(request);

        assertFalse(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(response.getErrors().stream().anyMatch(item -> item.contains("请选择目标 Dolphin 环境")));
        assertNull(response.getRuntimeBinding());
    }

    @Test
    void previewShouldFailWhenTargetProjectUnresolvable() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setTasks(Collections.singletonList(sqlTask(1L, "t_extract", "SQL_A")));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        when(dolphinSchedulerService.findProjectCode(TARGET_DOLPHIN_CONFIG_ID)).thenReturn(null);

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        WorkflowImportPreviewResponse response = service.preview(request);

        assertFalse(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(response.getErrors().stream().anyMatch(item -> item.contains("无法解析目标 Dolphin 项目")));
    }

    @Test
    void previewShouldResetRuntimeWhenNoLinkedWorkflow() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setTasks(Collections.singletonList(sqlTask(1L, "t_extract", "SQL_A")));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        WorkflowImportPreviewResponse response = service.preview(request);

        assertTrue(Boolean.TRUE.equals(response.getCanImport()));
        assertEquals("RESET", response.getRuntimeBinding().getDecision());
        assertEquals(TARGET_PROJECT_CODE, response.getRuntimeBinding().getProjectCode());
        // 文件里带的是来源平台的 1001L，不关联时不能被继承
        assertNull(response.getRuntimeBinding().getWorkflowCode());
    }

    @Test
    void previewShouldAdoptRuntimeWhenLinkedWorkflowSelected() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setTasks(Collections.singletonList(sqlTask(1L, "t_extract", "SQL_A")));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(runtimeDefinitionService.findRuntimeWorkflow(TARGET_DOLPHIN_CONFIG_ID, TARGET_PROJECT_CODE, 8888L))
                .thenReturn(runtimeOption(8888L, "wf_runtime_target", "ONLINE"));

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        request.setLinkedWorkflowCode(8888L);
        WorkflowImportPreviewResponse response = service.preview(request);

        assertTrue(Boolean.TRUE.equals(response.getCanImport()));
        assertEquals("ADOPT", response.getRuntimeBinding().getDecision());
        assertEquals(8888L, response.getRuntimeBinding().getWorkflowCode());
        assertEquals("wf_runtime_target", response.getRuntimeBinding().getRuntimeWorkflowName());
    }

    @Test
    void previewShouldFailWhenLinkedWorkflowAbsentInTargetDolphin() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setTasks(Collections.singletonList(sqlTask(1L, "t_extract", "SQL_A")));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(runtimeDefinitionService.findRuntimeWorkflow(TARGET_DOLPHIN_CONFIG_ID, TARGET_PROJECT_CODE, 9999L))
                .thenReturn(null);

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        request.setLinkedWorkflowCode(9999L);
        WorkflowImportPreviewResponse response = service.preview(request);

        assertFalse(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(response.getErrors().stream()
                .anyMatch(item -> item.contains("所选运行态工作流在目标 Dolphin 中不存在")));
    }

    @Test
    void previewShouldFailWhenLinkedWorkflowAlreadyBoundToLocalWorkflow() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setTasks(Collections.singletonList(sqlTask(1L, "t_extract", "SQL_A")));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(runtimeDefinitionService.findRuntimeWorkflow(TARGET_DOLPHIN_CONFIG_ID, TARGET_PROJECT_CODE, 8888L))
                .thenReturn(runtimeOption(8888L, "wf_runtime_target", "ONLINE"));

        DataWorkflow occupied = new DataWorkflow();
        occupied.setId(321L);
        occupied.setWorkflowName("wf_already_bound");
        // 第一次 selectOne 是工作流重名校验，第二次才是运行态占用校验
        when(dataWorkflowMapper.selectOne(any())).thenReturn(null, occupied);

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        request.setLinkedWorkflowCode(8888L);
        WorkflowImportPreviewResponse response = service.preview(request);

        assertFalse(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(response.getErrors().stream().anyMatch(item -> item.contains("wf_already_bound")));
        assertEquals(321L, response.getRuntimeBinding().getConflictWorkflowId());
    }

    @Test
    void previewShouldFailWhenJsonImportWorkflowNameConflict() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setWorkflowName("wf_json_conflict");
        definition.setTasks(Collections.singletonList(sqlTask(1L, "t_extract", "SQL_A")));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        DataWorkflow existing = new DataWorkflow();
        existing.setId(55L);
        existing.setWorkflowName("wf_json_conflict");
        when(dataWorkflowMapper.selectOne(any())).thenReturn(existing);

        WorkflowImportPreviewRequest request = new WorkflowImportPreviewRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        WorkflowImportPreviewResponse response = service.preview(request);

        assertFalse(Boolean.TRUE.equals(response.getCanImport()));
        assertTrue(response.getErrors().stream().anyMatch(item -> item.contains("工作流名称已存在")));
    }

    @Test
    void commitShouldStripRuntimeBindingWhenNotLinked() {
        DataWorkflow updated = commitSingleTaskImport(null, 88L);

        assertNull(updated.getWorkflowCode(), "不关联导入时不能继承来源平台的 workflowCode");
        assertNull(updated.getDolphinScheduleId());
        assertEquals("never", updated.getPublishStatus());
        assertEquals("draft", updated.getStatus());
        assertEquals(TARGET_PROJECT_CODE, updated.getProjectCode());
        assertEquals(TARGET_DOLPHIN_CONFIG_ID, updated.getDolphinConfigId());
        verify(workflowDefinitionAssembler)
                .refreshRuntimeBindings(any(), eq(TARGET_DOLPHIN_CONFIG_ID), eq(TARGET_PROJECT_CODE));
    }

    @Test
    void commitShouldKeepRuntimeBindingWhenLinked() {
        when(runtimeDefinitionService.findRuntimeWorkflow(TARGET_DOLPHIN_CONFIG_ID, TARGET_PROJECT_CODE, 8888L))
                .thenReturn(runtimeOption(8888L, "wf_runtime_target", "ONLINE"));

        DataWorkflow updated = commitSingleTaskImport(8888L, 89L);

        assertEquals(8888L, updated.getWorkflowCode());
        assertEquals(TARGET_PROJECT_CODE, updated.getProjectCode());
        assertEquals(TARGET_DOLPHIN_CONFIG_ID, updated.getDolphinConfigId());
        assertEquals("published", updated.getPublishStatus());
        assertEquals("online", updated.getStatus());
    }

    /** 跑一次单任务导入提交，返回落库前被填充的工作流实体。 */
    private DataWorkflow commitSingleTaskImport(Long linkedWorkflowCode, Long workflowId) {
        RuntimeWorkflowDefinition definition = baseDefinition();
        definition.setTasks(Collections.singletonList(sqlTask(1L, "t_extract", "SQL_A")));
        definition.setExplicitEdges(Collections.singletonList(new RuntimeTaskEdge(0L, 1L)));

        when(runtimeDefinitionService.parseRuntimeDefinitionFromJson(any())).thenReturn(definition);
        stubTargetProject();
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL"))).thenReturn(analyze(null, 101L));
        when(dataTaskMapper.selectList(any())).thenReturn(Collections.emptyList());

        DataTask persistedTask = new DataTask();
        persistedTask.setId(1L);
        persistedTask.setTaskName("t_extract");
        persistedTask.setTaskCode("wf_imp_t_extract_1");
        when(dataTaskService.create(any(), any(), any())).thenReturn(persistedTask);

        DataWorkflow createdWorkflow = new DataWorkflow();
        createdWorkflow.setId(workflowId);
        createdWorkflow.setWorkflowName("wf_import_demo");
        when(workflowService.createWorkflow(any())).thenReturn(createdWorkflow);

        WorkflowImportCommitRequest request = new WorkflowImportCommitRequest();
        request.setDefinitionJson("{\"dummy\":true}");
        request.setDolphinConfigId(TARGET_DOLPHIN_CONFIG_ID);
        request.setLinkedWorkflowCode(linkedWorkflowCode);
        request.setOperator("tester");
        service.commit(request);

        ArgumentCaptor<DataWorkflow> workflowCaptor = ArgumentCaptor.forClass(DataWorkflow.class);
        verify(dataWorkflowMapper).updateById(workflowCaptor.capture());
        return workflowCaptor.getValue();
    }

    private DolphinRuntimeWorkflowOption runtimeOption(Long workflowCode, String name, String releaseState) {
        DolphinRuntimeWorkflowOption option = new DolphinRuntimeWorkflowOption();
        option.setProjectCode(TARGET_PROJECT_CODE);
        option.setWorkflowCode(workflowCode);
        option.setWorkflowName(name);
        option.setReleaseState(releaseState);
        return option;
    }

    private RuntimeWorkflowDefinition baseDefinition() {
        RuntimeWorkflowDefinition definition = new RuntimeWorkflowDefinition();
        definition.setProjectCode(1L);
        definition.setWorkflowCode(1001L);
        definition.setWorkflowName("wf_import_demo");
        definition.setDescription("import");
        definition.setGlobalParams("[]");
        return definition;
    }

    private RuntimeTaskDefinition sqlTask(Long taskCode, String taskName, String sql) {
        RuntimeTaskDefinition task = new RuntimeTaskDefinition();
        task.setTaskCode(taskCode);
        task.setTaskName(taskName);
        task.setNodeType("SQL");
        task.setSql(sql);
        task.setDatasourceName("mysql_ds");
        task.setDatasourceType("MYSQL");
        task.setTaskVersion(1);
        task.setFlag("YES");
        return task;
    }

    private SqlTableAnalyzeResponse analyze(Long inputTableId, Long outputTableId) {
        SqlTableAnalyzeResponse response = new SqlTableAnalyzeResponse();
        if (inputTableId != null) {
            response.setInputRefs(Collections.singletonList(matchedRef("db.t_in_" + inputTableId, inputTableId)));
        } else {
            response.setInputRefs(Collections.emptyList());
        }
        if (outputTableId != null) {
            response.setOutputRefs(Collections.singletonList(matchedRef("db.t_out_" + outputTableId, outputTableId)));
        } else {
            response.setOutputRefs(Collections.emptyList());
        }
        return response;
    }

    private SqlTableAnalyzeResponse.TableRefMatch matchedRef(String rawName, Long tableId) {
        SqlTableAnalyzeResponse.TableRefMatch match = new SqlTableAnalyzeResponse.TableRefMatch();
        match.setRawName(rawName);
        match.setMatchStatus("matched");
        SqlTableAnalyzeResponse.TableCandidate table = new SqlTableAnalyzeResponse.TableCandidate();
        table.setTableId(tableId);
        table.setTableName(rawName);
        match.setChosenTable(table);
        return match;
    }
}
