package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.runtime.DolphinRuntimeWorkflowOption;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeRelationChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeRelationCompareDetail;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncErrorCodes;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncExecuteRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncExecuteResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncIssue;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncRecordDetailResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncRecordListItem;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncPreviewRequest;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncPreviewResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskRenamePlan;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDiffResponse;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowSchedule;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowRuntimeSyncRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowRuntimeSyncRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 运行态反向同步编排服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowRuntimeSyncService {

    private static final DateTimeFormatter[] DATETIME_FORMATS = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    };

    private static final String ENGINE_DOLPHIN = "dolphin";
    private static final String INGEST_MODE_EXPORT_ONLY = "export_only";
    private static final String RELATION_DECISION_DECLARED = "DECLARED";
    private static final String RELATION_DECISION_INFERRED = "INFERRED";

    @Value("${workflow.runtime-sync.enabled:true}")
    private boolean runtimeSyncEnabled;

    @Value("${workflow.runtime-sync.ingest-mode:export_only}")
    private String runtimeSyncIngestMode;

    private final DolphinRuntimeDefinitionService runtimeDefinitionService;
    private final WorkflowRuntimeDiffService runtimeDiffService;
    private final SqlTableMatcherService sqlTableMatcherService;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataTaskMapper dataTaskMapper;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowRuntimeSyncRecordMapper workflowRuntimeSyncRecordMapper;
    private final DataTaskService dataTaskService;
    private final WorkflowService workflowService;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public PageResult<DolphinRuntimeWorkflowOption> listRuntimeWorkflows(Long projectCode,
            Integer pageNum,
            Integer pageSize,
            String keyword) {
        ensureRuntimeSyncEnabled();
        DolphinRuntimeDefinitionService.DolphinRuntimeWorkflowPage runtimePage =
                runtimeDefinitionService.listRuntimeWorkflows(projectCode, pageNum, pageSize, keyword);
        List<DolphinRuntimeWorkflowOption> options = runtimePage.getRecords();
        if (CollectionUtils.isEmpty(options)) {
            return PageResult.of(runtimePage.getTotal(), Collections.emptyList());
        }

        Set<Long> workflowCodes = options.stream()
                .map(DolphinRuntimeWorkflowOption::getWorkflowCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (workflowCodes.isEmpty()) {
            return PageResult.of(runtimePage.getTotal(), options);
        }

        Long resolvedProjectCode = options.stream()
                .map(DolphinRuntimeWorkflowOption::getProjectCode)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(projectCode);

        List<DataWorkflow> localWorkflows = dataWorkflowMapper.selectList(
                Wrappers.<DataWorkflow>lambdaQuery()
                        .eq(resolvedProjectCode != null, DataWorkflow::getProjectCode, resolvedProjectCode)
                        .in(DataWorkflow::getWorkflowCode, workflowCodes)
                        .orderByDesc(DataWorkflow::getRuntimeSyncAt)
                        .orderByDesc(DataWorkflow::getUpdatedAt)
                        .orderByDesc(DataWorkflow::getId));

        Map<Long, DataWorkflow> workflowByCode = new LinkedHashMap<>();
        for (DataWorkflow local : localWorkflows) {
            if (local.getWorkflowCode() == null) {
                continue;
            }
            workflowByCode.putIfAbsent(local.getWorkflowCode(), local);
        }

        for (DolphinRuntimeWorkflowOption option : options) {
            DataWorkflow local = workflowByCode.get(option.getWorkflowCode());
            if (local != null) {
                option.setSynced(true);
                option.setLocalWorkflowId(local.getId());
                option.setLocalWorkflowName(local.getWorkflowName());
                option.setLastRuntimeSyncAt(local.getRuntimeSyncAt());
            } else {
                option.setSynced(false);
            }
        }
        return PageResult.of(runtimePage.getTotal(), options);
    }

    public RuntimeSyncPreviewResponse preview(RuntimeSyncPreviewRequest request) {
        if (!runtimeSyncEnabled) {
            RuntimeSyncPreviewResponse disabled = new RuntimeSyncPreviewResponse();
            disabled.setIngestMode(resolveIngestMode());
            disabled.getErrors().add(RuntimeSyncIssue.error(
                    RuntimeSyncErrorCodes.RUNTIME_SYNC_DISABLED, "运行态同步功能未开启"));
            return disabled;
        }
        PreviewContext context = buildPreviewContext(request.getProjectCode(), request.getWorkflowCode(), false);
        RuntimeSyncPreviewResponse response = new RuntimeSyncPreviewResponse();
        response.setIngestMode(context.getIngestMode());
        response.setCanSync(context.getErrors().isEmpty());
        response.setErrors(context.getErrors());
        response.setWarnings(context.getWarnings());
        response.setDiffSummary(context.getDiffSummary());
        response.setRenamePlan(context.getRenamePlan());
        response.setRelationDecisionRequired(context.getRelationDecisionRequired());
        response.setRelationCompareDetail(context.getRelationCompareDetail());
        return response;
    }

    public RuntimeSyncExecuteResponse sync(RuntimeSyncExecuteRequest request) {
        RuntimeSyncExecuteResponse response = new RuntimeSyncExecuteResponse();
        if (!runtimeSyncEnabled) {
            response.setIngestMode(resolveIngestMode());
            response.getErrors().add(RuntimeSyncIssue.error(
                    RuntimeSyncErrorCodes.RUNTIME_SYNC_DISABLED, "运行态同步功能未开启"));
            return response;
        }

        String operator = resolveOperator(request.getOperator());
        PreviewContext context = buildPreviewContext(request.getProjectCode(), request.getWorkflowCode(), true);
        response.setIngestMode(context.getIngestMode());
        response.setWarnings(context.getWarnings());
        response.setErrors(context.getErrors());
        response.setDiffSummary(context.getDiffSummary());
        response.setRelationDecisionRequired(context.getRelationDecisionRequired());
        response.setRelationCompareDetail(context.getRelationCompareDetail());

        if (!context.getErrors().isEmpty()) {
            Long recordId = saveFailedSyncRecord(context,
                    firstIssueCode(context.getErrors()),
                    firstIssueMessage(context.getErrors()),
                    operator);
            response.setSyncRecordId(recordId);
            return response;
        }

        if (Boolean.TRUE.equals(context.getRelationDecisionRequired())
                && !isValidRelationDecision(request.getRelationDecision())) {
            RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                    RuntimeSyncErrorCodes.RELATION_DECISION_REQUIRED,
                    "检测到声明关系与 SQL 推断关系不一致，请先选择关系轨道后再执行同步");
            issue.setWorkflowCode(context.getDefinition().getWorkflowCode());
            issue.setWorkflowName(context.getDefinition().getWorkflowName());
            response.getErrors().add(issue);
            Long recordId = saveFailedSyncRecord(context, issue.getCode(), issue.getMessage(), operator);
            response.setSyncRecordId(recordId);
            return response;
        }
        applyRelationDecision(context, request.getRelationDecision());
        buildPreviewArtifacts(context);
        response.setDiffSummary(context.getDiffSummary());

        try {
            SyncTransactionResult txResult = transactionTemplate.execute(status -> doSyncInTransaction(context, operator));
            if (txResult == null) {
                throw new IllegalStateException("同步事务执行失败");
            }
            response.setSuccess(true);
            response.setWorkflowId(txResult.getWorkflowId());
            response.setVersionNo(txResult.getVersionNo());
            response.setSyncRecordId(txResult.getSyncRecordId());
            return response;
        } catch (Exception ex) {
            log.error("Runtime sync failed for workflowCode={}", request.getWorkflowCode(), ex);
            RuntimeSyncIssue issue = buildExceptionIssue(ex);
            response.getErrors().add(issue);
            Long recordId = saveFailedSyncRecord(context, issue.getCode(), issue.getMessage(), operator);
            response.setSyncRecordId(recordId);
            return response;
        }
    }

    public RuntimeWorkflowDiffResponse runtimeDiff(Long workflowId) {
        RuntimeWorkflowDiffResponse response = new RuntimeWorkflowDiffResponse();
        response.setWorkflowId(workflowId);

        if (!runtimeSyncEnabled) {
            response.getErrors().add(RuntimeSyncIssue.error(
                    RuntimeSyncErrorCodes.RUNTIME_SYNC_DISABLED, "运行态同步功能未开启"));
            return response;
        }

        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null || workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            response.getErrors().add(RuntimeSyncIssue.error(
                    RuntimeSyncErrorCodes.RUNTIME_WORKFLOW_NOT_FOUND, "未找到可比对的运行态工作流编码"));
            return response;
        }

        response.setProjectCode(workflow.getProjectCode());
        response.setWorkflowCode(workflow.getWorkflowCode());

        PreviewContext context = buildPreviewContext(
                workflow.getDolphinConfigId(), workflow.getProjectCode(), workflow.getWorkflowCode(), false);
        response.setWarnings(context.getWarnings());
        response.setErrors(context.getErrors());
        response.setDiffSummary(context.getDiffSummary());
        return response;
    }

    public PageResult<RuntimeSyncRecordListItem> listSyncRecords(Long workflowId, Integer pageNum, Integer pageSize) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        int currentPage = pageNum == null || pageNum <= 0 ? 1 : pageNum;
        int currentSize = pageSize == null || pageSize <= 0 ? 20 : pageSize;
        Page<WorkflowRuntimeSyncRecord> page = new Page<>(currentPage, currentSize);
        Page<WorkflowRuntimeSyncRecord> result = workflowRuntimeSyncRecordMapper.selectPage(
                page,
                Wrappers.<WorkflowRuntimeSyncRecord>lambdaQuery()
                        .eq(WorkflowRuntimeSyncRecord::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowRuntimeSyncRecord::getCreatedAt)
                        .orderByDesc(WorkflowRuntimeSyncRecord::getId));

        List<RuntimeSyncRecordListItem> records = result.getRecords().stream()
                .map(this::toSyncRecordListItem)
                .collect(Collectors.toList());
        return PageResult.of(result.getTotal(), records);
    }

    public RuntimeSyncRecordDetailResponse getSyncRecordDetail(Long workflowId, Long recordId) {
        WorkflowRuntimeSyncRecord record = workflowRuntimeSyncRecordMapper.selectById(recordId);
        if (record == null || !Objects.equals(record.getWorkflowId(), workflowId)) {
            throw new IllegalArgumentException("同步记录不存在: " + recordId);
        }
        RuntimeSyncRecordDetailResponse response = new RuntimeSyncRecordDetailResponse();
        response.setId(record.getId());
        response.setWorkflowId(record.getWorkflowId());
        response.setProjectCode(record.getProjectCode());
        response.setWorkflowCode(record.getWorkflowCode());
        response.setVersionId(record.getVersionId());
        response.setStatus(record.getStatus());
        response.setIngestMode(record.getIngestMode());
        response.setRawDefinitionJson(record.getRawDefinitionJson());
        response.setSnapshotHash(record.getSnapshotHash());
        response.setSnapshotJson(record.getSnapshotJson());
        response.setDiffSummary(parseDiffSummary(record.getDiffJson()));
        response.setErrorCode(record.getErrorCode());
        response.setErrorMessage(record.getErrorMessage());
        response.setOperator(record.getOperator());
        response.setCreatedAt(record.getCreatedAt());
        return response;
    }

    private PreviewContext buildPreviewContext(Long projectCode, Long workflowCode, boolean strictDefinitionError) {
        return buildPreviewContext(null, projectCode, workflowCode, strictDefinitionError);
    }

    private PreviewContext buildPreviewContext(Long dolphinConfigId,
            Long projectCode,
            Long workflowCode,
            boolean strictDefinitionError) {
        PreviewContext context = new PreviewContext();
        context.setProjectCode(projectCode);
        context.setWorkflowCode(workflowCode);
        context.setIngestMode(resolveIngestMode());

        if (workflowCode == null || workflowCode <= 0) {
            context.getErrors().add(RuntimeSyncIssue.error(
                    RuntimeSyncErrorCodes.RUNTIME_WORKFLOW_NOT_FOUND, "workflowCode 不能为空"));
            return context;
        }

        RuntimeWorkflowDefinition definition;
        try {
            definition = dolphinConfigId == null
                    ? runtimeDefinitionService.loadRuntimeDefinitionFromExport(projectCode, workflowCode)
                    : runtimeDefinitionService.loadRuntimeDefinitionFromExport(dolphinConfigId, projectCode, workflowCode);
        } catch (Exception ex) {
            context.getErrors().add(RuntimeSyncIssue.error(
                    resolveDefinitionErrorCode(ex.getMessage(), strictDefinitionError),
                    ex.getMessage()));
            return context;
        }

        if (definition == null) {
            context.getErrors().add(RuntimeSyncIssue.error(
                    RuntimeSyncErrorCodes.DEFINITION_FORMAT_UNSUPPORTED,
                    "运行态定义解析结果为空"));
            return context;
        }

        context.setDefinition(definition);
        context.setProjectCode(definition.getProjectCode());
        context.setWorkflowCode(definition.getWorkflowCode());

        DataWorkflow localWorkflow = findLocalWorkflow(definition.getProjectCode(), definition.getWorkflowCode());
        context.setLocalWorkflow(localWorkflow);

        if (CollectionUtils.isEmpty(definition.getTasks())) {
            context.getErrors().add(RuntimeSyncIssue.error(
                    RuntimeSyncErrorCodes.DEFINITION_FORMAT_UNSUPPORTED,
                    "Dolphin 定义缺少 taskDefinitionJson 或无法解析"));
            buildPreviewArtifacts(context);
            return context;
        }

        Map<Long, DolphinDatasourceOption> datasourceById = (dolphinConfigId == null
                ? dolphinSchedulerService.listDatasources(null, null)
                : dolphinSchedulerService.listDatasources(null, null, dolphinConfigId))
                .stream()
                .filter(option -> option != null && option.getId() != null)
                .collect(Collectors.toMap(DolphinDatasourceOption::getId, option -> option, (left, right) -> left));
        context.setDatasourceById(datasourceById);

        normalizeAndValidateTasks(context);
        validateTaskCodeMapping(context);
        validateWorkflowBindingConflict(context);
        buildRenamePlan(context);
        buildRelationCompare(context);
        buildPreviewArtifacts(context);
        return context;
    }

    private String resolveDefinitionErrorCode(String message, boolean strictDefinitionError) {
        String code = RuntimeSyncErrorCodes.RUNTIME_WORKFLOW_NOT_FOUND;
        if (StringUtils.hasText(message) && !message.contains("未找到")) {
            code = RuntimeSyncErrorCodes.DEFINITION_FORMAT_UNSUPPORTED;
        } else if (strictDefinitionError) {
            code = RuntimeSyncErrorCodes.DEFINITION_FORMAT_UNSUPPORTED;
        }
        return code;
    }

    private void normalizeAndValidateTasks(PreviewContext context) {
        List<RuntimeTaskDefinition> tasks = context.getDefinition().getTasks();
        for (RuntimeTaskDefinition task : tasks) {
            if (task == null) {
                continue;
            }
            if (task.getTaskCode() == null || task.getTaskCode() <= 0) {
                RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                        RuntimeSyncErrorCodes.DEFINITION_FORMAT_UNSUPPORTED,
                        "任务缺少合法 taskCode");
                fillTaskIssue(issue, context.getDefinition(), task);
                context.getErrors().add(issue);
                continue;
            }

            String nodeType = task.getNodeType();
            if (!"SQL".equalsIgnoreCase(nodeType)) {
                RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                        RuntimeSyncErrorCodes.UNSUPPORTED_NODE_TYPE,
                        String.format("仅支持 SQL 节点，当前节点类型=%s", nodeType));
                fillTaskIssue(issue, context.getDefinition(), task);
                context.getErrors().add(issue);
                continue;
            }

            SqlTableAnalyzeResponse analyze = sqlTableMatcherService.analyze(task.getSql(), "SQL");
            if (analyze == null) {
                RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                        RuntimeSyncErrorCodes.SQL_LINEAGE_INCOMPLETE, "SQL 解析失败");
                fillTaskIssue(issue, context.getDefinition(), task);
                context.getErrors().add(issue);
                continue;
            }

            if (!CollectionUtils.isEmpty(analyze.getAmbiguous())) {
                RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                        RuntimeSyncErrorCodes.SQL_TABLE_AMBIGUOUS,
                        "SQL 表匹配存在歧义: " + String.join(", ", analyze.getAmbiguous()));
                fillTaskIssue(issue, context.getDefinition(), task);
                issue.setRawName(String.join(", ", analyze.getAmbiguous()));
                context.getErrors().add(issue);
            }

            if (!CollectionUtils.isEmpty(analyze.getUnmatched())) {
                RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                        RuntimeSyncErrorCodes.SQL_TABLE_UNMATCHED,
                        "SQL 表未匹配到平台元数据: " + String.join(", ", analyze.getUnmatched()));
                fillTaskIssue(issue, context.getDefinition(), task);
                issue.setRawName(String.join(", ", analyze.getUnmatched()));
                context.getErrors().add(issue);
            }

            List<Long> inputTableIds = analyze.getInputRefs().stream()
                    .filter(ref -> "matched".equalsIgnoreCase(ref.getMatchStatus()))
                    .map(SqlTableAnalyzeResponse.TableRefMatch::getChosenTable)
                    .filter(Objects::nonNull)
                    .map(SqlTableAnalyzeResponse.TableCandidate::getTableId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
            List<Long> outputTableIds = analyze.getOutputRefs().stream()
                    .filter(ref -> "matched".equalsIgnoreCase(ref.getMatchStatus()))
                    .map(SqlTableAnalyzeResponse.TableRefMatch::getChosenTable)
                    .filter(Objects::nonNull)
                    .map(SqlTableAnalyzeResponse.TableCandidate::getTableId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());

            task.setInputTableIds(inputTableIds);
            task.setOutputTableIds(outputTableIds);
            if (outputTableIds.isEmpty()) {
                RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                        RuntimeSyncErrorCodes.SQL_LINEAGE_INCOMPLETE,
                        "SQL 任务输出表推断不完整");
                fillTaskIssue(issue, context.getDefinition(), task);
                context.getErrors().add(issue);
            }

            DolphinDatasourceOption datasource = context.getDatasourceById().get(task.getDatasourceId());
            if (task.getDatasourceId() == null || datasource == null) {
                RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                        RuntimeSyncErrorCodes.DATASOURCE_NOT_FOUND,
                        "无法将 datasourceId 映射到平台数据源");
                fillTaskIssue(issue, context.getDefinition(), task);
                context.getErrors().add(issue);
            } else {
                task.setDatasourceName(datasource.getName());
                if (!StringUtils.hasText(task.getDatasourceType())) {
                    task.setDatasourceType(datasource.getType());
                }
            }
        }
    }

    private void validateTaskCodeMapping(PreviewContext context) {
        List<Long> taskCodes = context.getDefinition().getTasks().stream()
                .map(RuntimeTaskDefinition::getTaskCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (taskCodes.isEmpty()) {
            return;
        }

        List<DataTask> matchedTasks = dataTaskMapper.selectList(
                Wrappers.<DataTask>lambdaQuery()
                        .eq(DataTask::getEngine, ENGINE_DOLPHIN)
                        .in(DataTask::getDolphinTaskCode, taskCodes));

        Map<Long, List<DataTask>> grouped = matchedTasks.stream()
                .filter(task -> task.getDolphinTaskCode() != null)
                .collect(Collectors.groupingBy(DataTask::getDolphinTaskCode));

        for (Map.Entry<Long, List<DataTask>> entry : grouped.entrySet()) {
            if (entry.getValue().size() > 1) {
                RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                        RuntimeSyncErrorCodes.TASK_CODE_DUPLICATE,
                        "本地存在多条相同 dolphin_task_code 记录: " + entry.getKey());
                issue.setTaskCode(entry.getKey());
                context.getErrors().add(issue);
            } else {
                context.getExistingTaskByRuntimeCode().put(entry.getKey(), entry.getValue().get(0));
            }
        }
    }

    private void validateWorkflowBindingConflict(PreviewContext context) {
        if (context.getExistingTaskByRuntimeCode().isEmpty()) {
            return;
        }
        List<Long> taskIds = context.getExistingTaskByRuntimeCode().values().stream()
                .map(DataTask::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return;
        }

        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .in(WorkflowTaskRelation::getTaskId, taskIds));
        Map<Long, WorkflowTaskRelation> relationByTaskId = new LinkedHashMap<>();
        for (WorkflowTaskRelation relation : relations) {
            relationByTaskId.putIfAbsent(relation.getTaskId(), relation);
        }
        context.setExistingTaskRelationByTaskId(relationByTaskId);

        Long targetWorkflowId = context.getLocalWorkflow() != null ? context.getLocalWorkflow().getId() : null;
        for (RuntimeTaskDefinition runtimeTask : context.getDefinition().getTasks()) {
            if (runtimeTask.getTaskCode() == null) {
                continue;
            }
            DataTask localTask = context.getExistingTaskByRuntimeCode().get(runtimeTask.getTaskCode());
            if (localTask == null || localTask.getId() == null) {
                continue;
            }
            WorkflowTaskRelation relation = relationByTaskId.get(localTask.getId());
            if (relation == null || relation.getWorkflowId() == null) {
                continue;
            }
            if (targetWorkflowId == null || !Objects.equals(targetWorkflowId, relation.getWorkflowId())) {
                RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                        RuntimeSyncErrorCodes.WORKFLOW_BINDING_CONFLICT,
                        String.format("任务已归属其他工作流 workflowId=%s", relation.getWorkflowId()));
                fillTaskIssue(issue, context.getDefinition(), runtimeTask);
                context.getErrors().add(issue);
            }
        }
    }

    private void buildRenamePlan(PreviewContext context) {
        List<RuntimeTaskDefinition> tasks = context.getDefinition().getTasks().stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RuntimeTaskDefinition::getTaskCode, Comparator.nullsLast(Long::compareTo)))
                .collect(Collectors.toList());

        Set<Long> managedTaskIds = context.getExistingTaskByRuntimeCode().values().stream()
                .map(DataTask::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<DataTask> activeTasks = dataTaskMapper.selectList(
                Wrappers.<DataTask>lambdaQuery()
                        .select(DataTask::getId, DataTask::getTaskName));
        Set<String> reservedNames = activeTasks.stream()
                .filter(Objects::nonNull)
                .filter(task -> task.getId() == null || !managedTaskIds.contains(task.getId()))
                .map(DataTask::getTaskName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        for (RuntimeTaskDefinition task : tasks) {
            String originalName = StringUtils.hasText(task.getTaskName())
                    ? task.getTaskName().trim()
                    : "task_" + task.getTaskCode();
            String resolvedName = originalName;
            if (reservedNames.contains(resolvedName)) {
                resolvedName = buildRenamedTaskName(originalName, context.getWorkflowCode(), task.getTaskCode(), reservedNames);
                RuntimeTaskRenamePlan plan = new RuntimeTaskRenamePlan();
                plan.setTaskCode(task.getTaskCode());
                plan.setOriginalName(originalName);
                plan.setResolvedName(resolvedName);
                plan.setReason("任务名称冲突，自动重命名");
                context.getRenamePlan().add(plan);
            }
            reservedNames.add(resolvedName);
            task.setTaskName(resolvedName);
            context.getResolvedTaskNameByCode().put(task.getTaskCode(), resolvedName);
        }
    }

    private void buildRelationCompare(PreviewContext context) {
        List<RuntimeTaskEdge> declaredEdges = normalizeEdges(context.getDefinition().getExplicitEdges());
        List<RuntimeTaskEdge> inferredEdges = inferEdgesFromLineage(context.getDefinition().getTasks());
        context.setDeclaredEdges(declaredEdges);
        context.setInferredEdges(inferredEdges);
        context.setSelectedEdges(inferredEdges);

        Set<String> declaredSet = toEdgeSet(declaredEdges);
        Set<String> inferredSet = toEdgeSet(inferredEdges);
        Map<Long, String> taskNameByCode = buildTaskNameByCode(context.getDefinition().getTasks());

        RuntimeRelationCompareDetail detail = new RuntimeRelationCompareDetail();
        detail.setDeclaredRelations(toRelationChanges(declaredSet, taskNameByCode));
        detail.setInferredRelations(toRelationChanges(inferredSet, taskNameByCode));
        detail.setOnlyInDeclared(toRelationChanges(diffSet(declaredSet, inferredSet), taskNameByCode));
        detail.setOnlyInInferred(toRelationChanges(diffSet(inferredSet, declaredSet), taskNameByCode));
        context.setRelationCompareDetail(detail);

        boolean mismatch = !Objects.equals(declaredSet, inferredSet);
        context.setRelationDecisionRequired(mismatch);
        if (mismatch) {
            RuntimeSyncIssue warning = RuntimeSyncIssue.warning(
                    RuntimeSyncErrorCodes.RELATION_MISMATCH,
                    String.format("声明关系与 SQL 推断关系不一致（declared=%d, inferred=%d），需人工选择轨道后方可同步",
                            declaredSet.size(),
                            inferredSet.size()));
            warning.setWorkflowCode(context.getDefinition().getWorkflowCode());
            warning.setWorkflowName(context.getDefinition().getWorkflowName());
            context.getWarnings().add(warning);
        }
    }

    private List<RuntimeTaskEdge> normalizeEdges(List<RuntimeTaskEdge> edges) {
        if (CollectionUtils.isEmpty(edges)) {
            return Collections.emptyList();
        }
        Map<String, RuntimeTaskEdge> dedup = new LinkedHashMap<>();
        for (RuntimeTaskEdge edge : edges) {
            if (edge == null || edge.getUpstreamTaskCode() == null || edge.getDownstreamTaskCode() == null) {
                continue;
            }
            String key = edge.getUpstreamTaskCode() + "->" + edge.getDownstreamTaskCode();
            dedup.putIfAbsent(key, new RuntimeTaskEdge(edge.getUpstreamTaskCode(), edge.getDownstreamTaskCode()));
        }
        return dedup.values().stream()
                .sorted(Comparator.comparing(RuntimeTaskEdge::getUpstreamTaskCode)
                        .thenComparing(RuntimeTaskEdge::getDownstreamTaskCode))
                .collect(Collectors.toList());
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

    private Map<Long, String> buildTaskNameByCode(List<RuntimeTaskDefinition> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyMap();
        }
        Map<Long, String> taskNameByCode = new LinkedHashMap<>();
        for (RuntimeTaskDefinition task : tasks) {
            if (task == null || task.getTaskCode() == null) {
                continue;
            }
            if (!StringUtils.hasText(task.getTaskName())) {
                continue;
            }
            taskNameByCode.putIfAbsent(task.getTaskCode(), task.getTaskName().trim());
        }
        return taskNameByCode;
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
        Long preTaskCode = parseLong(parts[0].trim());
        Long postTaskCode = parseLong(parts[1].trim());
        if (preTaskCode == null || postTaskCode == null) {
            return null;
        }
        RuntimeRelationChange change = new RuntimeRelationChange();
        change.setPreTaskCode(preTaskCode);
        change.setPostTaskCode(postTaskCode);
        change.setEntryEdge(preTaskCode == 0L);
        change.setPreTaskName(preTaskCode == 0L ? "入口" : taskNameByCode.get(preTaskCode));
        change.setPostTaskName(taskNameByCode.get(postTaskCode));
        return change;
    }

    private boolean isValidRelationDecision(String relationDecision) {
        if (!StringUtils.hasText(relationDecision)) {
            return false;
        }
        String normalized = relationDecision.trim().toUpperCase(Locale.ROOT);
        return RELATION_DECISION_DECLARED.equals(normalized) || RELATION_DECISION_INFERRED.equals(normalized);
    }

    private void applyRelationDecision(PreviewContext context, String relationDecision) {
        if (context == null) {
            return;
        }
        if (!Boolean.TRUE.equals(context.getRelationDecisionRequired())) {
            context.setSelectedEdges(context.getInferredEdges());
            return;
        }
        String normalized = StringUtils.hasText(relationDecision)
                ? relationDecision.trim().toUpperCase(Locale.ROOT)
                : "";
        if (RELATION_DECISION_DECLARED.equals(normalized)) {
            context.setSelectedEdges(context.getDeclaredEdges());
            return;
        }
        context.setSelectedEdges(context.getInferredEdges());
    }

    private void buildPreviewArtifacts(PreviewContext context) {
        WorkflowRuntimeDiffService.RuntimeSnapshot snapshot =
                runtimeDiffService.buildSnapshot(context.getDefinition(), context.getSelectedEdges());
        context.setSnapshot(snapshot);

        WorkflowRuntimeSyncRecord baselineRecord = findLatestSyncRecord(context.getLocalWorkflow(),
                context.getDefinition() != null ? context.getDefinition().getProjectCode() : context.getProjectCode(),
                context.getDefinition() != null ? context.getDefinition().getWorkflowCode() : context.getWorkflowCode());
        String baselineSnapshot = baselineRecord != null ? baselineRecord.getSnapshotJson() : null;
        RuntimeDiffSummary diffSummary = runtimeDiffService.buildDiff(baselineSnapshot, snapshot);
        context.setDiffSummary(diffSummary);
    }

    private SyncTransactionResult doSyncInTransaction(PreviewContext context, String operator) {
        DataWorkflow existingWorkflow = context.getLocalWorkflow();
        Map<Long, Long> persistedTaskIdByRuntimeCode = new LinkedHashMap<>();
        Set<String> generatedTaskCodes = new HashSet<>();

        List<RuntimeTaskDefinition> tasksInOrder = context.getDefinition().getTasks().stream()
                .sorted(Comparator.comparing(RuntimeTaskDefinition::getTaskCode))
                .collect(Collectors.toList());

        for (RuntimeTaskDefinition runtimeTask : tasksInOrder) {
            DataTask existingTask = context.getExistingTaskByRuntimeCode().get(runtimeTask.getTaskCode());
            DataTask payload = buildTaskPayload(runtimeTask, context.getDefinition(), existingTask, operator);

            DataTask persisted;
            if (existingTask != null) {
                payload.setId(existingTask.getId());
                payload.setTaskCode(existingTask.getTaskCode());
                payload.setWorkflowId(resolveExistingWorkflowBinding(existingTask.getId(), context));
                persisted = dataTaskService.update(payload,
                        runtimeTask.getInputTableIds(),
                        runtimeTask.getOutputTableIds());
            } else {
                payload.setTaskCode(generateTaskCode(context.getDefinition().getWorkflowCode(),
                        runtimeTask.getTaskCode(),
                        generatedTaskCodes));
                payload.setWorkflowId(null);
                persisted = dataTaskService.create(payload,
                        runtimeTask.getInputTableIds(),
                        runtimeTask.getOutputTableIds());
            }
            persistedTaskIdByRuntimeCode.put(runtimeTask.getTaskCode(), persisted.getId());
        }

        WorkflowDefinitionRequest workflowRequest = new WorkflowDefinitionRequest();
        workflowRequest.setWorkflowName(resolveWorkflowName(context.getDefinition()));
        workflowRequest.setDescription(context.getDefinition().getDescription());
        workflowRequest.setGlobalParams(context.getDefinition().getGlobalParams());
        workflowRequest.setTaskGroupName(existingWorkflow != null ? existingWorkflow.getTaskGroupName() : null);
        workflowRequest.setTasks(buildWorkflowTaskBindings(tasksInOrder, persistedTaskIdByRuntimeCode));
        workflowRequest.setOperator(operator);
        workflowRequest.setTriggerSource("runtime_sync");
        workflowRequest.setProjectCode(context.getDefinition().getProjectCode());

        DataWorkflow workflow = existingWorkflow == null
                ? workflowService.createWorkflow(workflowRequest)
                : workflowService.updateWorkflow(existingWorkflow.getId(), workflowRequest);

        applyWorkflowRuntimeFields(workflow, context, operator);
        dataWorkflowMapper.updateById(workflow);

        WorkflowRuntimeSyncRecord syncRecord = new WorkflowRuntimeSyncRecord();
        syncRecord.setWorkflowId(workflow.getId());
        syncRecord.setProjectCode(context.getDefinition().getProjectCode());
        syncRecord.setWorkflowCode(context.getDefinition().getWorkflowCode());
        syncRecord.setSnapshotHash(context.getSnapshot().getSnapshotHash());
        syncRecord.setSnapshotJson(context.getSnapshot().getSnapshotJson());
        syncRecord.setDiffJson(toJson(context.getDiffSummary()));
        syncRecord.setVersionId(workflow.getCurrentVersionId());
        syncRecord.setIngestMode(context.getIngestMode());
        syncRecord.setRawDefinitionJson(context.getDefinition() != null ? context.getDefinition().getRawDefinitionJson() : null);
        syncRecord.setStatus("success");
        syncRecord.setOperator(operator);
        workflowRuntimeSyncRecordMapper.insert(syncRecord);

        Integer versionNo = resolveVersionNo(workflow.getCurrentVersionId());

        SyncTransactionResult result = new SyncTransactionResult();
        result.setWorkflowId(workflow.getId());
        result.setVersionNo(versionNo);
        result.setSyncRecordId(syncRecord.getId());
        return result;
    }

    private DataTask buildTaskPayload(RuntimeTaskDefinition runtimeTask,
            RuntimeWorkflowDefinition definition,
            DataTask existingTask,
            String operator) {
        DataTask task = new DataTask();
        task.setTaskName(runtimeTask.getTaskName());
        task.setTaskType(existingTask != null && StringUtils.hasText(existingTask.getTaskType())
                ? existingTask.getTaskType()
                : "batch");
        task.setEngine(ENGINE_DOLPHIN);
        task.setDolphinNodeType("SQL");
        task.setTaskSql(runtimeTask.getSql());
        task.setTaskDesc(runtimeTask.getDescription());
        task.setDatasourceName(runtimeTask.getDatasourceName());
        task.setDatasourceType(runtimeTask.getDatasourceType());
        task.setTaskGroupName(runtimeTask.getTaskGroupName());
        task.setDolphinFlag(runtimeTask.getFlag());
        task.setDolphinProcessCode(definition.getWorkflowCode());
        task.setDolphinTaskCode(runtimeTask.getTaskCode());
        task.setDolphinTaskVersion(runtimeTask.getTaskVersion() != null ? runtimeTask.getTaskVersion() : 1);
        task.setRetryTimes(runtimeTask.getRetryTimes());
        task.setRetryInterval(runtimeTask.getRetryInterval());
        task.setTimeoutSeconds(runtimeTask.getTimeoutSeconds());
        if (existingTask != null && StringUtils.hasText(existingTask.getOwner())) {
            task.setOwner(existingTask.getOwner());
        } else {
            task.setOwner(operator);
        }
        return task;
    }

    private List<WorkflowTaskBinding> buildWorkflowTaskBindings(List<RuntimeTaskDefinition> tasksInOrder,
            Map<Long, Long> persistedTaskIdByRuntimeCode) {
        List<WorkflowTaskBinding> bindings = new ArrayList<>();
        for (RuntimeTaskDefinition runtimeTask : tasksInOrder) {
            Long taskId = persistedTaskIdByRuntimeCode.get(runtimeTask.getTaskCode());
            if (taskId == null) {
                continue;
            }
            WorkflowTaskBinding binding = new WorkflowTaskBinding();
            binding.setTaskId(taskId);
            bindings.add(binding);
        }
        return bindings;
    }

    private void applyWorkflowRuntimeFields(DataWorkflow workflow, PreviewContext context, String operator) {
        RuntimeWorkflowDefinition definition = context.getDefinition();
        RuntimeWorkflowSchedule schedule = definition.getSchedule();
        LocalDateTime now = LocalDateTime.now();

        workflow.setWorkflowCode(definition.getWorkflowCode());
        workflow.setProjectCode(definition.getProjectCode());
        workflow.setStatus(mapWorkflowStatus(definition.getReleaseState()));
        workflow.setPublishStatus("published");
        workflow.setGlobalParams(definition.getGlobalParams());
        workflow.setSyncSource("runtime");
        workflow.setRuntimeSyncHash(context.getSnapshot().getSnapshotHash());
        workflow.setRuntimeSyncStatus("success");
        workflow.setRuntimeSyncMessage("同步成功");
        workflow.setRuntimeSyncAt(now);
        workflow.setUpdatedBy(operator);
        workflow.setUpdatedAt(now);

        if (schedule != null) {
            workflow.setDolphinScheduleId(schedule.getScheduleId());
            workflow.setScheduleState(schedule.getReleaseState());
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
        } else {
            workflow.setDolphinScheduleId(null);
            workflow.setScheduleState(null);
            workflow.setScheduleCron(null);
            workflow.setScheduleTimezone(null);
            workflow.setScheduleStartTime(null);
            workflow.setScheduleEndTime(null);
            workflow.setScheduleFailureStrategy(null);
            workflow.setScheduleWarningType(null);
            workflow.setScheduleWarningGroupId(null);
            workflow.setScheduleProcessInstancePriority(null);
            workflow.setScheduleWorkerGroup(null);
            workflow.setScheduleTenantCode(null);
            workflow.setScheduleEnvironmentCode(null);
        }
    }

    private Long saveFailedSyncRecord(PreviewContext context, String errorCode, String errorMessage, String operator) {
        WorkflowRuntimeSyncRecord record = new WorkflowRuntimeSyncRecord();
        if (context != null && context.getLocalWorkflow() != null) {
            record.setWorkflowId(context.getLocalWorkflow().getId());
        }
        Long projectCode = context != null && context.getDefinition() != null
                ? context.getDefinition().getProjectCode()
                : context != null ? context.getProjectCode() : null;
        Long workflowCode = context != null && context.getDefinition() != null
                ? context.getDefinition().getWorkflowCode()
                : context != null ? context.getWorkflowCode() : null;

        record.setProjectCode(projectCode);
        record.setWorkflowCode(workflowCode);
        if (context != null && context.getSnapshot() != null) {
            record.setSnapshotHash(context.getSnapshot().getSnapshotHash());
            record.setSnapshotJson(context.getSnapshot().getSnapshotJson());
        }
        if (context != null && context.getDiffSummary() != null) {
            record.setDiffJson(toJson(context.getDiffSummary()));
        }
        record.setIngestMode(context != null ? context.getIngestMode() : resolveIngestMode());
        record.setRawDefinitionJson(context != null && context.getDefinition() != null
                ? context.getDefinition().getRawDefinitionJson()
                : null);
        record.setStatus("failed");
        record.setErrorCode(errorCode);
        record.setErrorMessage(errorMessage);
        record.setOperator(operator);
        workflowRuntimeSyncRecordMapper.insert(record);

        if (context != null && context.getLocalWorkflow() != null) {
            DataWorkflow workflow = context.getLocalWorkflow();
            workflow.setSyncSource("runtime");
            workflow.setRuntimeSyncStatus("failed");
            workflow.setRuntimeSyncMessage(errorMessage);
            workflow.setRuntimeSyncAt(LocalDateTime.now());
            workflow.setUpdatedBy(operator);
            dataWorkflowMapper.updateById(workflow);
        }
        return record.getId();
    }

    private WorkflowRuntimeSyncRecord findLatestSyncRecord(DataWorkflow workflow, Long projectCode, Long workflowCode) {
        if (workflow != null && workflow.getId() != null) {
            WorkflowRuntimeSyncRecord byWorkflow = workflowRuntimeSyncRecordMapper.selectOne(
                    Wrappers.<WorkflowRuntimeSyncRecord>lambdaQuery()
                            .eq(WorkflowRuntimeSyncRecord::getWorkflowId, workflow.getId())
                            .orderByDesc(WorkflowRuntimeSyncRecord::getCreatedAt)
                            .orderByDesc(WorkflowRuntimeSyncRecord::getId)
                            .last("LIMIT 1"));
            if (byWorkflow != null) {
                return byWorkflow;
            }
        }
        if (projectCode == null || workflowCode == null) {
            return null;
        }
        return workflowRuntimeSyncRecordMapper.selectOne(
                Wrappers.<WorkflowRuntimeSyncRecord>lambdaQuery()
                        .eq(WorkflowRuntimeSyncRecord::getProjectCode, projectCode)
                        .eq(WorkflowRuntimeSyncRecord::getWorkflowCode, workflowCode)
                        .orderByDesc(WorkflowRuntimeSyncRecord::getCreatedAt)
                        .orderByDesc(WorkflowRuntimeSyncRecord::getId)
                        .last("LIMIT 1"));
    }

    private DataWorkflow findLocalWorkflow(Long projectCode, Long workflowCode) {
        if (projectCode == null || workflowCode == null) {
            return null;
        }
        return dataWorkflowMapper.selectOne(
                Wrappers.<DataWorkflow>lambdaQuery()
                        .eq(DataWorkflow::getProjectCode, projectCode)
                        .eq(DataWorkflow::getWorkflowCode, workflowCode)
                        .orderByDesc(DataWorkflow::getRuntimeSyncAt)
                        .orderByDesc(DataWorkflow::getUpdatedAt)
                        .orderByDesc(DataWorkflow::getId)
                        .last("LIMIT 1"));
    }

    private List<RuntimeTaskEdge> inferEdgesFromLineage(List<RuntimeTaskDefinition> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        List<RuntimeTaskDefinition> sorted = tasks.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RuntimeTaskDefinition::getTaskCode, Comparator.nullsLast(Long::compareTo)))
                .collect(Collectors.toList());

        List<RuntimeTaskEdge> edges = new ArrayList<>();
        Set<Long> taskCodes = sorted.stream()
                .map(RuntimeTaskDefinition::getTaskCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (RuntimeTaskDefinition downstream : sorted) {
            if (downstream.getTaskCode() == null) {
                continue;
            }
            Set<Long> downstreamReads = new LinkedHashSet<>(downstream.getInputTableIds());
            if (downstreamReads.isEmpty()) {
                continue;
            }
            for (RuntimeTaskDefinition upstream : sorted) {
                if (upstream.getTaskCode() == null || Objects.equals(upstream.getTaskCode(), downstream.getTaskCode())) {
                    continue;
                }
                Set<Long> upstreamWrites = new LinkedHashSet<>(upstream.getOutputTableIds());
                if (upstreamWrites.isEmpty()) {
                    continue;
                }
                Set<Long> intersection = new HashSet<>(upstreamWrites);
                intersection.retainAll(downstreamReads);
                if (!intersection.isEmpty()) {
                    edges.add(new RuntimeTaskEdge(upstream.getTaskCode(), downstream.getTaskCode()));
                }
            }
        }
        List<RuntimeTaskEdge> internalEdges = edges.stream()
                .distinct()
                .sorted(Comparator.comparing(RuntimeTaskEdge::getUpstreamTaskCode)
                        .thenComparing(RuntimeTaskEdge::getDownstreamTaskCode))
                .collect(Collectors.toList());

        Set<Long> tasksWithIncoming = internalEdges.stream()
                .map(RuntimeTaskEdge::getDownstreamTaskCode)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        for (Long taskCode : taskCodes) {
            if (taskCode == null || tasksWithIncoming.contains(taskCode)) {
                continue;
            }
            internalEdges.add(new RuntimeTaskEdge(0L, taskCode));
        }

        return internalEdges.stream()
                .distinct()
                .sorted(Comparator.comparing(RuntimeTaskEdge::getUpstreamTaskCode)
                        .thenComparing(RuntimeTaskEdge::getDownstreamTaskCode))
                .collect(Collectors.toList());
    }

    private Set<String> toEdgeSet(List<RuntimeTaskEdge> edges) {
        if (CollectionUtils.isEmpty(edges)) {
            return Collections.emptySet();
        }
        return edges.stream()
                .filter(Objects::nonNull)
                .filter(edge -> edge.getUpstreamTaskCode() != null && edge.getDownstreamTaskCode() != null)
                .map(edge -> edge.getUpstreamTaskCode() + "->" + edge.getDownstreamTaskCode())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String buildRenamedTaskName(String originalName, Long workflowCode, Long taskCode, Set<String> reservedNames) {
        String baseOriginal = StringUtils.hasText(originalName) ? originalName.trim() : "task_" + taskCode;
        String baseSuffix = "__ds_" + workflowCode + "_" + taskCode;
        String candidate = fitTaskName(baseOriginal, baseSuffix);
        int counter = 2;
        while (reservedNames.contains(candidate)) {
            String indexedSuffix = baseSuffix + "_" + counter;
            candidate = fitTaskName(baseOriginal, indexedSuffix);
            counter++;
        }
        return candidate;
    }

    private String fitTaskName(String originalName, String suffix) {
        int maxLength = 100;
        if (!StringUtils.hasText(suffix)) {
            return truncate(originalName, maxLength);
        }
        if (suffix.length() >= maxLength) {
            return suffix.substring(0, maxLength);
        }
        int leftLength = maxLength - suffix.length();
        String prefix = truncate(originalName, leftLength);
        return prefix + suffix;
    }

    private String generateTaskCode(Long workflowCode, Long taskCode, Set<String> generatedTaskCodes) {
        String base = String.format("ds_%s_%s", workflowCode, taskCode).replaceAll("[^a-zA-Z0-9_]", "_");
        String normalized = normalizeTaskCode(base);
        String candidate = normalized;
        int counter = 2;
        while (generatedTaskCodes.contains(candidate) || existsTaskCode(candidate)) {
            String suffix = "_" + counter;
            candidate = normalizeTaskCode(normalized + suffix);
            counter++;
        }
        generatedTaskCodes.add(candidate);
        return candidate;
    }

    private String normalizeTaskCode(String taskCode) {
        if (!StringUtils.hasText(taskCode)) {
            return "runtime_task";
        }
        String normalized = taskCode.trim();
        if (normalized.length() <= 100) {
            return normalized;
        }
        String digest = shortHash(normalized);
        int keep = Math.max(8, 100 - digest.length() - 1);
        return normalized.substring(0, keep) + "_" + digest;
    }

    private boolean existsTaskCode(String taskCode) {
        Long count = dataTaskMapper.countByTaskCodeIncludingDeleted(taskCode);
        return count != null && count > 0;
    }

    private String shortHash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < 4 && i < bytes.length; i++) {
                builder.append(String.format("%02x", bytes[i]));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法生成 hash", e);
        }
    }

    private String truncate(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private RuntimeSyncIssue buildExceptionIssue(Exception ex) {
        String message = ex.getMessage() != null ? ex.getMessage() : "同步失败";
        if (message.contains("任务已归属其他工作流")) {
            return RuntimeSyncIssue.error(RuntimeSyncErrorCodes.WORKFLOW_BINDING_CONFLICT, message);
        }
        return RuntimeSyncIssue.error(RuntimeSyncErrorCodes.SYNC_FAILED, message);
    }

    private void fillTaskIssue(RuntimeSyncIssue issue, RuntimeWorkflowDefinition definition, RuntimeTaskDefinition task) {
        if (issue == null) {
            return;
        }
        if (definition != null) {
            issue.setWorkflowCode(definition.getWorkflowCode());
            issue.setWorkflowName(definition.getWorkflowName());
        }
        if (task != null) {
            issue.setTaskCode(task.getTaskCode());
            issue.setTaskName(task.getTaskName());
            issue.setNodeType(task.getNodeType());
        }
    }

    private Long resolveExistingWorkflowBinding(Long taskId, PreviewContext context) {
        WorkflowTaskRelation relation = context.getExistingTaskRelationByTaskId().get(taskId);
        return relation != null ? relation.getWorkflowId() : null;
    }

    private String resolveWorkflowName(RuntimeWorkflowDefinition definition) {
        if (definition == null) {
            return "runtime_sync_workflow";
        }
        if (StringUtils.hasText(definition.getWorkflowName())) {
            return definition.getWorkflowName();
        }
        return "ds_workflow_" + definition.getWorkflowCode();
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

    private Integer resolveVersionNo(Long currentVersionId) {
        if (currentVersionId == null) {
            return null;
        }
        WorkflowVersion version = workflowVersionMapper.selectById(currentVersionId);
        return version != null ? version.getVersionNo() : null;
    }

    private LocalDateTime parseFlexibleDateTime(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        for (DateTimeFormatter formatter : DATETIME_FORMATS) {
            try {
                String candidate = raw.trim().replace("Z", "");
                return LocalDateTime.parse(candidate, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private String resolveOperator(String requestOperator) {
        if (StringUtils.hasText(requestOperator)) {
            return requestOperator.trim();
        }
        String userId = UserContextHolder.getCurrentUserId();
        if (StringUtils.hasText(userId)) {
            return userId.trim();
        }
        return "runtime-sync";
    }

    private String resolveIngestMode() {
        if (!StringUtils.hasText(runtimeSyncIngestMode)) {
            return INGEST_MODE_EXPORT_ONLY;
        }
        String configured = runtimeSyncIngestMode.trim().toLowerCase(Locale.ROOT);
        return INGEST_MODE_EXPORT_ONLY.equals(configured) ? configured : INGEST_MODE_EXPORT_ONLY;
    }

    private RuntimeSyncRecordListItem toSyncRecordListItem(WorkflowRuntimeSyncRecord record) {
        RuntimeSyncRecordListItem item = new RuntimeSyncRecordListItem();
        item.setId(record.getId());
        item.setWorkflowId(record.getWorkflowId());
        item.setProjectCode(record.getProjectCode());
        item.setWorkflowCode(record.getWorkflowCode());
        item.setVersionId(record.getVersionId());
        item.setStatus(record.getStatus());
        item.setIngestMode(record.getIngestMode());
        item.setSnapshotHash(record.getSnapshotHash());
        item.setDiffSummary(parseDiffSummary(record.getDiffJson()));
        item.setErrorCode(record.getErrorCode());
        item.setErrorMessage(record.getErrorMessage());
        item.setOperator(record.getOperator());
        item.setCreatedAt(record.getCreatedAt());
        return item;
    }

    private RuntimeDiffSummary parseDiffSummary(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, RuntimeDiffSummary.class);
        } catch (Exception ex) {
            log.warn("Failed to parse runtime sync diff summary", ex);
            return null;
        }
    }

    private String firstIssueCode(List<RuntimeSyncIssue> issues) {
        if (CollectionUtils.isEmpty(issues)) {
            return RuntimeSyncErrorCodes.SYNC_FAILED;
        }
        return StringUtils.hasText(issues.get(0).getCode())
                ? issues.get(0).getCode()
                : RuntimeSyncErrorCodes.SYNC_FAILED;
    }

    private String firstIssueMessage(List<RuntimeSyncIssue> issues) {
        if (CollectionUtils.isEmpty(issues)) {
            return "同步失败";
        }
        return StringUtils.hasText(issues.get(0).getMessage())
                ? issues.get(0).getMessage()
                : "同步失败";
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private void ensureRuntimeSyncEnabled() {
        if (!runtimeSyncEnabled) {
            throw new IllegalStateException("运行态同步功能未开启");
        }
    }

    @Data
    private static class PreviewContext {
        private Long projectCode;
        private Long workflowCode;
        private String ingestMode = INGEST_MODE_EXPORT_ONLY;
        private RuntimeWorkflowDefinition definition;
        private DataWorkflow localWorkflow;
        private WorkflowRuntimeDiffService.RuntimeSnapshot snapshot;
        private RuntimeDiffSummary diffSummary;
        private List<RuntimeSyncIssue> errors = new ArrayList<>();
        private List<RuntimeSyncIssue> warnings = new ArrayList<>();
        private List<RuntimeTaskRenamePlan> renamePlan = new ArrayList<>();
        private Boolean relationDecisionRequired = false;
        private RuntimeRelationCompareDetail relationCompareDetail;
        private Map<Long, DolphinDatasourceOption> datasourceById = new HashMap<>();
        private Map<Long, DataTask> existingTaskByRuntimeCode = new LinkedHashMap<>();
        private Map<Long, WorkflowTaskRelation> existingTaskRelationByTaskId = new LinkedHashMap<>();
        private Map<Long, String> resolvedTaskNameByCode = new LinkedHashMap<>();
        private List<RuntimeTaskEdge> declaredEdges = new ArrayList<>();
        private List<RuntimeTaskEdge> inferredEdges = new ArrayList<>();
        private List<RuntimeTaskEdge> selectedEdges = new ArrayList<>();
    }

    @Data
    private static class SyncTransactionResult {
        private Long workflowId;
        private Integer versionNo;
        private Long syncRecordId;
    }
}
