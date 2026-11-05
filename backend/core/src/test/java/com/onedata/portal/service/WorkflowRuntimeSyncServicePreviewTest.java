package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeRelationChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncErrorCodes;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncExecuteRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncExecuteResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncPreviewRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncPreviewResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowRuntimeSyncRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowRuntimeSyncRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowRuntimeSyncServicePreviewTest {

    @Mock
    private DolphinRuntimeDefinitionService runtimeDefinitionService;

    @Mock
    private WorkflowRuntimeDiffService runtimeDiffService;

    @Mock
    private SqlTableMatcherService sqlTableMatcherService;

    @Mock
    private DolphinSchedulerService dolphinSchedulerService;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private DataWorkflowMapper dataWorkflowMapper;

    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    @Mock
    private WorkflowVersionMapper workflowVersionMapper;

    @Mock
    private WorkflowRuntimeSyncRecordMapper workflowRuntimeSyncRecordMapper;

    @Mock
    private DataTaskService dataTaskService;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @InjectMocks
    private WorkflowRuntimeSyncService service;

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataTask.class);
        TableInfoHelper.initTableInfo(assistant, DataWorkflow.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowRuntimeSyncRecord.class);
        TableInfoHelper.initTableInfo(assistant, WorkflowTaskRelation.class);
    }

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "runtimeSyncEnabled", true);
        ReflectionTestUtils.setField(service, "runtimeSyncIngestMode", "export_only");

        WorkflowRuntimeDiffService.RuntimeSnapshot snapshot = new WorkflowRuntimeDiffService.RuntimeSnapshot();
        snapshot.setSnapshotHash("hash");
        snapshot.setSnapshotJson("{}");
        snapshot.setSnapshotNode(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        lenient().when(runtimeDiffService.buildSnapshot(any(), any())).thenReturn(snapshot);
        lenient().when(runtimeDiffService.buildDiff(any(), any())).thenReturn(new RuntimeDiffSummary());

        lenient().when(dataTaskMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(dataWorkflowMapper.selectOne(any())).thenReturn(null);
        lenient().when(workflowRuntimeSyncRecordMapper.selectOne(any())).thenReturn(null);
        lenient().when(dolphinSchedulerService.listDatasources(any(), any()))
                .thenReturn(Collections.singletonList(datasource(10L, "doris_ds")));
    }

    @Test
    void previewShouldFailWhenContainsNonSqlTask() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition shellTask = new RuntimeTaskDefinition();
        shellTask.setTaskCode(1L);
        shellTask.setTaskName("shell-node");
        shellTask.setNodeType("SHELL");
        definition.setTasks(Collections.singletonList(shellTask));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 1001L)).thenReturn(definition);

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertFalse(response.getCanSync());
        assertTrue(response.getErrors().stream()
                .anyMatch(issue -> RuntimeSyncErrorCodes.UNSUPPORTED_NODE_TYPE.equals(issue.getCode())));
    }

    @Test
    void previewShouldFailWhenSqlTableAmbiguous() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition sqlTask = sqlTask(1L, "task_a", "INSERT INTO dws.t1 SELECT * FROM ods.t0", 10L);
        definition.setTasks(Collections.singletonList(sqlTask));

        SqlTableAnalyzeResponse analyze = matchedAnalyze(11L, 22L);
        analyze.setAmbiguous(Collections.singletonList("dws.t1"));
        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq(sqlTask.getSql()), eq("SQL"))).thenReturn(analyze);

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertFalse(response.getCanSync());
        assertTrue(response.getErrors().stream()
                .anyMatch(issue -> RuntimeSyncErrorCodes.SQL_TABLE_AMBIGUOUS.equals(issue.getCode())));
    }

    @Test
    void previewShouldAllowSqlWithoutInputButWithOutput() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition sqlTask = sqlTask(1L, "task_a", "UPDATE dws.t1 SET c1 = 1", 10L);
        definition.setTasks(Collections.singletonList(sqlTask));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq(sqlTask.getSql()), eq("SQL")))
                .thenReturn(outputOnlyAnalyze(22L));

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertTrue(response.getCanSync());
        assertTrue(response.getErrors().stream()
                .noneMatch(issue -> RuntimeSyncErrorCodes.SQL_LINEAGE_INCOMPLETE.equals(issue.getCode())));
    }

    @Test
    void previewShouldKeepEntryEdgeWhenDeclaredAndInferredAligned() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition taskUpdate = sqlTask(1L, "task_update", "UPDATE ods.user_tag SET tag = 1", 10L);
        RuntimeTaskDefinition taskInsert = sqlTask(2L, "task_insert",
                "INSERT INTO dws.user_tag_di SELECT * FROM ods.user_tag", 10L);
        definition.setTasks(Arrays.asList(taskUpdate, taskInsert));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 1L),
                new RuntimeTaskEdge(1L, 2L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq(taskUpdate.getSql()), eq("SQL")))
                .thenReturn(outputOnlyAnalyze(501L));
        when(sqlTableMatcherService.analyze(eq(taskInsert.getSql()), eq("SQL")))
                .thenReturn(matchedAnalyze(501L, 601L));

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertTrue(response.getCanSync(), "UPDATE 首节点仅输出场景预检应通过");
        assertTrue(response.getErrors().isEmpty(), "预检不应返回错误");
        assertFalse(Boolean.TRUE.equals(response.getRelationDecisionRequired()));
        assertNotNull(response.getRelationCompareDetail());
        assertTrue(response.getRelationCompareDetail().getOnlyInDeclared().isEmpty());
        assertTrue(response.getRelationCompareDetail().getOnlyInInferred().isEmpty());
        assertTrue(response.getRelationCompareDetail().getDeclaredRelations().stream()
                .anyMatch(edge -> Boolean.TRUE.equals(edge.getEntryEdge())
                        && edge.getPreTaskCode() == 0L
                        && edge.getPostTaskCode() == 1L));
    }

    @Test
    void previewShouldRequireRelationDecisionWhenRelationMismatchDetected() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition taskA = sqlTask(1L, "task_a", "SQL_A", 10L);
        RuntimeTaskDefinition taskB = sqlTask(2L, "task_b", "SQL_B", 10L);
        definition.setTasks(Arrays.asList(taskA, taskB));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 2L),
                new RuntimeTaskEdge(2L, 1L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL")))
                .thenReturn(matchedAnalyze(101L, 201L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL")))
                .thenReturn(matchedAnalyze(201L, 301L));

        RuntimeSyncPreviewRequest request = new RuntimeSyncPreviewRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        RuntimeSyncPreviewResponse response = service.preview(request);

        assertTrue(response.getCanSync());
        assertEquals(0, response.getErrors().size());
        assertTrue(Boolean.TRUE.equals(response.getRelationDecisionRequired()));
        assertTrue(response.getWarnings().stream()
                .anyMatch(issue -> RuntimeSyncErrorCodes.RELATION_MISMATCH.equals(issue.getCode())));

        RuntimeRelationChange onlyInDeclaredEntry = response.getRelationCompareDetail().getOnlyInDeclared().stream()
                .filter(item -> item.getPreTaskCode() != null && item.getPreTaskCode() == 0L)
                .findFirst()
                .orElse(null);
        assertNotNull(onlyInDeclaredEntry, "应显示仅在声明关系中的入口边");

        RuntimeRelationChange onlyInInferredEntry = response.getRelationCompareDetail().getOnlyInInferred().stream()
                .filter(item -> item.getPreTaskCode() != null && item.getPreTaskCode() == 0L)
                .findFirst()
                .orElse(null);
        assertNotNull(onlyInInferredEntry, "应显示仅在推断关系中的入口边");
    }

    @Test
    void syncShouldFailWhenRelationDecisionMissingUnderMismatch() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition taskA = sqlTask(1L, "task_a", "SQL_A", 10L);
        RuntimeTaskDefinition taskB = sqlTask(2L, "task_b", "SQL_B", 10L);
        definition.setTasks(Arrays.asList(taskA, taskB));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 2L),
                new RuntimeTaskEdge(2L, 1L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL")))
                .thenReturn(matchedAnalyze(101L, 201L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL")))
                .thenReturn(matchedAnalyze(201L, 301L));

        RuntimeSyncExecuteRequest request = new RuntimeSyncExecuteRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        request.setOperator("tester");

        RuntimeSyncExecuteResponse response = service.sync(request);

        assertFalse(Boolean.TRUE.equals(response.getSuccess()));
        assertTrue(Boolean.TRUE.equals(response.getRelationDecisionRequired()));
        assertTrue(response.getErrors().stream()
                .anyMatch(issue -> RuntimeSyncErrorCodes.RELATION_DECISION_REQUIRED.equals(issue.getCode())));
        verify(transactionTemplate, never()).execute(any());
    }

    @Test
    void syncShouldUseDeclaredEdgesWhenDeclaredDecisionProvided() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition taskA = sqlTask(1L, "task_a", "SQL_A", 10L);
        RuntimeTaskDefinition taskB = sqlTask(2L, "task_b", "SQL_B", 10L);
        definition.setTasks(Arrays.asList(taskA, taskB));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 2L),
                new RuntimeTaskEdge(2L, 1L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL")))
                .thenReturn(matchedAnalyze(101L, 201L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL")))
                .thenReturn(matchedAnalyze(201L, 301L));

        List<List<RuntimeTaskEdge>> snapshotEdges = new ArrayList<>();
        when(runtimeDiffService.buildSnapshot(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RuntimeTaskEdge> edges = invocation.getArgument(1);
            snapshotEdges.add(edges == null ? Collections.emptyList() : new ArrayList<>(edges));
            return runtimeSnapshot("hash_declared");
        });
        when(transactionTemplate.execute(any())).thenReturn(null);

        RuntimeSyncExecuteRequest request = new RuntimeSyncExecuteRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        request.setOperator("tester");
        request.setRelationDecision("DECLARED");
        RuntimeSyncExecuteResponse response = service.sync(request);

        assertFalse(Boolean.TRUE.equals(response.getSuccess()));
        assertTrue(Boolean.TRUE.equals(response.getRelationDecisionRequired()));
        assertTrue(response.getErrors().stream()
                .noneMatch(issue -> RuntimeSyncErrorCodes.RELATION_DECISION_REQUIRED.equals(issue.getCode())));
        assertEquals(setOfEdges("0->2", "2->1"), toEdgeSet(snapshotEdges.get(snapshotEdges.size() - 1)));
    }

    @Test
    void syncShouldUseInferredEdgesWhenInferredDecisionProvided() {
        RuntimeWorkflowDefinition definition = baseDefinition();
        RuntimeTaskDefinition taskA = sqlTask(1L, "task_a", "SQL_A", 10L);
        RuntimeTaskDefinition taskB = sqlTask(2L, "task_b", "SQL_B", 10L);
        definition.setTasks(Arrays.asList(taskA, taskB));
        definition.setExplicitEdges(Arrays.asList(
                new RuntimeTaskEdge(0L, 2L),
                new RuntimeTaskEdge(2L, 1L)));

        when(runtimeDefinitionService.loadRuntimeDefinitionFromExport(1L, 1001L)).thenReturn(definition);
        when(sqlTableMatcherService.analyze(eq("SQL_A"), eq("SQL")))
                .thenReturn(matchedAnalyze(101L, 201L));
        when(sqlTableMatcherService.analyze(eq("SQL_B"), eq("SQL")))
                .thenReturn(matchedAnalyze(201L, 301L));

        List<List<RuntimeTaskEdge>> snapshotEdges = new ArrayList<>();
        when(runtimeDiffService.buildSnapshot(any(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<RuntimeTaskEdge> edges = invocation.getArgument(1);
            snapshotEdges.add(edges == null ? Collections.emptyList() : new ArrayList<>(edges));
            return runtimeSnapshot("hash_inferred");
        });
        when(transactionTemplate.execute(any())).thenReturn(null);

        RuntimeSyncExecuteRequest request = new RuntimeSyncExecuteRequest();
        request.setProjectCode(1L);
        request.setWorkflowCode(1001L);
        request.setOperator("tester");
        request.setRelationDecision("INFERRED");
        RuntimeSyncExecuteResponse response = service.sync(request);

        assertFalse(Boolean.TRUE.equals(response.getSuccess()));
        assertTrue(Boolean.TRUE.equals(response.getRelationDecisionRequired()));
        assertTrue(response.getErrors().stream()
                .noneMatch(issue -> RuntimeSyncErrorCodes.RELATION_DECISION_REQUIRED.equals(issue.getCode())));
        assertEquals(setOfEdges("0->1", "1->2"), toEdgeSet(snapshotEdges.get(snapshotEdges.size() - 1)));
    }

    private RuntimeWorkflowDefinition baseDefinition() {
        RuntimeWorkflowDefinition definition = new RuntimeWorkflowDefinition();
        definition.setProjectCode(1L);
        definition.setWorkflowCode(1001L);
        definition.setWorkflowName("wf_runtime");
        definition.setGlobalParams("[]");
        return definition;
    }

    private RuntimeTaskDefinition sqlTask(Long taskCode, String taskName, String sql, Long datasourceId) {
        RuntimeTaskDefinition task = new RuntimeTaskDefinition();
        task.setTaskCode(taskCode);
        task.setTaskName(taskName);
        task.setNodeType("SQL");
        task.setSql(sql);
        task.setDatasourceId(datasourceId);
        task.setDatasourceType("DORIS");
        return task;
    }

    private SqlTableAnalyzeResponse matchedAnalyze(Long inputTableId, Long outputTableId) {
        SqlTableAnalyzeResponse response = new SqlTableAnalyzeResponse();
        response.getInputRefs().add(matchedRef("input_tbl", inputTableId));
        response.getOutputRefs().add(matchedRef("output_tbl", outputTableId));
        return response;
    }

    private SqlTableAnalyzeResponse outputOnlyAnalyze(Long outputTableId) {
        SqlTableAnalyzeResponse response = new SqlTableAnalyzeResponse();
        response.getOutputRefs().add(matchedRef("output_tbl", outputTableId));
        return response;
    }

    private SqlTableAnalyzeResponse.TableRefMatch matchedRef(String rawName, Long tableId) {
        SqlTableAnalyzeResponse.TableRefMatch ref = new SqlTableAnalyzeResponse.TableRefMatch();
        ref.setRawName(rawName);
        ref.setMatchStatus("matched");
        SqlTableAnalyzeResponse.TableCandidate chosen = new SqlTableAnalyzeResponse.TableCandidate();
        chosen.setTableId(tableId);
        ref.setChosenTable(chosen);
        return ref;
    }

    private DolphinDatasourceOption datasource(Long id, String name) {
        DolphinDatasourceOption option = new DolphinDatasourceOption();
        option.setId(id);
        option.setName(name);
        option.setType("DORIS");
        return option;
    }

    private WorkflowRuntimeDiffService.RuntimeSnapshot runtimeSnapshot(String hash) {
        WorkflowRuntimeDiffService.RuntimeSnapshot snapshot = new WorkflowRuntimeDiffService.RuntimeSnapshot();
        snapshot.setSnapshotHash(hash);
        snapshot.setSnapshotJson("{}");
        snapshot.setSnapshotNode(new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode());
        return snapshot;
    }

    private Set<String> toEdgeSet(List<RuntimeTaskEdge> edges) {
        if (edges == null) {
            return Collections.emptySet();
        }
        return edges.stream()
                .filter(item -> item != null
                        && item.getUpstreamTaskCode() != null
                        && item.getDownstreamTaskCode() != null)
                .map(item -> item.getUpstreamTaskCode() + "->" + item.getDownstreamTaskCode())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> setOfEdges(String... edges) {
        Set<String> result = new LinkedHashSet<>();
        if (edges == null) {
            return result;
        }
        result.addAll(Arrays.asList(edges));
        return result;
    }
}
