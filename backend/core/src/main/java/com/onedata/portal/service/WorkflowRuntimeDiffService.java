package com.onedata.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffFieldChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeRelationChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskChange;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowSchedule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
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
import org.springframework.util.StringUtils;



/**
 * 运行态快照与差异计算服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowRuntimeDiffService {

    private final ObjectMapper objectMapper;

    public RuntimeSnapshot buildSnapshot(RuntimeWorkflowDefinition definition, Collection<RuntimeTaskEdge> edges) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("workflow", buildWorkflowSnapshot(definition));
        root.put("tasks", buildTaskSnapshot(definition != null ? definition.getTasks() : Collections.emptyList()));
        root.put("edges", buildEdgeSnapshot(edges));
        root.put("schedule", buildScheduleSnapshot(definition != null ? definition.getSchedule() : null));

        String snapshotJson = toJson(root);
        RuntimeSnapshot snapshot = new RuntimeSnapshot();
        snapshot.setSnapshotJson(snapshotJson);
        snapshot.setSnapshotHash(hash(snapshotJson));
        snapshot.setSnapshotNode(readTree(snapshotJson));
        return snapshot;
    }

    public RuntimeDiffSummary buildDiff(String baselineSnapshotJson, RuntimeSnapshot currentSnapshot) {
        JsonNode baseline = readTree(baselineSnapshotJson);
        JsonNode current = currentSnapshot != null ? currentSnapshot.getSnapshotNode() : null;

        RuntimeDiffSummary summary = new RuntimeDiffSummary();
        summary.setBaselineHash(hash(baselineSnapshotJson));
        summary.setCurrentHash(currentSnapshot != null ? currentSnapshot.getSnapshotHash() : null);

        compareWorkflowFields(baseline, current, summary);
        compareTasks(baseline, current, summary);
        compareEdges(baseline, current, summary);
        compareScheduleFields(baseline, current, summary);
        summary.setChanged(hasChanges(summary));
        return summary;
    }

    private Map<String, Object> buildWorkflowSnapshot(RuntimeWorkflowDefinition definition) {
        Map<String, Object> workflow = new LinkedHashMap<>();
        if (definition == null) {
            return workflow;
        }
        workflow.put("projectCode", definition.getProjectCode());
        workflow.put("workflowCode", definition.getWorkflowCode());
        workflow.put("workflowName", definition.getWorkflowName());
        workflow.put("description", definition.getDescription());
        workflow.put("releaseState", definition.getReleaseState());
        workflow.put("globalParams", normalizeJsonString(definition.getGlobalParams()));
        return workflow;
    }

    private List<Map<String, Object>> buildTaskSnapshot(List<RuntimeTaskDefinition> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return Collections.emptyList();
        }
        return tasks.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(RuntimeTaskDefinition::getTaskCode, Comparator.nullsLast(Long::compareTo))
                        .thenComparing(RuntimeTaskDefinition::getTaskName, Comparator.nullsLast(String::compareTo)))
                .map(task -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("taskCode", task.getTaskCode());
                    item.put("taskVersion", task.getTaskVersion());
                    item.put("taskName", task.getTaskName());
                    item.put("description", task.getDescription());
                    item.put("nodeType", task.getNodeType());
                    item.put("datasourceId", task.getDatasourceId());
                    item.put("datasourceName", task.getDatasourceName());
                    item.put("datasourceType", task.getDatasourceType());
                    item.put("taskGroupName", task.getTaskGroupName());
                    item.put("taskGroupId", task.getTaskGroupId());
                    item.put("flag", task.getFlag());
                    item.put("taskPriority", task.getTaskPriority());
                    item.put("retryTimes", task.getRetryTimes());
                    item.put("retryInterval", task.getRetryInterval());
                    item.put("timeoutSeconds", task.getTimeoutSeconds());
                    item.put("sql", normalizeSql(task.getSql()));
                    item.put("inputTableIds", sortDistinct(task.getInputTableIds()));
                    item.put("outputTableIds", sortDistinct(task.getOutputTableIds()));
                    return item;
                })
                .collect(Collectors.toList());
    }

    private List<Map<String, Object>> buildEdgeSnapshot(Collection<RuntimeTaskEdge> edges) {
        if (edges == null || edges.isEmpty()) {
            return Collections.emptyList();
        }
        return edges.stream()
                .filter(Objects::nonNull)
                .filter(edge -> edge.getUpstreamTaskCode() != null && edge.getDownstreamTaskCode() != null)
                .sorted(Comparator.comparing(RuntimeTaskEdge::getUpstreamTaskCode)
                        .thenComparing(RuntimeTaskEdge::getDownstreamTaskCode))
                .map(edge -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("upstreamTaskCode", edge.getUpstreamTaskCode());
                    item.put("downstreamTaskCode", edge.getDownstreamTaskCode());
                    return item;
                })
                .collect(Collectors.toList());
    }

    private Map<String, Object> buildScheduleSnapshot(RuntimeWorkflowSchedule schedule) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (schedule == null) {
            return map;
        }
        map.put("scheduleId", schedule.getScheduleId());
        map.put("releaseState", schedule.getReleaseState());
        map.put("crontab", schedule.getCrontab());
        map.put("timezoneId", schedule.getTimezoneId());
        map.put("startTime", schedule.getStartTime());
        map.put("endTime", schedule.getEndTime());
        map.put("failureStrategy", schedule.getFailureStrategy());
        map.put("warningType", schedule.getWarningType());
        map.put("warningGroupId", schedule.getWarningGroupId());
        map.put("processInstancePriority", schedule.getProcessInstancePriority());
        map.put("workerGroup", schedule.getWorkerGroup());
        map.put("tenantCode", schedule.getTenantCode());
        map.put("environmentCode", schedule.getEnvironmentCode());
        return map;
    }

    private void compareWorkflowFields(JsonNode baseline, JsonNode current, RuntimeDiffSummary summary) {
        JsonNode before = baseline != null ? baseline.path("workflow") : null;
        JsonNode after = current != null ? current.path("workflow") : null;
        compareFlatFields(before, after, summary.getWorkflowFieldChanges(), "workflow");
    }

    private void compareScheduleFields(JsonNode baseline, JsonNode current, RuntimeDiffSummary summary) {
        JsonNode before = baseline != null ? baseline.path("schedule") : null;
        JsonNode after = current != null ? current.path("schedule") : null;
        compareFlatFields(before, after, summary.getScheduleChanges(), "schedule");
    }

    private void compareTasks(JsonNode baseline, JsonNode current, RuntimeDiffSummary summary) {
        Map<String, JsonNode> beforeMap = toTaskMap(baseline != null ? baseline.path("tasks") : null);
        Map<String, JsonNode> afterMap = toTaskMap(current != null ? current.path("tasks") : null);

        LinkedHashSet<String> beforeCodes = new LinkedHashSet<>(beforeMap.keySet());
        LinkedHashSet<String> afterCodes = new LinkedHashSet<>(afterMap.keySet());

        afterCodes.stream()
                .filter(code -> !beforeCodes.contains(code))
                .forEach(code -> summary.getTaskAdded().add(toTaskChange(afterMap.get(code), code, Collections.emptyList())));

        beforeCodes.stream()
                .filter(code -> !afterCodes.contains(code))
                .forEach(code -> summary.getTaskRemoved().add(toTaskChange(beforeMap.get(code), code, Collections.emptyList())));

        beforeCodes.stream()
                .filter(afterCodes::contains)
                .forEach(code -> {
                    JsonNode beforeNode = beforeMap.get(code);
                    JsonNode afterNode = afterMap.get(code);
                    List<RuntimeDiffFieldChange> fieldChanges = collectChangedTaskFields(beforeNode, afterNode);
                    if (!fieldChanges.isEmpty()) {
                        summary.getTaskModified().add(toTaskChange(afterNode != null ? afterNode : beforeNode, code, fieldChanges));
                    }
                });
    }

    private void compareEdges(JsonNode baseline, JsonNode current, RuntimeDiffSummary summary) {
        Map<String, JsonNode> beforeTasks = toTaskMap(baseline != null ? baseline.path("tasks") : null);
        Map<String, JsonNode> afterTasks = toTaskMap(current != null ? current.path("tasks") : null);
        Map<String, JsonNode> taskLookup = new LinkedHashMap<>();
        taskLookup.putAll(beforeTasks);
        taskLookup.putAll(afterTasks);

        Set<String> beforeEdges = toEdgeSet(baseline != null ? baseline.path("edges") : null);
        Set<String> afterEdges = toEdgeSet(current != null ? current.path("edges") : null);

        afterEdges.stream()
                .filter(edge -> !beforeEdges.contains(edge))
                .forEach(edge -> summary.getEdgeAdded().add(toRelationChange(edge, taskLookup)));

        beforeEdges.stream()
                .filter(edge -> !afterEdges.contains(edge))
                .forEach(edge -> summary.getEdgeRemoved().add(toRelationChange(edge, taskLookup)));
    }

    private void compareFlatFields(JsonNode before,
            JsonNode after,
            List<RuntimeDiffFieldChange> collector,
            String prefix) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(fieldNames(before));
        keys.addAll(fieldNames(after));

        keys.stream()
                .sorted()
                .forEach(key -> {
                    JsonNode beforeValue = before != null ? before.get(key) : null;
                    JsonNode afterValue = after != null ? after.get(key) : null;
                    if (!Objects.equals(beforeValue, afterValue)) {
                        RuntimeDiffFieldChange change = new RuntimeDiffFieldChange();
                        change.setField(prefix + "." + key);
                        change.setBefore(toText(beforeValue));
                        change.setAfter(toText(afterValue));
                        collector.add(change);
                    }
                });
    }

    private Set<String> fieldNames(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        node.fieldNames().forEachRemaining(keys::add);
        return keys;
    }

    private Map<String, JsonNode> toTaskMap(JsonNode tasksNode) {
        if (tasksNode == null || tasksNode.isNull() || !tasksNode.isArray()) {
            return Collections.emptyMap();
        }
        Map<String, JsonNode> map = new LinkedHashMap<>();
        for (JsonNode node : tasksNode) {
            if (node == null || node.isNull()) {
                continue;
            }
            String taskCode = node.path("taskCode").asText(null);
            if (!StringUtils.hasText(taskCode)) {
                continue;
            }
            map.put(taskCode, node);
        }
        return map;
    }

    private Set<String> toEdgeSet(JsonNode edgesNode) {
        if (edgesNode == null || edgesNode.isNull() || !edgesNode.isArray()) {
            return Collections.emptySet();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (JsonNode edge : edgesNode) {
            if (edge == null || edge.isNull()) {
                continue;
            }
            String upstream = edge.path("upstreamTaskCode").asText(null);
            String downstream = edge.path("downstreamTaskCode").asText(null);
            if (!StringUtils.hasText(upstream) || !StringUtils.hasText(downstream)) {
                continue;
            }
            result.add(upstream + "->" + downstream);
        }
        return result;
    }

    private RuntimeTaskChange toTaskChange(JsonNode taskNode,
            String taskCodeText,
            List<RuntimeDiffFieldChange> fieldChanges) {
        RuntimeTaskChange change = new RuntimeTaskChange();
        change.setTaskCode(parseLong(taskCodeText));
        change.setTaskName(resolveTaskName(taskNode));
        if (fieldChanges != null && !fieldChanges.isEmpty()) {
            change.setFieldChanges(fieldChanges);
        }
        return change;
    }

    private String resolveTaskName(JsonNode taskNode) {
        if (taskNode == null || taskNode.isNull()) {
            return null;
        }
        String taskName = taskNode.path("taskName").asText(null);
        return StringUtils.hasText(taskName) ? taskName : null;
    }

    private List<RuntimeDiffFieldChange> collectChangedTaskFields(JsonNode beforeTask, JsonNode afterTask) {
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(fieldNames(beforeTask));
        keys.addAll(fieldNames(afterTask));
        if (keys.isEmpty()) {
            return Collections.emptyList();
        }
        List<RuntimeDiffFieldChange> changed = new ArrayList<>();
        List<String> ordered = new ArrayList<>(keys);
        Collections.sort(ordered);
        for (String field : ordered) {
            JsonNode beforeValue = beforeTask != null ? beforeTask.get(field) : null;
            JsonNode afterValue = afterTask != null ? afterTask.get(field) : null;
            if (Objects.equals(beforeValue, afterValue)) {
                continue;
            }
            RuntimeDiffFieldChange change = new RuntimeDiffFieldChange();
            change.setField("task." + field);
            change.setBefore(toText(beforeValue));
            change.setAfter(toText(afterValue));
            changed.add(change);
        }
        return changed;
    }

    private RuntimeRelationChange toRelationChange(String edgeKey, Map<String, JsonNode> taskLookup) {
        RuntimeRelationChange change = new RuntimeRelationChange();
        if (!StringUtils.hasText(edgeKey)) {
            return change;
        }
        String[] parts = edgeKey.split("->", 2);
        if (parts.length != 2) {
            return change;
        }
        Long preCode = parseLong(parts[0].trim());
        Long postCode = parseLong(parts[1].trim());
        change.setPreTaskCode(preCode);
        change.setPostTaskCode(postCode);
        change.setEntryEdge(preCode != null && preCode == 0L);
        change.setPreTaskName(resolveRelationTaskName(preCode, taskLookup));
        change.setPostTaskName(resolveRelationTaskName(postCode, taskLookup));
        return change;
    }

    private String resolveRelationTaskName(Long taskCode, Map<String, JsonNode> taskLookup) {
        if (taskCode == null) {
            return null;
        }
        if (taskCode == 0L) {
            return "入口";
        }
        if (taskLookup == null) {
            return null;
        }
        JsonNode node = taskLookup.get(String.valueOf(taskCode));
        return resolveTaskName(node);
    }

    private Long parseLong(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean hasChanges(RuntimeDiffSummary summary) {
        return !summary.getWorkflowFieldChanges().isEmpty()
                || !summary.getTaskAdded().isEmpty()
                || !summary.getTaskRemoved().isEmpty()
                || !summary.getTaskModified().isEmpty()
                || !summary.getEdgeAdded().isEmpty()
                || !summary.getEdgeRemoved().isEmpty()
                || !summary.getScheduleChanges().isEmpty();
    }

    private List<Long> sortDistinct(List<Long> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return source.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private String normalizeSql(String sql) {
        if (!StringUtils.hasText(sql)) {
            return null;
        }
        return sql.replace("\r\n", "\n").trim();
    }

    private String normalizeJsonString(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        try {
            JsonNode node = objectMapper.readTree(trimmed);
            return toJson(node);
        } catch (Exception e) {
            // 非法 JSON 时回退为原始文本
            log.trace("规范化 JSON 失败，回退原始文本", e);
            return trimmed;
        }
    }

    private JsonNode readTree(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String toText(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isValueNode()) {
            return node.asText();
        }
        return toJson(node);
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

    private String hash(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("无法初始化 SHA-256", e);
        }
    }

    @Data
    public static class RuntimeSnapshot {
        private String snapshotHash;
        private String snapshotJson;
        private JsonNode snapshotNode;
    }
}
