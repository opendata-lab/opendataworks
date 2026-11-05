package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionDeleteResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionDiffSection;
import com.onedata.portal.dto.workflow.WorkflowVersionDiffSummary;
import com.onedata.portal.dto.workflow.WorkflowVersionErrorCodes;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackResponse;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;



/**
 * 工作流版本比对与回退服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowVersionOperationService {

    private static final int SNAPSHOT_SCHEMA_VERSION_DEFINITION = 3;

    private final WorkflowVersionMapper workflowVersionMapper;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final DataTaskMapper dataTaskMapper;
    private final WorkflowPublishRecordMapper workflowPublishRecordMapper;
    private final WorkflowRuntimeSyncRecordMapper workflowRuntimeSyncRecordMapper;
    private final DataTaskService dataTaskService;
    private final WorkflowService workflowService;
    private final ObjectMapper objectMapper;

    public WorkflowVersionCompareResponse compare(Long workflowId, WorkflowVersionCompareRequest request) {
        Long rightVersionId = request != null ? request.getRightVersionId() : null;
        Long leftVersionId = request != null ? request.getLeftVersionId() : null;

        if (rightVersionId == null) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_COMPARE_INVALID, "rightVersionId 不能为空");
        }

        if (leftVersionId != null && leftVersionId.equals(rightVersionId)) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_COMPARE_INVALID, "leftVersionId 与 rightVersionId 不能相同");
        }

        if (leftVersionId != null && leftVersionId > rightVersionId) {
            Long tmp = leftVersionId;
            leftVersionId = rightVersionId;
            rightVersionId = tmp;
        }

        WorkflowVersion rightVersion = requireVersion(workflowId, rightVersionId);
        WorkflowVersion leftVersion = leftVersionId == null ? null : requireVersion(workflowId, leftVersionId);
        requireV3Version(rightVersion, WorkflowVersionErrorCodes.VERSION_COMPARE_ONLY_V3, "版本比对");
        if (leftVersion != null) {
            requireV3Version(leftVersion, WorkflowVersionErrorCodes.VERSION_COMPARE_ONLY_V3, "版本比对");
        }

        SnapshotNormalized left = leftVersion == null ? SnapshotNormalized.empty() : normalizeSnapshotForCompare(leftVersion);
        SnapshotNormalized right = normalizeSnapshotForCompare(rightVersion);

        WorkflowVersionCompareResponse response = new WorkflowVersionCompareResponse();
        response.setLeftVersionId(leftVersion != null ? leftVersion.getId() : null);
        response.setLeftVersionNo(leftVersion != null ? leftVersion.getVersionNo() : null);
        response.setRightVersionId(rightVersion.getId());
        response.setRightVersionNo(rightVersion.getVersionNo());

        compareFlatFields(left.getWorkflow(), right.getWorkflow(), response.getAdded().getWorkflowFields(),
                response.getRemoved().getWorkflowFields(), response.getModified().getWorkflowFields(),
                response.getUnchanged().getWorkflowFields(), "workflow");

        compareTasks(left.getTasks(), right.getTasks(), response.getAdded().getTasks(), response.getRemoved().getTasks(),
                response.getModified().getTasks(), response.getUnchanged().getTasks());

        compareEdgeSets(left.getEdges(), right.getEdges(), response.getAdded().getEdges(), response.getRemoved().getEdges(),
                response.getUnchanged().getEdges(), left.getTasks(), right.getTasks());

        compareFlatFields(left.getSchedule(), right.getSchedule(), response.getAdded().getSchedules(),
                response.getRemoved().getSchedules(), response.getModified().getSchedules(),
                response.getUnchanged().getSchedules(), "schedule");

        WorkflowVersionDiffSummary summary = response.getSummary();
        summary.setAdded(totalCount(response.getAdded()));
        summary.setRemoved(totalCount(response.getRemoved()));
        summary.setModified(totalCount(response.getModified()));
        summary.setUnchanged(totalCount(response.getUnchanged()));
        response.setChanged(summary.getAdded() > 0 || summary.getRemoved() > 0 || summary.getModified() > 0);
        response.setRawDiff(buildUnifiedRawDiff(left.getRoot(), right.getRoot(), leftVersion, rightVersion));
        return response;
    }

    @Transactional
    public WorkflowVersionRollbackResponse rollback(Long workflowId,
                                                    Long targetVersionId,
                                                    WorkflowVersionRollbackRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_NOT_FOUND, "工作流不存在: " + workflowId);
        }

        WorkflowVersion targetVersion = requireVersion(workflowId, targetVersionId);
        requireV3Version(targetVersion, WorkflowVersionErrorCodes.VERSION_ROLLBACK_ONLY_V3, "版本回滚");
        SnapshotNormalized target = normalizeSnapshotForRollback(targetVersion);

        String operator = resolveOperator(request != null ? request.getOperator() : null);
        restoreWorkflowRuntimeFields(workflowId, target.getWorkflow(), target.getSchedule(), operator);

        List<WorkflowTaskBinding> bindings = restoreTasks(workflowId, target, operator);
        if (CollectionUtils.isEmpty(bindings)) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED, "目标版本快照不包含任务定义");
        }

        WorkflowDefinitionRequest definitionRequest = new WorkflowDefinitionRequest();
        definitionRequest.setWorkflowName(readText(target.getWorkflow(), "workflowName", workflow.getWorkflowName()));
        definitionRequest.setDescription(readText(target.getWorkflow(), "description", workflow.getDescription()));
        definitionRequest.setGlobalParams(readText(target.getWorkflow(), "globalParams", workflow.getGlobalParams()));
        definitionRequest.setTaskGroupName(readText(target.getWorkflow(), "taskGroupName", workflow.getTaskGroupName()));
        definitionRequest.setProjectCode(workflow.getProjectCode());
        definitionRequest.setTasks(bindings);
        definitionRequest.setOperator(operator);
        definitionRequest.setTriggerSource("version_rollback");

        DataWorkflow updated = workflowService.updateWorkflow(workflowId, definitionRequest);
        WorkflowVersion newVersion = workflowVersionMapper.selectById(updated.getCurrentVersionId());
        if (newVersion == null) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_ROLLBACK_FAILED, "回退后未生成新版本");
        }
        newVersion.setRollbackFromVersionId(targetVersion.getId());
        workflowVersionMapper.updateById(newVersion);

        WorkflowVersionRollbackResponse response = new WorkflowVersionRollbackResponse();
        response.setWorkflowId(workflowId);
        response.setNewVersionId(newVersion.getId());
        response.setNewVersionNo(newVersion.getVersionNo());
        response.setRollbackFromVersionId(targetVersion.getId());
        response.setRollbackFromVersionNo(targetVersion.getVersionNo());
        return response;
    }

    @Transactional
    public WorkflowVersionDeleteResponse deleteVersion(Long workflowId, Long versionId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_NOT_FOUND, "工作流不存在: " + workflowId);
        }

        WorkflowVersion targetVersion = requireVersion(workflowId, versionId);
        Long lastSuccessfulPublishedVersionId = resolveLastSuccessfulPublishedVersionId(workflowId);
        boolean isCurrentVersion = Objects.equals(workflow.getCurrentVersionId(), versionId);

        if (isCurrentVersion) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_DELETE_FORBIDDEN, "当前版本不可删除");
        }
        if (Objects.equals(lastSuccessfulPublishedVersionId, versionId)) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_DELETE_FORBIDDEN, "最后一次成功发布版本不可删除");
        }

        workflowPublishRecordMapper.delete(
                Wrappers.<WorkflowPublishRecord>lambdaQuery()
                        .eq(WorkflowPublishRecord::getWorkflowId, workflowId)
                        .eq(WorkflowPublishRecord::getVersionId, versionId));

        workflowRuntimeSyncRecordMapper.update(
                null,
                Wrappers.<WorkflowRuntimeSyncRecord>lambdaUpdate()
                        .eq(WorkflowRuntimeSyncRecord::getWorkflowId, workflowId)
                        .eq(WorkflowRuntimeSyncRecord::getVersionId, versionId)
                        .set(WorkflowRuntimeSyncRecord::getVersionId, null));

        workflowVersionMapper.update(
                null,
                Wrappers.<WorkflowVersion>lambdaUpdate()
                        .eq(WorkflowVersion::getWorkflowId, workflowId)
                        .eq(WorkflowVersion::getRollbackFromVersionId, versionId)
                        .set(WorkflowVersion::getRollbackFromVersionId, null));

        int deleted = workflowVersionMapper.delete(
                Wrappers.<WorkflowVersion>lambdaQuery()
                        .eq(WorkflowVersion::getId, versionId)
                        .eq(WorkflowVersion::getWorkflowId, workflowId));
        if (deleted <= 0) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_DELETE_FAILED, "删除版本失败: " + versionId);
        }

        WorkflowVersionDeleteResponse response = new WorkflowVersionDeleteResponse();
        response.setWorkflowId(workflowId);
        response.setDeletedVersionId(versionId);
        response.setDeletedVersionNo(targetVersion.getVersionNo());
        return response;
    }

    private List<WorkflowTaskBinding> restoreTasks(Long workflowId, SnapshotNormalized target, String operator) {
        List<TaskSnapshot> taskSnapshots = target.getTaskSnapshots();
        if (CollectionUtils.isEmpty(taskSnapshots)) {
            return Collections.emptyList();
        }

        List<Long> taskIds = taskSnapshots.stream()
                .map(TaskSnapshot::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        Map<Long, DataTask> existsById = dataTaskMapper.selectBatchIds(taskIds).stream()
                .filter(Objects::nonNull)
                .filter(item -> item.getId() != null)
                .collect(Collectors.toMap(DataTask::getId, item -> item, (left, right) -> left));

        List<WorkflowTaskBinding> bindings = new ArrayList<>();
        for (TaskSnapshot taskSnapshot : taskSnapshots) {
            Long taskId = taskSnapshot.getTaskId();
            if (taskId == null) {
                throw badRequest(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED, "快照任务缺少 taskId");
            }
            DataTask existing = existsById.get(taskId);
            if (existing == null) {
                throw badRequest(WorkflowVersionErrorCodes.VERSION_TASK_NOT_FOUND,
                        "回退任务不存在: taskId=" + taskId);
            }

            DataTask payload = new DataTask();
            payload.setId(taskId);
            payload.setTaskName(readText(taskSnapshot.getNode(), "taskName", existing.getTaskName()));
            payload.setTaskCode(readText(taskSnapshot.getNode(), "platformTaskCode", existing.getTaskCode()));
            String rollbackTaskType = readText(taskSnapshot.getNode(), "taskType", existing.getTaskType());
            if (!"batch".equalsIgnoreCase(rollbackTaskType) && !"stream".equalsIgnoreCase(rollbackTaskType)) {
                rollbackTaskType = existing.getTaskType();
            }
            payload.setTaskType(rollbackTaskType);
            payload.setEngine(readText(taskSnapshot.getNode(), "engine", existing.getEngine()));
            payload.setDolphinNodeType(readText(taskSnapshot.getNode(), "dolphinNodeType", existing.getDolphinNodeType()));
            payload.setTaskSql(readText(taskSnapshot.getNode(), "taskSql", existing.getTaskSql()));
            payload.setTaskDesc(readText(taskSnapshot.getNode(), "taskDesc", existing.getTaskDesc()));
            payload.setDatasourceName(readText(taskSnapshot.getNode(), "datasourceName", existing.getDatasourceName()));
            payload.setDatasourceType(readText(taskSnapshot.getNode(), "datasourceType", existing.getDatasourceType()));
            payload.setTaskGroupName(readText(taskSnapshot.getNode(), "taskGroupName", existing.getTaskGroupName()));
            payload.setDolphinFlag(readText(taskSnapshot.getNode(), "dolphinFlag", existing.getDolphinFlag()));
            payload.setRetryTimes(readInteger(taskSnapshot.getNode(), "retryTimes", existing.getRetryTimes()));
            payload.setRetryInterval(readInteger(taskSnapshot.getNode(), "retryInterval", existing.getRetryInterval()));
            payload.setTimeoutSeconds(readInteger(taskSnapshot.getNode(), "timeoutSeconds", existing.getTimeoutSeconds()));
            payload.setPriority(readInteger(taskSnapshot.getNode(), "priority", existing.getPriority()));
            payload.setOwner(StringUtils.hasText(existing.getOwner()) ? existing.getOwner() : operator);
            payload.setDolphinProcessCode(existing.getDolphinProcessCode());
            payload.setDolphinTaskCode(readLong(taskSnapshot.getNode(), "dolphinTaskCode", existing.getDolphinTaskCode()));
            payload.setDolphinTaskVersion(readInteger(taskSnapshot.getNode(), "dolphinTaskVersion", existing.getDolphinTaskVersion()));
            payload.setWorkflowId(workflowId);

            dataTaskService.update(payload, taskSnapshot.getInputTableIds(), taskSnapshot.getOutputTableIds());

            WorkflowTaskBinding binding = new WorkflowTaskBinding();
            binding.setTaskId(taskId);
            binding.setEntry(readBoolean(taskSnapshot.getNode(), "entry", null));
            binding.setExit(readBoolean(taskSnapshot.getNode(), "exit", null));
            JsonNode nodeAttrs = taskSnapshot.getNode().get("nodeAttrs");
            if (nodeAttrs != null && !nodeAttrs.isNull()) {
                binding.setNodeAttrs(objectMapper.convertValue(nodeAttrs, Map.class));
            }
            bindings.add(binding);
        }
        return bindings;
    }

    private void restoreWorkflowRuntimeFields(Long workflowId,
                                              JsonNode workflowNode,
                                              JsonNode scheduleNode,
                                              String operator) {
        dataWorkflowMapper.update(
                null,
                Wrappers.<DataWorkflow>lambdaUpdate()
                        .eq(DataWorkflow::getId, workflowId)
                        .set(DataWorkflow::getPublishStatus, readText(workflowNode, "publishStatus", null))
                        .set(DataWorkflow::getDolphinScheduleId, readLong(scheduleNode, "dolphinScheduleId", null))
                        .set(DataWorkflow::getScheduleState, readText(scheduleNode, "scheduleState", null))
                        .set(DataWorkflow::getScheduleCron, readText(scheduleNode, "scheduleCron", null))
                        .set(DataWorkflow::getScheduleTimezone, readText(scheduleNode, "scheduleTimezone", null))
                        .set(DataWorkflow::getScheduleStartTime, readDateTime(scheduleNode, "scheduleStartTime", null))
                        .set(DataWorkflow::getScheduleEndTime, readDateTime(scheduleNode, "scheduleEndTime", null))
                        .set(DataWorkflow::getScheduleFailureStrategy, readText(scheduleNode, "scheduleFailureStrategy", null))
                        .set(DataWorkflow::getScheduleWarningType, readText(scheduleNode, "scheduleWarningType", null))
                        .set(DataWorkflow::getScheduleWarningGroupId, readLong(scheduleNode, "scheduleWarningGroupId", null))
                        .set(DataWorkflow::getScheduleProcessInstancePriority,
                                readText(scheduleNode, "scheduleProcessInstancePriority", null))
                        .set(DataWorkflow::getScheduleWorkerGroup, readText(scheduleNode, "scheduleWorkerGroup", null))
                        .set(DataWorkflow::getScheduleTenantCode, readText(scheduleNode, "scheduleTenantCode", null))
                        .set(DataWorkflow::getScheduleEnvironmentCode, readLong(scheduleNode, "scheduleEnvironmentCode", null))
                        .set(DataWorkflow::getScheduleAutoOnline, readBoolean(scheduleNode, "scheduleAutoOnline", null))
                        .set(DataWorkflow::getUpdatedBy, operator)
                        .set(DataWorkflow::getUpdatedAt, LocalDateTime.now()));
    }

    private void compareTasks(Map<String, JsonNode> left,
                              Map<String, JsonNode> right,
                              List<String> added,
                              List<String> removed,
                              List<String> modified,
                              List<String> unchanged) {
        Set<String> leftKeys = new LinkedHashSet<>(left.keySet());
        Set<String> rightKeys = new LinkedHashSet<>(right.keySet());

        for (String key : rightKeys) {
            if (!leftKeys.contains(key)) {
                added.add(describeTask(right.get(key), key));
            }
        }
        for (String key : leftKeys) {
            if (!rightKeys.contains(key)) {
                removed.add(describeTask(left.get(key), key));
            }
        }
        for (String key : leftKeys) {
            if (!rightKeys.contains(key)) {
                continue;
            }
            JsonNode leftNode = left.get(key);
            JsonNode rightNode = right.get(key);
            if (Objects.equals(leftNode, rightNode)) {
                unchanged.add(describeTask(rightNode, key));
            } else {
                modified.add(describeModifiedTask(leftNode, rightNode, key));
            }
        }
    }

    private void compareEdgeSets(Set<String> left,
                                 Set<String> right,
                                 List<String> added,
                                 List<String> removed,
                                 List<String> unchanged,
                                 Map<String, JsonNode> leftTasks,
                                 Map<String, JsonNode> rightTasks) {
        Map<String, JsonNode> taskLookup = buildTaskLookupForEdges(leftTasks, rightTasks);
        for (String edge : right) {
            if (!left.contains(edge)) {
                added.add(describeEdge(edge, taskLookup));
            }
        }
        for (String edge : left) {
            if (!right.contains(edge)) {
                removed.add(describeEdge(edge, taskLookup));
            }
        }
        for (String edge : left) {
            if (right.contains(edge)) {
                unchanged.add(describeEdge(edge, taskLookup));
            }
        }
    }

    private void compareFlatFields(JsonNode left,
                                   JsonNode right,
                                   List<String> added,
                                   List<String> removed,
                                   List<String> modified,
                                   List<String> unchanged,
                                   String prefix) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(fieldNames(left));
        keys.addAll(fieldNames(right));

        List<String> orderedKeys = new ArrayList<>(keys);
        Collections.sort(orderedKeys);
        for (String key : orderedKeys) {
            JsonNode leftValue = left != null ? left.get(key) : null;
            JsonNode rightValue = right != null ? right.get(key) : null;
            if (leftValue == null || leftValue.isNull()) {
                if (rightValue != null && !rightValue.isNull()) {
                    added.add(prefix + "." + key + " = " + toText(rightValue));
                }
                continue;
            }
            if (rightValue == null || rightValue.isNull()) {
                removed.add(prefix + "." + key + " = " + toText(leftValue));
                continue;
            }
            if (Objects.equals(leftValue, rightValue)) {
                unchanged.add(prefix + "." + key);
            } else {
                modified.add(prefix + "." + key + ": " + toText(leftValue) + " -> " + toText(rightValue));
            }
        }
    }

    private int totalCount(WorkflowVersionDiffSection section) {
        return section.getWorkflowFields().size()
                + section.getTasks().size()
                + section.getEdges().size()
                + section.getSchedules().size();
    }

    private String buildUnifiedRawDiff(JsonNode leftRoot,
                                       JsonNode rightRoot,
                                       WorkflowVersion leftVersion,
                                       WorkflowVersion rightVersion) {
        String leftText = toPrettyJson(leftRoot);
        String rightText = toPrettyJson(rightRoot);

        List<String> leftLines = Arrays.asList(leftText.split("\\R", -1));
        List<String> rightLines = Arrays.asList(rightText.split("\\R", -1));
        int[][] lcs = buildLcsMatrix(leftLines, rightLines);
        LinkedList<String> diffLines = buildDiffLines(leftLines, rightLines, lcs);

        String leftLabel = leftVersion != null && leftVersion.getVersionNo() != null
                ? "v" + leftVersion.getVersionNo()
                : "empty";
        String rightLabel = rightVersion != null && rightVersion.getVersionNo() != null
                ? "v" + rightVersion.getVersionNo()
                : "unknown";

        StringBuilder builder = new StringBuilder();
        builder.append("--- ").append(leftLabel).append('\n');
        builder.append("+++ ").append(rightLabel).append('\n');
        builder.append("@@ JSON Snapshot @@").append('\n');
        for (String line : diffLines) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }

    private int[][] buildLcsMatrix(List<String> leftLines, List<String> rightLines) {
        int leftSize = leftLines.size();
        int rightSize = rightLines.size();
        int[][] matrix = new int[leftSize + 1][rightSize + 1];
        for (int i = 1; i <= leftSize; i++) {
            for (int j = 1; j <= rightSize; j++) {
                if (Objects.equals(leftLines.get(i - 1), rightLines.get(j - 1))) {
                    matrix[i][j] = matrix[i - 1][j - 1] + 1;
                } else {
                    matrix[i][j] = Math.max(matrix[i - 1][j], matrix[i][j - 1]);
                }
            }
        }
        return matrix;
    }

    private LinkedList<String> buildDiffLines(List<String> leftLines,
                                              List<String> rightLines,
                                              int[][] lcsMatrix) {
        int i = leftLines.size();
        int j = rightLines.size();
        LinkedList<String> diffLines = new LinkedList<>();
        while (i > 0 && j > 0) {
            String leftLine = leftLines.get(i - 1);
            String rightLine = rightLines.get(j - 1);
            if (Objects.equals(leftLine, rightLine)) {
                diffLines.addFirst(" " + leftLine);
                i--;
                j--;
                continue;
            }
            if (lcsMatrix[i - 1][j] >= lcsMatrix[i][j - 1]) {
                diffLines.addFirst("-" + leftLine);
                i--;
            } else {
                diffLines.addFirst("+" + rightLine);
                j--;
            }
        }
        while (i > 0) {
            diffLines.addFirst("-" + leftLines.get(i - 1));
            i--;
        }
        while (j > 0) {
            diffLines.addFirst("+" + rightLines.get(j - 1));
            j--;
        }
        return diffLines;
    }

    private String toPrettyJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return "{}";
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception ex) {
            return node.toString();
        }
    }

    private Long resolveLastSuccessfulPublishedVersionId(Long workflowId) {
        WorkflowPublishRecord latestSuccess = workflowPublishRecordMapper.selectOne(
                Wrappers.<WorkflowPublishRecord>lambdaQuery()
                        .eq(WorkflowPublishRecord::getWorkflowId, workflowId)
                        .eq(WorkflowPublishRecord::getStatus, "success")
                        .isNotNull(WorkflowPublishRecord::getVersionId)
                        .orderByDesc(WorkflowPublishRecord::getCreatedAt)
                        .orderByDesc(WorkflowPublishRecord::getId)
                        .last("limit 1"));
        return latestSuccess != null ? latestSuccess.getVersionId() : null;
    }

    private WorkflowVersion requireVersion(Long workflowId, Long versionId) {
        WorkflowVersion version = workflowVersionMapper.selectById(versionId);
        if (version == null || !Objects.equals(version.getWorkflowId(), workflowId)) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_NOT_FOUND,
                    "版本不存在或不属于当前工作流: versionId=" + versionId);
        }
        return version;
    }

    private SnapshotNormalized normalizeSnapshotForCompare(WorkflowVersion version) {
        if (version == null) {
            return SnapshotNormalized.empty();
        }
        return normalizeSnapshotFromDefinitionJson(version, false, false);
    }

    private SnapshotNormalized normalizeSnapshotForRollback(WorkflowVersion version) {
        if (version == null) {
            return SnapshotNormalized.empty();
        }
        return normalizeSnapshotFromDefinitionJson(version, true, false);
    }

    private SnapshotNormalized normalizeSnapshotFromDefinitionJson(WorkflowVersion version,
                                                                  boolean requireTaskId,
                                                                  boolean includeWorkflowStatus) {
        if (version == null || !StringUtils.hasText(version.getStructureSnapshot())) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED,
                    "版本定义为空: versionId=" + (version != null ? version.getId() : null));
        }
        JsonNode rootNode;
        try {
            rootNode = objectMapper.readTree(version.getStructureSnapshot());
        } catch (Exception ex) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED,
                    "版本定义解析失败: versionId=" + (version != null ? version.getId() : null));
        }

        JsonNode processNode = firstPresentNode(rootNode, "processDefinition", "workflowDefinition", "workflow");
        if (processNode == null) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_SNAPSHOT_UNSUPPORTED,
                    "版本定义缺少 processDefinition: versionId=" + version.getId());
        }
        JsonNode platformWorkflowMeta = firstPresentNode(rootNode, "xPlatformWorkflowMeta");

        Map<String, Object> workflowNode = new LinkedHashMap<>();
        workflowNode.put("workflowCode", firstLong(processNode, "workflowCode", "code", "processDefinitionCode"));
        workflowNode.put("projectCode", firstLong(processNode, "projectCode"));
        workflowNode.put("workflowName", firstText(processNode, "workflowName", "name"));
        workflowNode.put("description", firstText(processNode, "description", "desc"));
        workflowNode.put("globalParams", normalizeJsonTextValue(processNode.get("globalParams")));
        workflowNode.put("taskGroupName", firstText(processNode, "taskGroupName"));
        if (includeWorkflowStatus) {
            workflowNode.put("status", firstText(processNode, "releaseState", "status"));
        }
        workflowNode.put("publishStatus", firstNonBlank(
                firstText(platformWorkflowMeta, "publishStatus"),
                firstText(processNode, "publishStatus")));

        Map<String, Object> scheduleNode = new LinkedHashMap<>();
        JsonNode schedule = firstPresentNode(rootNode, "schedule");
        if (schedule == null) {
            schedule = firstPresentNode(processNode, "schedule");
        }
        if (schedule != null) {
            scheduleNode.put("dolphinScheduleId", firstLong(schedule, "id", "scheduleId", "dolphinScheduleId"));
            scheduleNode.put("scheduleState", firstText(schedule, "scheduleState", "releaseState"));
            scheduleNode.put("scheduleCron", firstText(schedule, "scheduleCron", "crontab", "cron"));
            scheduleNode.put("scheduleTimezone", firstText(schedule, "scheduleTimezone", "timezoneId", "timezone"));
            scheduleNode.put("scheduleStartTime", firstText(schedule, "scheduleStartTime", "startTime"));
            scheduleNode.put("scheduleEndTime", firstText(schedule, "scheduleEndTime", "endTime"));
            scheduleNode.put("scheduleFailureStrategy", firstText(schedule, "scheduleFailureStrategy", "failureStrategy"));
            scheduleNode.put("scheduleWarningType", firstText(schedule, "scheduleWarningType", "warningType"));
            scheduleNode.put("scheduleWarningGroupId", firstLong(schedule, "scheduleWarningGroupId", "warningGroupId"));
            scheduleNode.put("scheduleProcessInstancePriority",
                    firstText(schedule, "scheduleProcessInstancePriority", "processInstancePriority"));
            scheduleNode.put("scheduleWorkerGroup", firstText(schedule, "scheduleWorkerGroup", "workerGroup"));
            scheduleNode.put("scheduleTenantCode", firstText(schedule, "scheduleTenantCode", "tenantCode"));
            scheduleNode.put("scheduleEnvironmentCode", firstLong(schedule, "scheduleEnvironmentCode", "environmentCode"));
            Boolean scheduleAutoOnline = readBoolean(schedule, "scheduleAutoOnline", null);
            if (scheduleAutoOnline == null) {
                scheduleAutoOnline = readBoolean(schedule, "autoOnline", null);
            }
            scheduleNode.put("scheduleAutoOnline", scheduleAutoOnline);
        }

        List<Map<String, Object>> taskNodes = new ArrayList<>();
        JsonNode taskList = firstPresentNode(rootNode, "taskDefinitionList", "tasks", "taskDefinitionJson");
        if (taskList != null && taskList.isArray()) {
            for (JsonNode task : taskList) {
                if (task == null || task.isNull()) {
                    continue;
                }
                Long runtimeTaskCode = firstLong(task, "taskCode", "code");
                JsonNode platformTaskMeta = firstPresentNode(task, "xPlatformTaskMeta", "platformTaskMeta");
                Long platformTaskId = firstLong(platformTaskMeta, "taskId", "id");
                if (requireTaskId && platformTaskId == null) {
                    throw badRequest(WorkflowVersionErrorCodes.VERSION_ROLLBACK_TASK_ID_REQUIRED,
                            "版本定义缺少 xPlatformTaskMeta.taskId: versionId=" + version.getId()
                                    + ", taskCode=" + runtimeTaskCode);
                }
                Long normalizedTaskId = platformTaskId != null ? platformTaskId : runtimeTaskCode;
                if (normalizedTaskId == null) {
                    continue;
                }

                JsonNode taskParams = normalizeNode(task.get("taskParams"));
                Map<String, Object> taskNode = new LinkedHashMap<>();
                taskNode.put("taskId", normalizedTaskId);
                taskNode.put("taskCode", runtimeTaskCode);
                taskNode.put("platformTaskCode", firstText(platformTaskMeta, "platformTaskCode"));
                taskNode.put("taskName", firstText(task, "taskName", "name"));
                taskNode.put("taskType", firstNonBlank(
                        firstText(platformTaskMeta, "platformTaskType"),
                        firstText(task, "taskType")));
                taskNode.put("dolphinNodeType", firstText(task, "nodeType", "taskType"));
                taskNode.put("engine", firstText(platformTaskMeta, "engine"));
                taskNode.put("taskSql", firstNonBlank(
                        firstText(task, "taskSql", "sql", "rawScript"),
                        firstText(taskParams, "sql", "rawScript")));
                taskNode.put("taskDesc", firstText(task, "description", "taskDesc"));
                taskNode.put("datasourceName", firstNonBlank(
                        firstText(task, "datasourceName"),
                        firstText(taskParams, "datasourceName")));
                taskNode.put("datasourceType", firstNonBlank(
                        firstText(task, "datasourceType"),
                        firstText(taskParams, "type")));
                taskNode.put("taskGroupName", firstText(task, "taskGroupName"));
                taskNode.put("dolphinFlag", firstText(task, "dolphinFlag", "flag"));
                taskNode.put("retryTimes", firstLong(task, "retryTimes", "failRetryTimes"));
                taskNode.put("retryInterval", firstLong(task, "retryInterval", "failRetryInterval"));
                taskNode.put("timeoutSeconds", firstLong(task, "timeoutSeconds", "timeout"));
                taskNode.put("priority", firstLong(task, "priority", "taskPriority"));
                taskNode.put("dolphinTaskCode", firstLong(platformTaskMeta, "dolphinTaskCode", "taskCode", "code"));
                taskNode.put("dolphinTaskVersion", firstLong(platformTaskMeta, "dolphinTaskVersion", "version"));
                taskNode.put("entry", readBoolean(platformTaskMeta, "entry", null));
                taskNode.put("exit", readBoolean(platformTaskMeta, "exit", null));
                JsonNode nodeAttrs = platformTaskMeta != null ? platformTaskMeta.get("nodeAttrs") : null;
                if (nodeAttrs != null && !nodeAttrs.isNull()) {
                    taskNode.put("nodeAttrs", nodeAttrs);
                }
                taskNode.put("inputTableIds", normalizeLongList(toLongList(firstPresentNode(task, "inputTableIds"))));
                taskNode.put("outputTableIds", normalizeLongList(toLongList(firstPresentNode(task, "outputTableIds"))));
                taskNodes.add(taskNode);
            }
        }

        List<Map<String, Object>> edgeNodes = new ArrayList<>();
        JsonNode relations = firstPresentNode(rootNode,
                "processTaskRelationList",
                "workflowTaskRelationList",
                "taskRelationList",
                "edges",
                "taskRelationJson");
        if (relations != null && relations.isArray()) {
            for (JsonNode relation : relations) {
                if (relation == null || relation.isNull()) {
                    continue;
                }
                Long upstream = firstLong(relation, "upstreamTaskCode", "upstreamTaskId", "preTaskCode");
                Long downstream = firstLong(relation, "downstreamTaskCode", "downstreamTaskId", "postTaskCode");
                if (upstream == null || downstream == null) {
                    continue;
                }
                Map<String, Object> edge = new LinkedHashMap<>();
                edge.put("upstreamTaskCode", upstream);
                edge.put("downstreamTaskCode", downstream);
                edgeNodes.add(edge);
            }
        }
        sortTaskNodes(taskNodes);
        sortEdgeNodes(edgeNodes);

        Map<String, Object> compareRoot = new LinkedHashMap<>();
        compareRoot.put("workflow", workflowNode);
        compareRoot.put("tasks", taskNodes);
        compareRoot.put("edges", edgeNodes);
        compareRoot.put("schedule", scheduleNode);
        JsonNode compareRootNode = objectMapper.valueToTree(compareRoot);

        SnapshotNormalized normalized = new SnapshotNormalized();
        normalized.setSchemaVersion(SNAPSHOT_SCHEMA_VERSION_DEFINITION);
        normalized.setRoot(compareRootNode);
        normalized.setWorkflow(compareRootNode.path("workflow"));
        normalized.setSchedule(compareRootNode.path("schedule"));
        normalized.setTasks(toTaskMap(compareRootNode.path("tasks")));
        normalized.setTaskSnapshots(toTaskSnapshotList(compareRootNode.path("tasks")));
        normalized.setEdges(toEdgeSet(compareRootNode.path("edges")));
        normalized.setRollbackSupported(requireTaskId);
        return normalized;
    }

    private void sortTaskNodes(List<Map<String, Object>> taskNodes) {
        if (CollectionUtils.isEmpty(taskNodes)) {
            return;
        }
        taskNodes.sort(Comparator
                .comparing((Map<String, Object> taskNode) -> asLongObject(taskNode.get("taskId")),
                        Comparator.nullsLast(Long::compareTo))
                .thenComparing(taskNode -> asLongObject(taskNode.get("taskCode")),
                        Comparator.nullsLast(Long::compareTo))
                .thenComparing(taskNode -> asTextObject(taskNode.get("taskName")),
                        Comparator.nullsLast(String::compareTo)));
    }

    private void sortEdgeNodes(List<Map<String, Object>> edgeNodes) {
        if (CollectionUtils.isEmpty(edgeNodes)) {
            return;
        }
        edgeNodes.sort(Comparator
                .comparing((Map<String, Object> edgeNode) -> asLongObject(edgeNode.get("upstreamTaskCode")),
                        Comparator.nullsLast(Long::compareTo))
                .thenComparing(edgeNode -> asLongObject(edgeNode.get("downstreamTaskCode")),
                        Comparator.nullsLast(Long::compareTo)));
    }

    private void requireV3Version(WorkflowVersion version, String errorCode, String operationName) {
        if (version == null) {
            throw badRequest(WorkflowVersionErrorCodes.VERSION_NOT_FOUND, "版本不存在");
        }
        Integer schemaVersion = version.getSnapshotSchemaVersion();
        if (!Objects.equals(schemaVersion, SNAPSHOT_SCHEMA_VERSION_DEFINITION)) {
            throw badRequest(errorCode,
                    operationName + "仅支持 V3 definition 版本: versionId=" + version.getId()
                            + ", snapshotSchemaVersion=" + schemaVersion + "。请先保存生成 V3 基线版本");
        }
    }

    private JsonNode firstPresentNode(JsonNode root, String... fields) {
        if (root == null || fields == null || fields.length == 0) {
            return null;
        }
        for (String field : fields) {
            if (!StringUtils.hasText(field)) {
                continue;
            }
            JsonNode node = root.path(field);
            if (node != null && !node.isMissingNode() && !node.isNull()) {
                return node;
            }
        }
        return null;
    }

    private JsonNode normalizeNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (!node.isTextual()) {
            return node;
        }
        String text = node.asText();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return objectMapper.readTree(text);
        } catch (Exception ex) {
            return node;
        }
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null || candidates.length == 0) {
            return null;
        }
        for (String candidate : candidates) {
            if (StringUtils.hasText(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private String normalizeJsonTextValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (!StringUtils.hasText(text)) {
                return null;
            }
            String trimmed = text.trim();
            if (!StringUtils.hasText(trimmed)) {
                return null;
            }
            try {
                return objectMapper.writeValueAsString(objectMapper.readTree(trimmed));
            } catch (Exception ex) {
                return trimmed;
            }
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception ex) {
            return node.toString();
        }
    }

    private List<Long> normalizeLongList(List<Long> values) {
        if (CollectionUtils.isEmpty(values)) {
            return Collections.emptyList();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private List<TaskSnapshot> toTaskSnapshotList(JsonNode tasksNode) {
        if (tasksNode == null || tasksNode.isNull() || !tasksNode.isArray()) {
            return Collections.emptyList();
        }
        List<TaskSnapshot> snapshots = new ArrayList<>();
        for (JsonNode taskNode : tasksNode) {
            if (taskNode == null || taskNode.isNull()) {
                continue;
            }
            Long taskId = firstLong(taskNode, "taskId", "id");
            if (taskId == null) {
                continue;
            }
            TaskSnapshot snapshot = new TaskSnapshot();
            snapshot.setTaskId(taskId);
            snapshot.setNode(taskNode);
            snapshot.setInputTableIds(toLongList(taskNode.path("inputTableIds")));
            snapshot.setOutputTableIds(toLongList(taskNode.path("outputTableIds")));
            snapshots.add(snapshot);
        }
        snapshots.sort(Comparator.comparing(TaskSnapshot::getTaskId));
        return snapshots;
    }

    private Map<String, JsonNode> toTaskMap(JsonNode tasksNode) {
        if (tasksNode == null || tasksNode.isNull() || !tasksNode.isArray()) {
            return Collections.emptyMap();
        }
        Map<String, JsonNode> map = new LinkedHashMap<>();
        int index = 0;
        for (JsonNode taskNode : tasksNode) {
            if (taskNode == null || taskNode.isNull()) {
                continue;
            }
            String key = firstText(taskNode, "taskId", "taskCode", "id", "code");
            if (!StringUtils.hasText(key)) {
                key = String.valueOf(index);
            }
            map.putIfAbsent(key, taskNode);
            index++;
        }
        return map;
    }

    private Set<String> toEdgeSet(JsonNode edgesNode) {
        if (edgesNode == null || edgesNode.isNull() || !edgesNode.isArray()) {
            return Collections.emptySet();
        }
        Set<String> edges = new LinkedHashSet<>();
        for (JsonNode edge : edgesNode) {
            if (edge == null || edge.isNull()) {
                continue;
            }
            String upstream = firstText(edge, "upstreamTaskId", "upstreamTaskCode");
            String downstream = firstText(edge, "downstreamTaskId", "downstreamTaskCode");
            if (!StringUtils.hasText(upstream) || !StringUtils.hasText(downstream)) {
                continue;
            }
            edges.add(upstream + "->" + downstream);
        }
        return edges;
    }

    private Set<String> fieldNames(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Collections.emptySet();
        }
        Set<String> names = new LinkedHashSet<>();
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            names.add(it.next());
        }
        return names;
    }

    private String describeTask(JsonNode taskNode, String key) {
        String taskId = firstText(taskNode, "taskId", "id");
        String taskCode = firstText(taskNode, "taskCode", "code");
        String identity = buildTaskIdentity(taskId, taskCode, key);
        if (taskNode == null || taskNode.isNull()) {
            return identity;
        }
        String name = firstText(taskNode, "taskName", "name");
        if (StringUtils.hasText(name)) {
            if (StringUtils.hasText(identity)) {
                return name + " [" + identity + "]";
            }
            return name;
        }
        return identity;
    }

    private String describeModifiedTask(JsonNode beforeTask, JsonNode afterTask, String key) {
        JsonNode preferred = afterTask != null && !afterTask.isNull() ? afterTask : beforeTask;
        String base = describeTask(preferred, key);
        List<String> changedFields = collectChangedTaskFields(beforeTask, afterTask);
        if (changedFields.isEmpty()) {
            return base;
        }
        return base + " | 变更: " + String.join("; ", changedFields);
    }

    private List<String> collectChangedTaskFields(JsonNode beforeTask, JsonNode afterTask) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(fieldNames(beforeTask));
        keys.addAll(fieldNames(afterTask));
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> changed = new ArrayList<>();
        List<String> ordered = new ArrayList<>(keys);
        Collections.sort(ordered);
        for (String field : ordered) {
            JsonNode beforeValue = beforeTask != null ? beforeTask.get(field) : null;
            JsonNode afterValue = afterTask != null ? afterTask.get(field) : null;
            if (Objects.equals(beforeValue, afterValue)) {
                continue;
            }
            changed.add(formatTaskFieldDiff(field, beforeValue, afterValue));
        }
        return changed;
    }

    private String formatTaskFieldDiff(String field, JsonNode beforeValue, JsonNode afterValue) {
        String beforeText = summarizeText(toText(beforeValue));
        String afterText = summarizeText(toText(afterValue));
        if (shouldOnlyShowFieldName(field, beforeText, afterText)) {
            return field;
        }
        return field + ": " + beforeText + " -> " + afterText;
    }

    private boolean shouldOnlyShowFieldName(String field, String beforeText, String afterText) {
        String lowerField = field != null ? field.toLowerCase(Locale.ROOT) : "";
        if (lowerField.contains("sql") || lowerField.contains("json")) {
            return true;
        }
        return isLongInlineText(beforeText) || isLongInlineText(afterText);
    }

    private boolean isLongInlineText(String value) {
        if (!StringUtils.hasText(value)) {
            return false;
        }
        return value.length() > 48 || value.contains("\n") || value.contains("\r");
    }

    private String summarizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return "null";
        }
        String compact = value.replace('\n', ' ').replace('\r', ' ').trim();
        if (compact.length() <= 48) {
            return compact;
        }
        return compact.substring(0, 45) + "...";
    }

    private String describeEdge(String edge, Map<String, JsonNode> taskLookup) {
        if (!StringUtils.hasText(edge)) {
            return edge;
        }
        String[] parts = edge.split("->", 2);
        if (parts.length != 2) {
            return edge;
        }
        String upstreamKey = parts[0].trim();
        String downstreamKey = parts[1].trim();
        JsonNode upstreamTask = taskLookup != null ? taskLookup.get(upstreamKey) : null;
        JsonNode downstreamTask = taskLookup != null ? taskLookup.get(downstreamKey) : null;

        String upstream = describeTaskBrief(upstreamTask, upstreamKey);
        String downstream = describeTaskBrief(downstreamTask, downstreamKey);
        if (Objects.equals(upstream, upstreamKey) && Objects.equals(downstream, downstreamKey)) {
            return edge;
        }
        return upstream + " -> " + downstream + " [" + edge + "]";
    }

    private String describeTaskBrief(JsonNode taskNode, String key) {
        if (taskNode == null || taskNode.isNull()) {
            return key;
        }
        String name = firstText(taskNode, "taskName", "name");
        if (!StringUtils.hasText(name)) {
            return key;
        }
        return StringUtils.hasText(key) ? name + "(" + key + ")" : name;
    }

    private String buildTaskIdentity(String taskId, String taskCode, String fallbackKey) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(taskId)) {
            parts.add("taskId=" + taskId);
        }
        if (StringUtils.hasText(taskCode) && !Objects.equals(taskCode, taskId)) {
            parts.add("taskCode=" + taskCode);
        }
        if (parts.isEmpty() && StringUtils.hasText(fallbackKey)) {
            parts.add("key=" + fallbackKey);
        }
        return String.join(", ", parts);
    }

    private Map<String, JsonNode> buildTaskLookupForEdges(Map<String, JsonNode> leftTasks,
                                                          Map<String, JsonNode> rightTasks) {
        Map<String, JsonNode> lookup = new LinkedHashMap<>();
        registerTaskLookupEntries(lookup, leftTasks);
        registerTaskLookupEntries(lookup, rightTasks);
        return lookup;
    }

    private void registerTaskLookupEntries(Map<String, JsonNode> lookup, Map<String, JsonNode> source) {
        if (lookup == null || source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, JsonNode> entry : source.entrySet()) {
            JsonNode taskNode = entry.getValue();
            registerTaskAlias(lookup, entry.getKey(), taskNode);
            registerTaskAlias(lookup, firstText(taskNode, "taskId", "id"), taskNode);
            registerTaskAlias(lookup, firstText(taskNode, "taskCode", "code"), taskNode);
        }
    }

    private void registerTaskAlias(Map<String, JsonNode> lookup, String alias, JsonNode taskNode) {
        if (lookup == null || !StringUtils.hasText(alias) || taskNode == null || taskNode.isNull()) {
            return;
        }
        lookup.putIfAbsent(alias, taskNode);
    }

    private String firstText(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            String text = value.asText(null);
            if (StringUtils.hasText(text)) {
                return text;
            }
        }
        return null;
    }

    private Long firstLong(JsonNode node, String... fields) {
        if (node == null || fields == null) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.get(field);
            Long converted = asLong(value);
            if (converted != null) {
                return converted;
            }
        }
        return null;
    }

    private String readText(JsonNode node, String field, String defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        return value.asText(defaultValue);
    }

    private Long readLong(JsonNode node, String field, Long defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        Long converted = asLong(value);
        return converted != null ? converted : defaultValue;
    }

    private Integer readInteger(JsonNode node, String field, Integer defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isInt() || value.isLong()) {
            return value.asInt();
        }
        try {
            return Integer.parseInt(value.asText());
        } catch (Exception ex) {
            return defaultValue;
        }
    }

    private Boolean readBoolean(JsonNode node, String field, Boolean defaultValue) {
        if (node == null || node.isNull()) {
            return defaultValue;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return defaultValue;
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        String text = value.asText();
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Boolean.parseBoolean(text);
        }
        return defaultValue;
    }

    private LocalDateTime readDateTime(JsonNode node, String field, LocalDateTime defaultValue) {
        String text = readText(node, field, null);
        if (!StringUtils.hasText(text)) {
            return defaultValue;
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (DateTimeParseException e) {
            // ISO 格式不匹配，回退到 yyyy-MM-dd HH:mm:ss
            log.trace("按 ISO 格式解析时间失败，尝试备用格式: {}", text, e);
        }
        try {
            return LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (DateTimeParseException e) {
            // 备用格式也不匹配，返回默认值
            log.trace("按备用格式解析时间失败，返回默认值: {}", text, e);
            return defaultValue;
        }
    }

    private Long asLong(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isLong() || value.isInt()) {
            return value.asLong();
        }
        try {
            return Long.parseLong(value.asText());
        } catch (Exception ex) {
            return null;
        }
    }

    private Long asLongObject(Object value) {
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

    private String asTextObject(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return StringUtils.hasText(text) ? text : null;
    }

    private List<Long> toLongList(JsonNode arrayNode) {
        if (arrayNode == null || arrayNode.isNull() || !arrayNode.isArray()) {
            return Collections.emptyList();
        }
        List<Long> result = new ArrayList<>();
        for (JsonNode node : arrayNode) {
            Long value = asLong(node);
            if (value != null) {
                result.add(value);
            }
        }
        return result;
    }

    private String toText(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        return node.toString();
    }

    private String resolveOperator(String operator) {
        if (StringUtils.hasText(operator)) {
            return operator;
        }
        return "portal-ui";
    }

    private IllegalArgumentException badRequest(String code, String message) {
        return new IllegalArgumentException(code + ": " + message);
    }

    @Data
    private static class SnapshotNormalized {

        private int schemaVersion;

        private JsonNode root;

        private JsonNode workflow;

        private JsonNode schedule;

        private Map<String, JsonNode> tasks = Collections.emptyMap();

        private List<TaskSnapshot> taskSnapshots = Collections.emptyList();

        private Set<String> edges = Collections.emptySet();

        private boolean rollbackSupported;

        static SnapshotNormalized empty() {
            SnapshotNormalized snapshot = new SnapshotNormalized();
            snapshot.setSchemaVersion(0);
            snapshot.setWorkflow(null);
            snapshot.setSchedule(null);
            snapshot.setTasks(Collections.emptyMap());
            snapshot.setTaskSnapshots(Collections.emptyList());
            snapshot.setEdges(Collections.emptySet());
            snapshot.setRollbackSupported(false);
            return snapshot;
        }
    }

    @Data
    private static class TaskSnapshot {

        private Long taskId;

        private JsonNode node;

        private List<Long> inputTableIds = new ArrayList<>();

        private List<Long> outputTableIds = new ArrayList<>();
    }
}
