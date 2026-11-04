package com.onedata.portal.service.lineage;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRepairIssue;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.service.SqlTableMatcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskLineageConsistencyCheckerTest {

    @Mock
    private SqlTableMatcherService sqlTableMatcherService;
    @Mock
    private DataTaskMapper dataTaskMapper;
    @Mock
    private DataWorkflowMapper dataWorkflowMapper;
    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;
    @Mock
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;

    private LineageConsistencyProperties properties;
    private TaskLineageConsistencyChecker checker;

    @BeforeEach
    void setUp() {
        properties = new LineageConsistencyProperties();
        checker = new TaskLineageConsistencyChecker(
                sqlTableMatcherService,
                dataTaskMapper,
                dataWorkflowMapper,
                tableTaskRelationMapper,
                workflowTaskRelationMapper,
                properties,
                new ObjectMapper());
    }

    private SqlTableAnalyzeResponse.TableRefMatch matched(String name, Long tableId) {
        SqlTableAnalyzeResponse.TableCandidate candidate = new SqlTableAnalyzeResponse.TableCandidate();
        candidate.setTableId(tableId);
        candidate.setTableName(name);
        SqlTableAnalyzeResponse.TableRefMatch ref = new SqlTableAnalyzeResponse.TableRefMatch();
        ref.setRawName(name);
        ref.setMatchStatus("matched");
        ref.setChosenTable(candidate);
        return ref;
    }

    private DataTask sqlTask(long id, String name) {
        DataTask task = new DataTask();
        task.setId(id);
        task.setTaskName(name);
        task.setDolphinNodeType("SQL");
        task.setTaskSql("INSERT INTO dws.t SELECT * FROM ods.s");
        task.setDolphinTaskCode(1000L + id);
        return task;
    }

    private void stubAnalyze(List<Long> inputIds, List<Long> outputIds,
            List<String> unmatched, List<String> ambiguous) {
        SqlTableAnalyzeResponse analyze = new SqlTableAnalyzeResponse();
        List<SqlTableAnalyzeResponse.TableRefMatch> inputs = new ArrayList<>();
        for (Long id : inputIds) {
            inputs.add(matched("ods.s" + id, id));
        }
        List<SqlTableAnalyzeResponse.TableRefMatch> outputs = new ArrayList<>();
        for (Long id : outputIds) {
            outputs.add(matched("dws.t" + id, id));
        }
        analyze.setInputRefs(inputs);
        analyze.setOutputRefs(outputs);
        analyze.setUnmatched(unmatched);
        analyze.setAmbiguous(ambiguous);
        when(sqlTableMatcherService.analyze(anyString(), anyString())).thenReturn(analyze);
    }

    private void stubWorkflowTasks(DataWorkflow workflow, List<DataTask> tasks,
            List<TableTaskRelation> relations) {
        List<WorkflowTaskRelation> bindings = new ArrayList<>();
        for (DataTask task : tasks) {
            WorkflowTaskRelation binding = new WorkflowTaskRelation();
            binding.setWorkflowId(workflow.getId());
            binding.setTaskId(task.getId());
            bindings.add(binding);
        }
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(bindings);
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(tasks);
        when(tableTaskRelationMapper.selectList(any())).thenAnswer(invocation -> relations.stream()
                .filter(item -> "read".equals(item.getRelationType()))
                .collect(java.util.stream.Collectors.toList()));
    }

    private TableTaskRelation relation(long taskId, long tableId, String type) {
        TableTaskRelation relation = new TableTaskRelation();
        relation.setTaskId(taskId);
        relation.setTableId(tableId);
        relation.setRelationType(type);
        return relation;
    }

    private DataWorkflow workflow() {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setId(100L);
        workflow.setWorkflowName("wf");
        return workflow;
    }

    @Test
    void highConfidenceGapReportsMissingInputAndOutput() {
        stubAnalyze(Collections.singletonList(1L), Collections.singletonList(9L),
                Collections.emptyList(), Collections.emptyList());

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "t"), Collections.emptyList(), Collections.emptyList());

        assertFalse(gap.isEmpty());
        assertEquals(1, gap.getMissingInputs().size());
        assertEquals(1, gap.getMissingOutputs().size());
    }

    @Test
    void highConfidenceGapIsEmptyWhenLineageCoversSqlTables() {
        stubAnalyze(Collections.singletonList(1L), Collections.singletonList(9L),
                Collections.emptyList(), Collections.emptyList());

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "t"), Collections.singletonList(1L), Collections.singletonList(9L));

        assertTrue(gap.isEmpty());
    }

    @Test
    void extraLineageNeverBlocksSave() {
        // 多余血缘只在发布预检里告警，不参与保存校验，否则手工补充的依赖会导致任务存不进去。
        stubAnalyze(Collections.singletonList(1L), Collections.singletonList(9L),
                Collections.emptyList(), Collections.emptyList());

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "t"), Arrays.asList(1L, 42L), Collections.singletonList(9L));

        assertTrue(gap.isEmpty());
    }

    @Test
    void selfReferentialTableIsExemptFromTheInputSideCheck() {
        // INSERT INTO t SELECT ... FROM t：t 同时是输入和输出。
        // 血缘里只登记了输出 t，不应因为"缺少输入 t"而拒绝保存——
        // 任务不可能依赖自己，补这条读关系不会产生任何依赖边。
        SqlTableAnalyzeResponse analyze = new SqlTableAnalyzeResponse();
        analyze.setInputRefs(Collections.singletonList(matched("dws.t", 5L)));
        analyze.setOutputRefs(Collections.singletonList(matched("dws.t", 5L)));
        when(sqlTableMatcherService.analyze(anyString(), anyString())).thenReturn(analyze);

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "self_merge"), Collections.emptyList(), Collections.singletonList(5L));

        assertTrue(gap.isEmpty(), "自读自写不应算作血缘缺失: " + gap.describe());
    }

    @Test
    void selfReferentialExemptionDoesNotHideAMissingOutput() {
        // 输出侧不豁免：写关系决定下游任务能否连上来，缺了就是真的少边。
        SqlTableAnalyzeResponse analyze = new SqlTableAnalyzeResponse();
        analyze.setInputRefs(Collections.singletonList(matched("dws.t", 5L)));
        analyze.setOutputRefs(Collections.singletonList(matched("dws.t", 5L)));
        when(sqlTableMatcherService.analyze(anyString(), anyString())).thenReturn(analyze);

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "self_merge"), Collections.singletonList(5L), Collections.emptyList());

        assertFalse(gap.isEmpty());
        assertEquals(1, gap.getMissingOutputs().size());
        assertTrue(gap.getMissingInputs().isEmpty());
    }

    @Test
    void selfReferentialExemptionStillFlagsAGenuinelyMissingInput() {
        // 只豁免自读自写那张表，其他输入表照常校验。
        SqlTableAnalyzeResponse analyze = new SqlTableAnalyzeResponse();
        analyze.setInputRefs(Arrays.asList(matched("dws.t", 5L), matched("ods.src", 9L)));
        analyze.setOutputRefs(Collections.singletonList(matched("dws.t", 5L)));
        when(sqlTableMatcherService.analyze(anyString(), anyString())).thenReturn(analyze);

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "self_merge"), Collections.emptyList(), Collections.singletonList(5L));

        assertEquals(1, gap.getMissingInputs().size());
        assertTrue(gap.describe().contains("ods.src"), "实际: " + gap.describe());
    }

    @Test
    void workflowCheckExemptsSelfReferentialTableOnBothMissingAndExtra() {
        DataWorkflow workflow = workflow();
        DataTask task = sqlTask(7L, "self_merge");
        SqlTableAnalyzeResponse analyze = new SqlTableAnalyzeResponse();
        // 解析器只识别出输出侧（自读常被方言写法漏掉）
        analyze.setInputRefs(Collections.emptyList());
        analyze.setOutputRefs(Collections.singletonList(matched("dws.t", 5L)));
        when(sqlTableMatcherService.analyze(anyString(), anyString())).thenReturn(analyze);

        List<WorkflowTaskRelation> bindings = new ArrayList<>();
        WorkflowTaskRelation binding = new WorkflowTaskRelation();
        binding.setWorkflowId(workflow.getId());
        binding.setTaskId(7L);
        bindings.add(binding);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(bindings);
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(task));
        // 用户把读写两侧都登记了
        when(tableTaskRelationMapper.selectList(any())).thenReturn(
                Collections.singletonList(relation(7L, 5L, "read")),
                Collections.singletonList(relation(7L, 5L, "write")));

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, false);

        assertTrue(issues.isEmpty(), "读写都登记齐全的自读自写任务不应产生任何问题: " + issues);
    }

    @Test
    void selfReferentialExemptionIsWithdrawnWhenAnotherTaskWritesTheSameTable() {
        // A: INSERT INTO dws.stat SELECT * FROM ods.src   -> 写 5
        // B: INSERT INTO dws.stat SELECT * FROM dws.stat  -> 读 5 且写 5（自读自写）
        // B 缺 read(5) 时，边 A->B 会彻底消失（inferTaskEdges 对没有读关系的任务直接跳过），
        // 因此这条不能豁免。
        DataWorkflow workflow = workflow();
        DataTask a = sqlTask(1L, "load_stat");
        DataTask b = sqlTask(2L, "dedup_stat");

        List<WorkflowTaskRelation> bindings = new ArrayList<>();
        for (DataTask t : Arrays.asList(a, b)) {
            WorkflowTaskRelation binding = new WorkflowTaskRelation();
            binding.setWorkflowId(workflow.getId());
            binding.setTaskId(t.getId());
            bindings.add(binding);
        }
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(bindings);
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Arrays.asList(a, b));
        // read 关系一条都没有；write：A 和 B 都写 5
        when(tableTaskRelationMapper.selectList(any())).thenReturn(
                Collections.emptyList(),
                Arrays.asList(relation(1L, 5L, "write"), relation(2L, 5L, "write")));
        // 两个任务的 SQL 解析：A 输入 9 输出 5；B 输入 5 输出 5
        SqlTableAnalyzeResponse aAnalyze = new SqlTableAnalyzeResponse();
        aAnalyze.setInputRefs(Collections.singletonList(matched("ods.src", 9L)));
        aAnalyze.setOutputRefs(Collections.singletonList(matched("dws.stat", 5L)));
        SqlTableAnalyzeResponse bAnalyze = new SqlTableAnalyzeResponse();
        bAnalyze.setInputRefs(Collections.singletonList(matched("dws.stat", 5L)));
        bAnalyze.setOutputRefs(Collections.singletonList(matched("dws.stat", 5L)));
        when(sqlTableMatcherService.analyze(anyString(), anyString())).thenReturn(aAnalyze, bAnalyze);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, false);

        assertTrue(issues.stream().anyMatch(issue ->
                        "dedup_stat".equals(issue.getTaskName())
                                && TaskLineageConsistencyChecker.CODE_SQL_RELATION_MISSING.equals(issue.getCode())
                                && issue.getMessage().contains("其他任务也在写入")),
                "同表多写时自读自写不应被豁免，实际问题: " + issues);
    }

    @Test
    void selfReferentialStaysExemptWhenNoOtherTaskWritesThatTable() {
        // 只有本任务写 5，读关系产生不了任何边，豁免继续生效。
        DataWorkflow workflow = workflow();
        DataTask only = sqlTask(2L, "dedup_stat");

        WorkflowTaskRelation binding = new WorkflowTaskRelation();
        binding.setWorkflowId(workflow.getId());
        binding.setTaskId(2L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(binding));
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(only));
        when(tableTaskRelationMapper.selectList(any())).thenReturn(
                Collections.emptyList(),
                Collections.singletonList(relation(2L, 5L, "write")));
        SqlTableAnalyzeResponse analyze = new SqlTableAnalyzeResponse();
        analyze.setInputRefs(Collections.singletonList(matched("dws.stat", 5L)));
        analyze.setOutputRefs(Collections.singletonList(matched("dws.stat", 5L)));
        when(sqlTableMatcherService.analyze(anyString(), anyString())).thenReturn(analyze);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, false);

        assertTrue(issues.isEmpty(), "只有本任务写该表时应继续豁免: " + issues);
    }

    @Test
    void taskLevelSaveCheckStaysExemptRegardlessOfOtherWriters() {
        // 保存时拿不到工作流上下文，且被依赖的任务可能还没创建，
        // 一律豁免，由发布/导出兜底。
        SqlTableAnalyzeResponse analyze = new SqlTableAnalyzeResponse();
        analyze.setInputRefs(Collections.singletonList(matched("dws.stat", 5L)));
        analyze.setOutputRefs(Collections.singletonList(matched("dws.stat", 5L)));
        when(sqlTableMatcherService.analyze(anyString(), anyString())).thenReturn(analyze);

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(2L, "dedup_stat"), Collections.emptyList(), Collections.singletonList(5L));

        assertTrue(gap.isEmpty(), "保存路径应保持豁免: " + gap.describe());
    }

    @Test
    void nonSqlTaskSkipsSqlAnalysisEntirely() {
        DataTask shellTask = sqlTask(7L, "t");
        shellTask.setDolphinNodeType("SHELL");

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                shellTask, Collections.emptyList(), Collections.emptyList());

        assertTrue(gap.isEmpty());
        verify(sqlTableMatcherService, never()).analyze(any(), any());
    }

    @Test
    void workflowCheckFlagsMissingRelationAsNonRepairable() {
        DataWorkflow workflow = workflow();
        DataTask task = sqlTask(7L, "t");
        stubAnalyze(Collections.singletonList(1L), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        stubWorkflowTasks(workflow, Collections.singletonList(task), Collections.emptyList());

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, false);

        assertTrue(issues.stream().anyMatch(issue ->
                TaskLineageConsistencyChecker.CODE_SQL_RELATION_MISSING.equals(issue.getCode())
                        && Boolean.FALSE.equals(issue.getRepairable())));
    }

    @Test
    void workflowCheckFlagsExtraRelationAsWarning() {
        DataWorkflow workflow = workflow();
        DataTask task = sqlTask(7L, "t");
        stubAnalyze(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        stubWorkflowTasks(workflow, Collections.singletonList(task),
                Collections.singletonList(relation(7L, 42L, "read")));

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, false);

        assertTrue(issues.stream().anyMatch(issue ->
                TaskLineageConsistencyChecker.CODE_RELATION_EXTRA.equals(issue.getCode())));
    }

    @Test
    void workflowCheckFlagsUnresolvedReferences() {
        DataWorkflow workflow = workflow();
        DataTask task = sqlTask(7L, "t");
        stubAnalyze(Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList("ext.unknown_table"), Collections.emptyList());
        stubWorkflowTasks(workflow, Collections.singletonList(task), Collections.emptyList());

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, false);

        assertTrue(issues.stream().anyMatch(issue ->
                TaskLineageConsistencyChecker.CODE_SQL_UNRESOLVED.equals(issue.getCode())));
    }

    @Test
    void definitionDriftIsRepairableAndSkippedWhenNotRequested() {
        DataWorkflow workflow = workflow();
        workflow.setDefinitionJson("{\"taskDefinitionList\":[{\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[1,2],\"outputTableIds\":[9]}]}");
        DataTask task = sqlTask(7L, "t");
        stubAnalyze(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
        stubWorkflowTasks(workflow, Collections.singletonList(task), Collections.emptyList());

        List<WorkflowPublishRepairIssue> withDrift = checker.checkWorkflow(workflow, true);
        assertTrue(withDrift.stream().anyMatch(issue ->
                TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())
                        && Boolean.TRUE.equals(issue.getRepairable())));

        // deploy 在 syncCurrentVersion() 之后调用时必须跳过漂移比对，
        // 否则会拦下一个 deploy 本来就会修好的工作流。
        List<WorkflowPublishRepairIssue> withoutDrift = checker.checkWorkflow(workflow, false);
        assertTrue(withoutDrift.stream().noneMatch(issue ->
                TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())));
    }

    /**
     * 关系表推导出 7 -> 8 这条边（7 写 tbl 5，8 读 tbl 5）。
     * 两个节点的 inputTableIds/outputTableIds 都正确，只有 processTaskRelationList 少了这条边。
     */
    private DataWorkflow workflowWithTwoTasks(String processTaskRelationList) {
        DataWorkflow workflow = workflow();
        workflow.setDefinitionJson("{\"taskDefinitionList\":["
                + "{\"taskCode\":1007,\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[],\"outputTableIds\":[5]},"
                + "{\"taskCode\":1008,\"xPlatformTaskMeta\":{\"taskId\":8},"
                + "\"inputTableIds\":[5],\"outputTableIds\":[6]}],"
                + "\"processTaskRelationList\":" + processTaskRelationList + "}");
        return workflow;
    }

    private void stubTwoTaskWorkflow(DataWorkflow workflow) {
        DataTask upstream = sqlTask(7L, "upstream");
        DataTask downstream = sqlTask(8L, "downstream");
        List<WorkflowTaskRelation> bindings = new ArrayList<>();
        for (DataTask task : Arrays.asList(upstream, downstream)) {
            WorkflowTaskRelation binding = new WorkflowTaskRelation();
            binding.setWorkflowId(workflow.getId());
            binding.setTaskId(task.getId());
            bindings.add(binding);
        }
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(bindings);
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Arrays.asList(upstream, downstream));
        // read: 8 读 5；write: 7 写 5，8 写 6
        when(tableTaskRelationMapper.selectList(any())).thenReturn(
                Collections.singletonList(relation(8L, 5L, "read")),
                Arrays.asList(relation(7L, 5L, "write"), relation(8L, 6L, "write")));
        stubAnalyze(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());
    }

    @Test
    void definitionDriftDetectsMissingProcessTaskRelationEdge() {
        // 表数组全对，只有流程边少了一条：这正是发布出去的 DAG 会缺依赖的情况。
        // 只比对 taskDefinitionList 的 inputTableIds/outputTableIds 是发现不了的。
        DataWorkflow workflow = workflowWithTwoTasks("[{\"preTaskCode\":0,\"postTaskCode\":1007}]");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().anyMatch(issue ->
                        TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())
                                && "workflow.definitionJson.processTaskRelationList".equals(issue.getField())
                                && issue.getMessage().contains("缺少")),
                "应报告缺失的任务依赖边，实际问题: " + issues);
    }

    @Test
    void definitionDriftAcceptsMatchingProcessTaskRelationEdges() {
        DataWorkflow workflow = workflowWithTwoTasks(
                "[{\"preTaskCode\":0,\"postTaskCode\":1007},{\"preTaskCode\":1007,\"postTaskCode\":1008}]");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().noneMatch(issue ->
                        TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())),
                "边与表清单都一致时不应报漂移，实际问题: " + issues);
    }

    @Test
    void definitionDriftDetectsExtraProcessTaskRelationEdge() {
        DataWorkflow workflow = workflowWithTwoTasks(
                "[{\"preTaskCode\":0,\"postTaskCode\":1007},{\"preTaskCode\":1007,\"postTaskCode\":1008},"
                        + "{\"preTaskCode\":1008,\"postTaskCode\":1007}]");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().anyMatch(issue ->
                        TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())
                                && issue.getMessage().contains("不存在的任务依赖边")),
                "应报告多余的任务依赖边，实际问题: " + issues);
    }

    @Test
    void definitionDriftDetectsTaskNodeMissingFromDefinition() {
        DataWorkflow workflow = workflow();
        // 定义里只有 7，工作流实际绑定了 7 和 8
        workflow.setDefinitionJson("{\"taskDefinitionList\":["
                + "{\"taskCode\":1007,\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[],\"outputTableIds\":[5]}],"
                + "\"processTaskRelationList\":[]}");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().anyMatch(issue ->
                        "workflow.definitionJson.taskDefinitionList".equals(issue.getField())
                                && issue.getMessage().contains("缺少该任务节点")),
                "应报告定义中缺失的任务节点，实际问题: " + issues);
    }

    @Test
    void definitionDriftDetectsTaskNodeNotBoundToWorkflow() {
        DataWorkflow workflow = workflowWithTwoTasks("[]");
        DataTask only = sqlTask(7L, "upstream");
        WorkflowTaskRelation binding = new WorkflowTaskRelation();
        binding.setWorkflowId(workflow.getId());
        binding.setTaskId(7L);
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.singletonList(binding));
        when(dataTaskMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(only));
        when(tableTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
        stubAnalyze(Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().anyMatch(issue ->
                        issue.getMessage().contains("未绑定到该工作流的任务节点")),
                "应报告定义中多余的任务节点，实际问题: " + issues);
    }

    @Test
    void emptyTaskDefinitionListIsReportedWhenWorkflowActuallyHasTasks() {
        // 回归：此前 tablesByTaskId 为空就直接返回，这段合法 JSON 会被当成"没有漂移"，
        // 导出得到一个不含任何任务的文件却毫无提示。
        DataWorkflow workflow = workflow();
        workflow.setDefinitionJson("{\"taskDefinitionList\":[],\"processTaskRelationList\":[]}");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        long missingNodeIssues = issues.stream()
                .filter(issue -> "workflow.definitionJson.taskDefinitionList".equals(issue.getField()))
                .filter(issue -> issue.getMessage().contains("缺少该任务节点"))
                .count();
        assertEquals(2, missingNodeIssues, "两个绑定任务都应被报告，实际问题: " + issues);
    }

    @Test
    void definitionNodesAreReportedWhenWorkflowHasNoBoundTasks() {
        // 反向盲区：工作流没有任何绑定任务时，此前在 taskIds 为空处就返回了，
        // 定义里残留的旧节点永远发现不了。
        DataWorkflow workflow = workflow();
        workflow.setDefinitionJson("{\"taskDefinitionList\":["
                + "{\"taskCode\":1007,\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[],\"outputTableIds\":[5]}],"
                + "\"processTaskRelationList\":[]}");
        when(workflowTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());
        when(tableTaskRelationMapper.selectList(any())).thenReturn(Collections.emptyList());

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().anyMatch(issue ->
                        issue.getMessage().contains("未绑定到该工作流的任务节点")),
                "应报告定义中残留的任务节点，实际问题: " + issues);
        verify(dataTaskMapper, never()).selectBatchIds(any());
    }

    @Test
    void oneUnidentifiableNodeDoesNotDisableTheWholeDriftCheck() {
        // 回归：此前"解析出的 taskId 数 != 节点数"就整份返回 null，
        // 一个 {} 节点会让本来可确认的节点、表清单和任务边全部不再比较。
        DataWorkflow workflow = workflow();
        workflow.setDefinitionJson("{\"taskDefinitionList\":["
                + "{\"taskCode\":1007,\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[99],\"outputTableIds\":[5]},"
                + "{}],"
                + "\"processTaskRelationList\":[]}");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        // 不完整这件事本身必须被报告，不能表现为一致
        assertTrue(issues.stream().anyMatch(issue ->
                        issue.getMessage().contains("无法识别 taskId")
                                && issue.getMessage().contains("检查不完整")),
                "应报告存在无法识别的节点，实际问题: " + issues);
        // 能识别的节点仍然照常比对表清单：定义写了输入 [99]，关系表里任务 7 没有读关系
        assertTrue(issues.stream().anyMatch(issue ->
                        "workflow.definitionJson".equals(issue.getField())
                                && issue.getMessage().contains("与当前血缘不一致")),
                "可识别节点的表清单仍应比较，实际问题: " + issues);
    }

    @Test
    void unidentifiableNodesSuppressOnlyTheClaimsTheyCouldInvalidate() {
        // 任务 8 在定义里没有对应节点，但存在一个认不出的节点——它可能正是任务 8，
        // 因此不能断言"定义缺少任务 8"。多余边同理：认不出的节点也占着 taskCode。
        DataWorkflow workflow = workflow();
        workflow.setDefinitionJson("{\"taskDefinitionList\":["
                + "{\"taskCode\":1007,\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[],\"outputTableIds\":[5]},"
                + "{}],"
                + "\"processTaskRelationList\":[{\"preTaskCode\":9999,\"postTaskCode\":1007}]}");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().noneMatch(issue ->
                        issue.getMessage().contains("缺少该任务节点")),
                "有认不出的节点时不应断言任务缺失，实际问题: " + issues);
        assertTrue(issues.stream().noneMatch(issue ->
                        issue.getMessage().contains("不存在的任务依赖边")),
                "有认不出的节点时不应断言多余边，实际问题: " + issues);
    }

    @Test
    void malformedDefinitionJsonDegradesGracefullyInsteadOfFailingThePreview() {
        // definitionJson 可能来自导入或历史版本，格式不受本模块控制。
        // 降级的含义是"不抛异常、不阻断"，不是"静默当成没问题"：定义非空却读不出
        // 节点清单时，导出会把这段内容原样发出去，存量扫描也会把它统计成干净的。
        for (String broken : Arrays.asList(
                "{not json",
                "[]",
                "{\"taskDefinitionList\":\"not-an-array\"}",
                "{\"processTaskRelationList\":[]}")) {
            DataWorkflow workflow = workflow();
            workflow.setDefinitionJson(broken);
            stubTwoTaskWorkflow(workflow);

            List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

            assertTrue(issues.stream().anyMatch(issue ->
                            TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())
                                    && issue.getMessage().contains("无法解析或缺少")),
                    "定义不可检查这件事必须被报告，输入: " + broken + "，实际问题: " + issues);
        }
    }

    @Test
    void blankDefinitionJsonIsNotTreatedAsDrift() {
        // 定义尚未生成是合法状态，导出会走构建兜底，不该报成损坏。
        for (String blank : Arrays.asList(null, "", "   ")) {
            DataWorkflow workflow = workflow();
            workflow.setDefinitionJson(blank);
            stubTwoTaskWorkflow(workflow);

            List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

            assertTrue(issues.stream().noneMatch(issue ->
                            TaskLineageConsistencyChecker.CODE_DEFINITION_DRIFT.equals(issue.getCode())),
                    "定义为空不应报漂移，输入: " + blank + "，实际问题: " + issues);
        }
    }

    @Test
    void duplicateTaskIdIsReportedSeparatelyAndDoesNotSuppressMissingTaskNodes() {
        // 回归：此前用 nodeCount - map.size() 反推无法识别的节点数，map 会对重复
        // taskId 去重，两个都写着 taskId=7 的节点会被误算成"1 个无法识别"，
        // 进而错误抑制"任务 8 在定义中缺失"这个本可确认的结论。
        DataWorkflow workflow = workflow();
        workflow.setDefinitionJson("{\"taskDefinitionList\":["
                + "{\"taskCode\":1007,\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[],\"outputTableIds\":[5]},"
                + "{\"taskCode\":1007,\"xPlatformTaskMeta\":{\"taskId\":7},"
                + "\"inputTableIds\":[],\"outputTableIds\":[5]}],"
                + "\"processTaskRelationList\":[]}");
        stubTwoTaskWorkflow(workflow);

        List<WorkflowPublishRepairIssue> issues = checker.checkWorkflow(workflow, true);

        assertTrue(issues.stream().anyMatch(issue ->
                        issue.getMessage().contains("重复的 taskId")),
                "重复 taskId 应单独报告，实际问题: " + issues);
        assertTrue(issues.stream().noneMatch(issue ->
                        issue.getMessage().contains("无法识别 taskId")),
                "重复不等于无法识别，实际问题: " + issues);
        // 任务 8 确实不在定义里，这个结论不该被重复 taskId 抑制
        assertTrue(issues.stream().anyMatch(issue ->
                        issue.getMessage().contains("缺少该任务节点")),
                "缺失结论不应被重复 taskId 抑制，实际问题: " + issues);
    }

    @Test
    void sqlTaskWithoutSqlTextIsSkippedInsteadOfBeingFlagged() {
        // 节点类型是 SQL 但 taskSql 为空（草稿态常见），没有可解析的内容，不应报任何 SQL 类问题。
        DataTask task = sqlTask(7L, "draft");
        task.setTaskSql(null);

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                task, Collections.emptyList(), Collections.emptyList());

        assertTrue(gap.isEmpty());
        verify(sqlTableMatcherService, never()).analyze(any(), any());

        task.setTaskSql("   ");
        assertTrue(checker.findHighConfidenceGap(task, Collections.emptyList(), Collections.emptyList())
                .isEmpty());
        verify(sqlTableMatcherService, never()).analyze(any(), any());
    }

    @Test
    void sqlParseFailureDoesNotBlockSave() {
        // 解析器对方言 SQL 存在误判空间，抛异常时必须放行而不是拒绝保存。
        when(sqlTableMatcherService.analyze(anyString(), anyString()))
                .thenThrow(new IllegalStateException("parser blew up"));

        TaskLineageConsistencyChecker.HighConfidenceGap gap = checker.findHighConfidenceGap(
                sqlTask(7L, "t"), Collections.emptyList(), Collections.emptyList());

        assertTrue(gap.isEmpty());
    }

    @Test
    void warnModeNeverReportsBlockingIssues() {
        WorkflowPublishRepairIssue missing = new WorkflowPublishRepairIssue();
        missing.setCode(TaskLineageConsistencyChecker.CODE_SQL_RELATION_MISSING);

        assertFalse(checker.hasBlockingIssue(Collections.singletonList(missing)));
    }

    @Test
    void blockMissingModeBlocksOnlyOnMissingRelation() {
        properties.setEnforcementMode(LineageConsistencyProperties.MODE_BLOCK_MISSING);

        WorkflowPublishRepairIssue missing = new WorkflowPublishRepairIssue();
        missing.setCode(TaskLineageConsistencyChecker.CODE_SQL_RELATION_MISSING);
        WorkflowPublishRepairIssue unresolved = new WorkflowPublishRepairIssue();
        unresolved.setCode(TaskLineageConsistencyChecker.CODE_SQL_UNRESOLVED);
        WorkflowPublishRepairIssue extra = new WorkflowPublishRepairIssue();
        extra.setCode(TaskLineageConsistencyChecker.CODE_RELATION_EXTRA);

        assertTrue(checker.hasBlockingIssue(Collections.singletonList(missing)));
        // unmatched / ambiguous / 多余关系在任何模式下都只告警，
        // 否则用户会陷入"存不进、发不了、导不出"的死循环。
        assertFalse(checker.hasBlockingIssue(Arrays.asList(unresolved, extra)));
    }
}
