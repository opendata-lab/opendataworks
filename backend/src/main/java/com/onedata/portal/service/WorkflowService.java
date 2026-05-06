package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.dto.workflow.WorkflowSchedulerEngineRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 工作流定义服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private static final DateTimeFormatter[] DATETIME_FORMATS = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    };
    private static final int SNAPSHOT_SCHEMA_VERSION_DEFINITION = 3;
    private static final String DEFAULT_FAILURE_STRATEGY = "CONTINUE";
    private static final String DEFAULT_WARNING_TYPE = "NONE";
    private static final String DEFAULT_PROCESS_INSTANCE_PRIORITY = "MEDIUM";
    private static final Long DEFAULT_WARNING_GROUP_ID = 0L;
    private static final Long DEFAULT_ENVIRONMENT_CODE = -1L;
    private static final String DEFAULT_WORKER_GROUP = "default";
    private static final String DEFAULT_TENANT_CODE = "default";
    private static final Integer DEFAULT_TASK_PRIORITY = 5;
    private static final Integer DEFAULT_TASK_RETRY_TIMES = 1;
    private static final Integer DEFAULT_TASK_RETRY_INTERVAL = 1;
    private static final Integer DEFAULT_TASK_TIMEOUT_SECONDS = 60;

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final WorkflowPublishRecordMapper workflowPublishRecordMapper;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowInstanceCacheService workflowInstanceCacheService;
    private final ObjectMapper objectMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataTaskMapper dataTaskMapper;
    private final DataLineageMapper dataLineageMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final TaskExecutionLogMapper taskExecutionLogMapper;
    private final WorkflowTopologyService workflowTopologyService;
    private final DolphinConfigService dolphinConfigService;

    public Page<DataWorkflow> list(WorkflowQueryRequest request) {
        LambdaQueryWrapper<DataWorkflow> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.like(DataWorkflow::getWorkflowName, request.getKeyword());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(DataWorkflow::getStatus, request.getStatus());
        }
        wrapper.orderByDesc(DataWorkflow::getUpdatedAt);
        Page<DataWorkflow> page = new Page<>(request.getPageNum(), request.getPageSize());
        Page<DataWorkflow> result = dataWorkflowMapper.selectPage(page, wrapper);
        attachLatestInstanceInfo(result.getRecords());
        attachCurrentVersionInfo(result.getRecords());
        return result;
    }

    public WorkflowDetailResponse getDetail(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowTaskRelation::getCreatedAt));
        List<WorkflowVersion> versions = workflowVersionService.listByWorkflow(workflowId);
        List<WorkflowPublishRecord> publishRecords = workflowPublishRecordMapper.selectList(
                Wrappers.<WorkflowPublishRecord>lambdaQuery()
                        .eq(WorkflowPublishRecord::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowPublishRecord::getCreatedAt));
        workflow.setCurrentVersionNo(versions.stream()
                .filter(version -> Objects.equals(version.getId(), workflow.getCurrentVersionId()))
                .map(WorkflowVersion::getVersionNo)
                .findFirst()
                .orElse(null));
        List<WorkflowInstanceCache> recentInstances = resolveRecentInstances(workflow, 10);
        return WorkflowDetailResponse.builder()
                .workflow(workflow)
                .taskRelations(relations)
                .versions(versions)
                .publishRecords(publishRecords)
                .recentInstances(recentInstances)
                .build();
    }

    @Transactional
    public String buildDefinitionJsonForExport(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        if (StringUtils.hasText(workflow.getDefinitionJson())) {
            return sanitizeDefinitionJsonForExport(workflow.getDefinitionJson());
        }

        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        List<WorkflowTaskBinding> bindings = buildTaskBindingsFromRelations(relations);

        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(collectTaskIds(bindings));
        String definitionJson = resolveDefinitionJson(workflow, null, bindings, topology);
        workflow.setDefinitionJson(definitionJson);
        dataWorkflowMapper.updateById(workflow);
        return sanitizeDefinitionJsonForExport(definitionJson);
    }

    private List<WorkflowInstanceCache> resolveRecentInstances(DataWorkflow workflow, int limit) {
        if (workflow == null || workflow.getId() == null) {
            return Collections.emptyList();
        }
        if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            return workflowInstanceCacheService.listRecent(workflow.getId(), limit);
        }
        try {
            List<WorkflowInstanceSummary> realtimeSummaries = dolphinSchedulerService
                    .listWorkflowInstances(workflow.getDolphinConfigId(), workflow.getWorkflowCode(), limit);
            workflowInstanceCacheService.replaceCache(workflow, realtimeSummaries);
            return mapSummariesToCaches(workflow.getId(), realtimeSummaries);
        } catch (Exception ex) {
            log.warn("Failed to fetch realtime instances for workflow {}: {}", workflow.getWorkflowName(), ex.getMessage());
            return workflowInstanceCacheService.listRecent(workflow.getId(), limit);
        }
    }

    private void attachCurrentVersionInfo(List<DataWorkflow> workflows) {
        if (CollectionUtils.isEmpty(workflows)) {
            return;
        }
        Set<Long> versionIds = workflows.stream()
                .map(DataWorkflow::getCurrentVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (versionIds.isEmpty()) {
            return;
        }
        Map<Long, Integer> versionNoById = workflowVersionMapper.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(WorkflowVersion::getId, WorkflowVersion::getVersionNo, (left, right) -> left));
        workflows.forEach(workflow -> workflow.setCurrentVersionNo(versionNoById.get(workflow.getCurrentVersionId())));
    }

    @Transactional
    public DataWorkflow createWorkflow(WorkflowDefinitionRequest request) {
        DataWorkflow workflow = new DataWorkflow();
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowTaskBinding> taskBindings = normalizeTaskBindings(request.getTasks());
        request.setTasks(taskBindings);
        List<Long> taskIdsInOrder = collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setDefinitionJson(defaultJson(request.getDefinitionJson()));
        workflow.setEntryTaskIds(toJson(orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(toJson(orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflow.setGlobalParams(request.getGlobalParams());
        workflow.setTaskGroupName(request.getTaskGroupName());
        workflow.setStatus("draft");
        workflow.setPublishStatus("never");
        workflow.setDolphinConfigId(resolveDolphinConfigId(request.getDolphinConfigId()));
        workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        workflow.setCreatedBy(request.getOperator());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        normalizeWorkflowScheduleDefaults(workflow);
        dataWorkflowMapper.insert(workflow);

        persistTaskRelations(workflow.getId(), taskBindings, null, topology);
        normalizeTaskMetadata(taskIdsInOrder, workflow.getTaskGroupName());

        String resolvedDefinitionJson = resolveDefinitionJson(workflow, request, taskBindings, topology);
        workflow.setDefinitionJson(resolvedDefinitionJson);
        dataWorkflowMapper.updateById(workflow);

        String versionDefinitionJson = resolvedDefinitionJson;
        WorkflowVersion version = snapshotWorkflow(workflow, request, versionDefinitionJson);
        workflow.setCurrentVersionId(version.getId());
        dataWorkflowMapper.updateById(workflow);

        updateRelationVersion(workflow.getId(), version.getId());
        return workflow;
    }

    public String executeWorkflow(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，请先上线后再执行");
        }
        TaskExecutionLog executionLog = createWorkflowExecutionLog(workflowId, "manual");
        try {
            String executionId = dolphinSchedulerService.startProcessInstance(
                    workflow.getDolphinConfigId(),
                    workflowCode,
                    null,
                    workflow.getWorkflowName());
            if (executionLog != null) {
                executionLog.setExecutionId(executionId);
                executionLog.setStatus("running");
                taskExecutionLogMapper.updateById(executionLog);
            }
            return executionId;
        } catch (RuntimeException ex) {
            markExecutionFailed(executionLog, ex);
            throw ex;
        }
    }

    public String backfillWorkflow(Long workflowId, WorkflowBackfillRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (request == null) {
            throw new IllegalArgumentException("补数参数不能为空");
        }

        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，请先上线后再补数");
        }
        TaskExecutionLog executionLog = createWorkflowExecutionLog(workflowId, "manual");
        try {
            String triggerId = dolphinSchedulerService.backfillProcessInstance(
                    workflow.getDolphinConfigId(), workflowCode, request);
            if (executionLog != null) {
                executionLog.setExecutionId(triggerId);
                executionLog.setStatus("running");
                taskExecutionLogMapper.updateById(executionLog);
            }
            return triggerId;
        } catch (RuntimeException ex) {
            markExecutionFailed(executionLog, ex);
            throw ex;
        }
    }

    private TaskExecutionLog createWorkflowExecutionLog(Long workflowId, String triggerType) {
        Long taskId = resolveMonitorTaskId(workflowId);
        if (taskId == null) {
            log.warn("No task relation found for workflow {}, skip execution log creation", workflowId);
            return null;
        }
        TaskExecutionLog logRecord = new TaskExecutionLog();
        logRecord.setTaskId(taskId);
        logRecord.setStatus("pending");
        logRecord.setStartTime(LocalDateTime.now());
        logRecord.setTriggerType(StringUtils.hasText(triggerType) ? triggerType : "manual");
        taskExecutionLogMapper.insert(logRecord);
        return logRecord;
    }

    private void markExecutionFailed(TaskExecutionLog executionLog, RuntimeException ex) {
        if (executionLog == null) {
            return;
        }
        executionLog.setStatus("failed");
        executionLog.setEndTime(LocalDateTime.now());
        executionLog.setErrorMessage(ex.getMessage());
        taskExecutionLogMapper.updateById(executionLog);
    }

    private Long resolveMonitorTaskId(Long workflowId) {
        if (workflowId == null) {
            return null;
        }
        WorkflowTaskRelation relation = workflowTaskRelationMapper.selectOne(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowTaskRelation::getIsEntry)
                        .orderByAsc(WorkflowTaskRelation::getId)
                        .last("LIMIT 1"));
        return relation != null ? relation.getTaskId() : null;
    }

    @Transactional
    public DataWorkflow updateWorkflow(Long workflowId, WorkflowDefinitionRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskBinding> taskBindings = normalizeTaskBindings(request.getTasks());
        request.setTasks(taskBindings);
        List<Long> taskIdsInOrder = collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setEntryTaskIds(toJson(orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(toJson(orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflow.setGlobalParams(request.getGlobalParams());
        workflow.setTaskGroupName(request.getTaskGroupName());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setUpdatedAt(LocalDateTime.now());
        if (workflow.getDolphinConfigId() == null) {
            workflow.setDolphinConfigId(resolveDolphinConfigId(request.getDolphinConfigId()));
        }
        if (workflow.getProjectCode() == null || workflow.getProjectCode() == 0) {
            workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        }
        normalizeWorkflowScheduleDefaults(workflow);

        persistTaskRelations(workflowId, taskBindings, workflow.getCurrentVersionId(), topology);
        normalizeTaskMetadata(taskIdsInOrder, workflow.getTaskGroupName());

        String resolvedDefinitionJson = resolveDefinitionJson(workflow, request, taskBindings, topology);
        workflow.setDefinitionJson(resolvedDefinitionJson);
        dataWorkflowMapper.updateById(workflow);

        String versionDefinitionJson = resolvedDefinitionJson;
        if (shouldCreateNewVersion(workflow, versionDefinitionJson)) {
            WorkflowVersion version = snapshotWorkflow(workflow, request, versionDefinitionJson);
            workflow.setCurrentVersionId(version.getId());
            dataWorkflowMapper.updateById(workflow);
            updateRelationVersion(workflowId, version.getId());
        }
        return workflow;
    }

    @Transactional
    public DataWorkflow syncCurrentVersion(Long workflowId, String operator, String triggerSource) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest();
        request.setWorkflowName(workflow.getWorkflowName());
        request.setDescription(workflow.getDescription());
        request.setTaskGroupName(workflow.getTaskGroupName());
        request.setGlobalParams(workflow.getGlobalParams());
        request.setDolphinConfigId(workflow.getDolphinConfigId());
        request.setProjectCode(workflow.getProjectCode());
        request.setTasks(buildTaskBindingsFromRelations(relations));
        request.setOperator(resolveWorkflowOperator(workflow, operator));
        request.setTriggerSource(StringUtils.hasText(triggerSource) ? triggerSource.trim() : "publish_auto_save");
        return updateWorkflow(workflowId, request);
    }

    @Transactional
    public DataWorkflow normalizeAndPersistMetadata(Long workflowId, String operator) {
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId 不能为空");
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        List<WorkflowTaskBinding> taskBindings = buildTaskBindingsFromRelations(relations);
        List<Long> taskIdsInOrder = collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setEntryTaskIds(toJson(orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(toJson(orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        normalizeWorkflowScheduleDefaults(workflow);
        normalizeTaskMetadata(taskIdsInOrder, workflow.getTaskGroupName());
        workflow.setDefinitionJson(resolveDefinitionJson(workflow, null, taskBindings, topology));
        if (StringUtils.hasText(operator)) {
            workflow.setUpdatedBy(operator.trim());
        }
        workflow.setUpdatedAt(LocalDateTime.now());
        dataWorkflowMapper.updateById(workflow);
        return workflow;
    }

    @Transactional
    public DataWorkflow switchSchedulerEngine(Long workflowId, WorkflowSchedulerEngineRequest request) {
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId 不能为空");
        }
        if (request == null || request.getDolphinConfigId() == null || request.getDolphinConfigId() <= 0) {
            throw new IllegalArgumentException("dolphinConfigId 不能为空");
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long targetConfigId = request.getDolphinConfigId();
        DolphinConfig targetConfig = dolphinConfigService.getEnabledConfig(targetConfigId);
        if (!dolphinSchedulerService.testConnection(targetConfigId)) {
            throw new IllegalStateException("目标 Dolphin 环境连接失败: " + targetConfig.getConfigName());
        }
        Long targetProjectCode = dolphinSchedulerService.getProjectCode(targetConfigId, true);
        if (targetProjectCode == null || targetProjectCode <= 0) {
            throw new IllegalStateException("目标 Dolphin 项目不可用: " + targetConfig.getProjectName());
        }

        String operator = StringUtils.hasText(request.getOperator()) ? request.getOperator().trim() : "system";
        LocalDateTime updatedAt = LocalDateTime.now();

        workflow.setDolphinConfigId(targetConfigId);
        workflow.setWorkflowCode(null);
        workflow.setProjectCode(targetProjectCode);
        workflow.setDolphinScheduleId(null);
        workflow.setScheduleState("OFFLINE");
        workflow.setStatus("offline");
        workflow.setPublishStatus("never");
        workflow.setLastPublishedVersionId(null);
        workflow.setRuntimeSyncStatus(null);
        workflow.setRuntimeSyncMessage(null);
        workflow.setRuntimeSyncHash(null);
        workflow.setRuntimeSyncAt(null);
        workflow.setUpdatedBy(operator);
        workflow.setUpdatedAt(updatedAt);
        workflow.setDefinitionJson(refreshDefinitionRuntimeIds(
                workflow.getDefinitionJson(),
                targetConfigId,
                targetProjectCode));
        dataWorkflowMapper.update(null, Wrappers.<DataWorkflow>lambdaUpdate()
                .eq(DataWorkflow::getId, workflowId)
                .set(DataWorkflow::getDolphinConfigId, targetConfigId)
                .set(DataWorkflow::getWorkflowCode, null)
                .set(DataWorkflow::getProjectCode, targetProjectCode)
                .set(DataWorkflow::getDolphinScheduleId, null)
                .set(DataWorkflow::getScheduleState, "OFFLINE")
                .set(DataWorkflow::getStatus, "offline")
                .set(DataWorkflow::getPublishStatus, "never")
                .set(DataWorkflow::getLastPublishedVersionId, null)
                .set(DataWorkflow::getRuntimeSyncStatus, null)
                .set(DataWorkflow::getRuntimeSyncMessage, null)
                .set(DataWorkflow::getRuntimeSyncHash, null)
                .set(DataWorkflow::getRuntimeSyncAt, null)
                .set(DataWorkflow::getDefinitionJson, workflow.getDefinitionJson())
                .set(DataWorkflow::getUpdatedBy, operator)
                .set(DataWorkflow::getUpdatedAt, updatedAt));
        return workflow;
    }

    private void updateRelationVersion(Long workflowId, Long versionId) {
        WorkflowTaskRelation update = new WorkflowTaskRelation();
        update.setVersionId(versionId);
        workflowTaskRelationMapper.update(update,
                Wrappers.<WorkflowTaskRelation>lambdaUpdate()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
    }

    private WorkflowVersion snapshotWorkflow(DataWorkflow workflow,
            WorkflowDefinitionRequest request,
            String snapshotJson) {
        boolean isInitial = workflow.getCurrentVersionId() == null;
        String changeSummary = isInitial ? "initial workflow definition" : "updated workflow definition";
        return workflowVersionService.createVersion(
                workflow.getId(),
                snapshotJson,
                StringUtils.hasText(request.getDescription()) ? request.getDescription() : changeSummary,
                request.getTriggerSource(),
                request.getOperator(),
                SNAPSHOT_SCHEMA_VERSION_DEFINITION,
                null);
    }

    private boolean shouldCreateNewVersion(DataWorkflow workflow, String incomingSnapshotJson) {
        if (workflow == null) {
            return true;
        }
        if (workflow.getCurrentVersionId() == null) {
            return true;
        }
        WorkflowVersion currentVersion = workflowVersionMapper.selectById(workflow.getCurrentVersionId());
        if (currentVersion == null || !StringUtils.hasText(currentVersion.getStructureSnapshot())) {
            return true;
        }
        if (!Objects.equals(currentVersion.getSnapshotSchemaVersion(), SNAPSHOT_SCHEMA_VERSION_DEFINITION)) {
            return true;
        }
        String currentHash = snapshotContentHash(currentVersion.getStructureSnapshot());
        String incomingHash = snapshotContentHash(incomingSnapshotJson);
        if (!StringUtils.hasText(currentHash) || !StringUtils.hasText(incomingHash)) {
            return true;
        }
        return !Objects.equals(currentHash, incomingHash);
    }

    private String snapshotContentHash(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(snapshotJson);
            if (node != null && node.isObject()) {
                ((ObjectNode) node).remove("meta");
            }
            String normalized = node != null ? canonicalizeJson(node) : snapshotJson.trim();
            return sha256(normalized);
        } catch (Exception ignored) {
            return sha256(snapshotJson.trim());
        }
    }

    private String canonicalizeJson(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return "null";
        }
        if (node.isObject()) {
            StringBuilder sb = new StringBuilder();
            sb.append('{');
            boolean first = true;
            TreeSet<String> fieldNames = new TreeSet<>();
            node.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(fieldName).append('"').append(':');
                sb.append(canonicalizeJson(node.get(fieldName)));
            }
            sb.append('}');
            return sb.toString();
        }
        if (node.isArray()) {
            StringBuilder sb = new StringBuilder();
            sb.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(canonicalizeJson(node.get(i)));
            }
            sb.append(']');
            return sb.toString();
        }
        return node.toString();
    }

    private String sha256(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法生成 hash", e);
        }
    }

    private String resolveDefinitionJson(DataWorkflow workflow,
            WorkflowDefinitionRequest request,
            List<WorkflowTaskBinding> taskBindings,
            WorkflowTopologyResult topology) {
        String incomingDefinitionJson = null;
        if (request != null && StringUtils.hasText(request.getDefinitionJson())) {
            String normalized = normalizeJsonText(request.getDefinitionJson());
            if (isMeaningfulDefinitionJson(normalized)) {
                incomingDefinitionJson = normalized;
            }
        }
        Map<String, Object> definition = buildPlatformDefinitionDocument(workflow, taskBindings, topology);
        return mergeAndNormalizeDefinitionJson(definition,
                workflow != null ? workflow.getDefinitionJson() : null,
                incomingDefinitionJson,
                workflow != null ? workflow.getDolphinConfigId() : null);
    }

    private boolean isMeaningfulDefinitionJson(String definitionJson) {
        if (!StringUtils.hasText(definitionJson)) {
            return false;
        }
        String trimmed = definitionJson.trim();
        if (!StringUtils.hasText(trimmed) || "{}".equals(trimmed)) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            if (node == null || node.isNull() || node.isMissingNode()) {
                return false;
            }
            return !(node.isObject() && node.size() == 0);
        } catch (Exception ex) {
            return true;
        }
    }

    private String normalizeJsonText(String jsonText) {
        if (!StringUtils.hasText(jsonText)) {
            return "{}";
        }
        String trimmed = jsonText.trim();
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            return objectMapper.writeValueAsString(node);
        } catch (Exception ignored) {
            return trimmed;
        }
    }

    private String sanitizeDefinitionJsonForExport(String definitionJson) {
        if (!StringUtils.hasText(definitionJson)) {
            return definitionJson;
        }
        try {
            JsonNode rootNode = objectMapper.readTree(definitionJson);
            removeWorkflowStatusFields(firstPresent(rootNode, "processDefinition", "workflowDefinition", "workflow"));
            return objectMapper.writeValueAsString(rootNode);
        } catch (Exception ex) {
            return definitionJson;
        }
    }

    private String mergeAndNormalizeDefinitionJson(Map<String, Object> generatedDefinition,
            String persistedDefinitionJson,
            String incomingDefinitionJson,
            Long dolphinConfigId) {
        try {
            ObjectNode generatedNode = objectMapper.valueToTree(generatedDefinition);
            if (generatedNode == null || generatedNode.isNull() || generatedNode.isMissingNode()) {
                return "{}";
            }
            applyDefinitionMetadataSeed(generatedNode, persistedDefinitionJson);
            applyDefinitionMetadataSeed(generatedNode, incomingDefinitionJson);
            enrichDefinitionMetadataFromCatalog(generatedNode, dolphinConfigId);
            return objectMapper.writeValueAsString(generatedNode);
        } catch (Exception ex) {
            return toJson(generatedDefinition);
        }
    }

    private void enrichDefinitionMetadataFromCatalog(ObjectNode rootNode) {
        enrichDefinitionMetadataFromCatalog(rootNode, null);
    }

    private void enrichDefinitionMetadataFromCatalog(ObjectNode rootNode, Long dolphinConfigId) {
        if (rootNode == null || rootNode.isNull() || rootNode.isMissingNode()) {
            return;
        }
        JsonNode taskListNode = rootNode.get("taskDefinitionList");
        if (!(taskListNode instanceof ArrayNode)) {
            return;
        }

        String workflowTaskGroupName = normalizeText(readText(
                firstPresent(rootNode, "processDefinition", "workflowDefinition"),
                "taskGroupName"));
        boolean needDatasourceResolve = false;
        boolean needTaskGroupResolve = false;
        for (JsonNode taskNode : (ArrayNode) taskListNode) {
            if (!(taskNode instanceof ObjectNode)) {
                continue;
            }
            ObjectNode taskObject = (ObjectNode) taskNode;
            ObjectNode taskParams = ensureObjectNode(taskObject, "taskParams");
            Long datasourceId = readLong(taskParams, "datasourceId", "datasource");
            String datasourceName = normalizeText(readText(taskParams, "datasourceName"));
            if (StringUtils.hasText(datasourceName) || (datasourceId != null && datasourceId > 0)) {
                needDatasourceResolve = true;
            }

            Integer taskGroupId = readInt(taskObject, "taskGroupId");
            String taskGroupName = normalizeText(readText(taskObject, "taskGroupName"));
            if (!StringUtils.hasText(taskGroupName)) {
                taskGroupName = workflowTaskGroupName;
                if (StringUtils.hasText(taskGroupName)) {
                    taskObject.put("taskGroupName", taskGroupName);
                }
            }
            if ((taskGroupId == null || taskGroupId <= 0) && StringUtils.hasText(taskGroupName)) {
                needTaskGroupResolve = true;
            }
        }
        if (!needDatasourceResolve && !needTaskGroupResolve) {
            return;
        }

        DatasourceCatalog datasourceCatalog = needDatasourceResolve
                ? loadDatasourceCatalog(dolphinConfigId)
                : DatasourceCatalog.empty();
        Map<String, DolphinTaskGroupOption> taskGroupByName = needTaskGroupResolve
                ? loadTaskGroupCatalogByName(dolphinConfigId)
                : Collections.emptyMap();

        for (JsonNode taskNode : (ArrayNode) taskListNode) {
            if (!(taskNode instanceof ObjectNode)) {
                continue;
            }
            ObjectNode taskObject = (ObjectNode) taskNode;
            ObjectNode taskParams = ensureObjectNode(taskObject, "taskParams");

            Long datasourceId = readLong(taskParams, "datasourceId", "datasource");
            String datasourceName = normalizeText(readText(taskParams, "datasourceName"));
            DolphinDatasourceOption datasourceOption = resolveDatasourceOption(
                    datasourceCatalog, datasourceId, datasourceName);
            if (datasourceOption != null && datasourceOption.getId() != null && datasourceOption.getId() > 0) {
                taskParams.put("datasourceId", datasourceOption.getId());
                taskParams.put("datasource", datasourceOption.getId());
                String datasourceType = normalizeText(datasourceOption.getType());
                if (StringUtils.hasText(datasourceType)) {
                    taskParams.put("datasourceType", datasourceType);
                    taskParams.put("type", datasourceType);
                }
            }

            Integer taskGroupId = readInt(taskObject, "taskGroupId");
            String taskGroupName = normalizeText(readText(taskObject, "taskGroupName"));
            if (!StringUtils.hasText(taskGroupName)) {
                taskGroupName = workflowTaskGroupName;
            }
            if ((taskGroupId == null || taskGroupId <= 0) && StringUtils.hasText(taskGroupName)) {
                DolphinTaskGroupOption taskGroupOption = taskGroupByName.get(taskGroupName);
                if (taskGroupOption != null && taskGroupOption.getId() != null && taskGroupOption.getId() > 0) {
                    taskObject.put("taskGroupId", taskGroupOption.getId());
                }
            }
        }
    }

    private DatasourceCatalog loadDatasourceCatalog() {
        return loadDatasourceCatalog(null);
    }

    private DatasourceCatalog loadDatasourceCatalog(Long dolphinConfigId) {
        try {
            List<DolphinDatasourceOption> options = dolphinConfigId == null
                    ? dolphinSchedulerService.listDatasources(null, null)
                    : dolphinSchedulerService.listDatasources(null, null, dolphinConfigId);
            if (CollectionUtils.isEmpty(options)) {
                return DatasourceCatalog.empty();
            }
            Map<String, DolphinDatasourceOption> byName = new LinkedHashMap<>();
            Map<Long, DolphinDatasourceOption> byId = new LinkedHashMap<>();
            for (DolphinDatasourceOption option : options) {
                if (option == null || option.getId() == null || option.getId() <= 0) {
                    continue;
                }
                String name = normalizeText(option.getName());
                if (StringUtils.hasText(name)) {
                    byName.putIfAbsent(name, option);
                }
                byId.putIfAbsent(option.getId(), option);
            }
            return new DatasourceCatalog(byName, byId);
        } catch (Exception ex) {
            log.warn("Failed to load datasource catalog while enriching workflow definition metadata: {}",
                    ex.getMessage());
            return DatasourceCatalog.empty();
        }
    }

    private DolphinDatasourceOption resolveDatasourceOption(DatasourceCatalog datasourceCatalog,
            Long datasourceId,
            String datasourceName) {
        if (datasourceCatalog == null) {
            return null;
        }
        String normalizedName = normalizeText(datasourceName);
        if (StringUtils.hasText(normalizedName)) {
            DolphinDatasourceOption option = datasourceCatalog.byName.get(normalizedName);
            if (option != null) {
                return option;
            }
        }
        if (datasourceId != null && datasourceId > 0) {
            return datasourceCatalog.byId.get(datasourceId);
        }
        return null;
    }

    private String resolveDatasourceType(DolphinDatasourceOption datasourceOption, String fallbackType) {
        String catalogType = datasourceOption == null ? null : normalizeText(datasourceOption.getType());
        return StringUtils.hasText(catalogType) ? catalogType : normalizeText(fallbackType);
    }

    private Map<String, DolphinTaskGroupOption> loadTaskGroupCatalogByName() {
        return loadTaskGroupCatalogByName(null);
    }

    private Map<String, DolphinTaskGroupOption> loadTaskGroupCatalogByName(Long dolphinConfigId) {
        try {
            List<DolphinTaskGroupOption> options = dolphinConfigId == null
                    ? dolphinSchedulerService.listTaskGroups(null)
                    : dolphinSchedulerService.listTaskGroups(null, dolphinConfigId);
            if (CollectionUtils.isEmpty(options)) {
                return Collections.emptyMap();
            }
            Map<String, DolphinTaskGroupOption> result = new LinkedHashMap<>();
            for (DolphinTaskGroupOption option : options) {
                if (option == null || option.getId() == null || option.getId() <= 0) {
                    continue;
                }
                String name = normalizeText(option.getName());
                if (StringUtils.hasText(name)) {
                    result.putIfAbsent(name, option);
                }
            }
            return result;
        } catch (Exception ex) {
            log.warn("Failed to load task group catalog while enriching workflow definition metadata: {}",
                    ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private void applyDefinitionMetadataSeed(ObjectNode targetRoot, String seedJson) {
        if (targetRoot == null || !StringUtils.hasText(seedJson)) {
            return;
        }
        JsonNode seedRoot;
        try {
            seedRoot = objectMapper.readTree(seedJson);
        } catch (Exception ignored) {
            return;
        }
        if (seedRoot == null || seedRoot.isNull() || seedRoot.isMissingNode()) {
            return;
        }
        mergeScheduleSeed(targetRoot, seedRoot);
        mergeTaskSeed(targetRoot, seedRoot);
    }

    private void mergeScheduleSeed(ObjectNode targetRoot, JsonNode seedRoot) {
        if (targetRoot == null || seedRoot == null || seedRoot.isNull() || seedRoot.isMissingNode()) {
            return;
        }
        ObjectNode targetSchedule = ensureObjectNode(targetRoot, "schedule");
        JsonNode seedSchedule = firstPresent(seedRoot, "schedule");
        if (seedSchedule == null || seedSchedule.isNull() || seedSchedule.isMissingNode()) {
            JsonNode processDefinition = firstPresent(seedRoot, "processDefinition", "workflowDefinition");
            seedSchedule = firstPresent(processDefinition, "schedule");
        }
        if (seedSchedule == null || seedSchedule.isNull() || seedSchedule.isMissingNode()) {
            return;
        }
        copyLongIfMissing(targetSchedule, "id", seedSchedule, "id", "scheduleId");
        copyTextIfMissing(targetSchedule, "timezoneId", seedSchedule, "timezoneId", "timezone");
        copyTextIfMissing(targetSchedule, "crontab", seedSchedule, "crontab", "cron");
    }

    private void mergeTaskSeed(ObjectNode targetRoot, JsonNode seedRoot) {
        JsonNode targetTasksNode = targetRoot.get("taskDefinitionList");
        if (!(targetTasksNode instanceof ArrayNode)) {
            return;
        }
        ArrayNode targetTasks = (ArrayNode) targetTasksNode;
        if (targetTasks.isEmpty()) {
            return;
        }
        JsonNode seedTasksNode = firstPresent(seedRoot, "taskDefinitionList", "tasks", "taskList");
        if (seedTasksNode == null || seedTasksNode.isNull() || seedTasksNode.isMissingNode()) {
            JsonNode processDefinition = firstPresent(seedRoot, "processDefinition", "workflowDefinition");
            seedTasksNode = firstPresent(processDefinition, "taskDefinitionList", "tasks", "taskList");
        }
        if (!(seedTasksNode instanceof ArrayNode)) {
            return;
        }
        Map<String, JsonNode> seedTaskByCode = new LinkedHashMap<>();
        for (JsonNode seedTask : (ArrayNode) seedTasksNode) {
            String key = taskCodeKey(seedTask);
            if (StringUtils.hasText(key)) {
                seedTaskByCode.putIfAbsent(key, seedTask);
            }
        }
        if (seedTaskByCode.isEmpty()) {
            return;
        }
        for (JsonNode targetTaskNode : targetTasks) {
            if (!(targetTaskNode instanceof ObjectNode)) {
                continue;
            }
            ObjectNode targetTask = (ObjectNode) targetTaskNode;
            JsonNode seedTask = seedTaskByCode.get(taskCodeKey(targetTask));
            if (seedTask == null || seedTask.isNull() || seedTask.isMissingNode()) {
                continue;
            }
            mergeTaskMetadata(targetTask, seedTask);
        }
    }

    private void mergeTaskMetadata(ObjectNode targetTask, JsonNode seedTask) {
        copyLongIfMissing(targetTask, "version", seedTask, "version", "taskVersion");
        copyLongIfMissing(targetTask, "taskGroupId", seedTask, "taskGroupId");
        copyTextIfMissing(targetTask, "flag", seedTask, "flag", "dolphinFlag");
        copyTextIfMissing(targetTask, "taskPriority", seedTask, "taskPriority", "priority");
        copyArrayIfMissing(targetTask, "inputTableIds", seedTask, "inputTableIds");
        copyArrayIfMissing(targetTask, "outputTableIds", seedTask, "outputTableIds");

        ObjectNode targetParams = ensureObjectNode(targetTask, "taskParams");
        JsonNode seedParams = firstPresent(seedTask, "taskParams");
        if (seedParams == null || seedParams.isNull() || seedParams.isMissingNode()) {
            return;
        }
        copyLongIfMissing(targetParams, "datasourceId", seedParams, "datasourceId", "datasource");
        copyLongIfMissing(targetParams, "datasource", seedParams, "datasource", "datasourceId");
        copyTextIfMissing(targetParams, "datasourceName", seedParams, "datasourceName");
        copyTextIfMissing(targetParams, "type", seedParams, "type", "datasourceType");
        copyTextIfMissing(targetParams, "datasourceType", seedParams, "datasourceType", "type");
    }

    private JsonNode firstPresent(JsonNode node, String... fields) {
        if (node == null || node.isNull() || node.isMissingNode() || fields == null) {
            return null;
        }
        for (String field : fields) {
            if (!StringUtils.hasText(field)) {
                continue;
            }
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.isMissingNode()) {
                return value;
            }
        }
        return null;
    }

    private void removeWorkflowStatusFields(JsonNode node) {
        if (!(node instanceof ObjectNode)) {
            return;
        }
        ObjectNode workflowNode = (ObjectNode) node;
        workflowNode.remove("releaseState");
        workflowNode.remove("status");
    }

    private String taskCodeKey(JsonNode taskNode) {
        if (taskNode == null || taskNode.isNull() || taskNode.isMissingNode()) {
            return null;
        }
        JsonNode taskCodeNode = firstPresent(taskNode, "taskCode", "code");
        if (taskCodeNode == null || taskCodeNode.isNull() || taskCodeNode.isMissingNode()) {
            return null;
        }
        String key = taskCodeNode.asText(null);
        return StringUtils.hasText(key) ? key.trim() : null;
    }

    private ObjectNode ensureObjectNode(ObjectNode root, String fieldName) {
        JsonNode existing = root.get(fieldName);
        if (existing instanceof ObjectNode) {
            return (ObjectNode) existing;
        }
        ObjectNode created = objectMapper.createObjectNode();
        root.set(fieldName, created);
        return created;
    }

    private void copyLongIfMissing(ObjectNode target,
            String targetField,
            JsonNode source,
            String... sourceFields) {
        if (target == null || !isMissingNodeValue(target.get(targetField))) {
            return;
        }
        Long value = readLong(source, sourceFields);
        if (value == null || value <= 0) {
            return;
        }
        target.put(targetField, value);
    }

    private void copyTextIfMissing(ObjectNode target,
            String targetField,
            JsonNode source,
            String... sourceFields) {
        if (target == null || !isMissingNodeValue(target.get(targetField))) {
            return;
        }
        String value = readText(source, sourceFields);
        if (!StringUtils.hasText(value)) {
            return;
        }
        target.put(targetField, value.trim());
    }

    private void copyArrayIfMissing(ObjectNode target,
            String targetField,
            JsonNode source,
            String... sourceFields) {
        if (target == null || !isMissingNodeValue(target.get(targetField))) {
            JsonNode current = target != null ? target.get(targetField) : null;
            if (current != null && current.isArray() && current.size() == 0) {
                // allow fallback fill from seed
            } else {
                return;
            }
        }
        JsonNode sourceArray = firstPresent(source, sourceFields);
        if (sourceArray == null || !sourceArray.isArray() || sourceArray.size() == 0) {
            return;
        }
        ArrayNode copied = objectMapper.createArrayNode();
        sourceArray.forEach(copied::add);
        target.set(targetField, copied);
    }

    private boolean isMissingNodeValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return true;
        }
        if (node.isTextual()) {
            return !StringUtils.hasText(node.asText(null));
        }
        return false;
    }

    private Long readLong(JsonNode node, String... fieldNames) {
        if (node == null || node.isNull() || node.isMissingNode() || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            if (!StringUtils.hasText(fieldName)) {
                continue;
            }
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull() || value.isMissingNode()) {
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
                    // ignore invalid number
                }
            }
        }
        return null;
    }

    private String readText(JsonNode node, String... fieldNames) {
        if (node == null || node.isNull() || node.isMissingNode() || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            if (!StringUtils.hasText(fieldName)) {
                continue;
            }
            JsonNode value = node.get(fieldName);
            if (value == null || value.isNull() || value.isMissingNode()) {
                continue;
            }
            String text = value.isTextual() ? value.asText() : value.toString();
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private Integer readInt(JsonNode node, String... fieldNames) {
        Long value = readLong(node, fieldNames);
        return value == null ? null : value.intValue();
    }

    private Map<String, Object> buildPlatformDefinitionDocument(DataWorkflow workflow,
            List<WorkflowTaskBinding> bindings,
            WorkflowTopologyResult topology) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", SNAPSHOT_SCHEMA_VERSION_DEFINITION);
        root.put("processDefinition", buildProcessDefinitionNode(workflow));
        List<Map<String, Object>> taskNodes = buildTaskSnapshotNodes(bindings);
        root.put("taskDefinitionList", buildTaskDefinitionNodes(taskNodes,
                workflow != null ? workflow.getTaskGroupName() : null));
        root.put("processTaskRelationList", buildProcessTaskRelationNodes(taskNodes, topology));
        root.put("schedule", buildScheduleDefinitionNode(workflow));
        root.put("xPlatformWorkflowMeta", buildPlatformWorkflowMetaNode(workflow));
        return root;
    }

    private Map<String, Object> buildProcessDefinitionNode(DataWorkflow workflow) {
        Map<String, Object> node = new LinkedHashMap<>();
        if (workflow == null) {
            return node;
        }
        node.put("code", workflow.getWorkflowCode());
        node.put("workflowCode", workflow.getWorkflowCode());
        node.put("projectCode", workflow.getProjectCode());
        node.put("name", workflow.getWorkflowName());
        node.put("description", workflow.getDescription());
        node.put("globalParams", workflow.getGlobalParams());
        node.put("taskGroupName", workflow.getTaskGroupName());
        node.put("publishStatus", workflow.getPublishStatus());
        return node;
    }

    private Map<String, Object> buildPlatformWorkflowMetaNode(DataWorkflow workflow) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (workflow == null) {
            return meta;
        }
        meta.put("workflowId", workflow.getId());
        meta.put("workflowCode", workflow.getWorkflowCode());
        meta.put("projectCode", workflow.getProjectCode());
        meta.put("workflowName", workflow.getWorkflowName());
        meta.put("publishStatus", workflow.getPublishStatus());
        return meta;
    }

    private List<Map<String, Object>> buildTaskDefinitionNodes(List<Map<String, Object>> taskNodes,
            String workflowTaskGroupName) {
        if (CollectionUtils.isEmpty(taskNodes)) {
            return Collections.emptyList();
        }
        String normalizedWorkflowTaskGroupName = normalizeText(workflowTaskGroupName);
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (Map<String, Object> taskNode : taskNodes) {
            if (taskNode == null) {
                continue;
            }
            Long runtimeTaskCode = asLong(taskNode.get("dolphinTaskCode"));
            if (runtimeTaskCode == null || runtimeTaskCode <= 0) {
                runtimeTaskCode = asLong(taskNode.get("taskId"));
            }
            if (runtimeTaskCode == null || runtimeTaskCode <= 0) {
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", runtimeTaskCode);
            item.put("taskCode", runtimeTaskCode);
            item.put("name", taskNode.get("taskName"));
            item.put("taskName", taskNode.get("taskName"));
            item.put("description", taskNode.get("taskDesc"));
            item.put("taskType", taskNode.get("dolphinNodeType"));
            item.put("nodeType", taskNode.get("dolphinNodeType"));
            item.put("version", taskNode.get("dolphinTaskVersion") != null ? taskNode.get("dolphinTaskVersion") : 1);
            item.put("timeout", taskNode.get("timeoutSeconds"));
            item.put("failRetryTimes", taskNode.get("retryTimes"));
            item.put("failRetryInterval", taskNode.get("retryInterval"));
            item.put("taskPriority", taskNode.get("priority"));
            item.put("flag", normalizeDolphinFlag(asText(taskNode.get("dolphinFlag"))));
            String taskGroupName = normalizeText(asText(taskNode.get("taskGroupName")));
            if (!StringUtils.hasText(taskGroupName)) {
                taskGroupName = normalizedWorkflowTaskGroupName;
            }
            item.put("taskGroupName", taskGroupName);

            Map<String, Object> taskParams = new LinkedHashMap<>();
            taskParams.put("sql", taskNode.get("taskSql"));
            taskParams.put("rawScript", taskNode.get("taskSql"));
            taskParams.put("datasourceName", taskNode.get("datasourceName"));
            taskParams.put("type", taskNode.get("datasourceType"));
            item.put("taskParams", taskParams);

            item.put("inputTableIds", taskNode.get("inputTableIds"));
            item.put("outputTableIds", taskNode.get("outputTableIds"));
            Map<String, Object> platformTaskMeta = new LinkedHashMap<>();
            platformTaskMeta.put("taskId", taskNode.get("taskId"));
            platformTaskMeta.put("platformTaskCode", taskNode.get("taskCode"));
            platformTaskMeta.put("entry", taskNode.get("entry"));
            platformTaskMeta.put("exit", taskNode.get("exit"));
            platformTaskMeta.put("nodeAttrs", taskNode.get("nodeAttrs"));
            platformTaskMeta.put("engine", taskNode.get("engine"));
            platformTaskMeta.put("platformTaskType", taskNode.get("taskType"));
            platformTaskMeta.put("dolphinTaskCode", taskNode.get("dolphinTaskCode"));
            platformTaskMeta.put("dolphinTaskVersion", taskNode.get("dolphinTaskVersion"));
            item.put("xPlatformTaskMeta", platformTaskMeta);
            definitions.add(item);
        }
        return definitions;
    }

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : null;
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeDolphinFlag(String value) {
        if (!StringUtils.hasText(value)) {
            return "YES";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "NO".equals(normalized) ? "NO" : "YES";
    }

    private List<Map<String, Object>> buildProcessTaskRelationNodes(List<Map<String, Object>> taskNodes,
            WorkflowTopologyResult topology) {
        if (CollectionUtils.isEmpty(taskNodes)) {
            return Collections.emptyList();
        }
        Map<Long, Long> runtimeTaskCodeByTaskId = new LinkedHashMap<>();
        List<Long> allTaskCodes = new ArrayList<>();
        for (Map<String, Object> taskNode : taskNodes) {
            if (taskNode == null) {
                continue;
            }
            Long taskId = asLong(taskNode.get("taskId"));
            Long runtimeTaskCode = asLong(taskNode.get("dolphinTaskCode"));
            if (runtimeTaskCode == null || runtimeTaskCode <= 0) {
                runtimeTaskCode = taskId;
            }
            if (taskId != null && runtimeTaskCode != null && runtimeTaskCode > 0) {
                runtimeTaskCodeByTaskId.put(taskId, runtimeTaskCode);
                allTaskCodes.add(runtimeTaskCode);
            }
        }
        if (runtimeTaskCodeByTaskId.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> edgeSet = new LinkedHashSet<>();
        List<Map<String, Object>> relations = new ArrayList<>();
        List<Map<String, Object>> inferredEdges = inferTaskEdges(taskNodes);
        for (Map<String, Object> edge : inferredEdges) {
            Long upstreamTaskId = asLong(edge.get("upstreamTaskId"));
            Long downstreamTaskId = asLong(edge.get("downstreamTaskId"));
            Long preTaskCode = runtimeTaskCodeByTaskId.get(upstreamTaskId);
            Long postTaskCode = runtimeTaskCodeByTaskId.get(downstreamTaskId);
            if (preTaskCode == null || postTaskCode == null || postTaskCode <= 0) {
                continue;
            }
            addRelationNode(relations, edgeSet, preTaskCode, postTaskCode);
        }

        Set<Long> entryCodes = new LinkedHashSet<>();
        if (topology != null && !CollectionUtils.isEmpty(topology.getEntryTaskIds())) {
            for (Long entryTaskId : topology.getEntryTaskIds()) {
                Long entryCode = runtimeTaskCodeByTaskId.get(entryTaskId);
                if (entryCode != null && entryCode > 0) {
                    entryCodes.add(entryCode);
                }
            }
        }
        if (entryCodes.isEmpty()) {
            Set<Long> downstreamWithUpstream = relations.stream()
                    .map(item -> asLong(item.get("postTaskCode")))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            for (Long taskCode : allTaskCodes) {
                if (!downstreamWithUpstream.contains(taskCode)) {
                    entryCodes.add(taskCode);
                }
            }
        }
        for (Long entryCode : entryCodes) {
            addRelationNode(relations, edgeSet, 0L, entryCode);
        }
        relations.sort(Comparator
                .comparing((Map<String, Object> item) -> asLong(item.get("preTaskCode")), Comparator.nullsLast(Long::compareTo))
                .thenComparing(item -> asLong(item.get("postTaskCode")), Comparator.nullsLast(Long::compareTo)));
        return relations;
    }

    private void addRelationNode(List<Map<String, Object>> relations,
            Set<String> edgeSet,
            Long preTaskCode,
            Long postTaskCode) {
        if (postTaskCode == null || postTaskCode <= 0) {
            return;
        }
        Long normalizedPre = preTaskCode == null ? 0L : preTaskCode;
        if (normalizedPre < 0) {
            return;
        }
        String key = normalizedPre + "->" + postTaskCode;
        if (!edgeSet.add(key)) {
            return;
        }
        Map<String, Object> relation = new LinkedHashMap<>();
        relation.put("preTaskCode", normalizedPre);
        relation.put("postTaskCode", postTaskCode);
        relations.add(relation);
    }

    private Map<String, Object> buildScheduleDefinitionNode(DataWorkflow workflow) {
        Map<String, Object> schedule = new LinkedHashMap<>();
        if (workflow == null) {
            return schedule;
        }
        schedule.put("id", workflow.getDolphinScheduleId());
        schedule.put("releaseState", workflow.getScheduleState());
        schedule.put("crontab", workflow.getScheduleCron());
        schedule.put("timezoneId", workflow.getScheduleTimezone());
        schedule.put("startTime", toDateTimeText(workflow.getScheduleStartTime()));
        schedule.put("endTime", toDateTimeText(workflow.getScheduleEndTime()));
        schedule.put("failureStrategy", workflow.getScheduleFailureStrategy());
        schedule.put("warningType", workflow.getScheduleWarningType());
        schedule.put("warningGroupId", workflow.getScheduleWarningGroupId());
        schedule.put("processInstancePriority", workflow.getScheduleProcessInstancePriority());
        schedule.put("workerGroup", workflow.getScheduleWorkerGroup());
        schedule.put("tenantCode", workflow.getScheduleTenantCode());
        schedule.put("environmentCode", workflow.getScheduleEnvironmentCode());
        schedule.put("scheduleAutoOnline", Boolean.TRUE.equals(workflow.getScheduleAutoOnline()));
        if (hasScheduleConfig(workflow)) {
            if (!StringUtils.hasText((String) schedule.get("failureStrategy"))) {
                schedule.put("failureStrategy", DEFAULT_FAILURE_STRATEGY);
            }
            if (!StringUtils.hasText((String) schedule.get("warningType"))) {
                schedule.put("warningType", DEFAULT_WARNING_TYPE);
            }
            if (schedule.get("warningGroupId") == null) {
                schedule.put("warningGroupId", DEFAULT_WARNING_GROUP_ID);
            }
            if (!StringUtils.hasText((String) schedule.get("processInstancePriority"))) {
                schedule.put("processInstancePriority", DEFAULT_PROCESS_INSTANCE_PRIORITY);
            }
            if (!StringUtils.hasText((String) schedule.get("workerGroup"))) {
                schedule.put("workerGroup", DEFAULT_WORKER_GROUP);
            }
            if (!StringUtils.hasText((String) schedule.get("tenantCode"))) {
                schedule.put("tenantCode", DEFAULT_TENANT_CODE);
            }
            if (schedule.get("environmentCode") == null) {
                schedule.put("environmentCode", DEFAULT_ENVIRONMENT_CODE);
            }
        }
        return schedule;
    }

    private List<Map<String, Object>> buildTaskSnapshotNodes(List<WorkflowTaskBinding> bindings) {
        List<Long> taskIds = collectTaskIds(bindings);
        if (CollectionUtils.isEmpty(taskIds)) {
            return Collections.emptyList();
        }

        List<DataTask> taskRows = dataTaskMapper.selectBatchIds(taskIds);
        Map<Long, DataTask> taskById = taskRows.stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(DataTask::getId, item -> item, (left, right) -> left));

        Map<Long, WorkflowTaskBinding> bindingByTaskId = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(bindings)) {
            for (WorkflowTaskBinding binding : bindings) {
                if (binding == null || binding.getTaskId() == null) {
                    continue;
                }
                bindingByTaskId.putIfAbsent(binding.getTaskId(), binding);
            }
        }

        Map<Long, List<Long>> readTablesByTask = loadTaskTableRelationMap(taskIds, "read");
        Map<Long, List<Long>> writeTablesByTask = loadTaskTableRelationMap(taskIds, "write");

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Long taskId : taskIds) {
            DataTask task = taskById.get(taskId);
            if (task == null) {
                continue;
            }
            WorkflowTaskBinding binding = bindingByTaskId.get(taskId);
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("taskId", task.getId());
            node.put("taskCode", task.getTaskCode());
            node.put("taskName", task.getTaskName());
            node.put("taskType", task.getTaskType());
            node.put("engine", task.getEngine());
            node.put("dolphinNodeType", task.getDolphinNodeType());
            node.put("taskSql", normalizeSql(task.getTaskSql()));
            node.put("taskDesc", task.getTaskDesc());
            node.put("datasourceName", task.getDatasourceName());
            node.put("datasourceType", task.getDatasourceType());
            node.put("taskGroupName", task.getTaskGroupName());
            node.put("dolphinFlag", normalizeDolphinFlag(task.getDolphinFlag()));
            node.put("retryTimes", task.getRetryTimes());
            node.put("retryInterval", task.getRetryInterval());
            node.put("timeoutSeconds", task.getTimeoutSeconds());
            node.put("priority", task.getPriority());
            node.put("dolphinTaskCode", task.getDolphinTaskCode());
            node.put("dolphinTaskVersion", task.getDolphinTaskVersion());
            node.put("inputTableIds", readTablesByTask.getOrDefault(taskId, Collections.emptyList()));
            node.put("outputTableIds", writeTablesByTask.getOrDefault(taskId, Collections.emptyList()));
            node.put("entry", binding != null ? binding.getEntry() : null);
            node.put("exit", binding != null ? binding.getExit() : null);
            node.put("nodeAttrs", binding != null ? binding.getNodeAttrs() : null);
            nodes.add(node);
        }
        return nodes;
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

    private List<Map<String, Object>> inferTaskEdges(List<Map<String, Object>> taskNodes) {
        if (CollectionUtils.isEmpty(taskNodes)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> sorted = taskNodes.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(item -> asLong(item.get("taskId")), Comparator.nullsLast(Long::compareTo)))
                .collect(Collectors.toList());
        Set<String> edgeSet = new LinkedHashSet<>();
        List<Map<String, Object>> edges = new ArrayList<>();
        for (Map<String, Object> downstream : sorted) {
            Long downstreamTaskId = asLong(downstream.get("taskId"));
            Set<Long> downstreamReads = new LinkedHashSet<>(toLongList(downstream.get("inputTableIds")));
            if (downstreamTaskId == null || downstreamReads.isEmpty()) {
                continue;
            }
            for (Map<String, Object> upstream : sorted) {
                Long upstreamTaskId = asLong(upstream.get("taskId"));
                if (upstreamTaskId == null || Objects.equals(upstreamTaskId, downstreamTaskId)) {
                    continue;
                }
                Set<Long> upstreamWrites = new LinkedHashSet<>(toLongList(upstream.get("outputTableIds")));
                if (upstreamWrites.isEmpty()) {
                    continue;
                }
                Set<Long> intersection = new LinkedHashSet<>(upstreamWrites);
                intersection.retainAll(downstreamReads);
                if (intersection.isEmpty()) {
                    continue;
                }
                String edgeKey = upstreamTaskId + "->" + downstreamTaskId;
                if (edgeSet.add(edgeKey)) {
                    Map<String, Object> edge = new LinkedHashMap<>();
                    edge.put("upstreamTaskId", upstreamTaskId);
                    edge.put("downstreamTaskId", downstreamTaskId);
                    edges.add(edge);
                }
            }
        }
        edges.sort(Comparator
                .comparing((Map<String, Object> edge) -> asLong(edge.get("upstreamTaskId")), Comparator.nullsLast(Long::compareTo))
                .thenComparing(edge -> asLong(edge.get("downstreamTaskId")), Comparator.nullsLast(Long::compareTo)));
        return edges;
    }

    private Long asLong(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<Long> toLongList(Object value) {
        if (!(value instanceof List<?>)) {
            return Collections.emptyList();
        }
        List<?> source = (List<?>) value;
        if (source.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>();
        for (Object item : source) {
            Long converted = asLong(item);
            if (converted != null) {
                result.add(converted);
            }
        }
        return result;
    }

    private String normalizeSql(String sql) {
        if (!StringUtils.hasText(sql)) {
            return null;
        }
        return sql.replace("\r\n", "\n").trim();
    }

    private List<WorkflowTaskBinding> buildTaskBindingsFromRelations(List<WorkflowTaskRelation> relations) {
        if (CollectionUtils.isEmpty(relations)) {
            return Collections.emptyList();
        }
        List<WorkflowTaskBinding> bindings = new ArrayList<>();
        for (WorkflowTaskRelation relation : relations) {
            if (relation == null || relation.getTaskId() == null) {
                continue;
            }
            WorkflowTaskBinding binding = new WorkflowTaskBinding();
            binding.setTaskId(relation.getTaskId());
            binding.setEntry(relation.getIsEntry());
            binding.setExit(relation.getIsExit());
            if (StringUtils.hasText(relation.getNodeAttrs())) {
                try {
                    binding.setNodeAttrs(objectMapper.readValue(relation.getNodeAttrs(), Map.class));
                } catch (Exception ignored) {
                    // ignore malformed node attrs
                }
            }
            bindings.add(binding);
        }
        return bindings;
    }

    private String resolveWorkflowOperator(DataWorkflow workflow, String operator) {
        if (StringUtils.hasText(operator)) {
            return operator.trim();
        }
        if (workflow != null && StringUtils.hasText(workflow.getUpdatedBy())) {
            return workflow.getUpdatedBy().trim();
        }
        if (workflow != null && StringUtils.hasText(workflow.getCreatedBy())) {
            return workflow.getCreatedBy().trim();
        }
        return "system";
    }

    private String toDateTimeText(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private void persistTaskRelations(Long workflowId,
            List<WorkflowTaskBinding> tasks,
            Long previousVersionId,
            WorkflowTopologyResult topology) {
        // Rebuilding workflow topology reuses the same task ids, so logical delete would
        // immediately conflict with workflow_task_relation.uk_task on reinsert.
        workflowTaskRelationMapper.hardDeleteByWorkflowId(workflowId);
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        Set<Long> entrySet = topology != null && topology.getEntryTaskIds() != null
                ? topology.getEntryTaskIds()
                : Collections.emptySet();
        Set<Long> exitSet = topology != null && topology.getExitTaskIds() != null
                ? topology.getExitTaskIds()
                : Collections.emptySet();
        for (WorkflowTaskBinding binding : tasks) {
            if (binding.getTaskId() == null) {
                continue;
            }
            ensureTaskAssignable(binding.getTaskId(), workflowId);
            WorkflowTaskRelation relation = new WorkflowTaskRelation();
            relation.setWorkflowId(workflowId);
            relation.setTaskId(binding.getTaskId());
            relation.setIsEntry(entrySet.contains(binding.getTaskId()));
            relation.setIsExit(exitSet.contains(binding.getTaskId()));
            relation.setNodeAttrs(toJson(binding.getNodeAttrs()));
            relation.setVersionId(previousVersionId);
            relation.setUpstreamTaskCount(tableTaskRelationMapper.countUpstreamTasks(binding.getTaskId()));
            relation.setDownstreamTaskCount(tableTaskRelationMapper.countDownstreamTasks(binding.getTaskId()));
            workflowTaskRelationMapper.insert(relation);
        }
    }

    /**
     * 重新计算工作流中所有任务的上下游关系
     * 用于在单个任务被添加/更新/删除后重新计算整个工作流的关系
     */
    public void refreshTaskRelations(Long workflowId) {
        // 获取工作流中的所有任务
        List<WorkflowTaskRelation> existingRelations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));

        // 转换为 List<WorkflowTask bindings>，保留必要的属性
        List<WorkflowTaskBinding> taskBindings = new ArrayList<>();
        Long versionId = null;
        WorkflowTopologyResult topology = null;

        for (WorkflowTaskRelation relation : existingRelations) {
            WorkflowTaskBinding binding = new WorkflowTaskBinding();
            binding.setTaskId(relation.getTaskId());
            binding.setEntry(relation.getIsEntry());
            binding.setExit(relation.getIsExit());
            // 将原来的 nodeAttrs 转换回 NodeAttrs
            if (StringUtils.hasText(relation.getNodeAttrs())) {
                try {
                    binding.setNodeAttrs(objectMapper.readValue(relation.getNodeAttrs(), Map.class));
                } catch (Exception ex) {
                    // 忽略解析错误，使用空值
                }
            }
            taskBindings.add(binding);
            versionId = relation.getVersionId();
        }

        // 重新构建拓扑信息
        if (!taskBindings.isEmpty()) {
            List<Long> taskIds = taskBindings.stream()
                    .map(WorkflowTaskBinding::getTaskId)
                    .collect(Collectors.toList());
            topology = workflowTopologyService.buildTopology(taskIds);
        }

        // 重新保存所有关系（会先删除再插入）
        persistTaskRelations(workflowId, taskBindings, versionId, topology);
    }

    private List<Long> orderTaskIds(Set<Long> sourceIds, List<Long> taskOrder) {
        if (CollectionUtils.isEmpty(sourceIds) || CollectionUtils.isEmpty(taskOrder)) {
            return CollectionUtils.isEmpty(sourceIds) ? Collections.emptyList() : new ArrayList<>(sourceIds);
        }
        List<Long> ordered = new ArrayList<>();
        taskOrder.forEach(taskId -> {
            if (sourceIds.contains(taskId)) {
                ordered.add(taskId);
            }
        });
        if (ordered.size() < sourceIds.size()) {
            sourceIds.stream()
                    .filter(id -> !ordered.contains(id))
                    .forEach(ordered::add);
        }
        return ordered;
    }

    private List<Long> collectTaskIds(List<WorkflowTaskBinding> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        for (WorkflowTaskBinding task : tasks) {
            if (task != null && task.getTaskId() != null) {
                ordered.add(task.getTaskId());
            }
        }
        return new ArrayList<>(ordered);
    }

    private List<WorkflowTaskBinding> normalizeTaskBindings(List<WorkflowTaskBinding> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        return tasks;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize json", e);
        }
    }

    private void ensureTaskAssignable(Long taskId, Long workflowId) {
        DataTask dataTask = dataTaskMapper.selectById(taskId);
        if (dataTask == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        WorkflowTaskRelation existing = workflowTaskRelationMapper.selectOne(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getTaskId, taskId));
        if (existing != null && !existing.getWorkflowId().equals(workflowId)) {
            throw new IllegalStateException("任务已归属其他工作流, taskId=" + taskId);
        }
    }

    private String defaultJson(String definitionJson) {
        return normalizeJsonText(definitionJson);
    }

    private void normalizeTaskMetadata(List<Long> taskIds, String workflowTaskGroupName) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return;
        }
        List<DataTask> tasks = dataTaskMapper.selectBatchIds(taskIds);
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        DatasourceCatalog datasourceCatalog = tasks.stream()
                .filter(Objects::nonNull)
                .map(DataTask::getDatasourceName)
                .anyMatch(StringUtils::hasText)
                        ? loadDatasourceCatalog()
                        : DatasourceCatalog.empty();
        List<Long> existingDolphinTaskCodes = tasks.stream()
                .map(DataTask::getDolphinTaskCode)
                .filter(Objects::nonNull)
                .filter(code -> code > 0)
                .collect(Collectors.toList());
        dolphinSchedulerService.alignSequenceWithExistingTasks(existingDolphinTaskCodes);
        for (DataTask task : tasks) {
            if (task == null || task.getId() == null) {
                continue;
            }
            boolean changed = false;
            if (task.getDolphinTaskCode() == null || task.getDolphinTaskCode() <= 0) {
                task.setDolphinTaskCode(dolphinSchedulerService.nextTaskCode());
                changed = true;
            }
            if (task.getDolphinTaskVersion() == null || task.getDolphinTaskVersion() <= 0) {
                task.setDolphinTaskVersion(1);
                changed = true;
            }
            if (!StringUtils.hasText(task.getTaskGroupName()) && StringUtils.hasText(workflowTaskGroupName)) {
                task.setTaskGroupName(workflowTaskGroupName.trim());
                changed = true;
            }
            if (task.getPriority() == null) {
                task.setPriority(DEFAULT_TASK_PRIORITY);
                changed = true;
            }
            if (task.getRetryTimes() == null) {
                task.setRetryTimes(DEFAULT_TASK_RETRY_TIMES);
                changed = true;
            }
            if (task.getRetryInterval() == null) {
                task.setRetryInterval(DEFAULT_TASK_RETRY_INTERVAL);
                changed = true;
            }
            if (task.getTimeoutSeconds() == null || task.getTimeoutSeconds() <= 0) {
                task.setTimeoutSeconds(DEFAULT_TASK_TIMEOUT_SECONDS);
                changed = true;
            }
            String dolphinFlag = normalizeDolphinFlag(task.getDolphinFlag());
            if (!Objects.equals(task.getDolphinFlag(), dolphinFlag)) {
                task.setDolphinFlag(dolphinFlag);
                changed = true;
            }
            String datasourceName = normalizeText(task.getDatasourceName());
            if (!Objects.equals(task.getDatasourceName(), datasourceName)) {
                task.setDatasourceName(datasourceName);
                changed = true;
            }
            String datasourceType = resolveDatasourceType(
                    resolveDatasourceOption(datasourceCatalog, null, datasourceName),
                    task.getDatasourceType());
            if (!Objects.equals(task.getDatasourceType(), datasourceType)) {
                task.setDatasourceType(datasourceType);
                changed = true;
            }
            if (changed) {
                dataTaskMapper.updateById(task);
            }
        }
    }

    private static final class DatasourceCatalog {
        private final Map<String, DolphinDatasourceOption> byName;
        private final Map<Long, DolphinDatasourceOption> byId;

        private DatasourceCatalog(Map<String, DolphinDatasourceOption> byName,
                Map<Long, DolphinDatasourceOption> byId) {
            this.byName = byName;
            this.byId = byId;
        }

        private static DatasourceCatalog empty() {
            return new DatasourceCatalog(Collections.emptyMap(), Collections.emptyMap());
        }
    }

    private void normalizeWorkflowScheduleDefaults(DataWorkflow workflow) {
        if (!hasScheduleConfig(workflow)) {
            return;
        }
        if (!StringUtils.hasText(workflow.getScheduleFailureStrategy())) {
            workflow.setScheduleFailureStrategy(DEFAULT_FAILURE_STRATEGY);
        }
        if (!StringUtils.hasText(workflow.getScheduleWarningType())) {
            workflow.setScheduleWarningType(DEFAULT_WARNING_TYPE);
        }
        if (workflow.getScheduleWarningGroupId() == null) {
            workflow.setScheduleWarningGroupId(DEFAULT_WARNING_GROUP_ID);
        }
        if (!StringUtils.hasText(workflow.getScheduleProcessInstancePriority())) {
            workflow.setScheduleProcessInstancePriority(DEFAULT_PROCESS_INSTANCE_PRIORITY);
        }
        if (!StringUtils.hasText(workflow.getScheduleWorkerGroup())) {
            workflow.setScheduleWorkerGroup(DEFAULT_WORKER_GROUP);
        }
        if (!StringUtils.hasText(workflow.getScheduleTenantCode())) {
            workflow.setScheduleTenantCode(DEFAULT_TENANT_CODE);
        }
        if (workflow.getScheduleEnvironmentCode() == null) {
            workflow.setScheduleEnvironmentCode(DEFAULT_ENVIRONMENT_CODE);
        }
    }

    private boolean hasScheduleConfig(DataWorkflow workflow) {
        if (workflow == null) {
            return false;
        }
        return (workflow.getDolphinScheduleId() != null && workflow.getDolphinScheduleId() > 0)
                || StringUtils.hasText(workflow.getScheduleCron())
                || StringUtils.hasText(workflow.getScheduleTimezone())
                || workflow.getScheduleStartTime() != null
                || workflow.getScheduleEndTime() != null;
    }

    private Long resolveProjectCode(Long requestProjectCode) {
        if (requestProjectCode != null && requestProjectCode > 0) {
            return requestProjectCode;
        }
        return null;
    }

    private Long resolveDolphinConfigId(Long requestDolphinConfigId) {
        if (requestDolphinConfigId != null && requestDolphinConfigId > 0) {
            return dolphinConfigService.getEnabledConfig(requestDolphinConfigId).getId();
        }
        DolphinConfig config = dolphinConfigService.getDefaultConfig();
        return config != null ? config.getId() : null;
    }

    private String refreshDefinitionRuntimeIds(String definitionJson, Long dolphinConfigId, Long projectCode) {
        if (!StringUtils.hasText(definitionJson)) {
            return definitionJson;
        }
        try {
            JsonNode root = objectMapper.readTree(definitionJson);
            if (!(root instanceof ObjectNode)) {
                return definitionJson;
            }
            ObjectNode rootObject = (ObjectNode) root;
            resetDefinitionRuntimeBinding(rootObject, projectCode);
            enrichDefinitionMetadataFromCatalog(rootObject, dolphinConfigId);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            log.warn("Failed to refresh workflow definition metadata for Dolphin config {}: {}",
                    dolphinConfigId, ex.getMessage());
            return definitionJson;
        }
    }

    private void resetDefinitionRuntimeBinding(ObjectNode rootObject, Long projectCode) {
        if (rootObject == null) {
            return;
        }
        resetWorkflowDefinitionNode(firstPresent(rootObject, "processDefinition"), projectCode);
        resetWorkflowDefinitionNode(firstPresent(rootObject, "workflowDefinition"), projectCode);
        resetWorkflowDefinitionNode(firstPresent(rootObject, "workflow"), projectCode);

        JsonNode metaNode = rootObject.get("xPlatformWorkflowMeta");
        if (metaNode instanceof ObjectNode) {
            ObjectNode metaObject = (ObjectNode) metaNode;
            metaObject.remove("workflowCode");
            if (projectCode != null && projectCode > 0) {
                metaObject.put("projectCode", projectCode);
            } else {
                metaObject.remove("projectCode");
            }
        }

        resetScheduleRuntimeBinding(rootObject.get("schedule"));
        resetNestedScheduleRuntimeBinding(firstPresent(rootObject, "processDefinition"));
        resetNestedScheduleRuntimeBinding(firstPresent(rootObject, "workflowDefinition"));
        resetNestedScheduleRuntimeBinding(firstPresent(rootObject, "workflow"));
    }

    private void resetWorkflowDefinitionNode(JsonNode node, Long projectCode) {
        if (!(node instanceof ObjectNode)) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        object.remove("code");
        object.remove("workflowCode");
        object.remove("processDefinitionCode");
        if (projectCode != null && projectCode > 0) {
            object.put("projectCode", projectCode);
        } else {
            object.remove("projectCode");
        }
    }

    private void resetNestedScheduleRuntimeBinding(JsonNode definitionNode) {
        if (!(definitionNode instanceof ObjectNode)) {
            return;
        }
        resetScheduleRuntimeBinding(definitionNode.get("schedule"));
    }

    private void resetScheduleRuntimeBinding(JsonNode scheduleNode) {
        if (!(scheduleNode instanceof ObjectNode)) {
            return;
        }
        ObjectNode scheduleObject = (ObjectNode) scheduleNode;
        scheduleObject.remove("id");
        scheduleObject.remove("scheduleId");
        scheduleObject.remove("dolphinScheduleId");
        scheduleObject.put("scheduleState", "OFFLINE");
        scheduleObject.put("releaseState", "OFFLINE");
    }

    private void attachLatestInstanceInfo(List<DataWorkflow> workflows) {
        if (CollectionUtils.isEmpty(workflows)) {
            return;
        }
        for (DataWorkflow workflow : workflows) {
            if (workflow.getId() == null) {
                continue;
            }
            WorkflowInstanceCache latest = null;
            boolean realtimeLoaded = false;
            if (workflow.getWorkflowCode() != null && workflow.getWorkflowCode() > 0) {
                try {
                    List<WorkflowInstanceSummary> summaries = dolphinSchedulerService
                            .listWorkflowInstances(workflow.getDolphinConfigId(), workflow.getWorkflowCode(), 1);
                    realtimeLoaded = true;
                    if (!summaries.isEmpty()) {
                        latest = mapSummaryToCache(workflow.getId(), summaries.get(0));
                    }
                } catch (Exception ex) {
                    log.warn("Failed to fetch latest realtime instance for workflow {}: {}",
                            workflow.getWorkflowName(), ex.getMessage());
                }
            }
            if (latest == null && !realtimeLoaded) {
                latest = workflowInstanceCacheService.findLatest(workflow.getId());
            }
            if (latest != null) {
                applyInstance(
                        workflow,
                        latest.getInstanceId(),
                        latest.getState(),
                        latest.getStartTime(),
                        latest.getEndTime());
            }
        }
    }

    private void applyInstance(DataWorkflow workflow,
            Long instanceId,
            String state,
            Object start,
            Object end) {
        workflow.setLatestInstanceId(instanceId);
        workflow.setLatestInstanceState(state);
        workflow.setLatestInstanceStartTime(toLocalDateTime(start));
        workflow.setLatestInstanceEndTime(toLocalDateTime(end));
    }

    private LocalDateTime toLocalDateTime(Object temporal) {
        if (temporal == null) {
            return null;
        }
        if (temporal instanceof LocalDateTime) {
            return (LocalDateTime) temporal;
        }
        if (temporal instanceof Date) {
            return ((Date) temporal).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        if (temporal instanceof String) {
            String value = (String) temporal;
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                return parseFlexibleDateTime(value);
            } catch (DateTimeParseException ignore) {
                return null;
            }
        }
        return null;
    }

    private List<WorkflowInstanceCache> mapSummariesToCaches(Long workflowId,
            List<WorkflowInstanceSummary> summaries) {
        if (workflowId == null || CollectionUtils.isEmpty(summaries)) {
            return Collections.emptyList();
        }
        return summaries.stream()
                .map(summary -> mapSummaryToCache(workflowId, summary))
                .collect(Collectors.toList());
    }

    private WorkflowInstanceCache mapSummaryToCache(Long workflowId, WorkflowInstanceSummary summary) {
        WorkflowInstanceCache cache = new WorkflowInstanceCache();
        cache.setWorkflowId(workflowId);
        cache.setInstanceId(summary.getInstanceId());
        cache.setState(summary.getState());
        cache.setTriggerType(summary.getCommandType());
        cache.setDurationMs(summary.getDurationMs());
        cache.setStartTime(parseToDate(summary.getStartTime()));
        cache.setEndTime(parseToDate(summary.getEndTime()));
        cache.setExtra(summary.getRawJson());
        return cache;
    }

    private Date parseToDate(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            LocalDateTime ldt = parseFlexibleDateTime(text);
            if (ldt == null) {
                return null;
            }
            return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private LocalDateTime parseFlexibleDateTime(String raw) {
        String candidate = raw.replace("Z", "");
        for (DateTimeFormatter formatter : DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(candidate, formatter);
            } catch (DateTimeParseException ignore) {
                // try next
            }
        }
        return null;
    }

    /**
     * 删除工作流
     * 软删除工作流定义；默认保留任务定义以便复用
     */
    @Transactional
    public void deleteWorkflow(Long workflowId) {
        deleteWorkflow(workflowId, false);
    }

    /**
     * 删除工作流
     *
     * @param workflowId          工作流ID
     * @param cascadeDeleteTasks  是否级联软删除绑定任务
     */
    @Transactional
    public void deleteWorkflow(Long workflowId, boolean cascadeDeleteTasks) {
        if (workflowId == null) {
            throw new IllegalArgumentException("工作流ID不能为空");
        }

        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            log.warn("工作流不存在: {}", workflowId);
            return;
        }

        List<Long> taskIds = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId))
                .stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        log.info("开始删除工作流: workflowId={}, workflowCode={}, cascadeDeleteTasks={}, taskCount={}",
                workflowId, workflow.getWorkflowCode(), cascadeDeleteTasks, taskIds.size());

        try {
            if (workflow.getWorkflowCode() != null && workflow.getWorkflowCode() > 0) {
                try {
                    boolean dolphinWorkflowExists = dolphinSchedulerService.checkWorkflowExists(workflow.getWorkflowCode());
                    if (!dolphinWorkflowExists) {
                        log.info("DolphinScheduler中不存在工作流，跳过同步删除: {}", workflow.getWorkflowCode());
                    } else {
                        if (workflow.getDolphinScheduleId() != null && workflow.getDolphinScheduleId() > 0) {
                            try {
                                dolphinSchedulerService.offlineWorkflowSchedule(workflow.getDolphinScheduleId());
                            } catch (Exception ex) {
                                log.warn("Failed to offline schedule {} before workflow delete: {}",
                                        workflow.getDolphinScheduleId(), ex.getMessage());
                            }
                        }
                        dolphinSchedulerService.setWorkflowReleaseState(workflow.getWorkflowCode(), "OFFLINE");
                        dolphinSchedulerService.deleteWorkflow(workflow.getWorkflowCode());
                        log.info("已删除DolphinScheduler中的工作流定义: {}", workflow.getWorkflowCode());
                    }
                } catch (Exception e) {
                    log.warn("删除DolphinScheduler工作流定义失败: {}", e.getMessage());
                }
            }

            if (cascadeDeleteTasks && !taskIds.isEmpty()) {
                dataLineageMapper.delete(
                        Wrappers.<DataLineage>lambdaQuery()
                                .in(DataLineage::getTaskId, taskIds));
                tableTaskRelationMapper.delete(
                        Wrappers.<TableTaskRelation>lambdaQuery()
                                .in(TableTaskRelation::getTaskId, taskIds));
                dataTaskMapper.deleteBatchIds(taskIds);
                log.info("已级联软删除任务: workflowId={}, taskCount={}", workflowId, taskIds.size());
            }

            workflowTaskRelationMapper.hardDeleteByWorkflowId(workflowId);
            log.info("已删除工作流任务关联关系: workflowId={}", workflowId);

            dataWorkflowMapper.deleteById(workflowId);
            log.info("已软删除工作流定义: {}", workflowId);

            log.info("工作流删除完成: workflowId={}", workflowId);
        } catch (Exception e) {
            log.error("删除工作流失败: {}", workflowId, e);
            throw new RuntimeException("删除工作流失败: " + e.getMessage(), e);
        }
    }
}
