package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.workflow.WorkflowApprovalRequest;
import com.onedata.portal.dto.workflow.WorkflowPublishPreviewResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRepairIssue;
import com.onedata.portal.dto.workflow.WorkflowPublishRepairRequest;
import com.onedata.portal.dto.workflow.WorkflowPublishRepairResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.dto.dolphin.DolphinSchedule;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffFieldChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncIssue;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeRelationChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowSchedule;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
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
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工作流发布 orchestrator（Phase 1：记录 + 状态流转）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowPublishService {

    private static final String PUBLISH_DIFF_CONFIRM_REQUIRED = "PUBLISH_DIFF_CONFIRM_REQUIRED";
    private static final String PUBLISH_PREVIEW_FAILED = "PUBLISH_PREVIEW_FAILED";
    private static final String PUBLISH_RUNTIME_WORKFLOW_NOT_FOUND = "PUBLISH_RUNTIME_WORKFLOW_NOT_FOUND";
    private static final String PUBLISH_FIRST_DEPLOY = "PUBLISH_FIRST_DEPLOY";
    private static final String PUBLISH_METADATA_REPAIR_RECOMMENDED = "PUBLISH_METADATA_REPAIR_RECOMMENDED";
    private static final DateTimeFormatter SCHEDULE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter[] SCHEDULE_INPUT_FORMATTERS = new DateTimeFormatter[] {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };
    private static final Set<String> PUBLISH_NOISE_TASK_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList(
                    "task.inputTableIds",
                    "task.outputTableIds",
                    "task.taskVersion")));
    private static final Set<String> PUBLISH_NOISE_SCHEDULE_FIELDS = Collections.unmodifiableSet(
            new LinkedHashSet<>(java.util.Arrays.asList(
                    "schedule.releaseState")));

    private final WorkflowPublishRecordMapper publishRecordMapper;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final DataTaskMapper dataTaskMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final DolphinRuntimeDefinitionService runtimeDefinitionService;
    private final WorkflowRuntimeDiffService runtimeDiffService;
    private final WorkflowDeployService workflowDeployService;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    public WorkflowPublishPreviewResponse previewPublish(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        return buildPublishPreview(workflow);
    }

    @Transactional
    public WorkflowPublishRepairResponse repairPublishMetadata(Long workflowId, WorkflowPublishRepairRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }

        String operator = request != null && StringUtils.hasText(request.getOperator())
                ? request.getOperator().trim()
                : "system";
        RuntimeWorkflowDefinition runtimeDefinition = null;
        if (workflow.getWorkflowCode() != null && workflow.getWorkflowCode() > 0) {
            runtimeDefinition = loadRuntimeDefinitionFromExport(workflow);
        }

        WorkflowPublishRepairResponse response = new WorkflowPublishRepairResponse();
        response.setWorkflowId(workflowId);
        response.setWorkflowCode(workflow.getWorkflowCode());

        boolean workflowChanged = repairWorkflowMetadataFromRuntime(workflow, runtimeDefinition, operator, response);
        int updatedTaskCount = repairTaskMetadataFromRuntime(workflow, runtimeDefinition, operator);
        boolean definitionChanged = runtimeDefinition != null
                ? repairDefinitionMetadataFromRuntime(workflow, runtimeDefinition, operator, response)
                : repairDefinitionMetadataFromCatalog(workflow, operator, response);
        workflowService.normalizeAndPersistMetadata(workflowId, operator);
        DataWorkflow repairedWorkflow = dataWorkflowMapper.selectById(workflowId);
        ensureBlockingRepairIssuesResolved(repairedWorkflow);
        response.setUpdatedTaskCount(updatedTaskCount);
        response.setRepaired(workflowChanged || updatedTaskCount > 0 || definitionChanged);
        return response;
    }

    @Transactional
    public WorkflowPublishRecord publish(Long workflowId, WorkflowPublishRequest request) {
        if (!StringUtils.hasText(request.getOperation())) {
            throw new IllegalArgumentException("operation is required");
        }
        String operation = request.getOperation().trim().toLowerCase();
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        if ("deploy".equals(operation)) {
            workflow = workflowService.syncCurrentVersion(workflowId, request.getOperator(), "publish_auto_save");
        }
        Long versionId = resolvePublishVersionId(workflow, request);
        WorkflowVersion version = versionId == null ? null : workflowVersionMapper.selectById(versionId);
        if (version == null) {
            throw new IllegalArgumentException("Workflow version not found for publish");
        }

        WorkflowPublishRecord record = new WorkflowPublishRecord();
        record.setWorkflowId(workflowId);
        record.setVersionId(version.getId());
        record.setOperation(operation);
        record.setTargetEngine("dolphin");
        record.setDolphinConfigId(workflow.getDolphinConfigId());
        record.setStatus("pending");
        record.setOperator(request.getOperator());
        publishRecordMapper.insert(record);

        try {
            log.info("Workflow {} publish operation {} initiated for version {}", workflowId, record.getOperation(),
                    version.getVersionNo());
            switch (record.getOperation()) {
                case "deploy":
                    handleDeploy(workflow, version, record, request);
                    break;
                case "online":
                case "offline":
                    invokeDolphin(workflow, record);
                    applyWorkflowStatus(workflow, record);
                    record.setStatus("success");
                    record.setEngineWorkflowCode(workflow.getWorkflowCode());
                    break;
                default:
                    log.warn("Unsupported publish operation {}", record.getOperation());
                    record.setStatus("failed");
                    break;
            }
            publishRecordMapper.updateById(record);
            dataWorkflowMapper.updateById(workflow);
            return record;
        } catch (RuntimeException ex) {
            record.setStatus("failed");
            record.setLog(toJson(Collections.singletonMap("error", ex.getMessage())));
            publishRecordMapper.updateById(record);
            workflow.setPublishStatus("failed");
            dataWorkflowMapper.updateById(workflow);
            throw ex;
        }
    }

    private WorkflowPublishPreviewResponse buildPublishPreview(DataWorkflow workflow) {
        WorkflowPublishPreviewResponse response = new WorkflowPublishPreviewResponse();
        response.setWorkflowId(workflow.getId());
        response.setProjectCode(workflow.getProjectCode());
        boolean firstDeploy = isFirstDeploy(workflow);
        response.setWorkflowCode(firstDeploy ? null : workflow.getWorkflowCode());

        RuntimeWorkflowDefinition platformDefinition = buildPlatformDefinition(workflow);
        if (CollectionUtils.isEmpty(platformDefinition.getTasks())) {
            RuntimeSyncIssue issue = RuntimeSyncIssue.error(PUBLISH_PREVIEW_FAILED, "工作流未绑定任何任务，无法发布");
            issue.setWorkflowCode(workflow.getWorkflowCode());
            issue.setWorkflowName(workflow.getWorkflowName());
            response.getErrors().add(issue);
            response.setCanPublish(false);
            return response;
        }

        RuntimeWorkflowDefinition runtimeDefinition = null;
        if (firstDeploy) {
            RuntimeSyncIssue warning = RuntimeSyncIssue.warning(
                    PUBLISH_FIRST_DEPLOY,
                    "Dolphin 侧尚无 workflowCode，当前发布将执行首次部署");
            warning.setWorkflowName(workflow.getWorkflowName());
            response.getWarnings().add(warning);
        } else {
            try {
                runtimeDefinition = loadRuntimeDefinitionFromExport(workflow);
            } catch (Exception ex) {
                if (isRuntimeWorkflowMissing(ex.getMessage())) {
                    RuntimeSyncIssue warning = RuntimeSyncIssue.warning(
                            PUBLISH_RUNTIME_WORKFLOW_NOT_FOUND,
                            "Dolphin 侧未找到同编码工作流，将按首次部署处理: " + ex.getMessage());
                    warning.setWorkflowCode(workflow.getWorkflowCode());
                    warning.setWorkflowName(workflow.getWorkflowName());
                    response.getWarnings().add(warning);
                } else {
                    RuntimeSyncIssue issue = RuntimeSyncIssue.error(
                            PUBLISH_PREVIEW_FAILED,
                            "读取 Dolphin 运行态定义失败: " + ex.getMessage());
                    issue.setWorkflowCode(workflow.getWorkflowCode());
                    issue.setWorkflowName(workflow.getWorkflowName());
                    response.getErrors().add(issue);
                    response.setCanPublish(false);
                    return response;
                }
            }
        }

        List<RuntimeTaskEdge> platformEdges = normalizePublishEdges(inferEdgesFromLineage(platformDefinition.getTasks()));
        WorkflowRuntimeDiffService.RuntimeSnapshot platformSnapshot = runtimeDiffService.buildSnapshot(
                platformDefinition,
                platformEdges);
        String baselineSnapshotJson = null;
        if (runtimeDefinition != null) {
            List<RuntimeTaskEdge> runtimeEdges = CollectionUtils.isEmpty(runtimeDefinition.getExplicitEdges())
                    ? inferEdgesFromLineage(runtimeDefinition.getTasks())
                    : runtimeDefinition.getExplicitEdges();
            runtimeEdges = normalizePublishEdges(runtimeEdges);
            WorkflowRuntimeDiffService.RuntimeSnapshot runtimeSnapshot = runtimeDiffService.buildSnapshot(
                    runtimeDefinition,
                    runtimeEdges);
            baselineSnapshotJson = runtimeSnapshot.getSnapshotJson();
        }
        RuntimeDiffSummary rawDiffSummary = runtimeDiffService.buildDiff(baselineSnapshotJson, platformSnapshot);
        if (!hasRuntimeSchedule(runtimeDefinition) && rawDiffSummary != null) {
            // Workflow 没有运行态 scheduleId（未创建调度）时，schedule.* 仅反映平台侧配置，不应作为发布修复差异。
            rawDiffSummary.setScheduleChanges(Collections.emptyList());
        }
        // 发布确认负责承载正常定义差异；修复元数据仅保留真实的元数据缺失问题，
        // 避免把 SQL / 调度等合理发布变更误判成“先修复元数据”。
        response.setRepairIssues(buildPublishMetadataRepairIssues(workflow, platformDefinition));
        RuntimeDiffSummary diffSummary = normalizePublishDiffSummary(rawDiffSummary);
        response.setDiffSummary(diffSummary);
        response.setRequireConfirm(diffSummary != null && Boolean.TRUE.equals(diffSummary.getChanged()));
        response.setCanPublish(response.getErrors().isEmpty());
        return response;
    }

    private RuntimeWorkflowDefinition buildPlatformDefinition(DataWorkflow workflow) {
        RuntimeWorkflowDefinition definition = new RuntimeWorkflowDefinition();
        definition.setProjectCode(workflow.getProjectCode());
        definition.setWorkflowCode(isFirstDeploy(workflow) ? null : workflow.getWorkflowCode());
        definition.setWorkflowName(workflow.getWorkflowName());
        definition.setDescription(workflow.getDescription());
        definition.setReleaseState(mapWorkflowReleaseState(workflow.getStatus()));
        definition.setGlobalParams(workflow.getGlobalParams());
        definition.setSchedule(buildPlatformSchedule(workflow));

        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflow.getId())
                        .orderByAsc(WorkflowTaskRelation::getId));
        if (CollectionUtils.isEmpty(relations)) {
            definition.setTasks(Collections.emptyList());
            return definition;
        }

        List<Long> taskIds = relations.stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(taskIds)) {
            definition.setTasks(Collections.emptyList());
            return definition;
        }

        Map<Long, DataTask> taskById = dataTaskMapper.selectBatchIds(taskIds).stream()
                .filter(Objects::nonNull)
                .filter(task -> task.getId() != null)
                .collect(Collectors.toMap(DataTask::getId, item -> item, (left, right) -> left));
        Map<Long, DefinitionTaskMetadata> definitionTaskMetadataByCode = loadDefinitionTaskMetadata(
                workflow.getDefinitionJson());
        Map<Long, List<Long>> inputTableIdsByTask = loadTaskTableRelationMap(taskIds, "read");
        Map<Long, List<Long>> outputTableIdsByTask = loadTaskTableRelationMap(taskIds, "write");
        Map<Long, DefinitionTaskMetadata> metadataByCode = loadDefinitionTaskMetadata(workflow.getDefinitionJson());

        List<RuntimeTaskDefinition> tasks = new ArrayList<>();
        for (WorkflowTaskRelation relation : relations) {
            if (relation == null || relation.getTaskId() == null) {
                continue;
            }
            DataTask task = taskById.get(relation.getTaskId());
            if (task == null) {
                continue;
            }
            RuntimeTaskDefinition runtimeTask = new RuntimeTaskDefinition();
            Long runtimeTaskCode = resolveTaskCodeForDiff(task);
            runtimeTask.setTaskCode(runtimeTaskCode);
            runtimeTask.setTaskVersion(task.getDolphinTaskVersion());
            runtimeTask.setTaskName(task.getTaskName());
            runtimeTask.setDescription(task.getTaskDesc());
            runtimeTask.setNodeType(resolveTaskNodeType(task));
            runtimeTask.setSql(task.getTaskSql());
            runtimeTask.setDatasourceName(task.getDatasourceName());
            runtimeTask.setDatasourceType(task.getDatasourceType());
            if (StringUtils.hasText(task.getDolphinFlag())) {
                runtimeTask.setFlag(normalizeDolphinFlag(task.getDolphinFlag()));
            }
            DefinitionTaskMetadata metadata = runtimeTask.getTaskCode() != null
                    ? definitionTaskMetadataByCode.get(runtimeTask.getTaskCode())
                    : null;
            if (metadata != null) {
                runtimeTask.setDatasourceId(metadata.getDatasourceId());
                runtimeTask.setTaskGroupId(metadata.getTaskGroupId());
            }
            runtimeTask.setTaskGroupName(StringUtils.hasText(task.getTaskGroupName())
                    ? task.getTaskGroupName()
                    : workflow.getTaskGroupName());
            DefinitionTaskMetadata taskMetadata = runtimeTaskCode != null ? metadataByCode.get(runtimeTaskCode) : null;
            if (taskMetadata != null) {
                runtimeTask.setTaskGroupId(taskMetadata.getTaskGroupId());
            }
            runtimeTask.setRetryTimes(task.getRetryTimes());
            runtimeTask.setRetryInterval(task.getRetryInterval());
            runtimeTask.setTimeoutSeconds(task.getTimeoutSeconds());
            if (task.getPriority() != null) {
                runtimeTask.setTaskPriority(mapTaskPriorityForDiff(task.getPriority()));
            }
            runtimeTask.setInputTableIds(inputTableIdsByTask.getOrDefault(task.getId(), Collections.emptyList()));
            runtimeTask.setOutputTableIds(outputTableIdsByTask.getOrDefault(task.getId(), Collections.emptyList()));
            tasks.add(runtimeTask);
        }
        definition.setTasks(tasks);
        return definition;
    }

    private RuntimeWorkflowDefinition loadRuntimeDefinitionFromExport(DataWorkflow workflow) {
        if (workflow.getDolphinConfigId() == null) {
            return runtimeDefinitionService.loadRuntimeDefinitionFromExport(
                    workflow.getProjectCode(),
                    workflow.getWorkflowCode());
        }
        return runtimeDefinitionService.loadRuntimeDefinitionFromExport(
                workflow.getDolphinConfigId(),
                workflow.getProjectCode(),
                workflow.getWorkflowCode());
    }

    private boolean isFirstDeploy(DataWorkflow workflow) {
        if (workflow == null || workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            return true;
        }
        return "never".equalsIgnoreCase(normalizeText(workflow.getPublishStatus()));
    }

    private RuntimeWorkflowSchedule buildPlatformSchedule(DataWorkflow workflow) {
        RuntimeWorkflowSchedule schedule = new RuntimeWorkflowSchedule();
        schedule.setScheduleId(workflow.getDolphinScheduleId());
        schedule.setReleaseState(workflow.getScheduleState());
        schedule.setCrontab(workflow.getScheduleCron());
        schedule.setTimezoneId(workflow.getScheduleTimezone());
        schedule.setStartTime(toDateTimeText(workflow.getScheduleStartTime()));
        schedule.setEndTime(toDateTimeText(workflow.getScheduleEndTime()));
        schedule.setFailureStrategy(workflow.getScheduleFailureStrategy());
        schedule.setWarningType(workflow.getScheduleWarningType());
        schedule.setWarningGroupId(workflow.getScheduleWarningGroupId());
        schedule.setProcessInstancePriority(workflow.getScheduleProcessInstancePriority());
        schedule.setWorkerGroup(workflow.getScheduleWorkerGroup());
        schedule.setTenantCode(workflow.getScheduleTenantCode());
        schedule.setEnvironmentCode(workflow.getScheduleEnvironmentCode());
        return schedule;
    }

    private String mapTaskPriorityForDiff(Integer priority) {
        if (priority == null) {
            return null;
        }
        if (priority >= 9) {
            return "HIGHEST";
        }
        if (priority >= 7) {
            return "HIGH";
        }
        if (priority >= 5) {
            return "MEDIUM";
        }
        if (priority >= 3) {
            return "LOW";
        }
        return "LOWEST";
    }

    private Map<Long, List<Long>> loadTaskTableRelationMap(List<Long> taskIds, String relationType) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return Collections.emptyMap();
        }
        List<TableTaskRelation> relations = tableTaskRelationMapper.selectList(
                Wrappers.<TableTaskRelation>lambdaQuery()
                        .in(TableTaskRelation::getTaskId, taskIds)
                        .eq(TableTaskRelation::getRelationType, relationType)
                        .orderByAsc(TableTaskRelation::getTaskId)
                        .orderByAsc(TableTaskRelation::getTableId));
        if (CollectionUtils.isEmpty(relations)) {
            return Collections.emptyMap();
        }
        Map<Long, LinkedHashSet<Long>> grouped = new LinkedHashMap<>();
        for (TableTaskRelation relation : relations) {
            if (relation == null || relation.getTaskId() == null || relation.getTableId() == null) {
                continue;
            }
            grouped.computeIfAbsent(relation.getTaskId(), key -> new LinkedHashSet<>()).add(relation.getTableId());
        }
        Map<Long, List<Long>> result = new LinkedHashMap<>();
        grouped.forEach((taskId, tableIds) -> result.put(taskId, new ArrayList<>(tableIds)));
        return result;
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
                Set<Long> intersection = new LinkedHashSet<>(upstreamWrites);
                intersection.retainAll(downstreamReads);
                if (!intersection.isEmpty()) {
                    edges.add(new RuntimeTaskEdge(upstream.getTaskCode(), downstream.getTaskCode()));
                }
            }
        }
        return edges.stream()
                .distinct()
                .sorted(Comparator.comparing(RuntimeTaskEdge::getUpstreamTaskCode)
                        .thenComparing(RuntimeTaskEdge::getDownstreamTaskCode))
                .collect(Collectors.toList());
    }

    private List<RuntimeTaskEdge> normalizePublishEdges(List<RuntimeTaskEdge> edges) {
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
            if (post <= 0 || pre < 0) {
                continue;
            }
            // Dolphin 运行态会自动补 0->task 的入口边，发布预检只比较真实任务依赖边。
            if (pre == 0L) {
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

    private Long resolveTaskCodeForDiff(DataTask task) {
        if (task == null) {
            return null;
        }
        if (task.getDolphinTaskCode() != null && task.getDolphinTaskCode() > 0) {
            return task.getDolphinTaskCode();
        }
        return task.getId();
    }

    private String resolveTaskNodeType(DataTask task) {
        if (task == null) {
            return null;
        }
        if (StringUtils.hasText(task.getDolphinNodeType())) {
            return task.getDolphinNodeType();
        }
        return null;
    }

    private String mapWorkflowReleaseState(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        if ("online".equalsIgnoreCase(status)) {
            return "ONLINE";
        }
        if ("offline".equalsIgnoreCase(status)) {
            return "OFFLINE";
        }
        return status;
    }

    private Long resolvePublishVersionId(DataWorkflow workflow, WorkflowPublishRequest request) {
        if (request != null && request.getVersionId() != null) {
            return request.getVersionId();
        }
        String operation = request != null ? request.getOperation() : null;
        if (StringUtils.hasText(operation)) {
            String normalized = operation.trim().toLowerCase();
            if (("online".equals(normalized) || "offline".equals(normalized))
                    && workflow != null
                    && workflow.getLastPublishedVersionId() != null) {
                return workflow.getLastPublishedVersionId();
            }
        }
        return workflow != null ? workflow.getCurrentVersionId() : null;
    }

    private String toDateTimeText(LocalDateTime value) {
        return value != null ? value.format(SCHEDULE_TIME_FORMATTER) : null;
    }

    private void addRepairIssue(List<WorkflowPublishRepairIssue> collector,
            Set<String> dedupe,
            WorkflowPublishRepairIssue issue) {
        if (issue == null) {
            return;
        }
        String key = String.format("%s|%s|%s|%s|%s",
                normalizeDiffValue(issue.getField()),
                issue.getTaskCode(),
                normalizeDiffValue(issue.getBefore()),
                normalizeDiffValue(issue.getAfter()),
                normalizeDiffValue(issue.getMessage()));
        if (dedupe.add(key)) {
            collector.add(issue);
        }
    }

    private RuntimeDiffSummary normalizePublishDiffSummary(RuntimeDiffSummary summary) {
        if (summary == null) {
            return null;
        }

        if (!CollectionUtils.isEmpty(summary.getTaskModified())) {
            List<RuntimeTaskChange> retained = new ArrayList<>();
            for (RuntimeTaskChange taskChange : summary.getTaskModified()) {
                if (taskChange == null) {
                    continue;
                }
                if (CollectionUtils.isEmpty(taskChange.getFieldChanges())) {
                    retained.add(taskChange);
                    continue;
                }
                List<RuntimeDiffFieldChange> filteredFieldChanges = taskChange.getFieldChanges().stream()
                        .filter(this::isMeaningfulPublishTaskFieldChange)
                        .collect(Collectors.toList());
                if (!filteredFieldChanges.isEmpty()) {
                    taskChange.setFieldChanges(filteredFieldChanges);
                    retained.add(taskChange);
                }
            }
            summary.setTaskModified(retained);
        }

        if (!CollectionUtils.isEmpty(summary.getEdgeAdded())) {
            List<RuntimeRelationChange> filtered = summary.getEdgeAdded().stream()
                    .filter(this::isMeaningfulPublishEdgeChange)
                    .collect(Collectors.toList());
            summary.setEdgeAdded(filtered);
        }

        if (!CollectionUtils.isEmpty(summary.getEdgeRemoved())) {
            List<RuntimeRelationChange> filtered = summary.getEdgeRemoved().stream()
                    .filter(this::isMeaningfulPublishEdgeChange)
                    .collect(Collectors.toList());
            summary.setEdgeRemoved(filtered);
        }

        if (!CollectionUtils.isEmpty(summary.getScheduleChanges())) {
            List<RuntimeDiffFieldChange> filteredScheduleChanges = summary.getScheduleChanges().stream()
                    .filter(this::isMeaningfulPublishScheduleFieldChange)
                    .collect(Collectors.toList());
            summary.setScheduleChanges(filteredScheduleChanges);
        }

        summary.setChanged(hasMeaningfulDiffChanges(summary));
        return summary;
    }

    private boolean isMeaningfulPublishTaskFieldChange(RuntimeDiffFieldChange change) {
        if (change == null || !StringUtils.hasText(change.getField())) {
            return false;
        }
        return !PUBLISH_NOISE_TASK_FIELDS.contains(change.getField().trim())
                && hasActualValueChanged(change);
    }

    private boolean isMeaningfulPublishScheduleFieldChange(RuntimeDiffFieldChange change) {
        if (change == null || !StringUtils.hasText(change.getField())) {
            return false;
        }
        String field = change.getField().trim();
        return !PUBLISH_NOISE_SCHEDULE_FIELDS.contains(field)
                && hasActualValueChanged(change);
    }

    private boolean isMeaningfulPublishEdgeChange(RuntimeRelationChange change) {
        if (change == null) {
            return false;
        }
        Long preTaskCode = change.getPreTaskCode();
        Long postTaskCode = change.getPostTaskCode();
        return preTaskCode != null
                && preTaskCode >= 0
                && postTaskCode != null
                && postTaskCode > 0;
    }

    private boolean hasActualValueChanged(RuntimeDiffFieldChange change) {
        if (change == null) {
            return false;
        }
        return !Objects.equals(
                normalizeDiffValue(change.getBefore()),
                normalizeDiffValue(change.getAfter()));
    }

    private boolean hasMeaningfulDiffChanges(RuntimeDiffSummary summary) {
        if (summary == null) {
            return false;
        }
        return !CollectionUtils.isEmpty(summary.getWorkflowFieldChanges())
                || !CollectionUtils.isEmpty(summary.getTaskAdded())
                || !CollectionUtils.isEmpty(summary.getTaskRemoved())
                || !CollectionUtils.isEmpty(summary.getTaskModified())
                || !CollectionUtils.isEmpty(summary.getEdgeAdded())
                || !CollectionUtils.isEmpty(summary.getEdgeRemoved())
                || !CollectionUtils.isEmpty(summary.getScheduleChanges());
    }

    private boolean hasRuntimeSchedule(RuntimeWorkflowDefinition runtimeDefinition) {
        if (runtimeDefinition == null || runtimeDefinition.getSchedule() == null) {
            return false;
        }
        Long scheduleId = runtimeDefinition.getSchedule().getScheduleId();
        return scheduleId != null && scheduleId > 0;
    }

    private String normalizeDiffValue(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeDolphinFlag(String value) {
        if (!StringUtils.hasText(value)) {
            return "YES";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "NO".equals(normalized) ? "NO" : "YES";
    }

    private List<WorkflowPublishRepairIssue> buildPublishMetadataRepairIssues(DataWorkflow workflow,
            RuntimeWorkflowDefinition platformDefinition) {
        if (workflow == null || platformDefinition == null || CollectionUtils.isEmpty(platformDefinition.getTasks())) {
            return Collections.emptyList();
        }
        Map<Long, DefinitionTaskMetadata> metadataByCode = loadDefinitionTaskMetadata(workflow.getDefinitionJson());
        if (metadataByCode.isEmpty()) {
            return Collections.emptyList();
        }
        List<WorkflowPublishRepairIssue> result = new ArrayList<>();
        Set<String> dedupe = new LinkedHashSet<>();
        for (RuntimeTaskDefinition task : platformDefinition.getTasks()) {
            if (task == null || task.getTaskCode() == null || task.getTaskCode() <= 0) {
                continue;
            }
            DefinitionTaskMetadata metadata = metadataByCode.get(task.getTaskCode());
            if ("SQL".equalsIgnoreCase(task.getNodeType())) {
                Long datasourceId = metadata != null ? metadata.getDatasourceId() : null;
                if (datasourceId == null || datasourceId <= 0) {
                    WorkflowPublishRepairIssue issue = new WorkflowPublishRepairIssue();
                    issue.setCode(PUBLISH_METADATA_REPAIR_RECOMMENDED);
                    issue.setSeverity("WARNING");
                    issue.setRepairable(true);
                    issue.setField("task.datasourceId");
                    issue.setTaskCode(task.getTaskCode());
                    issue.setTaskName(task.getTaskName());
                    issue.setMessage(String.format(
                            "任务[%s(%s)] 缺少 datasourceId 元数据，建议先修复元数据再发布",
                            task.getTaskName() == null ? "-" : task.getTaskName(),
                            task.getTaskCode()));
                    addRepairIssue(result, dedupe, issue);
                }
            }
            String taskGroupName = normalizeDiffValue(task.getTaskGroupName());
            Integer taskGroupId = metadata != null ? metadata.getTaskGroupId() : null;
            if (StringUtils.hasText(taskGroupName) && (taskGroupId == null || taskGroupId <= 0)) {
                WorkflowPublishRepairIssue issue = new WorkflowPublishRepairIssue();
                issue.setCode(PUBLISH_METADATA_REPAIR_RECOMMENDED);
                issue.setSeverity("WARNING");
                issue.setRepairable(true);
                issue.setField("task.taskGroupId");
                issue.setTaskCode(task.getTaskCode());
                issue.setTaskName(task.getTaskName());
                issue.setMessage(String.format(
                        "任务[%s(%s)] 缺少 taskGroupId 元数据(任务组=%s)，建议先修复元数据再发布",
                        task.getTaskName() == null ? "-" : task.getTaskName(),
                        task.getTaskCode(),
                        taskGroupName));
                addRepairIssue(result, dedupe, issue);
            }
        }
        return result;
    }

    private Map<Long, DefinitionTaskMetadata> loadDefinitionTaskMetadata(String definitionJson) {
        if (!StringUtils.hasText(definitionJson)) {
            return Collections.emptyMap();
        }
        try {
            JsonNode rootNode = objectMapper.readTree(definitionJson);
            JsonNode taskListNode = rootNode != null ? rootNode.get("taskDefinitionList") : null;
            if (!(taskListNode instanceof ArrayNode)) {
                return Collections.emptyMap();
            }
            Map<Long, DefinitionTaskMetadata> result = new LinkedHashMap<>();
            for (JsonNode taskNode : (ArrayNode) taskListNode) {
                Long taskCode = readLong(taskNode, "taskCode", "code");
                if (taskCode == null || taskCode <= 0) {
                    taskCode = readLong(taskNode != null ? taskNode.get("xPlatformTaskMeta") : null, "dolphinTaskCode");
                }
                if (taskCode == null || taskCode <= 0) {
                    continue;
                }
                JsonNode taskParams = taskNode != null ? taskNode.get("taskParams") : null;
                DefinitionTaskMetadata metadata = new DefinitionTaskMetadata();
                metadata.setDatasourceId(readLong(taskParams, "datasourceId", "datasource"));
                metadata.setTaskGroupId(readInt(taskNode, "taskGroupId"));
                result.put(taskCode, metadata);
            }
            return result;
        } catch (Exception ex) {
            return Collections.emptyMap();
        }
    }

    private boolean repairWorkflowMetadataFromRuntime(DataWorkflow workflow,
            RuntimeWorkflowDefinition runtimeDefinition,
            String operator,
            WorkflowPublishRepairResponse response) {
        if (workflow == null || runtimeDefinition == null) {
            return false;
        }
        boolean changed = false;
        List<String> changedFields = response != null ? response.getUpdatedWorkflowFields() : new ArrayList<>();

        if (runtimeDefinition.getProjectCode() != null
                && !Objects.equals(workflow.getProjectCode(), runtimeDefinition.getProjectCode())) {
            workflow.setProjectCode(runtimeDefinition.getProjectCode());
            changed = true;
            changedFields.add("workflow.projectCode");
        }
        if (runtimeDefinition.getWorkflowCode() != null
                && !Objects.equals(workflow.getWorkflowCode(), runtimeDefinition.getWorkflowCode())) {
            workflow.setWorkflowCode(runtimeDefinition.getWorkflowCode());
            changed = true;
            changedFields.add("workflow.workflowCode");
        }

        RuntimeWorkflowSchedule schedule = runtimeDefinition.getSchedule();
        if (schedule != null) {
            changed |= updateWorkflowScheduleField(workflow.getDolphinScheduleId(), schedule.getScheduleId(),
                    workflow::setDolphinScheduleId, changedFields, "schedule.scheduleId");
            changed |= updateWorkflowScheduleField(workflow.getScheduleState(), schedule.getReleaseState(),
                    workflow::setScheduleState, changedFields, "schedule.releaseState");
            changed |= updateWorkflowScheduleField(workflow.getScheduleCron(), schedule.getCrontab(),
                    workflow::setScheduleCron, changedFields, "schedule.crontab");
            changed |= updateWorkflowScheduleField(workflow.getScheduleTimezone(), schedule.getTimezoneId(),
                    workflow::setScheduleTimezone, changedFields, "schedule.timezoneId");
            changed |= updateWorkflowScheduleField(workflow.getScheduleFailureStrategy(), schedule.getFailureStrategy(),
                    workflow::setScheduleFailureStrategy, changedFields, "schedule.failureStrategy");
            changed |= updateWorkflowScheduleField(workflow.getScheduleWarningType(), schedule.getWarningType(),
                    workflow::setScheduleWarningType, changedFields, "schedule.warningType");
            changed |= updateWorkflowScheduleField(workflow.getScheduleWarningGroupId(), schedule.getWarningGroupId(),
                    workflow::setScheduleWarningGroupId, changedFields, "schedule.warningGroupId");
            changed |= updateWorkflowScheduleField(workflow.getScheduleProcessInstancePriority(),
                    schedule.getProcessInstancePriority(),
                    workflow::setScheduleProcessInstancePriority, changedFields, "schedule.processInstancePriority");
            changed |= updateWorkflowScheduleField(workflow.getScheduleWorkerGroup(), schedule.getWorkerGroup(),
                    workflow::setScheduleWorkerGroup, changedFields, "schedule.workerGroup");
            changed |= updateWorkflowScheduleField(workflow.getScheduleTenantCode(), schedule.getTenantCode(),
                    workflow::setScheduleTenantCode, changedFields, "schedule.tenantCode");
            changed |= updateWorkflowScheduleField(workflow.getScheduleEnvironmentCode(), schedule.getEnvironmentCode(),
                    workflow::setScheduleEnvironmentCode, changedFields, "schedule.environmentCode");

            LocalDateTime runtimeStart = parseScheduleDateTime(schedule.getStartTime());
            LocalDateTime runtimeEnd = parseScheduleDateTime(schedule.getEndTime());
            changed |= updateWorkflowScheduleField(workflow.getScheduleStartTime(), runtimeStart,
                    workflow::setScheduleStartTime, changedFields, "schedule.startTime");
            changed |= updateWorkflowScheduleField(workflow.getScheduleEndTime(), runtimeEnd,
                    workflow::setScheduleEndTime, changedFields, "schedule.endTime");
        }

        if (changed) {
            workflow.setUpdatedBy(operator);
            dataWorkflowMapper.updateById(workflow);
        }
        return changed;
    }

    private int repairTaskMetadataFromRuntime(DataWorkflow workflow,
            RuntimeWorkflowDefinition runtimeDefinition,
            String operator) {
        if (workflow == null || runtimeDefinition == null || CollectionUtils.isEmpty(runtimeDefinition.getTasks())) {
            return 0;
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflow.getId()));
        if (CollectionUtils.isEmpty(relations)) {
            return 0;
        }
        List<Long> taskIds = relations.stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(taskIds)) {
            return 0;
        }
        Map<Long, DataTask> taskByRuntimeCode = dataTaskMapper.selectBatchIds(taskIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(
                        this::resolveTaskCodeForDiff,
                        task -> task,
                        (left, right) -> left,
                        LinkedHashMap::new));

        int updated = 0;
        for (RuntimeTaskDefinition runtimeTask : runtimeDefinition.getTasks()) {
            if (runtimeTask == null || runtimeTask.getTaskCode() == null) {
                continue;
            }
            DataTask localTask = taskByRuntimeCode.get(runtimeTask.getTaskCode());
            if (localTask == null) {
                continue;
            }
            boolean changed = false;
            if (runtimeTask.getTaskVersion() != null
                    && !Objects.equals(localTask.getDolphinTaskVersion(), runtimeTask.getTaskVersion())) {
                localTask.setDolphinTaskVersion(runtimeTask.getTaskVersion());
                changed = true;
            }
            if (StringUtils.hasText(runtimeTask.getDatasourceName())
                    && !Objects.equals(normalizeDiffValue(localTask.getDatasourceName()),
                            normalizeDiffValue(runtimeTask.getDatasourceName()))) {
                localTask.setDatasourceName(runtimeTask.getDatasourceName().trim());
                changed = true;
            }
            if (StringUtils.hasText(runtimeTask.getDatasourceType())
                    && !Objects.equals(normalizeDiffValue(localTask.getDatasourceType()),
                            normalizeDiffValue(runtimeTask.getDatasourceType()))) {
                localTask.setDatasourceType(runtimeTask.getDatasourceType().trim());
                changed = true;
            }
            if (StringUtils.hasText(runtimeTask.getTaskGroupName())
                    && !Objects.equals(normalizeDiffValue(localTask.getTaskGroupName()),
                            normalizeDiffValue(runtimeTask.getTaskGroupName()))) {
                localTask.setTaskGroupName(runtimeTask.getTaskGroupName().trim());
                changed = true;
            }
            if (!Objects.equals(normalizeDolphinFlag(localTask.getDolphinFlag()),
                    normalizeDolphinFlag(runtimeTask.getFlag()))) {
                localTask.setDolphinFlag(normalizeDolphinFlag(runtimeTask.getFlag()));
                changed = true;
            }
            if (!changed) {
                continue;
            }
            dataTaskMapper.updateById(localTask);
            updated++;
        }
        return updated;
    }

    private boolean repairDefinitionMetadataFromRuntime(DataWorkflow workflow,
            RuntimeWorkflowDefinition runtimeDefinition,
            String operator,
            WorkflowPublishRepairResponse response) {
        if (workflow == null || runtimeDefinition == null || !StringUtils.hasText(workflow.getDefinitionJson())) {
            return false;
        }
        if (CollectionUtils.isEmpty(runtimeDefinition.getTasks())) {
            return false;
        }
        Map<Long, RuntimeTaskDefinition> runtimeTaskByCode = runtimeDefinition.getTasks().stream()
                .filter(Objects::nonNull)
                .filter(task -> task.getTaskCode() != null && task.getTaskCode() > 0)
                .collect(Collectors.toMap(RuntimeTaskDefinition::getTaskCode, task -> task, (left, right) -> left));
        if (runtimeTaskByCode.isEmpty()) {
            return false;
        }

        try {
            JsonNode rootNode = objectMapper.readTree(workflow.getDefinitionJson());
            if (!(rootNode instanceof ObjectNode)) {
                return false;
            }
            ObjectNode rootObject = (ObjectNode) rootNode;
            JsonNode taskListNode = rootObject.get("taskDefinitionList");
            if (!(taskListNode instanceof ArrayNode)) {
                return false;
            }

            boolean changed = false;
            for (JsonNode taskNode : (ArrayNode) taskListNode) {
                if (!(taskNode instanceof ObjectNode)) {
                    continue;
                }
                ObjectNode taskObject = (ObjectNode) taskNode;
                Long taskCode = readLong(taskObject, "taskCode", "code");
                if (taskCode == null || taskCode <= 0) {
                    taskCode = readLong(taskObject.get("xPlatformTaskMeta"), "dolphinTaskCode");
                }
                if (taskCode == null || taskCode <= 0) {
                    continue;
                }
                RuntimeTaskDefinition runtimeTask = runtimeTaskByCode.get(taskCode);
                if (runtimeTask == null) {
                    continue;
                }

                ObjectNode taskParams = ensureObject(taskObject, "taskParams");
                changed |= putLong(taskParams, "datasourceId", runtimeTask.getDatasourceId());
                changed |= putLong(taskParams, "datasource", runtimeTask.getDatasourceId());
                changed |= putText(taskParams, "datasourceName", runtimeTask.getDatasourceName());
                changed |= putText(taskParams, "datasourceType", runtimeTask.getDatasourceType());
                changed |= putText(taskParams, "type", runtimeTask.getDatasourceType());

                changed |= putInt(taskObject, "taskGroupId", runtimeTask.getTaskGroupId());
                changed |= putText(taskObject, "flag", normalizeDolphinFlag(runtimeTask.getFlag()));
                changed |= putText(taskObject, "taskPriority", runtimeTask.getTaskPriority());
                changed |= putInt(taskObject, "version", runtimeTask.getTaskVersion());
                changed |= putLongArray(taskObject, "inputTableIds", runtimeTask.getInputTableIds());
                changed |= putLongArray(taskObject, "outputTableIds", runtimeTask.getOutputTableIds());
            }

            if (!changed) {
                return false;
            }
            workflow.setDefinitionJson(objectMapper.writeValueAsString(rootObject));
            workflow.setUpdatedBy(operator);
            dataWorkflowMapper.updateById(workflow);
            if (response != null && !response.getUpdatedWorkflowFields().contains("definition.taskDefinitionList")) {
                response.getUpdatedWorkflowFields().add("definition.taskDefinitionList");
            }
            return true;
        } catch (Exception ex) {
            throw new IllegalStateException(String.format(
                    "修复 definition 元数据失败(来源=runtime, workflowId=%s): %s",
                    workflow.getId(),
                    ex.getMessage()), ex);
        }
    }

    private boolean repairDefinitionMetadataFromCatalog(DataWorkflow workflow,
            String operator,
            WorkflowPublishRepairResponse response) {
        if (workflow == null || !StringUtils.hasText(workflow.getDefinitionJson())) {
            return false;
        }
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(workflow.getDefinitionJson());
        } catch (Exception ex) {
            throw new IllegalStateException(String.format(
                    "修复 definition 元数据失败(来源=catalog, workflowId=%s): definitionJson 解析失败: %s",
                    workflow.getId(),
                    ex.getMessage()), ex);
        }
        if (!(rootNode instanceof ObjectNode)) {
            return false;
        }
        ObjectNode rootObject = (ObjectNode) rootNode;
        JsonNode taskListNode = rootObject.get("taskDefinitionList");
        if (!(taskListNode instanceof ArrayNode)) {
            return false;
        }
        boolean needDatasourceResolve = false;
        boolean needTaskGroupResolve = false;
        for (JsonNode taskNode : (ArrayNode) taskListNode) {
            if (!(taskNode instanceof ObjectNode)) {
                continue;
            }
            ObjectNode taskObject = (ObjectNode) taskNode;
            ObjectNode taskParams = ensureObject(taskObject, "taskParams");
            Long datasourceId = readLong(taskParams, "datasourceId", "datasource");
            String datasourceName = readText(taskParams, "datasourceName");
            if (StringUtils.hasText(datasourceName) || (datasourceId != null && datasourceId > 0)) {
                needDatasourceResolve = true;
            }
            Integer taskGroupId = readInt(taskObject, "taskGroupId");
            String taskGroupName = readText(taskObject, "taskGroupName");
            if (shouldResolveTaskGroupByName(workflow, taskGroupId, taskGroupName)) {
                needTaskGroupResolve = true;
            }
        }

        Map<String, DolphinDatasourceOption> datasourceByName;
        Map<Long, DolphinDatasourceOption> datasourceById;
        try {
            List<DolphinDatasourceOption> datasourceOptions = workflow.getDolphinConfigId() == null
                    ? dolphinSchedulerService.listDatasources(null, null)
                    : dolphinSchedulerService.listDatasources(null, null, workflow.getDolphinConfigId());
            datasourceByName = new LinkedHashMap<>();
            datasourceById = new LinkedHashMap<>();
            if (!CollectionUtils.isEmpty(datasourceOptions)) {
                for (DolphinDatasourceOption item : datasourceOptions) {
                    if (item == null || item.getId() == null || item.getId() <= 0) {
                        continue;
                    }
                    String name = normalizeText(item.getName());
                    if (StringUtils.hasText(name)) {
                        datasourceByName.putIfAbsent(name, item);
                    }
                    datasourceById.putIfAbsent(item.getId(), item);
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException(String.format(
                    "修复 definition 元数据失败(来源=catalog, workflowId=%s): 无法读取 Dolphin 数据源目录: %s",
                    workflow.getId(),
                    ex.getMessage()), ex);
        }

        Map<String, DolphinTaskGroupOption> taskGroupByName;
        try {
            List<DolphinTaskGroupOption> taskGroupOptions = workflow.getDolphinConfigId() == null
                    ? dolphinSchedulerService.listTaskGroups(null)
                    : dolphinSchedulerService.listTaskGroups(null, workflow.getDolphinConfigId());
            taskGroupByName = taskGroupOptions
                    .stream()
                    .filter(Objects::nonNull)
                    .filter(item -> StringUtils.hasText(item.getName()) && item.getId() != null && item.getId() > 0)
                    .collect(Collectors.toMap(item -> item.getName().trim(), item -> item, (left, right) -> left));
        } catch (Exception ex) {
            throw new IllegalStateException(String.format(
                    "修复 definition 元数据失败(来源=catalog, workflowId=%s): 无法读取 Dolphin 任务组目录: %s",
                    workflow.getId(),
                    ex.getMessage()), ex);
        }
        if (needDatasourceResolve && datasourceByName.isEmpty() && datasourceById.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "修复 definition 元数据失败(来源=catalog, workflowId=%s): Dolphin 数据源目录为空，无法按名称补齐 datasourceId",
                    workflow.getId()));
        }
        if (needTaskGroupResolve && taskGroupByName.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "修复 definition 元数据失败(来源=catalog, workflowId=%s): Dolphin 任务组目录为空，无法按名称补齐 taskGroupId",
                    workflow.getId()));
        }

        boolean changed = false;
        for (JsonNode taskNode : (ArrayNode) taskListNode) {
            if (!(taskNode instanceof ObjectNode)) {
                continue;
            }
            ObjectNode taskObject = (ObjectNode) taskNode;
            ObjectNode taskParams = ensureObject(taskObject, "taskParams");
            Long datasourceId = readLong(taskParams, "datasourceId", "datasource");
            String datasourceName = readText(taskParams, "datasourceName");
            DolphinDatasourceOption datasourceOption = resolveDatasourceOption(
                    datasourceByName, datasourceById, datasourceId, datasourceName);
            if (datasourceOption != null && datasourceOption.getId() != null && datasourceOption.getId() > 0) {
                changed |= putLong(taskParams, "datasourceId", datasourceOption.getId());
                changed |= putLong(taskParams, "datasource", datasourceOption.getId());
                changed |= putText(taskParams, "datasourceType", datasourceOption.getType());
                changed |= putText(taskParams, "type", datasourceOption.getType());
            }
            Integer taskGroupId = readInt(taskObject, "taskGroupId");
            String taskGroupName = readText(taskObject, "taskGroupName");
            if (shouldResolveTaskGroupByName(workflow, taskGroupId, taskGroupName)) {
                DolphinTaskGroupOption groupOption = taskGroupByName.get(taskGroupName.trim());
                if (groupOption != null && groupOption.getId() != null && groupOption.getId() > 0) {
                    changed |= putInt(taskObject, "taskGroupId", groupOption.getId());
                } else if (isFirstDeploy(workflow)) {
                    changed |= removeField(taskObject, "taskGroupId");
                }
            }
        }

        if (!changed) {
            return false;
        }
        try {
            workflow.setDefinitionJson(objectMapper.writeValueAsString(rootObject));
        } catch (Exception ex) {
            throw new IllegalStateException(String.format(
                    "修复 definition 元数据失败(来源=catalog, workflowId=%s): definitionJson 序列化失败: %s",
                    workflow.getId(),
                    ex.getMessage()), ex);
        }
        workflow.setUpdatedBy(operator);
        dataWorkflowMapper.updateById(workflow);
        if (response != null && !response.getUpdatedWorkflowFields().contains("definition.taskDefinitionList")) {
            response.getUpdatedWorkflowFields().add("definition.taskDefinitionList");
        }
        return true;
    }

    private void ensureBlockingRepairIssuesResolved(DataWorkflow workflow) {
        if (workflow == null) {
            return;
        }
        List<WorkflowPublishRepairIssue> unresolvedIssues = buildPublishMetadataRepairIssues(
                workflow,
                buildPlatformDefinition(workflow)).stream()
                .filter(this::isBlockingRepairIssue)
                .collect(Collectors.toList());
        if (unresolvedIssues.isEmpty()) {
            return;
        }
        String details = unresolvedIssues.stream()
                .map(item -> StringUtils.hasText(item.getMessage()) ? item.getMessage() : item.getField())
                .filter(StringUtils::hasText)
                .limit(3)
                .collect(Collectors.joining("；"));
        String summary = details.isEmpty()
                ? "请检查任务 datasourceId/taskGroupId"
                : details;
        throw new IllegalStateException("元数据修复未完成，仍存在必填 ID 字段缺失: " + summary);
    }

    private boolean isBlockingRepairIssue(WorkflowPublishRepairIssue issue) {
        if (issue == null || !StringUtils.hasText(issue.getField())) {
            return false;
        }
        String field = issue.getField().trim();
        return "task.datasourceId".equals(field) || "task.taskGroupId".equals(field);
    }

    private boolean shouldResolveTaskGroupByName(DataWorkflow workflow, Integer taskGroupId, String taskGroupName) {
        if (!StringUtils.hasText(taskGroupName)) {
            return false;
        }
        return taskGroupId == null || taskGroupId <= 0 || isFirstDeploy(workflow);
    }

    private ObjectNode ensureObject(ObjectNode root, String field) {
        if (root == null || !StringUtils.hasText(field)) {
            return objectMapper.createObjectNode();
        }
        JsonNode node = root.get(field);
        if (node instanceof ObjectNode) {
            return (ObjectNode) node;
        }
        ObjectNode created = objectMapper.createObjectNode();
        root.set(field, created);
        return created;
    }

    private boolean putLong(ObjectNode node, String field, Long value) {
        if (node == null || !StringUtils.hasText(field) || value == null || value <= 0) {
            return false;
        }
        Long current = readLong(node, field);
        if (Objects.equals(current, value)) {
            return false;
        }
        node.put(field, value);
        return true;
    }

    private boolean putInt(ObjectNode node, String field, Integer value) {
        if (node == null || !StringUtils.hasText(field) || value == null || value <= 0) {
            return false;
        }
        Integer current = readInt(node, field);
        if (Objects.equals(current, value)) {
            return false;
        }
        node.put(field, value);
        return true;
    }

    private boolean removeField(ObjectNode node, String field) {
        if (node == null || !StringUtils.hasText(field) || !node.has(field)) {
            return false;
        }
        node.remove(field);
        return true;
    }

    private boolean putText(ObjectNode node, String field, String value) {
        if (node == null || !StringUtils.hasText(field) || !StringUtils.hasText(value)) {
            return false;
        }
        String normalized = value.trim();
        String current = readText(node, field);
        if (Objects.equals(normalizeDiffValue(current), normalizeDiffValue(normalized))) {
            return false;
        }
        node.put(field, normalized);
        return true;
    }

    private boolean putLongArray(ObjectNode node, String field, List<Long> values) {
        if (node == null || !StringUtils.hasText(field) || CollectionUtils.isEmpty(values)) {
            return false;
        }
        List<Long> normalized = values.stream()
                .filter(Objects::nonNull)
                .filter(item -> item > 0)
                .collect(Collectors.toList());
        if (normalized.isEmpty()) {
            return false;
        }
        JsonNode currentNode = node.get(field);
        List<Long> current = new ArrayList<>();
        if (currentNode != null && currentNode.isArray()) {
            for (JsonNode item : currentNode) {
                if (item == null || item.isNull()) {
                    continue;
                }
                if (item.isNumber()) {
                    current.add(item.asLong());
                } else if (item.isTextual()) {
                    try {
                        current.add(Long.parseLong(item.asText().trim()));
                    } catch (NumberFormatException ignored) {
                        // ignore invalid value
                    }
                }
            }
        }
        if (Objects.equals(current, normalized)) {
            return false;
        }
        ArrayNode arrayNode = objectMapper.createArrayNode();
        normalized.forEach(arrayNode::add);
        node.set(field, arrayNode);
        return true;
    }

    private Long readLong(JsonNode node, String... fields) {
        if (node == null || node.isNull() || fields == null) {
            return null;
        }
        for (String field : fields) {
            if (!StringUtils.hasText(field)) {
                continue;
            }
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.asLong();
            }
            if (value.isTextual()) {
                String text = value.asText();
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                try {
                    return Long.parseLong(text.trim());
                } catch (NumberFormatException ignored) {
                    // skip invalid value
                }
            }
        }
        return null;
    }

    private Integer readInt(JsonNode node, String... fields) {
        Long value = readLong(node, fields);
        return value == null ? null : value.intValue();
    }

    private String readText(JsonNode node, String... fields) {
        if (node == null || node.isNull() || fields == null) {
            return null;
        }
        for (String field : fields) {
            if (!StringUtils.hasText(field)) {
                continue;
            }
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.isTextual() ? value.asText() : value.toString();
            if (StringUtils.hasText(text)) {
                return text.trim();
            }
        }
        return null;
    }

    private DolphinDatasourceOption resolveDatasourceOption(Map<String, DolphinDatasourceOption> datasourceByName,
            Map<Long, DolphinDatasourceOption> datasourceById,
            Long datasourceId,
            String datasourceName) {
        String normalizedName = normalizeText(datasourceName);
        if (StringUtils.hasText(normalizedName)) {
            DolphinDatasourceOption option = datasourceByName.get(normalizedName);
            if (option != null) {
                return option;
            }
        }
        if (datasourceId != null && datasourceId > 0) {
            return datasourceById.get(datasourceId);
        }
        return null;
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static class DefinitionTaskMetadata {
        private Long datasourceId;
        private Integer taskGroupId;

        public Long getDatasourceId() {
            return datasourceId;
        }

        public void setDatasourceId(Long datasourceId) {
            this.datasourceId = datasourceId;
        }

        public Integer getTaskGroupId() {
            return taskGroupId;
        }

        public void setTaskGroupId(Integer taskGroupId) {
            this.taskGroupId = taskGroupId;
        }
    }

    private LocalDateTime parseScheduleDateTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        for (DateTimeFormatter formatter : SCHEDULE_INPUT_FORMATTERS) {
            try {
                return LocalDateTime.parse(trimmed, formatter);
            } catch (DateTimeParseException ignored) {
                // try next formatter
            }
        }
        return null;
    }

    private <T> boolean updateWorkflowScheduleField(T currentValue,
            T nextValue,
            java.util.function.Consumer<T> setter,
            List<String> changedFields,
            String fieldName) {
        if (Objects.equals(currentValue, nextValue)) {
            return false;
        }
        setter.accept(nextValue);
        if (changedFields != null && StringUtils.hasText(fieldName)) {
            changedFields.add(fieldName);
        }
        return true;
    }

    private boolean isRuntimeWorkflowMissing(String message) {
        if (!StringUtils.hasText(message)) {
            return false;
        }
        String normalized = message.trim().toLowerCase();
        return normalized.contains("未找到")
                || normalized.contains("not found")
                || normalized.contains("不存在");
    }

    private void applyWorkflowStatus(DataWorkflow workflow, WorkflowPublishRecord record) {
        switch (record.getOperation()) {
            case "deploy":
                workflow.setStatus("offline");
                workflow.setPublishStatus("published");
                workflow.setLastPublishedVersionId(record.getVersionId());
                break;
            case "online":
                workflow.setStatus("online");
                workflow.setPublishStatus("published");
                workflow.setLastPublishedVersionId(record.getVersionId());
                break;
            case "offline":
                workflow.setStatus("offline");
                workflow.setPublishStatus("published");
                workflow.setScheduleState("OFFLINE");
                break;
            default:
                log.warn("Unknown workflow publish operation {}", record.getOperation());
        }
    }

    private void invokeDolphin(DataWorkflow workflow, WorkflowPublishRecord record) {
        if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            throw new IllegalStateException("工作流尚未 deploy，无法执行 " + record.getOperation());
        }
        try {
            if ("online".equals(record.getOperation())) {
                setWorkflowReleaseState(workflow, "ONLINE");
                tryAutoOnlineSchedule(workflow);
            } else if ("offline".equals(record.getOperation())) {
                setWorkflowReleaseState(workflow, "OFFLINE");
                tryAutoOfflineSchedule(workflow);
            } else {
                log.debug("No Dolphin action for operation {}", record.getOperation());
            }
        } catch (RuntimeException ex) {
            throw new IllegalStateException("调用 DolphinScheduler 失败: " + ex.getMessage(), ex);
        }
    }

    private void setWorkflowReleaseState(DataWorkflow workflow, String releaseState) {
        if (workflow.getDolphinConfigId() == null) {
            dolphinSchedulerService.setWorkflowReleaseState(workflow.getWorkflowCode(), releaseState);
        } else {
            dolphinSchedulerService.setWorkflowReleaseState(
                    workflow.getDolphinConfigId(), workflow.getWorkflowCode(), releaseState);
        }
    }

    private DolphinSchedule getWorkflowSchedule(DataWorkflow workflow) {
        if (workflow.getDolphinConfigId() == null) {
            return dolphinSchedulerService.getWorkflowSchedule(workflow.getWorkflowCode());
        }
        return dolphinSchedulerService.getWorkflowSchedule(workflow.getDolphinConfigId(), workflow.getWorkflowCode());
    }

    private void onlineWorkflowSchedule(DataWorkflow workflow, Long scheduleId) {
        if (workflow.getDolphinConfigId() == null) {
            dolphinSchedulerService.onlineWorkflowSchedule(scheduleId);
        } else {
            dolphinSchedulerService.onlineWorkflowSchedule(workflow.getDolphinConfigId(), scheduleId);
        }
    }

    private void offlineWorkflowSchedule(DataWorkflow workflow, Long scheduleId) {
        if (workflow.getDolphinConfigId() == null) {
            dolphinSchedulerService.offlineWorkflowSchedule(scheduleId);
        } else {
            dolphinSchedulerService.offlineWorkflowSchedule(workflow.getDolphinConfigId(), scheduleId);
        }
    }

    private void tryAutoOnlineSchedule(DataWorkflow workflow) {
        if (!Boolean.TRUE.equals(workflow.getScheduleAutoOnline())) {
            return;
        }
        DolphinSchedule schedule = getWorkflowSchedule(workflow);
        if (schedule != null && schedule.getId() != null && schedule.getId() > 0) {
            workflow.setDolphinScheduleId(schedule.getId());
            if (StringUtils.hasText(schedule.getReleaseState())) {
                workflow.setScheduleState(schedule.getReleaseState());
            }
        }

        Long scheduleId = workflow.getDolphinScheduleId();
        if (scheduleId == null || scheduleId <= 0) {
            return;
        }

        boolean needOnline = true;
        if (schedule != null && StringUtils.hasText(schedule.getReleaseState())) {
            needOnline = !"ONLINE".equalsIgnoreCase(schedule.getReleaseState());
        } else if (StringUtils.hasText(workflow.getScheduleState())) {
            needOnline = !"ONLINE".equalsIgnoreCase(workflow.getScheduleState());
        }
        if (!needOnline) {
            return;
        }

        try {
            onlineWorkflowSchedule(workflow, scheduleId);
            workflow.setScheduleState("ONLINE");
        } catch (Exception ex) {
            log.warn("Failed to online schedule {} for workflow {}: {}",
                    scheduleId, workflow.getWorkflowCode(), ex.getMessage());
        }
    }

    private void tryAutoOfflineSchedule(DataWorkflow workflow) {
        DolphinSchedule schedule = getWorkflowSchedule(workflow);
        if (schedule != null && schedule.getId() != null && schedule.getId() > 0) {
            workflow.setDolphinScheduleId(schedule.getId());
            if (StringUtils.hasText(schedule.getReleaseState())) {
                workflow.setScheduleState(schedule.getReleaseState());
            }
        }

        Long scheduleId = workflow.getDolphinScheduleId();
        if (scheduleId == null || scheduleId <= 0) {
            workflow.setScheduleState("OFFLINE");
            return;
        }
        try {
            offlineWorkflowSchedule(workflow, scheduleId);
        } catch (Exception ex) {
            log.warn("Failed to offline schedule {} for workflow {}: {}",
                    scheduleId, workflow.getWorkflowCode(), ex.getMessage());
        } finally {
            workflow.setScheduleState("OFFLINE");
        }
    }

    private void handleDeploy(DataWorkflow workflow,
            WorkflowVersion version,
            WorkflowPublishRecord record,
            WorkflowPublishRequest request) {
        boolean needApproval = Boolean.TRUE.equals(request.getRequireApproval());
        boolean approved = Boolean.TRUE.equals(request.getApproved());
        if (needApproval && !approved) {
            record.setStatus("pending_approval");
            record.setLog(toJson(Collections.singletonMap("comment", request.getApprovalComment())));
            return;
        }

        WorkflowPublishPreviewResponse preview = buildPublishPreview(workflow);
        if (!Boolean.TRUE.equals(preview.getCanPublish())) {
            RuntimeSyncIssue issue = CollectionUtils.isEmpty(preview.getErrors()) ? null : preview.getErrors().get(0);
            String detail = issue != null && StringUtils.hasText(issue.getMessage())
                    ? issue.getMessage()
                    : "发布预检失败";
            throw new IllegalStateException(PUBLISH_PREVIEW_FAILED + ": " + detail);
        }
        if (Boolean.TRUE.equals(preview.getRequireConfirm()) && !Boolean.TRUE.equals(request.getConfirmDiff())) {
            throw new IllegalStateException(PUBLISH_DIFF_CONFIRM_REQUIRED + ": 检测到平台与 Dolphin 存在变更差异，请先确认变更详情后再发布");
        }
        performDeploy(workflow, version, record);
    }

    private void performDeploy(DataWorkflow workflow,
            WorkflowVersion version,
            WorkflowPublishRecord record) {
        WorkflowDeployService.DeploymentResult result = workflowDeployService.deploy(workflow);
        workflow.setWorkflowCode(result.getWorkflowCode());
        if (result.getProjectCode() != null) {
            workflow.setProjectCode(result.getProjectCode());
        }
        record.setDolphinConfigId(workflow.getDolphinConfigId());
        applyWorkflowStatus(workflow, record);
        record.setStatus("success");
        record.setEngineWorkflowCode(result.getWorkflowCode());
        record.setLog(toJson(Collections.singletonMap("taskCount", result.getTaskCount())));
    }

    @Transactional
    public WorkflowPublishRecord approve(Long workflowId,
            Long recordId,
            WorkflowApprovalRequest request) {
        WorkflowPublishRecord record = publishRecordMapper.selectById(recordId);
        if (record == null || !Objects.equals(record.getWorkflowId(), workflowId)) {
            throw new IllegalArgumentException("发布记录不存在");
        }
        if (!"pending_approval".equals(record.getStatus())) {
            throw new IllegalStateException("当前状态不可审批: " + record.getStatus());
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        WorkflowVersion version = workflowVersionMapper.selectById(record.getVersionId());
        if (!Boolean.TRUE.equals(request.getApproved())) {
            record.setStatus("rejected");
            record.setOperator(request.getApprover());
            record.setLog(toJson(Collections.singletonMap("comment", request.getComment())));
            publishRecordMapper.updateById(record);
            return record;
        }

        record.setOperator(request.getApprover());
        performDeploy(workflow, version, record);
        publishRecordMapper.updateById(record);
        dataWorkflowMapper.updateById(workflow);
        return record;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return String.valueOf(value);
        }
    }
}
