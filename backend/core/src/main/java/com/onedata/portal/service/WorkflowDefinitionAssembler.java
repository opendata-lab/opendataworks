package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;



/**
 * 工作流定义 JSON 组装与运行态绑定处理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowDefinitionAssembler {

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

    private final ObjectMapper objectMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataTaskMapper dataTaskMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final WorkflowTaskRelationService workflowTaskRelationService;

    public String resolveDefinitionJson(DataWorkflow workflow,
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

    public String sanitizeDefinitionJsonForExport(String definitionJson) {
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

    public String defaultJson(String definitionJson) {
        return normalizeJsonText(definitionJson);
    }

    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize json", e);
        }
    }

    public List<Long> orderTaskIds(Set<Long> sourceIds, List<Long> taskOrder) {
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

    public void normalizeWorkflowScheduleDefaults(DataWorkflow workflow) {
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

    public void normalizeTaskMetadata(List<Long> taskIds, String workflowTaskGroupName) {
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

    public String refreshRuntimeBindings(String definitionJson, Long dolphinConfigId, Long projectCode) {
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
            enrichMetadataFromCatalog(rootObject, dolphinConfigId);
            return objectMapper.writeValueAsString(root);
        } catch (Exception ex) {
            log.warn("Failed to refresh workflow definition metadata for Dolphin config {}: {}",
                    dolphinConfigId, ex.getMessage());
            return definitionJson;
        }
    }

    public void enrichMetadataFromCatalog(ObjectNode rootNode, Long dolphinConfigId) {
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
        } catch (Exception e) {
            log.trace("格式化 JSON 失败，回退原始文本", e);
            return trimmed;
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
            enrichMetadataFromCatalog(generatedNode, dolphinConfigId);
            return objectMapper.writeValueAsString(generatedNode);
        } catch (Exception ex) {
            return toJson(generatedDefinition);
        }
    }

    private void applyDefinitionMetadataSeed(ObjectNode targetRoot, String seedJson) {
        if (targetRoot == null || !StringUtils.hasText(seedJson)) {
            return;
        }
        JsonNode seedRoot;
        try {
            seedRoot = objectMapper.readTree(seedJson);
        } catch (Exception e) {
            log.trace("解析定义元数据种子 JSON 失败，跳过", e);
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
            taskParams.put("datasourceType", taskNode.get("datasourceType"));
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
        List<Long> taskIds = workflowTaskRelationService.collectTaskIds(bindings);
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

    private String asText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : null;
    }

    private String normalizeDolphinFlag(String value) {
        if (!StringUtils.hasText(value)) {
            return "YES";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "NO".equals(normalized) ? "NO" : "YES";
    }

    private String resolveDatasourceType(DolphinDatasourceOption datasourceOption, String fallbackType) {
        String catalogType = datasourceOption == null ? null : normalizeText(datasourceOption.getType());
        return StringUtils.hasText(catalogType) ? catalogType : normalizeText(fallbackType);
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

    private String toDateTimeText(LocalDateTime value) {
        return value == null ? null : value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
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

    private DatasourceCatalog loadDatasourceCatalog() {
        return loadDatasourceCatalog(null);
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
        resetTaskGroupRuntimeBinding(rootObject.get("taskDefinitionList"));
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

    private void resetTaskGroupRuntimeBinding(JsonNode taskListNode) {
        if (!(taskListNode instanceof ArrayNode)) {
            return;
        }
        for (JsonNode taskNode : (ArrayNode) taskListNode) {
            if (taskNode instanceof ObjectNode) {
                ((ObjectNode) taskNode).remove("taskGroupId");
            }
        }
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

    private ObjectNode ensureObjectNode(ObjectNode root, String fieldName) {
        JsonNode existing = root.get(fieldName);
        if (existing instanceof ObjectNode) {
            return (ObjectNode) existing;
        }
        ObjectNode created = objectMapper.createObjectNode();
        root.set(fieldName, created);
        return created;
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
                } catch (NumberFormatException e) {
                    log.trace("解析 Long 失败，跳过无效文本: {}", text, e);
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

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
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
}
