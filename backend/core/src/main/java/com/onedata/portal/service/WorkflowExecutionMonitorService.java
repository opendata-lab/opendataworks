package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.dto.dolphin.DolphinTaskInstance;
import com.onedata.portal.dto.execution.WorkflowExecutionPage;
import com.onedata.portal.dto.execution.WorkflowInstanceExecution;
import com.onedata.portal.dto.execution.WorkflowTaskInstanceExecution;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
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
 * Aggregates DolphinScheduler workflow instances and local platform trigger
 * records into the workflow-instance execution monitor.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowExecutionMonitorService {

    private static final int MAX_INSTANCES_PER_WORKFLOW = 100;

    /**
     * 执行监控展示的是「最近执行」，不需要保证每个工作流都被公平采样。
     * 不指定工作流时按项目级查询取最近这么多条，调用次数与工作流数量无关。
     */
    private static final int RECENT_INSTANCE_WINDOW = 50;

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final DataTaskMapper dataTaskMapper;
    private final TaskExecutionLogMapper executionLogMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final WorkflowInstanceCacheService workflowInstanceCacheService;

    public WorkflowExecutionPage listWorkflowInstances(Long workflowId,
            String status,
            boolean refresh,
            int pageNum,
            int pageSize) {
        int resolvedPageNum = Math.max(pageNum, 1);
        int resolvedPageSize = Math.min(Math.max(pageSize, 1), 100);

        List<DataWorkflow> workflows = dataWorkflowMapper.selectList(
                Wrappers.<DataWorkflow>lambdaQuery()
                        .eq(workflowId != null, DataWorkflow::getId, workflowId));
        if (CollectionUtils.isEmpty(workflows)) {
            return emptyPage(resolvedPageNum, resolvedPageSize);
        }

        // Web UI base URL 按 dolphinConfigId 记忆，避免逐工作流重复读配置。
        Map<Long, String> webuiBaseUrlCache = new HashMap<>();
        List<WorkflowInstanceExecution> snapshot;
        if (workflowId != null) {
            // 指定工作流：按 processDefinitionCode 服务端过滤，精确且只打一次 Dolphin。
            DataWorkflow workflow = workflows.get(0);
            Map<Long, List<TaskExecutionLog>> localLogsByWorkflow =
                    loadLocalLogsByWorkflow(workflows, MAX_INSTANCES_PER_WORKFLOW);
            snapshot = resolveWorkflowExecutions(
                    workflow,
                    localLogsByWorkflow.getOrDefault(workflow.getId(), Collections.emptyList()),
                    refresh,
                    webuiBaseUrlCache);
        } else {
            snapshot = resolveRecentExecutions(workflows, webuiBaseUrlCache);
        }

        List<WorkflowInstanceExecution> filtered = snapshot.stream()
                .filter(item -> !StringUtils.hasText(status) || status.equalsIgnoreCase(item.getStatus()))
                .sorted(Comparator.comparing(
                        WorkflowInstanceExecution::getStartTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        Map<String, Object> statistics = calculateStatistics(filtered);
        int from = Math.min((resolvedPageNum - 1) * resolvedPageSize, filtered.size());
        int to = Math.min(from + resolvedPageSize, filtered.size());
        return WorkflowExecutionPage.builder()
                .total(filtered.size())
                .pageNum(resolvedPageNum)
                .pageSize(resolvedPageSize)
                .records(new ArrayList<>(filtered.subList(from, to)))
                .statistics(statistics)
                .build();
    }

    public List<WorkflowTaskInstanceExecution> listTaskInstances(Long workflowId, Long instanceId) {
        if (workflowId == null || instanceId == null) {
            throw new IllegalArgumentException("workflowId and instanceId are required");
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            throw new IllegalStateException("工作流尚未部署，无法读取 Dolphin 任务实例");
        }

        List<DolphinTaskInstance> instances = dolphinSchedulerService.listTaskInstances(
                workflow.getDolphinConfigId(), instanceId);
        Map<Long, DataTask> taskByCode = loadWorkflowTasksByCode(workflowId);
        return instances.stream()
                .filter(Objects::nonNull)
                .map(instance -> mapTaskInstance(instance, taskByCode.get(instance.getTaskCode())))
                .sorted(Comparator
                        .comparing(WorkflowTaskInstanceExecution::getStartTime,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(WorkflowTaskInstanceExecution::getTaskInstanceId,
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    private List<WorkflowInstanceExecution> resolveWorkflowExecutions(DataWorkflow workflow,
            List<TaskExecutionLog> localLogs,
            boolean refresh,
            Map<Long, String> webuiBaseUrlCache) {
        List<RuntimeWorkflowInstance> runtimeInstances = new ArrayList<>();
        if (workflow.getWorkflowCode() != null && workflow.getWorkflowCode() > 0) {
            try {
                List<WorkflowInstanceSummary> summaries = dolphinSchedulerService.listWorkflowInstances(
                        workflow.getDolphinConfigId(),
                        workflow.getWorkflowCode(),
                        MAX_INSTANCES_PER_WORKFLOW);
                workflowInstanceCacheService.replaceCache(workflow, summaries);
                runtimeInstances = summaries.stream()
                        .map(summary -> RuntimeWorkflowInstance.fromSummary(summary, "dolphin"))
                        .collect(Collectors.toList());
            } catch (Exception ex) {
                log.warn("Failed to refresh workflow executions for workflow {} (refresh={}): {}",
                        workflow.getId(), refresh, ex.getMessage());
                runtimeInstances = workflowInstanceCacheService
                        .listRecent(workflow.getId(), MAX_INSTANCES_PER_WORKFLOW)
                        .stream()
                        .map(cache -> RuntimeWorkflowInstance.fromCache(cache, "cache"))
                        .collect(Collectors.toList());
            }
        }
        return mergeWithLocalLogs(workflow, runtimeInstances, localLogs, webuiBaseUrlCache);
    }

    /**
     * 不指定工作流时的取数路径：按 dolphinConfigId 分组做项目级查询，取最近
     * {@link #RECENT_INSTANCE_WINDOW} 条，Dolphin 调用次数等于配置数量而非工作流数量。
     * 该路径只读不写缓存——缓存是 per-workflow 语义，由 WorkflowExecutionSyncJob 维护。
     */
    private List<WorkflowInstanceExecution> resolveRecentExecutions(List<DataWorkflow> workflows,
            Map<Long, String> webuiBaseUrlCache) {
        Map<Long, DataWorkflow> workflowByCode = new LinkedHashMap<>();
        Map<Long, List<DataWorkflow>> workflowsByConfig = new LinkedHashMap<>();
        for (DataWorkflow workflow : workflows) {
            if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
                continue;
            }
            workflowByCode.putIfAbsent(workflow.getWorkflowCode(), workflow);
            workflowsByConfig
                    .computeIfAbsent(workflow.getDolphinConfigId(), key -> new ArrayList<>())
                    .add(workflow);
        }

        Map<DataWorkflow, List<RuntimeWorkflowInstance>> runtimeByWorkflow = new LinkedHashMap<>();
        boolean anyConfigFailed = false;
        for (Long dolphinConfigId : workflowsByConfig.keySet()) {
            try {
                List<WorkflowInstanceSummary> summaries = dolphinSchedulerService
                        .listRecentProjectInstances(dolphinConfigId, RECENT_INSTANCE_WINDOW);
                for (WorkflowInstanceSummary summary : summaries) {
                    DataWorkflow owner = workflowByCode.get(summary.getWorkflowCode());
                    if (owner == null) {
                        // 项目里可能有非本平台托管的工作流，其实例直接丢弃。
                        continue;
                    }
                    runtimeByWorkflow
                            .computeIfAbsent(owner, key -> new ArrayList<>())
                            .add(RuntimeWorkflowInstance.fromSummary(summary, "dolphin"));
                }
            } catch (Exception ex) {
                anyConfigFailed = true;
                log.warn("Failed to list recent instances for dolphin config {}: {}",
                        dolphinConfigId, ex.getMessage());
            }
        }

        if (runtimeByWorkflow.isEmpty() && anyConfigFailed) {
            Map<Long, DataWorkflow> workflowById = workflows.stream()
                    .filter(workflow -> workflow.getId() != null)
                    .collect(Collectors.toMap(
                            DataWorkflow::getId,
                            workflow -> workflow,
                            (left, right) -> left,
                            LinkedHashMap::new));
            for (WorkflowInstanceCache cache : workflowInstanceCacheService
                    .listRecentAcrossWorkflows(RECENT_INSTANCE_WINDOW)) {
                DataWorkflow owner = workflowById.get(cache.getWorkflowId());
                if (owner == null) {
                    continue;
                }
                runtimeByWorkflow
                        .computeIfAbsent(owner, key -> new ArrayList<>())
                        .add(RuntimeWorkflowInstance.fromCache(cache, "cache"));
            }
        }

        Map<Long, List<TaskExecutionLog>> localLogsByWorkflow =
                loadLocalLogsByWorkflow(workflows, RECENT_INSTANCE_WINDOW);
        List<WorkflowInstanceExecution> result = new ArrayList<>();
        Set<Long> coveredWorkflowIds = new HashSet<>();
        for (Map.Entry<DataWorkflow, List<RuntimeWorkflowInstance>> entry : runtimeByWorkflow.entrySet()) {
            DataWorkflow owner = entry.getKey();
            coveredWorkflowIds.add(owner.getId());
            result.addAll(mergeWithLocalLogs(
                    owner,
                    entry.getValue(),
                    localLogsByWorkflow.getOrDefault(owner.getId(), Collections.emptyList()),
                    webuiBaseUrlCache));
        }
        // 已触发但 Dolphin 侧还没有实例的平台记录，仍要在监控里可见。
        for (DataWorkflow workflow : workflows) {
            if (coveredWorkflowIds.contains(workflow.getId())) {
                continue;
            }
            List<TaskExecutionLog> localLogs =
                    localLogsByWorkflow.getOrDefault(workflow.getId(), Collections.emptyList());
            if (localLogs.isEmpty()) {
                continue;
            }
            result.addAll(mergeWithLocalLogs(
                    workflow, Collections.emptyList(), localLogs, webuiBaseUrlCache));
        }

        return result.stream()
                .sorted(Comparator.comparing(
                        WorkflowInstanceExecution::getStartTime,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(RECENT_INSTANCE_WINDOW)
                .collect(Collectors.toList());
    }

    /**
     * 把 Dolphin 运行实例与本地触发日志合并成一份展示快照：能对上的补齐来源与错误信息，
     * 对不上的本地记录单独成行。
     */
    private List<WorkflowInstanceExecution> mergeWithLocalLogs(DataWorkflow workflow,
            List<RuntimeWorkflowInstance> runtimeInstances,
            List<TaskExecutionLog> localLogs,
            Map<Long, String> webuiBaseUrlCache) {
        Map<String, TaskExecutionLog> localByExternalId = localLogs.stream()
                .filter(logRecord -> StringUtils.hasText(logRecord.getExecutionId()))
                .collect(Collectors.toMap(
                        TaskExecutionLog::getExecutionId,
                        logRecord -> logRecord,
                        this::newerLocalLog,
                        LinkedHashMap::new));

        List<WorkflowInstanceExecution> result = new ArrayList<>();
        Set<Long> matchedLocalLogIds = new HashSet<>();
        Set<String> seenExternalIds = new HashSet<>();
        for (RuntimeWorkflowInstance runtime : runtimeInstances) {
            String instanceKey = runtime.instanceId == null ? null : String.valueOf(runtime.instanceId);
            TaskExecutionLog localMatch = instanceKey == null ? null : localByExternalId.get(instanceKey);
            if (localMatch != null && localMatch.getId() != null) {
                matchedLocalLogIds.add(localMatch.getId());
            }
            if (instanceKey != null) {
                seenExternalIds.add(instanceKey);
            }
            result.add(mapRuntimeInstance(workflow, runtime, localMatch, webuiBaseUrlCache));
        }

        for (TaskExecutionLog localLog : localLogs) {
            if (localLog.getId() != null && matchedLocalLogIds.contains(localLog.getId())) {
                continue;
            }
            if (StringUtils.hasText(localLog.getExecutionId())
                    && !seenExternalIds.add(localLog.getExecutionId())) {
                continue;
            }
            result.add(mapLocalExecution(workflow, localLog, webuiBaseUrlCache));
        }
        return result;
    }

    private Map<Long, List<TaskExecutionLog>> loadLocalLogsByWorkflow(List<DataWorkflow> workflows, int limit) {
        List<Long> workflowIds = workflows.stream()
                .map(DataWorkflow::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (workflowIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .in(WorkflowTaskRelation::getWorkflowId, workflowIds));
        if (relations.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<Long, Long> workflowIdByTaskId = relations.stream()
                .filter(relation -> relation.getTaskId() != null && relation.getWorkflowId() != null)
                .collect(Collectors.toMap(
                        WorkflowTaskRelation::getTaskId,
                        WorkflowTaskRelation::getWorkflowId,
                        (left, right) -> left));
        if (workflowIdByTaskId.isEmpty()) {
            return Collections.emptyMap();
        }
        List<TaskExecutionLog> logs = executionLogMapper.selectList(
                Wrappers.<TaskExecutionLog>lambdaQuery()
                        .in(TaskExecutionLog::getTaskId, workflowIdByTaskId.keySet())
                        .orderByDesc(TaskExecutionLog::getStartTime)
                        .last("LIMIT " + Math.max(limit, 1)));
        return logs.stream()
                .filter(logRecord -> workflowIdByTaskId.containsKey(logRecord.getTaskId()))
                .collect(Collectors.groupingBy(
                        logRecord -> workflowIdByTaskId.get(logRecord.getTaskId()),
                        LinkedHashMap::new,
                        Collectors.toList()));
    }

    private Map<Long, DataTask> loadWorkflowTasksByCode(Long workflowId) {
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
        List<Long> taskIds = relations.stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return dataTaskMapper.selectBatchIds(taskIds).stream()
                .filter(task -> task.getDolphinTaskCode() != null)
                .collect(Collectors.toMap(
                        DataTask::getDolphinTaskCode,
                        task -> task,
                        (left, right) -> left,
                        LinkedHashMap::new));
    }

    private WorkflowInstanceExecution mapRuntimeInstance(DataWorkflow workflow,
            RuntimeWorkflowInstance runtime,
            TaskExecutionLog localMatch,
            Map<Long, String> webuiBaseUrlCache) {
        LocalDateTime startTime = runtime.startTime;
        LocalDateTime endTime = runtime.endTime;
        String triggerType = localMatch != null && StringUtils.hasText(localMatch.getTriggerType())
                ? DolphinExecutionMapper.mapTriggerType(localMatch.getTriggerType())
                : DolphinExecutionMapper.mapTriggerType(runtime.commandType);
        return WorkflowInstanceExecution.builder()
                .workflowId(workflow.getId())
                .workflowName(workflow.getWorkflowName())
                .workflowCode(workflow.getWorkflowCode())
                .instanceId(runtime.instanceId)
                .localExecutionLogId(localMatch == null ? null : localMatch.getId())
                .status(DolphinExecutionMapper.mapStatus(runtime.state))
                .dolphinState(runtime.state)
                .commandType(runtime.commandType)
                .triggerType(triggerType)
                .source(localMatch == null ? "dolphin" : "platform")
                .executionSource(runtime.executionSource)
                .scheduleTime(runtime.scheduleTime)
                .startTime(startTime)
                .endTime(endTime)
                .durationSeconds(resolveWorkflowDurationSeconds(startTime, endTime, runtime.durationMs))
                .errorMessage(localMatch == null ? null : localMatch.getErrorMessage())
                .expandable(runtime.instanceId != null)
                .dolphinInstanceUrl(resolveInstanceUrl(workflow, runtime.instanceId, webuiBaseUrlCache))
                .build();
    }

    private WorkflowInstanceExecution mapLocalExecution(DataWorkflow workflow,
            TaskExecutionLog localLog,
            Map<Long, String> webuiBaseUrlCache) {
        Long instanceId = parseLong(localLog.getExecutionId());
        return WorkflowInstanceExecution.builder()
                .workflowId(workflow.getId())
                .workflowName(workflow.getWorkflowName())
                .workflowCode(workflow.getWorkflowCode())
                .instanceId(instanceId)
                .localExecutionLogId(localLog.getId())
                .status(localLog.getStatus())
                .triggerType(DolphinExecutionMapper.mapTriggerType(localLog.getTriggerType()))
                .source("platform")
                .executionSource("local")
                .startTime(localLog.getStartTime())
                .endTime(localLog.getEndTime())
                .durationSeconds(localLog.getDurationSeconds())
                .errorMessage(localLog.getErrorMessage())
                .expandable(instanceId != null
                        && workflow.getWorkflowCode() != null
                        && workflow.getWorkflowCode() > 0)
                .dolphinInstanceUrl(resolveInstanceUrl(workflow, instanceId, webuiBaseUrlCache))
                .build();
    }

    /**
     * 解析实例详情深链。Web UI 地址按 dolphinConfigId 记忆，同一配置下的工作流只读一次配置；
     * 读取失败时记为空串，后续同一配置不再重试。
     */
    private String resolveInstanceUrl(DataWorkflow workflow,
            Long instanceId,
            Map<Long, String> webuiBaseUrlCache) {
        if (instanceId == null || workflow.getProjectCode() == null || workflow.getWorkflowCode() == null) {
            return null;
        }
        String baseUrl = webuiBaseUrlCache.computeIfAbsent(workflow.getDolphinConfigId(), configId -> {
            try {
                String resolved = dolphinSchedulerService.getWebuiBaseUrl(configId);
                return resolved == null ? "" : resolved;
            } catch (Exception ex) {
                log.warn("Failed to resolve Dolphin WebUI base url for config {}: {}",
                        configId, ex.getMessage());
                return "";
            }
        });
        return DolphinExecutionMapper.workflowInstanceUrl(
                baseUrl, workflow.getProjectCode(), workflow.getWorkflowCode(), instanceId);
    }

    private WorkflowTaskInstanceExecution mapTaskInstance(DolphinTaskInstance instance, DataTask task) {
        LocalDateTime startTime = DolphinExecutionMapper.parseDateTime(instance.getStartTime());
        LocalDateTime endTime = DolphinExecutionMapper.parseDateTime(instance.getEndTime());
        return WorkflowTaskInstanceExecution.builder()
                .platformTaskId(task == null ? null : task.getId())
                .dolphinTaskCode(instance.getTaskCode())
                .taskInstanceId(instance.getId())
                .taskName(task == null ? instance.getName() : task.getTaskName())
                .taskType(instance.getTaskType())
                .status(DolphinExecutionMapper.mapStatus(instance.getState()))
                .dolphinState(instance.getState())
                .host(instance.getHost())
                .retryTimes(instance.getRetryTimes())
                .executorName(instance.getExecutorName())
                .startTime(startTime)
                .endTime(endTime)
                .durationSeconds(DolphinExecutionMapper.durationSeconds(
                        startTime, endTime, instance.getDuration()))
                .build();
    }

    private Map<String, Object> calculateStatistics(List<WorkflowInstanceExecution> items) {
        Map<String, Long> distribution = items.stream()
                .collect(Collectors.groupingBy(
                        WorkflowInstanceExecution::getStatus,
                        LinkedHashMap::new,
                        Collectors.counting()));
        long total = items.size();
        long success = distribution.getOrDefault("success", 0L);
        long failed = distribution.getOrDefault("failed", 0L);
        long running = distribution.getOrDefault("running", 0L);
        double averageDuration = items.stream()
                .map(WorkflowInstanceExecution::getDurationSeconds)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .average()
                .orElse(0.0);

        Map<String, Object> statistics = new LinkedHashMap<>();
        statistics.put("totalExecutions", total);
        statistics.put("successCount", success);
        statistics.put("failedCount", failed);
        statistics.put("runningCount", running);
        statistics.put("successRate", total == 0 ? 0.0 : roundRate(success, total));
        statistics.put("failureRate", total == 0 ? 0.0 : roundRate(failed, total));
        statistics.put("avgDurationSeconds", Math.round(averageDuration * 100.0) / 100.0);
        statistics.put("statusDistribution", distribution);
        return statistics;
    }

    private WorkflowExecutionPage emptyPage(int pageNum, int pageSize) {
        return WorkflowExecutionPage.builder()
                .total(0)
                .pageNum(pageNum)
                .pageSize(pageSize)
                .records(Collections.emptyList())
                .statistics(calculateStatistics(Collections.emptyList()))
                .build();
    }

    private TaskExecutionLog newerLocalLog(TaskExecutionLog left, TaskExecutionLog right) {
        if (left.getStartTime() == null) {
            return right;
        }
        if (right.getStartTime() == null) {
            return left;
        }
        return right.getStartTime().isAfter(left.getStartTime()) ? right : left;
    }

    private Integer resolveWorkflowDurationSeconds(LocalDateTime startTime,
            LocalDateTime endTime,
            Long durationMs) {
        if (startTime != null && endTime != null) {
            long seconds = Math.max(0, Duration.between(startTime, endTime).getSeconds());
            return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
        }
        if (durationMs == null || durationMs < 0) {
            return null;
        }
        long seconds = durationMs / 1000;
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }

    private double roundRate(long count, long total) {
        return Math.round(((double) count / total * 100.0) * 100.0) / 100.0;
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static LocalDateTime toLocalDateTime(Date value) {
        return value == null
                ? null
                : LocalDateTime.ofInstant(value.toInstant(), ZoneId.systemDefault());
    }

    private static final class RuntimeWorkflowInstance {
        private Long instanceId;
        private String state;
        private String commandType;
        private LocalDateTime scheduleTime;
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private Long durationMs;
        private String executionSource;

        private static RuntimeWorkflowInstance fromSummary(WorkflowInstanceSummary summary,
                String executionSource) {
            RuntimeWorkflowInstance instance = new RuntimeWorkflowInstance();
            instance.instanceId = summary.getInstanceId();
            instance.state = summary.getState();
            instance.commandType = summary.getCommandType();
            instance.scheduleTime = DolphinExecutionMapper.parseDateTime(summary.getScheduleTime());
            instance.startTime = DolphinExecutionMapper.parseDateTime(summary.getStartTime());
            instance.endTime = DolphinExecutionMapper.parseDateTime(summary.getEndTime());
            instance.durationMs = summary.getDurationMs();
            instance.executionSource = executionSource;
            return instance;
        }

        private static RuntimeWorkflowInstance fromCache(WorkflowInstanceCache cache,
                String executionSource) {
            RuntimeWorkflowInstance instance = new RuntimeWorkflowInstance();
            instance.instanceId = cache.getInstanceId();
            instance.state = cache.getState();
            instance.commandType = cache.getTriggerType();
            instance.scheduleTime = toLocalDateTime(cache.getScheduleTime());
            instance.startTime = toLocalDateTime(cache.getStartTime());
            instance.endTime = toLocalDateTime(cache.getEndTime());
            instance.durationMs = cache.getDurationMs();
            instance.executionSource = executionSource;
            return instance;
        }
    }
}
