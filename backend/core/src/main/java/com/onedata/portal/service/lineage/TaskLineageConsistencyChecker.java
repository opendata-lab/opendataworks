package com.onedata.portal.service.lineage;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.workflow.WorkflowLineageConsistencyResponse;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;



/**
 * SQL 推断、{@code table_task_relation}、{@code definitionJson} 三方只读比对。
 *
 * <p>只读组件，不写任何表，不依赖 {@code DataTaskService} 与 {@link TaskLineageWriteService}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskLineageConsistencyChecker {

    /** SQL 已明确匹配，但关系表缺失。block-missing 模式下阻断发布。 */
    public static final String CODE_SQL_RELATION_MISSING = "LINEAGE_SQL_RELATION_MISSING";
    /** 关系表存在，但 SQL 未推断出来。始终只告警。 */
    public static final String CODE_RELATION_EXTRA = "LINEAGE_RELATION_EXTRA";
    /** SQL 存在未匹配或歧义的表引用。始终只告警。 */
    public static final String CODE_SQL_UNRESOLVED = "LINEAGE_SQL_UNRESOLVED";
    /** definitionJson 的表清单与关系表不一致。可走既有元数据修复流程。 */
    public static final String CODE_DEFINITION_DRIFT = "LINEAGE_DEFINITION_DRIFT";

    private static final String SEVERITY_ERROR = "ERROR";
    private static final String SEVERITY_WARNING = "WARNING";

    private final SqlTableMatcherService sqlTableMatcherService;
    private final DataTaskMapper dataTaskMapper;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final LineageConsistencyProperties properties;
    private final ObjectMapper objectMapper;

    /**
     * 任务保存前的 SQL 高可信缺失检查。
     *
     * <p>只看 {@code matchStatus=matched} 的表引用。多余血缘、unmatched、ambiguous 一律不参与，
     * 避免用户陷入"存不进、发不了、导不出"的死循环。
     *
     * @return 缺失明细；为空表示通过
     */
    public HighConfidenceGap findHighConfidenceGap(DataTask task,
            Collection<Long> finalInputTableIds,
            Collection<Long> finalOutputTableIds) {
        HighConfidenceGap gap = new HighConfidenceGap();
        if (!isSqlTask(task)) {
            return gap;
        }
        SqlTableAnalyzeResponse analyze = analyzeTaskSql(task);
        if (analyze == null) {
            return gap;
        }
        Set<Long> inputs = toIdSet(finalInputTableIds);
        Set<Long> outputs = toIdSet(finalOutputTableIds);

        for (SqlTableAnalyzeResponse.TableRefMatch ref : matchedRefs(analyze.getInputRefs())) {
            Long tableId = ref.getChosenTable().getTableId();
            // 自读自写的表豁免输入侧检查：INSERT INTO t SELECT ... FROM t 这类写法里，
            // t 已经作为输出登记在血缘中，任务不可能依赖自己（边推导本就排除自环），
            // 再要求一条读关系不会产生任何依赖边，只会让这类任务永远存不进去。
            if (isSelfReferential(tableId, outputs, matchedTableIds(analyze.getOutputRefs()))) {
                continue;
            }
            if (!inputs.contains(tableId)) {
                gap.getMissingInputs().add(describeRef(ref));
            }
        }
        for (SqlTableAnalyzeResponse.TableRefMatch ref : matchedRefs(analyze.getOutputRefs())) {
            if (!outputs.contains(ref.getChosenTable().getTableId())) {
                gap.getMissingOutputs().add(describeRef(ref));
            }
        }
        return gap;
    }

    /**
     * 工作流级完整报告，供只读接口使用。
     */
    public WorkflowLineageConsistencyResponse report(Long workflowId) {
        WorkflowLineageConsistencyResponse response = new WorkflowLineageConsistencyResponse();
        response.setWorkflowId(workflowId);
        response.setEnforcementMode(properties.getEnforcementMode());

        DataWorkflow workflow = workflowId == null ? null : dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        response.setWorkflowName(workflow.getWorkflowName());

        List<WorkflowPublishRepairIssue> issues = checkWorkflow(workflow, true);
        response.setIssues(issues);

        Map<String, Integer> counts = new LinkedHashMap<>();
        for (WorkflowPublishRepairIssue issue : issues) {
            counts.merge(issue.getCode(), 1, Integer::sum);
        }
        response.setCounts(counts);
        response.setBlocking(hasBlockingIssue(issues));
        return response;
    }

    /**
     * 工作流级一致性比对。
     *
     * @param includeDefinitionDrift deploy 在 {@code syncCurrentVersion()} 之后调用时传 false：
     *                               此时 definitionJson 必然已按关系表重建，再比对没有意义
     */
    public List<WorkflowPublishRepairIssue> checkWorkflow(DataWorkflow workflow, boolean includeDefinitionDrift) {
        if (workflow == null || workflow.getId() == null) {
            return Collections.emptyList();
        }
        List<Long> taskIds = findTaskIds(workflow.getId());
        // 这里不能在任务为空时提前返回：工作流没有绑定任务、而定义里还留着旧节点，
        // 同样是需要报告的漂移。空集合必须进入下面的双向比较。
        List<DataTask> tasks = taskIds.isEmpty()
                ? Collections.emptyList()
                : dataTaskMapper.selectBatchIds(taskIds);
        if (tasks == null) {
            tasks = Collections.emptyList();
        }
        Map<Long, Set<Long>> readsByTask = loadRelationMap(taskIds, "read");
        Map<Long, Set<Long>> writesByTask = loadRelationMap(taskIds, "write");

        List<WorkflowPublishRepairIssue> issues = new ArrayList<>();
        for (DataTask task : tasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            Set<Long> reads = readsByTask.getOrDefault(task.getId(), Collections.emptySet());
            Set<Long> writes = writesByTask.getOrDefault(task.getId(), Collections.emptySet());
            // 非 SQL 节点完全跳过 SQL 比对：analyze() 对非 SQL 返回空结果，
            // 若不跳过，SHELL/PYTHON 等任务的既有血缘会被整体误判成"多余"。
            if (isSqlTask(task)) {
                collectSqlIssues(task, reads, writes,
                        tablesWrittenByOtherTasks(task.getId(), writesByTask), issues);
            }
        }
        if (includeDefinitionDrift) {
            collectDefinitionDriftIssues(workflow, tasks, readsByTask, writesByTask, issues);
        }
        return issues;
    }

    /**
     * 当前模式下是否存在阻断性问题。
     */
    public boolean hasBlockingIssue(Collection<WorkflowPublishRepairIssue> issues) {
        if (CollectionUtils.isEmpty(issues) || !properties.isBlockMissing()) {
            return false;
        }
        return issues.stream()
                .filter(Objects::nonNull)
                .anyMatch(issue -> CODE_SQL_RELATION_MISSING.equals(issue.getCode()));
    }

    private void collectSqlIssues(DataTask task,
            Set<Long> reads,
            Set<Long> writes,
            Set<Long> tablesWrittenByOthers,
            List<WorkflowPublishRepairIssue> issues) {
        SqlTableAnalyzeResponse analyze = analyzeTaskSql(task);
        if (analyze == null) {
            return;
        }

        Set<Long> sqlInputs = matchedTableIds(analyze.getInputRefs());
        Set<Long> sqlOutputs = matchedTableIds(analyze.getOutputRefs());

        // 自读自写的表在输入侧豁免，但仅限"本工作流里只有本任务写这张表"的情况。
        // 若还有别的任务写它，这条读关系承载着"对方 -> 本任务"的真实依赖边，
        // 缺了会让该边彻底消失（inferTaskEdges 对没有任何读关系的任务直接跳过），
        // 因此单独拎出来照常报告。
        List<String> missingInputs = new ArrayList<>();
        List<String> missingSharedTableInputs = new ArrayList<>();
        for (SqlTableAnalyzeResponse.TableRefMatch ref : matchedRefs(analyze.getInputRefs())) {
            Long tableId = ref.getChosenTable().getTableId();
            if (reads.contains(tableId)) {
                continue;
            }
            if (!isSelfReferential(tableId, writes, sqlOutputs)) {
                missingInputs.add(describeRef(ref));
            } else if (tablesWrittenByOthers.contains(tableId)) {
                missingSharedTableInputs.add(describeRef(ref));
            }
            // 其余情况：自读自写且只有本任务写该表，豁免。
        }
        if (!missingSharedTableInputs.isEmpty()) {
            issues.add(buildIssue(task,
                    CODE_SQL_RELATION_MISSING,
                    blockMissingSeverity(),
                    false,
                    "task.lineage.missing",
                    String.format("任务[%s] 读取了同工作流其他任务也在写入的表 %s，但未登记读血缘。"
                                    + "缺这条读关系会让上游任务到本任务的依赖边消失，调度顺序可能出错。"
                                    + "请补齐输入表后重新保存。",
                            taskLabel(task),
                            String.join(", ", missingSharedTableInputs))));
        }
        // 输出侧不豁免：写关系决定下游任务能否连上这个任务，缺了就是真的少边。
        List<String> missingOutputs = matchedRefs(analyze.getOutputRefs()).stream()
                .filter(ref -> !writes.contains(ref.getChosenTable().getTableId()))
                .map(this::describeRef)
                .collect(Collectors.toList());

        if (!missingInputs.isEmpty() || !missingOutputs.isEmpty()) {
            issues.add(buildIssue(task,
                    CODE_SQL_RELATION_MISSING,
                    blockMissingSeverity(),
                    false,
                    "task.lineage.missing",
                    String.format("任务[%s] SQL 已解析出的表未登记到血缘：%s。请打开任务，按 SQL 分析结果补齐输入/输出表后重新保存。",
                            taskLabel(task),
                            joinGap(missingInputs, missingOutputs))));
        }

        Set<Long> extraInputs = new LinkedHashSet<>(reads);
        extraInputs.removeAll(sqlInputs);
        // 同一张表既读又写时，读关系不参与"多余"判定：解析器没识别出自读的情况下，
        // 用户手工登记的那条读关系会被误报成多余的依赖。
        extraInputs.removeAll(writes);
        extraInputs.removeAll(sqlOutputs);
        Set<Long> extraOutputs = new LinkedHashSet<>(writes);
        extraOutputs.removeAll(sqlOutputs);
        if (!extraInputs.isEmpty() || !extraOutputs.isEmpty()) {
            issues.add(buildIssue(task,
                    CODE_RELATION_EXTRA,
                    SEVERITY_WARNING,
                    false,
                    "task.lineage.extra",
                    String.format("任务[%s] 存在 SQL 未推断出的血缘：%s。若为手工补充的依赖可忽略。",
                            taskLabel(task),
                            joinGap(toIdText(extraInputs), toIdText(extraOutputs)))));
        }

        List<String> unresolved = new ArrayList<>();
        if (!CollectionUtils.isEmpty(analyze.getUnmatched())) {
            unresolved.add("未匹配: " + String.join(", ", analyze.getUnmatched()));
        }
        if (!CollectionUtils.isEmpty(analyze.getAmbiguous())) {
            unresolved.add("歧义: " + String.join(", ", analyze.getAmbiguous()));
        }
        if (!unresolved.isEmpty()) {
            issues.add(buildIssue(task,
                    CODE_SQL_UNRESOLVED,
                    SEVERITY_WARNING,
                    false,
                    "task.lineage.unresolved",
                    String.format("任务[%s] SQL 中存在无法确定的表引用（%s）。可能是未登记的外部表，血缘可能不完整。",
                            taskLabel(task),
                            String.join("；", unresolved))));
        }
    }

    private void collectDefinitionDriftIssues(DataWorkflow workflow,
            List<DataTask> tasks,
            Map<Long, Set<Long>> readsByTask,
            Map<Long, Set<Long>> writesByTask,
            List<WorkflowPublishRepairIssue> issues) {
        // definitionJson 为空是合法状态：定义尚未生成，导出会走构建兜底，不算漂移。
        if (!StringUtils.hasText(workflow.getDefinitionJson())) {
            return;
        }
        ParsedDefinition definition = parseDefinition(workflow.getDefinitionJson());
        if (definition == null) {
            // 降级的含义是"不抛异常、不阻断"，不是"静默当成没问题"。
            // 定义非空却读不出节点清单时，导出会把这段内容原样发出去，
            // 存量扫描也会把它统计成干净的——必须显式报告不可检查。
            issues.add(buildIssue(null,
                    CODE_DEFINITION_DRIFT,
                    SEVERITY_WARNING,
                    true,
                    "workflow.definitionJson",
                    "工作流定义无法解析或缺少 taskDefinitionList 节点清单，无法与当前血缘比对。"
                            + "该定义可能已损坏，建议重新保存任一任务以重建定义。"));
            return;
        }
        if (!definition.getDuplicateTaskIds().isEmpty()) {
            issues.add(buildIssue(null,
                    CODE_DEFINITION_DRIFT,
                    SEVERITY_WARNING,
                    true,
                    "workflow.definitionJson.taskDefinitionList",
                    String.format("工作流定义中存在重复的 taskId：%s。", definition.getDuplicateTaskIds())));
        }
        // 节点为空是一个明确结论（"定义里一个任务都没有"），必须参与比较，
        // 否则 taskDefinitionList:[] 会被当成"没有漂移"，导出得到的文件不含任何任务却毫无提示。

        Map<Long, DataTask> taskById = new LinkedHashMap<>();
        for (DataTask task : tasks) {
            if (task != null && task.getId() != null) {
                taskById.put(task.getId(), task);
            }
        }

        if (definition.hasUnresolvedNodes()) {
            // 单独报告，避免"有认不出的节点"这一事实被静默吞掉，表现为一致。
            issues.add(buildIssue(null,
                    CODE_DEFINITION_DRIFT,
                    SEVERITY_WARNING,
                    true,
                    "workflow.definitionJson.taskDefinitionList",
                    String.format("工作流定义中有 %d 个任务节点无法识别 taskId，本次定义漂移检查不完整。",
                            definition.getUnresolvedNodeCount())));
        }

        for (DataTask task : tasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            Set<Long> reads = readsByTask.getOrDefault(task.getId(), Collections.emptySet());
            Set<Long> writes = writesByTask.getOrDefault(task.getId(), Collections.emptySet());
            DefinitionTables persisted = definition.getTablesByTaskId().get(task.getId());
            if (persisted == null) {
                // 存在认不出 taskId 的节点时不报"缺少该任务"：那个节点可能正是它，
                // 信息不足以断言。上面的"检查不完整"已经提示了这一事实。
                if (definition.hasUnresolvedNodes()) {
                    continue;
                }
                // 工作流绑定了该任务，定义里却没有对应节点。发布出去会直接少一个节点。
                issues.add(buildIssue(task,
                        CODE_DEFINITION_DRIFT,
                        SEVERITY_WARNING,
                        true,
                        "workflow.definitionJson.taskDefinitionList",
                        String.format("任务[%s] 已绑定到工作流，但工作流定义中缺少该任务节点。",
                                taskLabel(task))));
                continue;
            }
            if (persisted.getInputs().equals(reads) && persisted.getOutputs().equals(writes)) {
                continue;
            }
            issues.add(buildIssue(task,
                    CODE_DEFINITION_DRIFT,
                    SEVERITY_WARNING,
                    true,
                    "workflow.definitionJson",
                    String.format("任务[%s] 的工作流定义与当前血缘不一致（定义 输入%s/输出%s，血缘 输入%s/输出%s）。",
                            taskLabel(task),
                            persisted.getInputs(),
                            persisted.getOutputs(),
                            reads,
                            writes)));
        }

        for (Long definitionTaskId : definition.getTablesByTaskId().keySet()) {
            if (taskById.containsKey(definitionTaskId)) {
                continue;
            }
            // 定义里有、工作流没绑定：发布会带上一个已经不属于该工作流的节点。
            issues.add(buildIssue(null,
                    CODE_DEFINITION_DRIFT,
                    SEVERITY_WARNING,
                    true,
                    "workflow.definitionJson.taskDefinitionList",
                    String.format("工作流定义中存在未绑定到该工作流的任务节点(taskId=%s)。", definitionTaskId)));
        }

        collectDefinitionEdgeDriftIssues(definition, taskById, readsByTask, writesByTask, issues);
    }

    /**
     * 比较关系表推导出的任务边与定义里的 {@code processTaskRelationList}。
     *
     * <p>光比对每个任务的表清单是不够的：表数组正确、但 {@code processTaskRelationList} 少边时，
     * 发布出去的 DAG 依然缺依赖。真正决定运行态拓扑的是这份关系列表。
     */
    private void collectDefinitionEdgeDriftIssues(ParsedDefinition definition,
            Map<Long, DataTask> taskById,
            Map<Long, Set<Long>> readsByTask,
            Map<Long, Set<Long>> writesByTask,
            List<WorkflowPublishRepairIssue> issues) {
        Set<String> expectedEdges = new LinkedHashSet<>();
        Map<String, String> edgeLabels = new LinkedHashMap<>();
        for (Long downstreamTaskId : taskById.keySet()) {
            Set<Long> downstreamReads = readsByTask.getOrDefault(downstreamTaskId, Collections.emptySet());
            if (downstreamReads.isEmpty()) {
                continue;
            }
            for (Long upstreamTaskId : taskById.keySet()) {
                if (Objects.equals(upstreamTaskId, downstreamTaskId)) {
                    continue;
                }
                Set<Long> upstreamWrites = writesByTask.getOrDefault(upstreamTaskId, Collections.emptySet());
                if (upstreamWrites.stream().noneMatch(downstreamReads::contains)) {
                    continue;
                }
                Long preCode = definition.getCodeByTaskId().get(upstreamTaskId);
                Long postCode = definition.getCodeByTaskId().get(downstreamTaskId);
                if (preCode == null || postCode == null) {
                    // 节点缺失已在上面单独报告，这里不重复。
                    continue;
                }
                String key = preCode + "->" + postCode;
                expectedEdges.add(key);
                edgeLabels.put(key, String.format("%s -> %s",
                        taskLabel(taskById.get(upstreamTaskId)),
                        taskLabel(taskById.get(downstreamTaskId))));
            }
        }

        Set<String> persistedEdges = definition.getTaskEdges();

        Set<String> missing = new LinkedHashSet<>(expectedEdges);
        missing.removeAll(persistedEdges);
        for (String key : missing) {
            issues.add(buildIssue(null,
                    CODE_DEFINITION_DRIFT,
                    SEVERITY_WARNING,
                    true,
                    "workflow.definitionJson.processTaskRelationList",
                    String.format("工作流定义缺少血缘推导出的任务依赖边：%s。", edgeLabels.get(key))));
        }

        // 多余边只在节点全部可识别时才敢断言：认不出的节点也有 taskCode，
        // 与它相连的边会被误判成"血缘中不存在"。缺失边不受影响——两端都能识别时，
        // 期望边没出现在定义里就是真的少了。
        if (definition.hasUnresolvedNodes()) {
            return;
        }
        Set<String> extra = new LinkedHashSet<>(persistedEdges);
        extra.removeAll(expectedEdges);
        for (String key : extra) {
            issues.add(buildIssue(null,
                    CODE_DEFINITION_DRIFT,
                    SEVERITY_WARNING,
                    true,
                    "workflow.definitionJson.processTaskRelationList",
                    String.format("工作流定义存在血缘中不存在的任务依赖边：taskCode %s。", key)));
        }
    }

    private ParsedDefinition parseDefinition(String definitionJson) {
        if (!StringUtils.hasText(definitionJson)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            if (root == null) {
                return null;
            }
            ParsedDefinition parsed = new ParsedDefinition();

            JsonNode taskListNode = root.get("taskDefinitionList");
            if (taskListNode == null || !taskListNode.isArray()) {
                // 读不出节点清单就无从判断漂移，降级跳过而不是误报。
                return null;
            }
            for (JsonNode taskNode : taskListNode) {
                JsonNode metaNode = taskNode == null ? null : taskNode.get("xPlatformTaskMeta");
                JsonNode taskIdNode = metaNode == null ? null : metaNode.get("taskId");
                if (taskIdNode == null || !taskIdNode.canConvertToLong()) {
                    // 直接累计真正认不出 taskId 的节点。不能用 nodeCount - map.size() 反推：
                    // map 会对重复 taskId 去重，两个都写着 taskId=7 的节点会被误算成
                    // "1 个无法识别"，进而错误抑制其他任务的缺失结论。
                    parsed.setUnresolvedNodeCount(parsed.getUnresolvedNodeCount() + 1);
                    continue;
                }
                long taskId = taskIdNode.asLong();
                if (parsed.getTablesByTaskId().containsKey(taskId)) {
                    // 重复 taskId 是另一类问题：节点身份是明确的，不影响"哪些任务在定义里"
                    // 的判断，因此单独报告，不参与 unresolved 的抑制逻辑。
                    parsed.getDuplicateTaskIds().add(taskId);
                }
                DefinitionTables tables = new DefinitionTables();
                tables.setInputs(readLongSet(taskNode.get("inputTableIds")));
                tables.setOutputs(readLongSet(taskNode.get("outputTableIds")));
                parsed.getTablesByTaskId().put(taskId, tables);

                // 关系列表用的是运行态 taskCode，缺失时 assembler 回退成 taskId。
                Long code = readLong(taskNode, "taskCode", "code");
                if (code == null || code <= 0) {
                    code = readLong(metaNode, "dolphinTaskCode");
                }
                parsed.getCodeByTaskId().put(taskId, code != null && code > 0 ? code : taskId);
            }

            JsonNode relationListNode = root.get("processTaskRelationList");
            if (relationListNode != null && relationListNode.isArray()) {
                for (JsonNode relationNode : relationListNode) {
                    Long preCode = readLong(relationNode, "preTaskCode");
                    Long postCode = readLong(relationNode, "postTaskCode");
                    // preTaskCode=0 是 Dolphin 表示入口节点的约定，不是血缘推导出的任务边。
                    if (preCode == null || postCode == null || preCode <= 0 || postCode <= 0) {
                        continue;
                    }
                    parsed.getTaskEdges().add(preCode + "->" + postCode);
                }
            }
            return parsed;
        } catch (Exception ex) {
            log.debug("Failed to parse definitionJson for lineage drift check: {}", ex.getMessage());
            return null;
        }
    }

    private Long readLong(JsonNode node, String... fields) {
        if (node == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.canConvertToLong()) {
                return value.asLong();
            }
        }
        return null;
    }

    private Set<Long> readLongSet(JsonNode node) {
        Set<Long> result = new LinkedHashSet<>();
        if (node == null || !node.isArray()) {
            return result;
        }
        for (JsonNode item : node) {
            if (item != null && item.canConvertToLong()) {
                result.add(item.asLong());
            }
        }
        return result;
    }

    private SqlTableAnalyzeResponse analyzeTaskSql(DataTask task) {
        if (!StringUtils.hasText(task.getTaskSql())) {
            return null;
        }
        try {
            return sqlTableMatcherService.analyze(task.getTaskSql(), "SQL");
        } catch (Exception ex) {
            // 解析失败不应阻断保存或发布：这里只做一致性提示，解析器对方言 SQL 存在误判空间。
            log.debug("SQL analyze failed for task {}: {}", task.getId(), ex.getMessage());
            return null;
        }
    }

    /**
     * 本工作流内、除该任务之外，还有哪些表被其他任务写入。
     *
     * <p>{@code writesByTask} 在工作流级检查里本就全量在手，因此这里没有额外查询。
     */
    private Set<Long> tablesWrittenByOtherTasks(Long taskId, Map<Long, Set<Long>> writesByTask) {
        Set<Long> result = new LinkedHashSet<>();
        for (Map.Entry<Long, Set<Long>> entry : writesByTask.entrySet()) {
            if (Objects.equals(entry.getKey(), taskId)) {
                continue;
            }
            result.addAll(entry.getValue());
        }
        return result;
    }

    /**
     * 判断某张表对该任务而言是否"自读自写"。
     *
     * <p>只要它出现在任务的输出侧（已登记的写关系，或 SQL 推断出的输出），就算自读自写。
     *
     * <p>这只是"是否自读自写"的判定，不等于最终豁免：
     * 工作流级检查还会看该表是否被其他任务写入，被写入时仍照常报告
     * （见 {@link #collectSqlIssues}）。任务级保存检查拿不到工作流上下文，
     * 一律豁免，由发布/导出阶段兜底。
     */
    private boolean isSelfReferential(Long tableId, Set<Long> writes, Set<Long> sqlOutputs) {
        if (tableId == null) {
            return false;
        }
        return writes.contains(tableId) || sqlOutputs.contains(tableId);
    }

    private boolean isSqlTask(DataTask task) {
        return task != null && "SQL".equalsIgnoreCase(task.getDolphinNodeType());
    }

    private List<SqlTableAnalyzeResponse.TableRefMatch> matchedRefs(List<SqlTableAnalyzeResponse.TableRefMatch> refs) {
        if (CollectionUtils.isEmpty(refs)) {
            return Collections.emptyList();
        }
        return refs.stream()
                .filter(Objects::nonNull)
                .filter(ref -> "matched".equals(ref.getMatchStatus()))
                .filter(ref -> ref.getChosenTable() != null && ref.getChosenTable().getTableId() != null)
                .collect(Collectors.toList());
    }

    private Set<Long> matchedTableIds(List<SqlTableAnalyzeResponse.TableRefMatch> refs) {
        return matchedRefs(refs).stream()
                .map(ref -> ref.getChosenTable().getTableId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private List<Long> findTaskIds(Long workflowId) {
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        return relations.stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private Map<Long, Set<Long>> loadRelationMap(List<Long> taskIds, String relationType) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return Collections.emptyMap();
        }
        List<TableTaskRelation> relations = tableTaskRelationMapper.selectList(
                Wrappers.<TableTaskRelation>lambdaQuery()
                        .in(TableTaskRelation::getTaskId, taskIds)
                        .eq(TableTaskRelation::getRelationType, relationType));
        Map<Long, Set<Long>> result = new LinkedHashMap<>();
        for (TableTaskRelation relation : relations) {
            if (relation == null || relation.getTaskId() == null || relation.getTableId() == null) {
                continue;
            }
            result.computeIfAbsent(relation.getTaskId(), key -> new LinkedHashSet<>()).add(relation.getTableId());
        }
        return result;
    }

    private WorkflowPublishRepairIssue buildIssue(DataTask task,
            String code,
            String severity,
            boolean repairable,
            String field,
            String message) {
        WorkflowPublishRepairIssue issue = new WorkflowPublishRepairIssue();
        issue.setCode(code);
        issue.setSeverity(severity);
        issue.setRepairable(repairable);
        issue.setField(field);
        issue.setTaskCode(task != null ? task.getDolphinTaskCode() : null);
        issue.setTaskName(task != null ? task.getTaskName() : null);
        issue.setMessage(message);
        return issue;
    }

    private String blockMissingSeverity() {
        return properties.isBlockMissing() ? SEVERITY_ERROR : SEVERITY_WARNING;
    }

    private String describeRef(SqlTableAnalyzeResponse.TableRefMatch ref) {
        SqlTableAnalyzeResponse.TableCandidate chosen = ref.getChosenTable();
        String name = StringUtils.hasText(ref.getRawName()) ? ref.getRawName() : chosen.getTableName();
        return String.format("%s(id=%s)", name, chosen.getTableId());
    }

    private List<String> toIdText(Collection<Long> ids) {
        return ids.stream().map(id -> "id=" + id).collect(Collectors.toList());
    }

    private String joinGap(List<String> inputs, List<String> outputs) {
        List<String> parts = new ArrayList<>();
        if (!inputs.isEmpty()) {
            parts.add("输入 " + String.join(", ", inputs));
        }
        if (!outputs.isEmpty()) {
            parts.add("输出 " + String.join(", ", outputs));
        }
        return String.join("；", parts);
    }

    private String taskLabel(DataTask task) {
        if (task == null) {
            return "unknown-task";
        }
        if (StringUtils.hasText(task.getTaskName())) {
            return task.getTaskName().trim();
        }
        return task.getId() != null ? "task#" + task.getId() : "unknown-task";
    }

    private Set<Long> toIdSet(Collection<Long> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Collections.emptySet();
        }
        return ids.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * SQL 高可信缺失明细。
     */
    @Data
    public static class HighConfidenceGap {
        private final List<String> missingInputs = new ArrayList<>();
        private final List<String> missingOutputs = new ArrayList<>();

        public boolean isEmpty() {
            return missingInputs.isEmpty() && missingOutputs.isEmpty();
        }

        public String describe() {
            List<String> parts = new ArrayList<>();
            if (!missingInputs.isEmpty()) {
                parts.add("输入表 " + String.join(", ", missingInputs));
            }
            if (!missingOutputs.isEmpty()) {
                parts.add("输出表 " + String.join(", ", missingOutputs));
            }
            return String.join("；", parts);
        }
    }

    /**
     * definitionJson 中持久化的表清单。
     */
    @Data
    private static class DefinitionTables {
        private Set<Long> inputs = new LinkedHashSet<>();
        private Set<Long> outputs = new LinkedHashSet<>();
    }

    /**
     * definitionJson 里与血缘相关的三部分：节点表清单、taskId→taskCode 映射、任务依赖边。
     */
    @Data
    private static class ParsedDefinition {
        private final Map<Long, DefinitionTables> tablesByTaskId = new LinkedHashMap<>();
        private final Map<Long, Long> codeByTaskId = new LinkedHashMap<>();
        /** {@code preTaskCode->postTaskCode}，已排除 preTaskCode=0 的入口边。 */
        private final Set<String> taskEdges = new LinkedHashSet<>();
        /** {@code taskDefinitionList} 中认不出 {@code taskId} 的节点数量。 */
        private int unresolvedNodeCount;
        /** 在 {@code taskDefinitionList} 中出现多次的 {@code taskId}。 */
        private final Set<Long> duplicateTaskIds = new LinkedHashSet<>();

        boolean hasUnresolvedNodes() {
            return unresolvedNodeCount > 0;
        }
    }
}
