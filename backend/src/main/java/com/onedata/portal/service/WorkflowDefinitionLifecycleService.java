package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowExportJsonResponse;
import com.onedata.portal.dto.workflow.WorkflowImportCommitRequest;
import com.onedata.portal.dto.workflow.WorkflowImportCommitResponse;
import com.onedata.portal.dto.workflow.WorkflowImportPreviewRequest;
import com.onedata.portal.dto.workflow.WorkflowImportPreviewResponse;
import com.onedata.portal.dto.workflow.WorkflowImportRuntimeBinding;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.runtime.RuntimeRelationChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeRelationCompareDetail;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowSchedule;
import com.onedata.portal.dto.workflow.runtime.DolphinRuntimeWorkflowOption;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import com.onedata.portal.service.lineage.TaskLineageConsistencyChecker;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流 JSON 导入/导出生命周期服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowDefinitionLifecycleService {

    private static final String SOURCE_TYPE_JSON = "json";
    private static final String SOURCE_TYPE_DOLPHIN = "dolphin";
    private static final String RELATION_DECISION_DECLARED = "DECLARED";
    private static final String RELATION_DECISION_INFERRED = "INFERRED";
    private static final String RUNTIME_BINDING_ADOPT = "ADOPT";
    private static final String RUNTIME_BINDING_RESET = "RESET";
    private static final DateTimeFormatter[] DATETIME_FORMATS = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    };

    private final DolphinRuntimeDefinitionService runtimeDefinitionService;
    private final SqlTableMatcherService sqlTableMatcherService;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataTaskService dataTaskService;
    private final WorkflowService workflowService;
    private final WorkflowDefinitionAssembler workflowDefinitionAssembler;
    private final TaskLineageConsistencyChecker lineageConsistencyChecker;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final DataTaskMapper dataTaskMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final ObjectMapper objectMapper;

    public PageResult<DolphinRuntimeWorkflowOption> listDolphinWorkflows(Long dolphinConfigId,
            Long projectCode,
            Integer pageNum,
            Integer pageSize,
            String keyword) {
        DolphinRuntimeDefinitionService.DolphinRuntimeWorkflowPage page = runtimeDefinitionService
                .listRuntimeWorkflows(dolphinConfigId, projectCode, pageNum, pageSize, keyword);
        fillLocalBindings(page.getRecords());
        return PageResult.of(page.getTotal(), page.getRecords());
    }

    /**
     * 按编码精确探测一条运行态工作流，供导入表单预选关联项使用。
     */
    public DolphinRuntimeWorkflowOption findDolphinWorkflow(Long dolphinConfigId, Long workflowCode) {
        Long projectCode = dolphinSchedulerService.findProjectCode(dolphinConfigId);
        if (projectCode == null || projectCode <= 0) {
            return null;
        }
        DolphinRuntimeWorkflowOption option = runtimeDefinitionService
                .findRuntimeWorkflow(dolphinConfigId, projectCode, workflowCode);
        fillLocalBinding(option);
        return option;
    }

    /**
     * 标注这些运行态是否已被平台工作流关联，让前端可以就地提示冲突。整页一次查询，避免逐条查库。
     */
    private void fillLocalBindings(List<DolphinRuntimeWorkflowOption> options) {
        List<DolphinRuntimeWorkflowOption> targets = options == null
                ? Collections.emptyList()
                : options.stream()
                        .filter(item -> item != null && item.getProjectCode() != null && item.getWorkflowCode() != null)
                        .collect(Collectors.toList());
        if (targets.isEmpty()) {
            return;
        }
        Set<Long> workflowCodes = targets.stream()
                .map(DolphinRuntimeWorkflowOption::getWorkflowCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, DataWorkflow> boundByRuntime = dataWorkflowMapper.selectList(
                Wrappers.<DataWorkflow>lambdaQuery()
                        .in(DataWorkflow::getWorkflowCode, workflowCodes))
                .stream()
                .filter(item -> item.getProjectCode() != null && item.getWorkflowCode() != null)
                .collect(Collectors.toMap(
                        item -> runtimeKey(item.getProjectCode(), item.getWorkflowCode()),
                        item -> item,
                        (first, second) -> first));

        for (DolphinRuntimeWorkflowOption option : targets) {
            DataWorkflow bound = boundByRuntime
                    .get(runtimeKey(option.getProjectCode(), option.getWorkflowCode()));
            option.setSynced(bound != null);
            if (bound != null) {
                option.setLocalWorkflowId(bound.getId());
                option.setLocalWorkflowName(bound.getWorkflowName());
            }
        }
    }

    private void fillLocalBinding(DolphinRuntimeWorkflowOption option) {
        fillLocalBindings(option == null ? Collections.emptyList() : Collections.singletonList(option));
    }

    private String runtimeKey(Long projectCode, Long workflowCode) {
        return projectCode + ":" + workflowCode;
    }

    private DataWorkflow findWorkflowByRuntime(Long projectCode, Long workflowCode) {
        if (projectCode == null || projectCode <= 0 || workflowCode == null || workflowCode <= 0) {
            return null;
        }
        return dataWorkflowMapper.selectOne(
                Wrappers.<DataWorkflow>lambdaQuery()
                        .eq(DataWorkflow::getProjectCode, projectCode)
                        .eq(DataWorkflow::getWorkflowCode, workflowCode)
                        .last("LIMIT 1"));
    }

    public WorkflowImportPreviewResponse preview(WorkflowImportPreviewRequest request) {
        ImportContext context = analyze(buildImportSource(request));
        return toPreviewResponse(context);
    }

    @Transactional
    public WorkflowImportCommitResponse commit(WorkflowImportCommitRequest request) {
        String operator = normalizeOperator(request != null ? request.getOperator() : null);
        ImportSource source = buildImportSource(request);
        ImportContext context = analyze(source);
        if (!context.getErrors().isEmpty()) {
            throw new IllegalArgumentException("导入预检失败: " + context.getErrors().get(0));
        }
        if (context.getDefinition() == null) {
            throw new IllegalArgumentException("导入预检失败: 无法解析工作流定义");
        }

        String relationDecision = resolveRelationDecision(context,
                request != null ? request.getRelationDecision() : null, true);
        List<RuntimeTaskEdge> selectedEdges = RELATION_DECISION_DECLARED.equals(relationDecision)
                ? context.getDeclaredEdges()
                : context.getInferredEdges();

        WorkflowImportRuntimeBinding runtimeBinding = context.getRuntimeBinding();
        String normalizedJson = buildNormalizedJson(context.getDefinition(), selectedEdges);
        if (RUNTIME_BINDING_RESET.equals(runtimeBinding.getDecision())) {
            // 不关联既有运行态时，把来源平台的 Dolphin 编码从定义里清干净，并按目标 Dolphin
            // 目录重新解析 datasource / taskGroup 编码，否则发布会拿着别人的编码去更新定义。
            normalizedJson = workflowDefinitionAssembler.refreshRuntimeBindings(normalizedJson,
                    runtimeBinding.getDolphinConfigId(), runtimeBinding.getProjectCode());
        } else {
            ensureWorkflowConflictAbsent(runtimeBinding);
        }

        TaskCreateResult taskCreateResult = createTasks(context.getDefinition().getTasks(), operator);
        WorkflowDefinitionRequest workflowRequest = new WorkflowDefinitionRequest();
        workflowRequest.setWorkflowName(resolveWorkflowName(context.getDefinition(), context.getRequestedWorkflowName()));
        workflowRequest.setDescription(context.getDefinition().getDescription());
        workflowRequest.setGlobalParams(context.getDefinition().getGlobalParams());
        workflowRequest.setTaskGroupName(resolveWorkflowTaskGroupName(context.getDefinition().getTasks()));
        workflowRequest.setTasks(taskCreateResult.getTaskBindings());
        workflowRequest.setOperator(operator);
        workflowRequest.setTriggerSource(resolveTriggerSource(context.getSourceType()));
        workflowRequest.setProjectCode(runtimeBinding.getProjectCode());
        workflowRequest.setDefinitionJson(normalizedJson);

        DataWorkflow workflow = workflowService.createWorkflow(workflowRequest);
        applyImportedWorkflowFields(workflow,
                context.getDefinition(),
                normalizedJson,
                operator,
                runtimeBinding);
        dataWorkflowMapper.updateById(workflow);
        workflowService.normalizeAndPersistMetadata(workflow.getId(), operator);

        WorkflowImportCommitResponse response = new WorkflowImportCommitResponse();
        response.setWorkflowId(workflow.getId());
        response.setVersionId(workflow.getCurrentVersionId());
        response.setVersionNo(resolveVersionNo(workflow.getCurrentVersionId()));
        response.setWorkflowName(workflow.getWorkflowName());
        response.setCreatedTaskCount(taskCreateResult.getCreatedTaskCount());
        response.setAppliedRelationDecision(relationDecision);
        response.setAppliedRuntimeBinding(runtimeBinding.getDecision());
        return response;
    }

    public WorkflowExportJsonResponse exportJson(Long workflowId) {
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId 不能为空");
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        String content = workflowService.buildDefinitionJsonForExport(workflowId);

        WorkflowExportJsonResponse response = new WorkflowExportJsonResponse();
        response.setFileName(buildExportFileName(workflow));
        response.setContent(content);
        // 附带一致性问题但不阻断：buildDefinitionJsonForExport 已经保证有定义就原样导出、
        // 不会在导出阶段重写，这里只负责让用户知道导出的定义可能有问题。
        response.setConsistencyIssues(lineageConsistencyChecker.checkWorkflow(
                dataWorkflowMapper.selectById(workflowId), true));
        return response;
    }

    private WorkflowImportPreviewResponse toPreviewResponse(ImportContext context) {
        WorkflowImportPreviewResponse response = new WorkflowImportPreviewResponse();
        RuntimeWorkflowDefinition definition = context.getDefinition();
        response.setCanImport(context.getErrors().isEmpty());
        response.setWorkflowName(definition != null
                ? resolveWorkflowName(definition, context.getRequestedWorkflowName())
                : null);
        response.setTaskCount(definition != null && definition.getTasks() != null ? definition.getTasks().size() : 0);
        response.setRelationDecisionRequired(context.getRelationDecisionRequired());
        response.setSuggestedRelationDecision(context.getSuggestedRelationDecision());
        response.setRelationCompareDetail(context.getRelationCompareDetail());
        response.setErrors(new ArrayList<>(context.getErrors()));
        response.setWarnings(new ArrayList<>(context.getWarnings()));
        response.setNormalizedJson(context.getNormalizedJson());
        response.setRuntimeBinding(context.getRuntimeBinding());
        return response;
    }

    private ImportContext analyze(ImportSource source) {
        ImportContext context = new ImportContext();
        context.setSourceType(source.getSourceType());
        context.setRequestedWorkflowName(source.getWorkflowName());
        context.setDolphinConfigId(source.getDolphinConfigId());
        context.setLinkedWorkflowCode(source.getLinkedWorkflowCode());

        RuntimeWorkflowDefinition definition = resolveRuntimeDefinition(source, context);
        if (definition == null) {
            return context;
        }
        context.setDefinition(definition);

        validateWorkflowHeader(context);
        validateWorkflowNameConflict(context);
        resolveRuntimeBinding(context);
        if (!context.getErrors().isEmpty()) {
            return context;
        }
        normalizeAndValidateTasks(context);
        buildRelationCompare(context);

        context.setNormalizedJson(buildNormalizedJson(definition, context.getInferredEdges()));
        return context;
    }

    /**
     * 解析本次导入的 Dolphin 运行态归属。
     *
     * <p>归属由用户在导入表单上显式选择的 {@code linkedWorkflowCode} 决定，而不是文件里携带的来源编码：
     * 选了就关联目标 Dolphin 的既有运行态，没选就按全新工作流导入。这里对选择做一次复核，
     * 复核不通过直接报错，不静默改判。
     */
    private void resolveRuntimeBinding(ImportContext context) {
        if (!context.getErrors().isEmpty()) {
            return;
        }
        Long dolphinConfigId = context.getDolphinConfigId();
        if (dolphinConfigId == null || dolphinConfigId <= 0) {
            context.getErrors().add("请选择目标 Dolphin 环境");
            return;
        }

        Long projectCode = dolphinSchedulerService.findProjectCode(dolphinConfigId);
        if (projectCode == null || projectCode <= 0) {
            context.getErrors().add("无法解析目标 Dolphin 项目，请检查该环境的连接配置");
            return;
        }

        WorkflowImportRuntimeBinding binding = new WorkflowImportRuntimeBinding();
        binding.setDolphinConfigId(dolphinConfigId);
        binding.setProjectCode(projectCode);

        Long linkedWorkflowCode = context.getLinkedWorkflowCode();
        if (linkedWorkflowCode == null || linkedWorkflowCode <= 0) {
            binding.setDecision(RUNTIME_BINDING_RESET);
            binding.setMessage("将作为全新工作流导入，发布时在目标 Dolphin 创建新的工作流定义");
            context.setRuntimeBinding(binding);
            return;
        }

        DolphinRuntimeWorkflowOption runtime = runtimeDefinitionService
                .findRuntimeWorkflow(dolphinConfigId, projectCode, linkedWorkflowCode);
        if (runtime == null) {
            context.getErrors().add("所选运行态工作流在目标 Dolphin 中不存在，请重新选择或改为不关联导入");
            return;
        }

        DataWorkflow occupied = findWorkflowByRuntime(projectCode, linkedWorkflowCode);
        if (occupied != null) {
            binding.setConflictWorkflowId(occupied.getId());
            binding.setConflictWorkflowName(occupied.getWorkflowName());
            context.getErrors().add(String.format(
                    "该 Dolphin 运行态已被平台工作流「%s」(id=%s) 关联，请直接编辑该工作流，或改为不关联导入",
                    occupied.getWorkflowName(), occupied.getId()));
            context.setRuntimeBinding(binding);
            return;
        }

        binding.setDecision(RUNTIME_BINDING_ADOPT);
        binding.setWorkflowCode(linkedWorkflowCode);
        binding.setRuntimeWorkflowName(runtime.getWorkflowName());
        binding.setReleaseState(runtime.getReleaseState());
        binding.setMessage(String.format("将关联目标 Dolphin 已有工作流「%s」(code=%s)，发布时更新该定义",
                runtime.getWorkflowName(), linkedWorkflowCode));
        context.setRuntimeBinding(binding);
    }

    private RuntimeWorkflowDefinition resolveRuntimeDefinition(ImportSource source, ImportContext context) {
        if (SOURCE_TYPE_DOLPHIN.equals(source.getSourceType())) {
            if (source.getWorkflowCode() == null || source.getWorkflowCode() <= 0) {
                context.getErrors().add("workflowCode 不能为空");
                return null;
            }
            try {
                RuntimeWorkflowDefinition definition = runtimeDefinitionService
                        .loadRuntimeDefinitionFromExport(source.getProjectCode(), source.getWorkflowCode());
                if (StringUtils.hasText(source.getWorkflowName())) {
                    definition.setWorkflowName(source.getWorkflowName().trim());
                }
                return definition;
            } catch (Exception ex) {
                context.getErrors().add("读取 Dolphin 导出定义失败: " + ex.getMessage());
                return null;
            }
        }

        if (!StringUtils.hasText(source.getDefinitionJson())) {
            context.getErrors().add("definitionJson 不能为空");
            return null;
        }
        try {
            RuntimeWorkflowDefinition definition = runtimeDefinitionService
                    .parseRuntimeDefinitionFromJson(source.getDefinitionJson());
            if (StringUtils.hasText(source.getWorkflowName())) {
                definition.setWorkflowName(source.getWorkflowName().trim());
            }
            return definition;
        } catch (Exception ex) {
            context.getErrors().add(ex.getMessage());
            return null;
        }
    }

    private void validateWorkflowHeader(ImportContext context) {
        RuntimeWorkflowDefinition definition = context.getDefinition();
        if (definition == null) {
            context.getErrors().add("导入定义解析失败");
            return;
        }
        if (!StringUtils.hasText(resolveWorkflowName(definition))) {
            context.getErrors().add("工作流名称不能为空");
        }
        if (CollectionUtils.isEmpty(definition.getTasks())) {
            context.getErrors().add("工作流任务列表为空");
        }
    }

    private void validateWorkflowNameConflict(ImportContext context) {
        if (context == null) {
            return;
        }
        RuntimeWorkflowDefinition definition = context.getDefinition();
        String workflowName = resolveWorkflowName(definition, context.getRequestedWorkflowName());
        if (!StringUtils.hasText(workflowName)) {
            return;
        }
        DataWorkflow existing = dataWorkflowMapper.selectOne(
                Wrappers.<DataWorkflow>lambdaQuery()
                        .eq(DataWorkflow::getWorkflowName, workflowName.trim())
                        .last("LIMIT 1"));
        if (existing != null) {
            context.getErrors().add("工作流名称已存在，请修改后重试: " + workflowName.trim());
        }
    }

    private void normalizeAndValidateTasks(ImportContext context) {
        RuntimeWorkflowDefinition definition = context.getDefinition();
        if (definition == null || CollectionUtils.isEmpty(definition.getTasks())) {
            return;
        }

        List<RuntimeTaskDefinition> orderedTasks = definition.getTasks().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RuntimeTaskDefinition::getTaskCode, Comparator.nullsLast(Long::compareTo)))
                .collect(Collectors.toList());
        definition.setTasks(orderedTasks);

        Map<Long, DolphinDatasourceOption> datasourceById = resolveDatasourceById(orderedTasks);
        Set<Long> seenTaskCodes = new LinkedHashSet<>();
        for (RuntimeTaskDefinition task : orderedTasks) {
            if (task == null) {
                continue;
            }
            Long taskCode = task.getTaskCode();
            if (taskCode == null || taskCode <= 0) {
                context.getErrors().add("任务缺少合法 taskCode");
                continue;
            }
            if (!seenTaskCodes.add(taskCode)) {
                context.getErrors().add("任务 taskCode 重复: " + taskCode);
            }

            if (!"SQL".equalsIgnoreCase(task.getNodeType())) {
                context.getErrors().add(String.format("仅支持 SQL 节点导入: taskCode=%s, nodeType=%s",
                        taskCode, task.getNodeType()));
                continue;
            }
            if (!StringUtils.hasText(task.getSql())) {
                context.getErrors().add(String.format("SQL 任务缺少 SQL 文本: taskCode=%s", taskCode));
                continue;
            }

            SqlTableAnalyzeResponse analyze = sqlTableMatcherService.analyze(task.getSql(), "SQL");
            if (analyze == null) {
                context.getErrors().add(String.format("SQL 解析失败: taskCode=%s", taskCode));
                continue;
            }
            if (!CollectionUtils.isEmpty(analyze.getAmbiguous())) {
                context.getErrors().add(String.format("SQL 表匹配存在歧义: taskCode=%s, tables=%s",
                        taskCode, String.join(", ", analyze.getAmbiguous())));
            }
            if (!CollectionUtils.isEmpty(analyze.getUnmatched())) {
                context.getErrors().add(String.format("SQL 表未匹配平台元数据: taskCode=%s, tables=%s",
                        taskCode, String.join(", ", analyze.getUnmatched())));
            }

            List<Long> inputTableIds = collectMatchedTableIds(analyze.getInputRefs());
            List<Long> outputTableIds = collectMatchedTableIds(analyze.getOutputRefs());
            task.setInputTableIds(inputTableIds);
            task.setOutputTableIds(outputTableIds);
            if (outputTableIds.isEmpty()) {
                context.getErrors().add(String.format("任务必须至少有一个输出表: taskCode=%s", taskCode));
            }

            if (!StringUtils.hasText(task.getDatasourceName()) && task.getDatasourceId() != null) {
                DolphinDatasourceOption option = datasourceById.get(task.getDatasourceId());
                if (option != null) {
                    task.setDatasourceName(option.getName());
                    if (!StringUtils.hasText(task.getDatasourceType())) {
                        task.setDatasourceType(option.getType());
                    }
                }
            }
            if (!StringUtils.hasText(task.getDatasourceName())) {
                context.getErrors().add(String.format("SQL 任务缺少数据源名称: taskCode=%s", taskCode));
            }

            if (!StringUtils.hasText(task.getTaskName())) {
                task.setTaskName("task_" + taskCode);
            }
        }
    }

    private Map<Long, DolphinDatasourceOption> resolveDatasourceById(List<RuntimeTaskDefinition> tasks) {
        boolean needsResolve = tasks.stream()
                .filter(Objects::nonNull)
                .anyMatch(task -> !StringUtils.hasText(task.getDatasourceName()) && task.getDatasourceId() != null);
        if (!needsResolve) {
            return Collections.emptyMap();
        }

        try {
            List<DolphinDatasourceOption> options = dolphinSchedulerService.listDatasources(null, null);
            if (CollectionUtils.isEmpty(options)) {
                return Collections.emptyMap();
            }
            return options.stream()
                    .filter(option -> option != null && option.getId() != null)
                    .collect(Collectors.toMap(DolphinDatasourceOption::getId, option -> option, (left, right) -> left));
        } catch (Exception ex) {
            log.warn("Resolve datasource options failed: {}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private List<Long> collectMatchedTableIds(List<SqlTableAnalyzeResponse.TableRefMatch> refs) {
        if (CollectionUtils.isEmpty(refs)) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> tableIds = new LinkedHashSet<>();
        for (SqlTableAnalyzeResponse.TableRefMatch ref : refs) {
            if (ref == null || !"matched".equalsIgnoreCase(ref.getMatchStatus())) {
                continue;
            }
            SqlTableAnalyzeResponse.TableCandidate chosen = ref.getChosenTable();
            if (chosen != null && chosen.getTableId() != null) {
                tableIds.add(chosen.getTableId());
            }
        }
        return new ArrayList<>(tableIds);
    }

    private void buildRelationCompare(ImportContext context) {
        RuntimeWorkflowDefinition definition = context.getDefinition();
        if (definition == null) {
            return;
        }

        List<RuntimeTaskEdge> declaredEdges = normalizeEdges(definition.getExplicitEdges());
        List<RuntimeTaskEdge> inferredEdges = inferEdgesFromLineage(definition.getTasks());
        context.setDeclaredEdges(declaredEdges);
        context.setInferredEdges(inferredEdges);

        Set<String> declaredSet = toEdgeSet(declaredEdges);
        Set<String> inferredSet = toEdgeSet(inferredEdges);
        Map<Long, String> taskNameByCode = definition.getTasks().stream()
                .filter(Objects::nonNull)
                .filter(task -> task.getTaskCode() != null)
                .collect(Collectors.toMap(RuntimeTaskDefinition::getTaskCode,
                        RuntimeTaskDefinition::getTaskName,
                        (left, right) -> left,
                        LinkedHashMap::new));

        RuntimeRelationCompareDetail detail = new RuntimeRelationCompareDetail();
        detail.setDeclaredRelations(toRelationChanges(declaredSet, taskNameByCode));
        detail.setInferredRelations(toRelationChanges(inferredSet, taskNameByCode));
        detail.setOnlyInDeclared(toRelationChanges(diffSet(declaredSet, inferredSet), taskNameByCode));
        detail.setOnlyInInferred(toRelationChanges(diffSet(inferredSet, declaredSet), taskNameByCode));
        context.setRelationCompareDetail(detail);

        boolean mismatch = !Objects.equals(declaredSet, inferredSet);
        context.setRelationDecisionRequired(mismatch);
        if (mismatch) {
            context.getWarnings().add(String.format("声明关系与 SQL 推断关系不一致（declared=%d, inferred=%d）",
                    declaredSet.size(), inferredSet.size()));
        }
        context.setSuggestedRelationDecision(RELATION_DECISION_INFERRED);
    }

    private List<RuntimeTaskEdge> normalizeEdges(List<RuntimeTaskEdge> edges) {
        if (CollectionUtils.isEmpty(edges)) {
            return Collections.emptyList();
        }
        Map<String, RuntimeTaskEdge> dedup = new LinkedHashMap<>();
        for (RuntimeTaskEdge edge : edges) {
            if (edge == null || edge.getDownstreamTaskCode() == null) {
                continue;
            }
            Long pre = edge.getUpstreamTaskCode() == null ? 0L : edge.getUpstreamTaskCode();
            Long post = edge.getDownstreamTaskCode();
            if (pre < 0 || post <= 0) {
                continue;
            }
            String key = pre + "->" + post;
            dedup.putIfAbsent(key, new RuntimeTaskEdge(pre, post));
        }
        return dedup.values().stream()
                .sorted(Comparator.comparing(RuntimeTaskEdge::getUpstreamTaskCode)
                        .thenComparing(RuntimeTaskEdge::getDownstreamTaskCode))
                .collect(Collectors.toList());
    }

    private List<RuntimeTaskEdge> inferEdgesFromLineage(List<RuntimeTaskDefinition> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        List<RuntimeTaskDefinition> sortedTasks = tasks.stream()
                .filter(Objects::nonNull)
                .filter(task -> task.getTaskCode() != null && task.getTaskCode() > 0)
                .sorted(Comparator.comparing(RuntimeTaskDefinition::getTaskCode))
                .collect(Collectors.toList());
        if (sortedTasks.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, Set<Long>> outputTableIdsByTaskCode = new LinkedHashMap<>();
        Map<Long, Set<Long>> inputTableIdsByTaskCode = new LinkedHashMap<>();
        for (RuntimeTaskDefinition task : sortedTasks) {
            outputTableIdsByTaskCode.put(task.getTaskCode(), new LinkedHashSet<>(task.getOutputTableIds()));
            inputTableIdsByTaskCode.put(task.getTaskCode(), new LinkedHashSet<>(task.getInputTableIds()));
        }

        Map<String, RuntimeTaskEdge> dedup = new LinkedHashMap<>();
        Set<Long> hasUpstream = new LinkedHashSet<>();
        for (RuntimeTaskDefinition downstream : sortedTasks) {
            Set<Long> inputs = inputTableIdsByTaskCode.getOrDefault(downstream.getTaskCode(), Collections.emptySet());
            if (CollectionUtils.isEmpty(inputs)) {
                continue;
            }
            for (RuntimeTaskDefinition upstream : sortedTasks) {
                if (Objects.equals(upstream.getTaskCode(), downstream.getTaskCode())) {
                    continue;
                }
                Set<Long> outputs = outputTableIdsByTaskCode.getOrDefault(upstream.getTaskCode(), Collections.emptySet());
                if (CollectionUtils.isEmpty(outputs)) {
                    continue;
                }
                Set<Long> intersection = new LinkedHashSet<>(outputs);
                intersection.retainAll(inputs);
                if (intersection.isEmpty()) {
                    continue;
                }
                String key = upstream.getTaskCode() + "->" + downstream.getTaskCode();
                dedup.putIfAbsent(key, new RuntimeTaskEdge(upstream.getTaskCode(), downstream.getTaskCode()));
                hasUpstream.add(downstream.getTaskCode());
            }
        }

        for (RuntimeTaskDefinition task : sortedTasks) {
            if (!hasUpstream.contains(task.getTaskCode())) {
                String key = "0->" + task.getTaskCode();
                dedup.putIfAbsent(key, new RuntimeTaskEdge(0L, task.getTaskCode()));
            }
        }

        return dedup.values().stream()
                .sorted(Comparator.comparing(RuntimeTaskEdge::getUpstreamTaskCode)
                        .thenComparing(RuntimeTaskEdge::getDownstreamTaskCode))
                .collect(Collectors.toList());
    }

    private Set<String> toEdgeSet(List<RuntimeTaskEdge> edges) {
        if (CollectionUtils.isEmpty(edges)) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>();
        for (RuntimeTaskEdge edge : edges) {
            if (edge == null) {
                continue;
            }
            Long pre = edge.getUpstreamTaskCode() == null ? 0L : edge.getUpstreamTaskCode();
            Long post = edge.getDownstreamTaskCode();
            if (post == null || post <= 0 || pre < 0) {
                continue;
            }
            result.add(pre + "->" + post);
        }
        return result;
    }

    private Set<String> diffSet(Set<String> left, Set<String> right) {
        if (CollectionUtils.isEmpty(left)) {
            return Collections.emptySet();
        }
        Set<String> result = new LinkedHashSet<>(left);
        if (!CollectionUtils.isEmpty(right)) {
            result.removeAll(right);
        }
        return result;
    }

    private List<RuntimeRelationChange> toRelationChanges(Set<String> edges, Map<Long, String> taskNameByCode) {
        if (CollectionUtils.isEmpty(edges)) {
            return Collections.emptyList();
        }
        List<RuntimeRelationChange> result = new ArrayList<>();
        for (String edge : edges) {
            RuntimeRelationChange change = toRelationChange(edge, taskNameByCode);
            if (change != null) {
                result.add(change);
            }
        }
        result.sort(Comparator.comparing(RuntimeRelationChange::getPreTaskCode, Comparator.nullsLast(Long::compareTo))
                .thenComparing(RuntimeRelationChange::getPostTaskCode, Comparator.nullsLast(Long::compareTo)));
        return result;
    }

    private RuntimeRelationChange toRelationChange(String edge, Map<Long, String> taskNameByCode) {
        if (!StringUtils.hasText(edge)) {
            return null;
        }
        String[] parts = edge.split("->", 2);
        if (parts.length != 2) {
            return null;
        }
        Long pre = parseLong(parts[0]);
        Long post = parseLong(parts[1]);
        if (pre == null || post == null || post <= 0) {
            return null;
        }
        RuntimeRelationChange change = new RuntimeRelationChange();
        change.setPreTaskCode(pre);
        change.setPostTaskCode(post);
        change.setEntryEdge(pre == 0L);
        change.setPreTaskName(pre == 0L ? "入口" : taskNameByCode.get(pre));
        change.setPostTaskName(taskNameByCode.get(post));
        return change;
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            // 非数字文本，返回 null
            log.trace("解析 Long 失败，返回 null: {}", value, e);
            return null;
        }
    }

    private String buildNormalizedJson(RuntimeWorkflowDefinition definition, List<RuntimeTaskEdge> selectedEdges) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", 1);
        root.put("processDefinition", buildProcessDefinitionNode(definition));
        root.put("taskDefinitionList", buildTaskDefinitionNodes(definition.getTasks()));
        root.put("processTaskRelationList", buildRelationNodes(selectedEdges));
        root.put("schedule", buildScheduleNode(definition.getSchedule()));
        return toJson(root);
    }

    private Map<String, Object> buildProcessDefinitionNode(RuntimeWorkflowDefinition definition) {
        Map<String, Object> node = new LinkedHashMap<>();
        if (definition == null) {
            return node;
        }
        node.put("code", definition.getWorkflowCode());
        node.put("workflowCode", definition.getWorkflowCode());
        node.put("projectCode", definition.getProjectCode());
        node.put("name", resolveWorkflowName(definition));
        node.put("description", definition.getDescription());
        node.put("globalParams", definition.getGlobalParams());
        node.put("releaseState", definition.getReleaseState());
        node.put("taskGroupName", resolveWorkflowTaskGroupName(definition.getTasks()));
        return node;
    }

    private List<Map<String, Object>> buildTaskDefinitionNodes(List<RuntimeTaskDefinition> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (RuntimeTaskDefinition task : tasks) {
            if (task == null) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("code", task.getTaskCode());
            node.put("taskCode", task.getTaskCode());
            node.put("version", task.getTaskVersion() != null ? task.getTaskVersion() : 1);
            node.put("name", task.getTaskName());
            node.put("taskName", task.getTaskName());
            node.put("description", task.getDescription());
            node.put("taskType", task.getNodeType());
            node.put("nodeType", task.getNodeType());
            node.put("timeout", task.getTimeoutSeconds());
            node.put("failRetryTimes", task.getRetryTimes());
            node.put("failRetryInterval", task.getRetryInterval());
            node.put("taskPriority", task.getTaskPriority());
            node.put("taskGroupId", task.getTaskGroupId());
            node.put("taskGroupName", task.getTaskGroupName());
            node.put("flag", normalizeDolphinFlag(task.getFlag()));

            Map<String, Object> taskParams = new LinkedHashMap<>();
            taskParams.put("sql", task.getSql());
            taskParams.put("rawScript", task.getSql());
            taskParams.put("datasource", task.getDatasourceId());
            taskParams.put("datasourceId", task.getDatasourceId());
            taskParams.put("datasourceName", task.getDatasourceName());
            taskParams.put("type", task.getDatasourceType());
            taskParams.put("datasourceType", task.getDatasourceType());
            node.put("taskParams", taskParams);
            node.put("inputTableIds", task.getInputTableIds());
            node.put("outputTableIds", task.getOutputTableIds());
            nodes.add(node);
        }
        return nodes;
    }

    private List<Map<String, Object>> buildRelationNodes(List<RuntimeTaskEdge> edges) {
        if (CollectionUtils.isEmpty(edges)) {
            return Collections.emptyList();
        }
        Set<String> edgeSet = new LinkedHashSet<>();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (RuntimeTaskEdge edge : edges) {
            if (edge == null || edge.getDownstreamTaskCode() == null || edge.getDownstreamTaskCode() <= 0) {
                continue;
            }
            Long pre = edge.getUpstreamTaskCode() == null ? 0L : edge.getUpstreamTaskCode();
            Long post = edge.getDownstreamTaskCode();
            if (pre < 0) {
                continue;
            }
            String key = pre + "->" + post;
            if (!edgeSet.add(key)) {
                continue;
            }
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("preTaskCode", pre);
            node.put("postTaskCode", post);
            nodes.add(node);
        }
        nodes.sort(Comparator
                .comparing((Map<String, Object> item) -> parseLong(String.valueOf(item.get("preTaskCode"))),
                        Comparator.nullsLast(Long::compareTo))
                .thenComparing(item -> parseLong(String.valueOf(item.get("postTaskCode"))),
                        Comparator.nullsLast(Long::compareTo)));
        return nodes;
    }

    private Map<String, Object> buildScheduleNode(RuntimeWorkflowSchedule schedule) {
        if (schedule == null) {
            return new LinkedHashMap<>();
        }
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("id", schedule.getScheduleId());
        node.put("releaseState", schedule.getReleaseState());
        node.put("crontab", schedule.getCrontab());
        node.put("timezoneId", schedule.getTimezoneId());
        node.put("startTime", schedule.getStartTime());
        node.put("endTime", schedule.getEndTime());
        node.put("failureStrategy", schedule.getFailureStrategy());
        node.put("warningType", schedule.getWarningType());
        node.put("warningGroupId", schedule.getWarningGroupId());
        node.put("processInstancePriority", schedule.getProcessInstancePriority());
        node.put("workerGroup", schedule.getWorkerGroup());
        node.put("tenantCode", schedule.getTenantCode());
        node.put("environmentCode", schedule.getEnvironmentCode());
        return node;
    }

    /**
     * 事务内兜底：预检到提交之间可能有并发导入抢先占用同一个运行态。
     */
    private void ensureWorkflowConflictAbsent(WorkflowImportRuntimeBinding binding) {
        DataWorkflow existing = findWorkflowByRuntime(binding.getProjectCode(), binding.getWorkflowCode());
        if (existing != null) {
            throw new IllegalStateException(String.format(
                    "该 Dolphin 运行态已被平台工作流「%s」(id=%s) 关联，请直接编辑该工作流，或改为不关联导入",
                    existing.getWorkflowName(), existing.getId()));
        }
    }

    private TaskCreateResult createTasks(List<RuntimeTaskDefinition> tasks, String operator) {
        TaskCreateResult result = new TaskCreateResult();
        if (CollectionUtils.isEmpty(tasks)) {
            return result;
        }
        Set<String> reservedTaskNames = dataTaskMapper.selectList(
                Wrappers.<DataTask>lambdaQuery().select(DataTask::getTaskName)).stream()
                .map(DataTask::getTaskName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        Set<String> reservedTaskCodes = dataTaskMapper.selectList(
                Wrappers.<DataTask>lambdaQuery().select(DataTask::getTaskCode)).stream()
                .map(DataTask::getTaskCode)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<RuntimeTaskDefinition> orderedTasks = tasks.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RuntimeTaskDefinition::getTaskCode, Comparator.nullsLast(Long::compareTo)))
                .collect(Collectors.toList());

        for (RuntimeTaskDefinition runtimeTask : orderedTasks) {
            String resolvedTaskName = resolveUniqueTaskName(runtimeTask.getTaskName(), runtimeTask.getTaskCode(),
                    reservedTaskNames);
            runtimeTask.setTaskName(resolvedTaskName);

            DataTask task = new DataTask();
            task.setTaskName(resolvedTaskName);
            task.setTaskCode(resolveUniqueTaskCode(resolvedTaskName, runtimeTask.getTaskCode(), reservedTaskCodes));
            task.setTaskType("batch");
            task.setEngine("dolphin");
            task.setDolphinNodeType(StringUtils.hasText(runtimeTask.getNodeType())
                    ? runtimeTask.getNodeType().trim()
                    : null);
            task.setDatasourceName(runtimeTask.getDatasourceName());
            task.setDatasourceType(runtimeTask.getDatasourceType());
            task.setTaskGroupName(runtimeTask.getTaskGroupName());
            task.setTaskSql(runtimeTask.getSql());
            task.setTaskDesc(runtimeTask.getDescription());
            task.setPriority(priorityToNumber(runtimeTask.getTaskPriority()));
            task.setTimeoutSeconds(runtimeTask.getTimeoutSeconds());
            task.setRetryTimes(runtimeTask.getRetryTimes());
            task.setRetryInterval(runtimeTask.getRetryInterval());
            task.setDolphinFlag(normalizeDolphinFlag(runtimeTask.getFlag()));
            task.setOwner(operator);
            task.setDolphinTaskCode(runtimeTask.getTaskCode());
            task.setDolphinTaskVersion(runtimeTask.getTaskVersion());

            DataTask persisted = dataTaskService.create(task, runtimeTask.getInputTableIds(), runtimeTask.getOutputTableIds());
            WorkflowTaskBinding binding = new WorkflowTaskBinding();
            binding.setTaskId(persisted.getId());
            result.getTaskBindings().add(binding);
            result.setCreatedTaskCount(result.getCreatedTaskCount() + 1);
        }
        return result;
    }

    private String resolveUniqueTaskName(String originalName, Long taskCode, Set<String> reservedNames) {
        String base = StringUtils.hasText(originalName) ? originalName.trim() : "task_" + (taskCode == null ? "x" : taskCode);
        if (!reservedNames.contains(base)) {
            reservedNames.add(base);
            return base;
        }
        String suffix = taskCode == null ? "x" : String.valueOf(taskCode);
        String candidate = base + "_import_" + suffix;
        int seq = 2;
        while (reservedNames.contains(candidate)) {
            candidate = base + "_import_" + suffix + "_" + seq;
            seq++;
        }
        reservedNames.add(candidate);
        return candidate;
    }

    private String resolveUniqueTaskCode(String taskName, Long taskCode, Set<String> reservedCodes) {
        String normalizedName = taskName == null
                ? "task"
                : taskName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("_+", "_");
        if (!StringUtils.hasText(normalizedName)) {
            normalizedName = "task";
        }
        String suffix = taskCode == null ? String.valueOf(System.currentTimeMillis()) : String.valueOf(taskCode);
        String base = "wf_imp_" + normalizedName;
        if (base.length() > 48) {
            base = base.substring(0, 48);
        }
        String candidate = base + "_" + suffix;
        int seq = 2;
        while (reservedCodes.contains(candidate) || existsTaskCodeIncludingDeleted(candidate)) {
            candidate = base + "_" + suffix + "_" + seq;
            seq++;
        }
        reservedCodes.add(candidate);
        return candidate;
    }

    private boolean existsTaskCodeIncludingDeleted(String taskCode) {
        Long count = dataTaskMapper.countByTaskCodeIncludingDeleted(taskCode);
        return count != null && count > 0;
    }

    private Integer priorityToNumber(String taskPriority) {
        if (!StringUtils.hasText(taskPriority)) {
            return null;
        }
        String normalized = taskPriority.trim().toUpperCase(Locale.ROOT);
        switch (normalized) {
            case "HIGHEST":
                return 10;
            case "HIGH":
                return 8;
            case "LOW":
                return 3;
            case "LOWEST":
                return 1;
            case "MEDIUM":
            default:
                return 5;
        }
    }

    private String normalizeDolphinFlag(String value) {
        if (!StringUtils.hasText(value)) {
            return "YES";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "NO".equals(normalized) ? "NO" : "YES";
    }

    private String resolveRelationDecision(ImportContext context, String requestedDecision, boolean strict) {
        if (context == null || !Boolean.TRUE.equals(context.getRelationDecisionRequired())) {
            return RELATION_DECISION_INFERRED;
        }
        String normalized = requestedDecision == null ? null : requestedDecision.trim().toUpperCase(Locale.ROOT);
        boolean valid = RELATION_DECISION_DECLARED.equals(normalized) || RELATION_DECISION_INFERRED.equals(normalized);
        if (valid) {
            return normalized;
        }
        if (strict) {
            throw new IllegalArgumentException("关系存在差异，请选择 relationDecision=DECLARED 或 INFERRED");
        }
        return context.getSuggestedRelationDecision();
    }

    private void applyImportedWorkflowFields(DataWorkflow workflow,
            RuntimeWorkflowDefinition definition,
            String definitionJson,
            String operator,
            WorkflowImportRuntimeBinding binding) {
        if (workflow == null || definition == null) {
            return;
        }
        workflow.setDefinitionJson(definitionJson);
        workflow.setSyncSource("import");
        workflow.setUpdatedBy(operator);
        workflow.setUpdatedAt(LocalDateTime.now());

        boolean adopt = RUNTIME_BINDING_ADOPT.equals(binding.getDecision());
        workflow.setDolphinConfigId(binding.getDolphinConfigId());
        workflow.setProjectCode(binding.getProjectCode());
        if (adopt) {
            workflow.setWorkflowCode(binding.getWorkflowCode());
            workflow.setStatus(mapWorkflowStatus(binding.getReleaseState()));
            workflow.setPublishStatus("published");
        } else {
            // 全新导入：清空运行态标识，让首次发布走创建分支而不是去更新别的定义
            workflow.setWorkflowCode(null);
            workflow.setDolphinScheduleId(null);
            workflow.setScheduleState(null);
            workflow.setStatus("draft");
            workflow.setPublishStatus("never");
        }

        RuntimeWorkflowSchedule schedule = definition.getSchedule();
        if (schedule != null) {
            if (adopt) {
                workflow.setDolphinScheduleId(schedule.getScheduleId());
                workflow.setScheduleState(schedule.getReleaseState());
            }
            workflow.setScheduleCron(schedule.getCrontab());
            workflow.setScheduleTimezone(schedule.getTimezoneId());
            workflow.setScheduleStartTime(parseFlexibleDateTime(schedule.getStartTime()));
            workflow.setScheduleEndTime(parseFlexibleDateTime(schedule.getEndTime()));
            workflow.setScheduleFailureStrategy(schedule.getFailureStrategy());
            workflow.setScheduleWarningType(schedule.getWarningType());
            workflow.setScheduleWarningGroupId(schedule.getWarningGroupId());
            workflow.setScheduleProcessInstancePriority(schedule.getProcessInstancePriority());
            workflow.setScheduleWorkerGroup(schedule.getWorkerGroup());
            workflow.setScheduleTenantCode(schedule.getTenantCode());
            workflow.setScheduleEnvironmentCode(schedule.getEnvironmentCode());
        }
    }

    private String mapWorkflowStatus(String releaseState) {
        if (!StringUtils.hasText(releaseState)) {
            return "draft";
        }
        String normalized = releaseState.trim().toUpperCase(Locale.ROOT);
        if ("ONLINE".equals(normalized)) {
            return "online";
        }
        if ("OFFLINE".equals(normalized)) {
            return "offline";
        }
        return "draft";
    }

    private LocalDateTime parseFlexibleDateTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String candidate = raw.trim().replace("Z", "");
        for (DateTimeFormatter formatter : DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(candidate, formatter);
            } catch (DateTimeParseException e) {
                // 当前格式不匹配，尝试下一个格式
                log.trace("按格式 {} 解析时间失败，尝试下一个", formatter, e);
            }
        }
        return null;
    }

    private String resolveWorkflowName(RuntimeWorkflowDefinition definition) {
        return resolveWorkflowName(definition, null);
    }

    private String resolveWorkflowName(RuntimeWorkflowDefinition definition, String overrideName) {
        if (StringUtils.hasText(overrideName)) {
            return overrideName.trim();
        }
        if (definition == null) {
            return null;
        }
        if (StringUtils.hasText(definition.getWorkflowName())) {
            return definition.getWorkflowName().trim();
        }
        Long workflowCode = definition.getWorkflowCode();
        return workflowCode == null ? null : "workflow_" + workflowCode;
    }

    private String resolveWorkflowTaskGroupName(List<RuntimeTaskDefinition> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return null;
        }
        for (RuntimeTaskDefinition task : tasks) {
            if (task != null && StringUtils.hasText(task.getTaskGroupName())) {
                return task.getTaskGroupName().trim();
            }
        }
        return null;
    }

    private Integer resolveVersionNo(Long versionId) {
        if (versionId == null) {
            return null;
        }
        WorkflowVersion version = workflowVersionMapper.selectById(versionId);
        return version != null ? version.getVersionNo() : null;
    }

    private String buildExportFileName(DataWorkflow workflow) {
        String baseName = workflow != null && StringUtils.hasText(workflow.getWorkflowName())
                ? workflow.getWorkflowName().trim()
                : "workflow";
        String safe = baseName.replaceAll("[^a-zA-Z0-9._-]+", "_");
        if (!StringUtils.hasText(safe)) {
            safe = "workflow";
        }
        return safe + ".json";
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON 序列化失败", e);
        }
    }

    private ImportSource buildImportSource(WorkflowImportPreviewRequest request) {
        ImportSource source = new ImportSource();
        source.setSourceType(resolveSourceType(request != null ? request.getSourceType() : null));
        source.setDefinitionJson(request != null ? request.getDefinitionJson() : null);
        source.setProjectCode(request != null ? request.getProjectCode() : null);
        source.setWorkflowCode(request != null ? request.getWorkflowCode() : null);
        source.setWorkflowName(request != null ? request.getWorkflowName() : null);
        source.setDolphinConfigId(request != null ? request.getDolphinConfigId() : null);
        source.setLinkedWorkflowCode(request != null ? request.getLinkedWorkflowCode() : null);
        return source;
    }

    private ImportSource buildImportSource(WorkflowImportCommitRequest request) {
        ImportSource source = new ImportSource();
        source.setSourceType(resolveSourceType(request != null ? request.getSourceType() : null));
        source.setDefinitionJson(request != null ? request.getDefinitionJson() : null);
        source.setProjectCode(request != null ? request.getProjectCode() : null);
        source.setWorkflowCode(request != null ? request.getWorkflowCode() : null);
        source.setWorkflowName(request != null ? request.getWorkflowName() : null);
        source.setDolphinConfigId(request != null ? request.getDolphinConfigId() : null);
        source.setLinkedWorkflowCode(request != null ? request.getLinkedWorkflowCode() : null);
        return source;
    }

    private String resolveSourceType(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return SOURCE_TYPE_JSON;
        }
        String normalized = sourceType.trim().toLowerCase(Locale.ROOT);
        if (SOURCE_TYPE_DOLPHIN.equals(normalized)) {
            return SOURCE_TYPE_DOLPHIN;
        }
        return SOURCE_TYPE_JSON;
    }

    private String resolveTriggerSource(String sourceType) {
        if (SOURCE_TYPE_DOLPHIN.equals(sourceType)) {
            return "import_dolphin";
        }
        return "import_file";
    }

    private String normalizeOperator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "workflow-import";
    }

    @Data
    private static class ImportSource {
        private String sourceType = SOURCE_TYPE_JSON;
        private String definitionJson;
        private Long projectCode;
        private Long workflowCode;
        private String workflowName;
        private Long dolphinConfigId;
        private Long linkedWorkflowCode;
    }

    @Data
    private static class ImportContext {
        private String sourceType = SOURCE_TYPE_JSON;
        private String requestedWorkflowName;
        private Long dolphinConfigId;
        private Long linkedWorkflowCode;
        private WorkflowImportRuntimeBinding runtimeBinding;
        private RuntimeWorkflowDefinition definition;
        private Boolean relationDecisionRequired = false;
        private String suggestedRelationDecision = RELATION_DECISION_INFERRED;
        private RuntimeRelationCompareDetail relationCompareDetail = new RuntimeRelationCompareDetail();
        private List<RuntimeTaskEdge> declaredEdges = new ArrayList<>();
        private List<RuntimeTaskEdge> inferredEdges = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
        private List<String> warnings = new ArrayList<>();
        private String normalizedJson;
    }

    @Data
    private static class TaskCreateResult {
        private Integer createdTaskCount = 0;
        private List<WorkflowTaskBinding> taskBindings = new ArrayList<>();
    }
}
