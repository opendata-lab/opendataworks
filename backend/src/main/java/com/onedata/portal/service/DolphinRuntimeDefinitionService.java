package com.onedata.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.dolphin.DolphinSchedule;
import com.onedata.portal.dto.workflow.runtime.DolphinRuntimeWorkflowOption;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowSchedule;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Dolphin 运行态定义提取服务
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DolphinRuntimeDefinitionService {

    private final DolphinOpenApiClient openApiClient;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final ObjectMapper objectMapper;

    public DolphinRuntimeWorkflowPage listRuntimeWorkflows(Long projectCode,
            Integer pageNum,
            Integer pageSize,
            String keyword) {
        long resolvedProjectCode = resolveProjectCode(projectCode);
        int resolvedPageNum = pageNum != null && pageNum > 0 ? pageNum : 1;
        int resolvedPageSize = pageSize != null && pageSize > 0 ? pageSize : 20;

        JsonNode page = openApiClient.listProcessDefinitions(resolvedProjectCode, resolvedPageNum, resolvedPageSize, keyword);
        if (page == null) {
            return DolphinRuntimeWorkflowPage.empty();
        }

        JsonNode listNode = page.path("totalList");
        List<DolphinRuntimeWorkflowOption> records = new ArrayList<>();
        if (listNode.isArray()) {
            for (JsonNode item : listNode) {
                if (item == null || item.isNull()) {
                    continue;
                }
                DolphinRuntimeWorkflowOption option = new DolphinRuntimeWorkflowOption();
                option.setProjectCode(resolvedProjectCode);
                option.setWorkflowCode(readLong(item, "code", "workflowCode", "processDefinitionCode"));
                option.setWorkflowName(readText(item, "name", "workflowName"));
                option.setReleaseState(readText(item, "releaseState", "publishStatus", "scheduleReleaseState"));
                records.add(option);
            }
        }

        long total = page.path("total").asLong(records.size());
        if (total <= 0 && page.path("totalList").isArray()) {
            total = page.path("totalList").size();
        }

        DolphinRuntimeWorkflowPage result = new DolphinRuntimeWorkflowPage();
        result.setTotal(total);
        result.setRecords(records);
        return result;
    }

    public DolphinRuntimeWorkflowPage listRuntimeWorkflows(Long dolphinConfigId,
            Long projectCode,
            Integer pageNum,
            Integer pageSize,
            String keyword) {
        return dolphinSchedulerService.withConfig(dolphinConfigId,
                () -> listRuntimeWorkflows(projectCode, pageNum, pageSize, keyword));
    }

    /**
     * 按编码精确查询单条运行态工作流，不存在时返回 null。
     * 用于导入表单按文件里的 workflowCode 预选关联项，避免它不在分页第一页而选不中。
     */
    public DolphinRuntimeWorkflowOption findRuntimeWorkflow(Long dolphinConfigId,
            Long projectCode,
            Long workflowCode) {
        if (workflowCode == null || workflowCode <= 0) {
            return null;
        }
        return dolphinSchedulerService.withConfig(dolphinConfigId, () -> {
            long resolvedProjectCode = resolveProjectCode(projectCode);
            JsonNode raw;
            try {
                raw = openApiClient.getProcessDefinition(resolvedProjectCode, workflowCode);
            } catch (Exception ex) {
                log.debug("Runtime workflow {} not found in project {}: {}",
                        workflowCode, resolvedProjectCode, ex.getMessage());
                return null;
            }
            if (raw == null || raw.isNull() || raw.isMissingNode()) {
                return null;
            }
            JsonNode definition = unwrapDefinition(raw);
            DolphinRuntimeWorkflowOption option = new DolphinRuntimeWorkflowOption();
            option.setProjectCode(resolvedProjectCode);
            option.setWorkflowCode(workflowCode);
            option.setWorkflowName(readText(definition, "name", "workflowName"));
            option.setReleaseState(readText(definition, "releaseState", "publishStatus", "scheduleReleaseState"));
            return option;
        });
    }

    public RuntimeWorkflowDefinition loadRuntimeDefinition(Long projectCode, Long workflowCode) {
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalArgumentException("workflowCode 不能为空");
        }
        long resolvedProjectCode = resolveProjectCode(projectCode);
        JsonNode raw = openApiClient.getProcessDefinition(resolvedProjectCode, workflowCode);
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            throw new IllegalStateException("未找到 Dolphin 工作流定义");
        }

        JsonNode definition = unwrapDefinition(raw);

        RuntimeWorkflowDefinition result = new RuntimeWorkflowDefinition();
        result.setProjectCode(resolvedProjectCode);
        result.setWorkflowCode(workflowCode);
        result.setWorkflowName(readText(definition, "name", "workflowName"));
        result.setDescription(readText(definition, "description", "desc"));
        result.setReleaseState(readText(definition, "releaseState", "publishStatus", "scheduleReleaseState"));
        result.setGlobalParams(normalizeJsonField(definition.get("globalParams")));
        result.setSchedule(extractSchedule(definition, workflowCode));

        List<RuntimeTaskDefinition> tasks = parseTaskDefinitions(definition);
        List<RuntimeTaskEdge> explicitEdges = parseTaskEdges(definition);

        JsonNode tasksNode = openApiClient.getProcessDefinitionTasks(resolvedProjectCode, workflowCode);
        if (tasks.isEmpty()) {
            tasks = parseTaskDefinitionsFromNode(tasksNode);
        }
        if (explicitEdges.isEmpty()) {
            explicitEdges = parseTaskEdgesFromNode(tasksNode);
        }

        if (tasks.isEmpty()) {
            JsonNode taskDefinitionList = openApiClient.queryTaskDefinitionList(resolvedProjectCode, workflowCode);
            tasks = parseTaskDefinitionsFromNode(taskDefinitionList);
        }
        if (tasks.isEmpty() || explicitEdges.isEmpty()) {
            try {
                JsonNode exported = openApiClient.exportDefinitionByCode(resolvedProjectCode, workflowCode);
                JsonNode exportedDefinition = readNode(exported, "workflowDefinition", "processDefinition");
                if (exportedDefinition == null || exportedDefinition.isNull() || exportedDefinition.isMissingNode()) {
                    exportedDefinition = unwrapDefinition(exported);
                }
                if (tasks.isEmpty()) {
                    tasks = parseTaskDefinitionsFromNode(readNode(exported, "taskDefinitionList"));
                    if (tasks.isEmpty()) {
                        tasks = parseTaskDefinitions(exportedDefinition);
                    }
                }
                if (explicitEdges.isEmpty()) {
                    explicitEdges = parseTaskEdgesFromNode(
                            readNode(exported, "workflowTaskRelationList", "processTaskRelationList"));
                    if (explicitEdges.isEmpty()) {
                        explicitEdges = parseTaskEdges(exportedDefinition);
                    }
                }
            } catch (Exception e) {
                // 旧路径兼容分支，导出兜底失败时保留已有解析结果
                log.trace("导出兜底解析失败，保留已有解析结果", e);
            }
        }
        enrichTaskGroupNames(tasks);

        tasks = enrichTaskMetadataFromCatalog(tasks);
        result.setTasks(tasks);
        result.setExplicitEdges(explicitEdges);
        result.setRawDefinitionJson(toJson(raw));
        return result;
    }

    public RuntimeWorkflowDefinition loadRuntimeDefinition(Long dolphinConfigId, Long projectCode, Long workflowCode) {
        return dolphinSchedulerService.withConfig(dolphinConfigId,
                () -> loadRuntimeDefinition(projectCode, workflowCode));
    }

    /**
     * 通过 Dolphin 导出 JSON 获取运行态定义（导出主路）。
     */
    public RuntimeWorkflowDefinition loadRuntimeDefinitionFromExport(Long projectCode, Long workflowCode) {
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalArgumentException("workflowCode 不能为空");
        }

        long resolvedProjectCode = resolveProjectCode(projectCode);
        JsonNode exported = openApiClient.exportDefinitionByCode(resolvedProjectCode, workflowCode);
        if (exported == null || exported.isNull() || exported.isMissingNode()) {
            throw new IllegalStateException("导出工作流定义为空");
        }

        JsonNode definition = readNode(exported, "workflowDefinition", "processDefinition");
        if (definition == null || definition.isNull() || definition.isMissingNode()) {
            definition = unwrapDefinition(exported);
        }
        if (definition == null || definition.isNull() || definition.isMissingNode()) {
            throw new IllegalStateException("导出工作流定义缺少 workflowDefinition");
        }

        Long exportedWorkflowCode = readLong(definition, "code", "workflowCode", "processDefinitionCode");
        long resolvedWorkflowCode = exportedWorkflowCode != null && exportedWorkflowCode > 0
                ? exportedWorkflowCode
                : workflowCode;

        RuntimeWorkflowDefinition result = new RuntimeWorkflowDefinition();
        result.setProjectCode(resolvedProjectCode);
        result.setWorkflowCode(resolvedWorkflowCode);
        result.setWorkflowName(readText(definition, "name", "workflowName"));
        result.setDescription(readText(definition, "description", "desc"));
        result.setReleaseState(readText(definition, "releaseState", "publishStatus", "scheduleReleaseState"));
        result.setGlobalParams(normalizeJsonField(definition.get("globalParams")));

        RuntimeWorkflowSchedule schedule = parseScheduleNode(readNode(exported, "schedule"));
        if (schedule == null || schedule.getScheduleId() == null || schedule.getScheduleId() <= 0) {
            schedule = extractSchedule(definition, resolvedWorkflowCode);
        } else {
            schedule = mergeRuntimeSchedule(schedule, resolvedWorkflowCode);
            if (!StringUtils.hasText(schedule.getReleaseState())) {
                schedule.setReleaseState(readText(definition, "scheduleReleaseState", "releaseState"));
            }
        }
        result.setSchedule(schedule);

        List<RuntimeTaskDefinition> tasks = parseTaskDefinitionsFromNode(readNode(exported, "taskDefinitionList"));
        if (tasks.isEmpty()) {
            tasks = parseTaskDefinitions(definition);
        }

        List<RuntimeTaskEdge> explicitEdges = parseTaskEdgesFromNode(
                readNode(exported, "workflowTaskRelationList", "processTaskRelationList"));
        if (explicitEdges.isEmpty()) {
            explicitEdges = parseTaskEdges(definition);
        }
        enrichTaskGroupNames(tasks);

        tasks = enrichTaskMetadataFromCatalog(tasks);
        result.setTasks(tasks);
        result.setExplicitEdges(explicitEdges);
        result.setRawDefinitionJson(toJson(exported));
        return result;
    }

    public RuntimeWorkflowDefinition loadRuntimeDefinitionFromExport(Long dolphinConfigId,
            Long projectCode,
            Long workflowCode) {
        return dolphinSchedulerService.withConfig(dolphinConfigId,
                () -> loadRuntimeDefinitionFromExport(projectCode, workflowCode));
    }

    /**
     * 解析离线 JSON（Dolphin 导出文件或平台同构文档）为运行态定义模型。
     */
    public RuntimeWorkflowDefinition parseRuntimeDefinitionFromJson(String definitionJson) {
        if (!StringUtils.hasText(definitionJson)) {
            throw new IllegalArgumentException("definitionJson 不能为空");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(definitionJson);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("definitionJson 不是合法 JSON");
        }
        if (root == null || root.isNull() || root.isMissingNode()) {
            throw new IllegalArgumentException("definitionJson 为空对象");
        }

        JsonNode definition = readNode(root, "workflowDefinition", "processDefinition", "workflow");
        if (definition == null || definition.isNull() || definition.isMissingNode()) {
            definition = unwrapDefinition(root);
        }
        if (definition == null || definition.isNull() || definition.isMissingNode()) {
            throw new IllegalArgumentException("definitionJson 缺少 processDefinition/workflowDefinition");
        }

        RuntimeWorkflowDefinition result = new RuntimeWorkflowDefinition();
        result.setProjectCode(readLong(definition, "projectCode"));
        result.setWorkflowCode(readLong(definition, "code", "workflowCode", "processDefinitionCode"));
        result.setWorkflowName(readText(definition, "name", "workflowName"));
        result.setDescription(readText(definition, "description", "desc"));
        result.setReleaseState(readText(definition, "releaseState", "publishStatus", "scheduleReleaseState"));
        result.setGlobalParams(normalizeJsonField(readNode(definition, "globalParams")));

        RuntimeWorkflowSchedule schedule = parseScheduleNode(readNode(root, "schedule"));
        if (schedule == null) {
            schedule = parseScheduleNode(readNode(definition, "schedule"));
        }
        if (schedule != null && !StringUtils.hasText(schedule.getReleaseState())) {
            schedule.setReleaseState(readText(definition, "scheduleReleaseState", "releaseState"));
        }
        result.setSchedule(schedule);

        List<RuntimeTaskDefinition> tasks = parseTaskDefinitionsFromNode(
                firstPresentNode(root, "taskDefinitionList", "tasks", "taskDefinitionJson"));
        if (tasks.isEmpty()) {
            tasks = parseTaskDefinitions(definition);
        }
        List<RuntimeTaskEdge> edges = parseTaskEdgesFromNode(
                firstPresentNode(root,
                        "workflowTaskRelationList",
                        "processTaskRelationList",
                        "taskRelationList",
                        "edges",
                        "taskRelationJson"));
        if (edges.isEmpty()) {
            edges = parseTaskEdges(definition);
        }

        tasks = enrichTaskMetadataFromCatalog(tasks);
        result.setTasks(tasks);
        result.setExplicitEdges(edges);
        result.setRawDefinitionJson(toJson(root));
        return result;
    }

    private List<RuntimeTaskDefinition> enrichTaskMetadataFromCatalog(List<RuntimeTaskDefinition> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return tasks;
        }

        boolean needDatasourceCatalog = false;
        boolean needTaskGroupCatalog = false;
        for (RuntimeTaskDefinition task : tasks) {
            if (task == null) {
                continue;
            }
            task.setDatasourceName(normalizeText(task.getDatasourceName()));
            task.setDatasourceType(normalizeText(task.getDatasourceType()));
            task.setTaskPriority(normalizeTaskPriority(task.getTaskPriority()));
            task.setTaskGroupName(normalizeText(task.getTaskGroupName()));
            task.setFlag(normalizeDolphinFlag(task.getFlag()));

            Long datasourceId = task.getDatasourceId();
            String datasourceName = task.getDatasourceName();
            String datasourceType = task.getDatasourceType();
            if (((datasourceId == null || datasourceId <= 0) && StringUtils.hasText(datasourceName))
                    || ((datasourceId != null && datasourceId > 0)
                            && (!StringUtils.hasText(datasourceName) || !StringUtils.hasText(datasourceType)))) {
                needDatasourceCatalog = true;
            }

            Integer taskGroupId = task.getTaskGroupId();
            String taskGroupName = task.getTaskGroupName();
            if (((taskGroupId == null || taskGroupId <= 0) && StringUtils.hasText(taskGroupName))
                    || ((taskGroupId != null && taskGroupId > 0) && !StringUtils.hasText(taskGroupName))) {
                needTaskGroupCatalog = true;
            }
        }

        if (!needDatasourceCatalog && !needTaskGroupCatalog) {
            return tasks;
        }

        Map<Long, DolphinDatasourceOption> datasourceById = Collections.emptyMap();
        Map<String, DolphinDatasourceOption> datasourceByName = Collections.emptyMap();
        if (needDatasourceCatalog) {
            List<DolphinDatasourceOption> datasourceOptions = safeListDatasources();
            datasourceById = new LinkedHashMap<>();
            datasourceByName = new LinkedHashMap<>();
            for (DolphinDatasourceOption option : datasourceOptions) {
                if (option == null) {
                    continue;
                }
                if (option.getId() != null && option.getId() > 0) {
                    datasourceById.putIfAbsent(option.getId(), option);
                }
                String name = normalizeText(option.getName());
                if (StringUtils.hasText(name)) {
                    datasourceByName.putIfAbsent(name, option);
                }
            }
        }

        Map<Integer, DolphinTaskGroupOption> taskGroupById = Collections.emptyMap();
        Map<String, DolphinTaskGroupOption> taskGroupByName = Collections.emptyMap();
        if (needTaskGroupCatalog) {
            List<DolphinTaskGroupOption> taskGroupOptions = safeListTaskGroups();
            taskGroupById = new LinkedHashMap<>();
            taskGroupByName = new LinkedHashMap<>();
            for (DolphinTaskGroupOption option : taskGroupOptions) {
                if (option == null) {
                    continue;
                }
                if (option.getId() != null && option.getId() > 0) {
                    taskGroupById.putIfAbsent(option.getId(), option);
                }
                String name = normalizeText(option.getName());
                if (StringUtils.hasText(name)) {
                    taskGroupByName.putIfAbsent(name, option);
                }
            }
        }

        int unresolvedDatasourceIdByName = 0;
        int unresolvedDatasourceDetailById = 0;
        int unresolvedTaskGroupIdByName = 0;
        int unresolvedTaskGroupNameById = 0;
        for (RuntimeTaskDefinition task : tasks) {
            if (task == null) {
                continue;
            }

            Long datasourceId = task.getDatasourceId();
            String datasourceName = normalizeText(task.getDatasourceName());
            String datasourceType = normalizeText(task.getDatasourceType());
            if ((datasourceId == null || datasourceId <= 0) && StringUtils.hasText(datasourceName)) {
                DolphinDatasourceOption option = datasourceByName.get(datasourceName);
                if (option != null && option.getId() != null && option.getId() > 0) {
                    task.setDatasourceId(option.getId());
                    if (!StringUtils.hasText(datasourceType) && StringUtils.hasText(option.getType())) {
                        task.setDatasourceType(option.getType().trim());
                    }
                } else {
                    unresolvedDatasourceIdByName++;
                }
            } else if (datasourceId != null && datasourceId > 0) {
                DolphinDatasourceOption option = datasourceById.get(datasourceId);
                if (option != null) {
                    if (!StringUtils.hasText(datasourceName) && StringUtils.hasText(option.getName())) {
                        task.setDatasourceName(option.getName().trim());
                    }
                    if (!StringUtils.hasText(datasourceType) && StringUtils.hasText(option.getType())) {
                        task.setDatasourceType(option.getType().trim());
                    }
                } else if (!StringUtils.hasText(datasourceName) || !StringUtils.hasText(datasourceType)) {
                    unresolvedDatasourceDetailById++;
                }
            }

            Integer taskGroupId = task.getTaskGroupId();
            String taskGroupName = normalizeText(task.getTaskGroupName());
            if ((taskGroupId == null || taskGroupId <= 0) && StringUtils.hasText(taskGroupName)) {
                DolphinTaskGroupOption option = taskGroupByName.get(taskGroupName);
                if (option != null && option.getId() != null && option.getId() > 0) {
                    task.setTaskGroupId(option.getId());
                } else {
                    unresolvedTaskGroupIdByName++;
                }
            } else if (taskGroupId != null && taskGroupId > 0 && !StringUtils.hasText(taskGroupName)) {
                DolphinTaskGroupOption option = taskGroupById.get(taskGroupId);
                if (option != null && StringUtils.hasText(option.getName())) {
                    task.setTaskGroupName(option.getName().trim());
                } else {
                    unresolvedTaskGroupNameById++;
                }
            }
        }

        if (unresolvedDatasourceIdByName > 0
                || unresolvedDatasourceDetailById > 0
                || unresolvedTaskGroupIdByName > 0
                || unresolvedTaskGroupNameById > 0) {
            log.warn(
                    "Runtime task metadata enrichment unresolved: datasourceIdByName={}, datasourceDetailById={}, taskGroupIdByName={}, taskGroupNameById={}",
                    unresolvedDatasourceIdByName,
                    unresolvedDatasourceDetailById,
                    unresolvedTaskGroupIdByName,
                    unresolvedTaskGroupNameById);
        }
        return tasks;
    }

    private RuntimeWorkflowSchedule extractSchedule(JsonNode definition, Long workflowCode) {
        JsonNode scheduleNode = readNode(definition, "schedule");
        RuntimeWorkflowSchedule schedule = parseScheduleNode(scheduleNode);
        schedule = mergeRuntimeSchedule(schedule, workflowCode);

        if (schedule != null && !StringUtils.hasText(schedule.getReleaseState())) {
            schedule.setReleaseState(readText(definition, "scheduleReleaseState", "releaseState"));
        }
        return schedule;
    }

    private RuntimeWorkflowSchedule mergeRuntimeSchedule(RuntimeWorkflowSchedule current, Long workflowCode) {
        if (workflowCode == null || workflowCode <= 0) {
            return current;
        }
        DolphinSchedule runtimeSchedule = dolphinSchedulerService.getWorkflowSchedule(workflowCode);
        if (runtimeSchedule == null) {
            return current;
        }
        RuntimeWorkflowSchedule merged = current != null ? current : new RuntimeWorkflowSchedule();
        if (merged.getScheduleId() == null || merged.getScheduleId() <= 0) {
            merged.setScheduleId(runtimeSchedule.getId());
        }
        if (StringUtils.hasText(runtimeSchedule.getReleaseState())) {
            merged.setReleaseState(runtimeSchedule.getReleaseState());
        }
        if (!StringUtils.hasText(merged.getCrontab())) {
            merged.setCrontab(runtimeSchedule.getCrontab());
        }
        if (!StringUtils.hasText(merged.getTimezoneId())) {
            merged.setTimezoneId(runtimeSchedule.getTimezoneId());
        }
        if (!StringUtils.hasText(merged.getStartTime())) {
            merged.setStartTime(runtimeSchedule.getStartTime());
        }
        if (!StringUtils.hasText(merged.getEndTime())) {
            merged.setEndTime(runtimeSchedule.getEndTime());
        }
        if (!StringUtils.hasText(merged.getFailureStrategy())) {
            merged.setFailureStrategy(runtimeSchedule.getFailureStrategy());
        }
        if (!StringUtils.hasText(merged.getWarningType())) {
            merged.setWarningType(runtimeSchedule.getWarningType());
        }
        if (merged.getWarningGroupId() == null) {
            merged.setWarningGroupId(runtimeSchedule.getWarningGroupId());
        }
        if (!StringUtils.hasText(merged.getProcessInstancePriority())) {
            merged.setProcessInstancePriority(runtimeSchedule.getProcessInstancePriority());
        }
        if (!StringUtils.hasText(merged.getWorkerGroup())) {
            merged.setWorkerGroup(runtimeSchedule.getWorkerGroup());
        }
        if (!StringUtils.hasText(merged.getTenantCode())) {
            merged.setTenantCode(runtimeSchedule.getTenantCode());
        }
        if (merged.getEnvironmentCode() == null) {
            merged.setEnvironmentCode(runtimeSchedule.getEnvironmentCode());
        }
        return merged;
    }

    private RuntimeWorkflowSchedule parseScheduleNode(JsonNode scheduleNode) {
        if (scheduleNode == null || scheduleNode.isNull() || scheduleNode.isMissingNode()) {
            return null;
        }
        JsonNode node = normalizeNode(scheduleNode);
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        RuntimeWorkflowSchedule schedule = new RuntimeWorkflowSchedule();
        schedule.setScheduleId(readLong(node, "id", "scheduleId"));
        schedule.setReleaseState(readText(node, "releaseState"));
        schedule.setCrontab(readText(node, "crontab", "cron"));
        schedule.setTimezoneId(readText(node, "timezoneId", "timezone"));
        schedule.setStartTime(readText(node, "startTime"));
        schedule.setEndTime(readText(node, "endTime"));
        schedule.setFailureStrategy(readText(node, "failureStrategy"));
        schedule.setWarningType(readText(node, "warningType"));
        schedule.setWarningGroupId(readLong(node, "warningGroupId"));
        schedule.setProcessInstancePriority(readText(node, "processInstancePriority"));
        schedule.setWorkerGroup(readText(node, "workerGroup"));
        schedule.setTenantCode(readText(node, "tenantCode"));
        schedule.setEnvironmentCode(readLong(node, "environmentCode"));
        return schedule;
    }

    private List<RuntimeTaskDefinition> parseTaskDefinitions(JsonNode definition) {
        JsonNode taskNode = firstPresentNode(definition,
                "taskDefinitionJson",
                "taskDefinitionList",
                "taskList",
                "tasks");
        return parseTaskDefinitionsFromNode(taskNode);
    }

    private void enrichTaskGroupNames(List<RuntimeTaskDefinition> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return;
        }
        LinkedHashSet<Integer> missingTaskGroupIds = tasks.stream()
                .filter(Objects::nonNull)
                .filter(task -> !StringUtils.hasText(task.getTaskGroupName()))
                .map(RuntimeTaskDefinition::getTaskGroupId)
                .filter(id -> id != null && id > 0)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (missingTaskGroupIds.isEmpty()) {
            return;
        }

        List<DolphinTaskGroupOption> taskGroups = dolphinSchedulerService.listTaskGroups(null);
        if (taskGroups == null || taskGroups.isEmpty()) {
            return;
        }
        Map<Integer, String> taskGroupNameById = new LinkedHashMap<>();
        for (DolphinTaskGroupOption taskGroup : taskGroups) {
            if (taskGroup == null || taskGroup.getId() == null || taskGroup.getId() <= 0
                    || !StringUtils.hasText(taskGroup.getName())) {
                continue;
            }
            taskGroupNameById.putIfAbsent(taskGroup.getId(), taskGroup.getName().trim());
        }
        if (taskGroupNameById.isEmpty()) {
            return;
        }

        for (RuntimeTaskDefinition task : tasks) {
            if (task == null || StringUtils.hasText(task.getTaskGroupName())
                    || task.getTaskGroupId() == null || task.getTaskGroupId() <= 0) {
                continue;
            }
            String resolvedName = taskGroupNameById.get(task.getTaskGroupId());
            if (StringUtils.hasText(resolvedName)) {
                task.setTaskGroupName(resolvedName);
            }
        }
    }

    private List<RuntimeTaskDefinition> parseTaskDefinitionsFromNode(JsonNode taskNode) {
        JsonNode normalized = normalizeNode(taskNode);
        if (normalized != null && normalized.isObject()) {
            JsonNode inner = firstPresentNode(normalized,
                    "taskDefinitionJson",
                    "taskDefinitionList",
                    "taskList",
                    "tasks");
            if (inner != null && !inner.isMissingNode() && !inner.isNull()) {
                normalized = normalizeNode(inner);
            }
        }
        if (normalized == null || !normalized.isArray()) {
            return Collections.emptyList();
        }

        List<RuntimeTaskDefinition> tasks = new ArrayList<>();
        for (JsonNode item : normalized) {
            if (item == null || item.isNull()) {
                continue;
            }
            RuntimeTaskDefinition task = new RuntimeTaskDefinition();
            task.setTaskCode(readLong(item, "code", "taskCode"));
            task.setTaskVersion(readInt(item, "version", "taskVersion"));
            task.setTaskName(readText(item, "name", "taskName"));
            task.setDescription(readText(item, "description", "taskDesc"));
            task.setNodeType(readText(item, "taskType", "nodeType", "type"));
            task.setTimeoutSeconds(readInt(item, "timeout", "timeoutSeconds"));
            task.setRetryTimes(readInt(item, "failRetryTimes", "retryTimes"));
            task.setRetryInterval(readInt(item, "failRetryInterval", "retryInterval"));
            task.setTaskPriority(normalizeTaskPriority(readText(item, "taskPriority", "priority")));
            task.setTaskGroupId(readInt(item, "taskGroupId"));
            task.setTaskGroupName(readText(item, "taskGroupName"));
            task.setFlag(normalizeDolphinFlag(readText(item, "flag", "taskFlag")));

            JsonNode taskParamsNode = normalizeNode(item.get("taskParams"));
            if (taskParamsNode != null && !taskParamsNode.isNull()) {
                task.setSql(readText(taskParamsNode, "sql", "rawScript"));
                task.setDatasourceId(readLong(taskParamsNode, "datasource", "datasourceId"));
                task.setDatasourceName(readText(taskParamsNode, "datasourceName"));
                task.setDatasourceType(readText(taskParamsNode, "type", "datasourceType"));
            }
            if (!StringUtils.hasText(task.getSql())) {
                task.setSql(readText(item, "sql", "rawScript"));
            }
            if (!StringUtils.hasText(task.getDatasourceName())) {
                task.setDatasourceName(readText(item, "datasourceName"));
            }
            if (!StringUtils.hasText(task.getDatasourceType())) {
                task.setDatasourceType(readText(item, "datasourceType"));
            }
            task.setInputTableIds(readLongList(item, "inputTableIds"));
            task.setOutputTableIds(readLongList(item, "outputTableIds"));
            tasks.add(task);
        }
        return tasks;
    }

    private List<RuntimeTaskEdge> parseTaskEdges(JsonNode definition) {
        JsonNode relationNode = firstPresentNode(definition,
                "taskRelationJson",
                "taskRelationList",
                "processTaskRelationList",
                "workflowTaskRelationList");
        return parseTaskEdgesFromNode(relationNode);
    }

    private List<RuntimeTaskEdge> parseTaskEdgesFromNode(JsonNode relationNode) {
        JsonNode normalized = normalizeNode(relationNode);
        if (normalized != null && normalized.isObject()) {
            JsonNode inner = firstPresentNode(normalized,
                    "taskRelationJson",
                    "taskRelationList",
                    "processTaskRelationList",
                    "workflowTaskRelationList",
                    "edges");
            if (inner != null && !inner.isMissingNode() && !inner.isNull()) {
                normalized = normalizeNode(inner);
            }
        }
        if (normalized == null || !normalized.isArray()) {
            return Collections.emptyList();
        }

        List<RuntimeTaskEdge> edges = new ArrayList<>();
        for (JsonNode relation : normalized) {
            if (relation == null || relation.isNull()) {
                continue;
            }
            Long preTaskCode = readLong(relation, "preTaskCode", "preTask", "upstreamTaskCode");
            Long postTaskCode = readLong(relation, "postTaskCode", "postTask", "downstreamTaskCode");
            if (postTaskCode == null || postTaskCode <= 0) {
                continue;
            }
            // keep entry edges (preTaskCode=0) for full relation comparison.
            if (preTaskCode == null || preTaskCode < 0) {
                continue;
            }
            edges.add(new RuntimeTaskEdge(preTaskCode, postTaskCode));
        }
        return edges;
    }

    private JsonNode unwrapDefinition(JsonNode raw) {
        if (raw == null || raw.isNull()) {
            return raw;
        }
        JsonNode processDefinition = readNode(raw, "processDefinition");
        if (processDefinition != null && processDefinition.isObject()) {
            return processDefinition;
        }
        JsonNode processDefinitionJson = readNode(raw, "processDefinitionJson");
        JsonNode normalized = normalizeNode(processDefinitionJson);
        if (normalized != null && normalized.isObject()) {
            return normalized;
        }
        return raw;
    }

    private JsonNode firstPresentNode(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = readNode(node, fieldName);
            if (value != null && !value.isMissingNode() && !value.isNull()) {
                return value;
            }
        }
        return null;
    }

    private JsonNode normalizeNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            String text = node.asText();
            if (!StringUtils.hasText(text)) {
                return null;
            }
            try {
                return objectMapper.readTree(text);
            } catch (JsonProcessingException e) {
                return null;
            }
        }
        return node;
    }

    private String normalizeJsonField(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }
        if (node.isTextual()) {
            String value = node.asText();
            return StringUtils.hasText(value) ? value : null;
        }
        return toJson(node);
    }

    private JsonNode readNode(JsonNode node, String... fieldNames) {
        if (node == null || fieldNames == null) {
            return null;
        }
        for (String fieldName : fieldNames) {
            JsonNode value = node.get(fieldName);
            if (value != null && !value.isMissingNode()) {
                return value;
            }
        }
        return null;
    }

    private String readText(JsonNode node, String... fieldNames) {
        JsonNode field = readNode(node, fieldNames);
        if (field == null || field.isNull() || field.isMissingNode()) {
            return null;
        }
        if (field.isTextual()) {
            String text = field.asText();
            return StringUtils.hasText(text) ? text.trim() : null;
        }
        String text = field.asText(null);
        return StringUtils.hasText(text) ? text.trim() : null;
    }

    private Long readLong(JsonNode node, String... fieldNames) {
        JsonNode field = readNode(node, fieldNames);
        if (field == null || field.isNull() || field.isMissingNode()) {
            return null;
        }
        if (field.isIntegralNumber()) {
            return field.asLong();
        }
        if (field.isTextual()) {
            String text = field.asText();
            if (!StringUtils.hasText(text)) {
                return null;
            }
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                // 非数字文本，返回 null
                log.trace("解析 Long 失败，返回 null: {}", text, e);
                return null;
            }
        }
        return null;
    }

    private Integer readInt(JsonNode node, String... fieldNames) {
        Long value = readLong(node, fieldNames);
        if (value == null) {
            return null;
        }
        return value.intValue();
    }

    private List<Long> readLongList(JsonNode node, String... fieldNames) {
        JsonNode target = fieldNames == null || fieldNames.length == 0 ? node : readNode(node, fieldNames);
        JsonNode normalized = normalizeNode(target);
        if (normalized == null || !normalized.isArray()) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> ids = new LinkedHashSet<>();
        for (JsonNode item : normalized) {
            if (item == null || item.isNull() || item.isMissingNode()) {
                continue;
            }
            Long value;
            if (item.isIntegralNumber()) {
                value = item.asLong();
            } else if (item.isTextual()) {
                String text = item.asText();
                if (!StringUtils.hasText(text)) {
                    continue;
                }
                try {
                    value = Long.parseLong(text.trim());
                } catch (NumberFormatException e) {
                    // 跳过非数字文本
                    log.trace("解析 Long 失败，跳过: {}", text, e);
                    continue;
                }
            } else {
                continue;
            }
            ids.add(value);
        }
        return new ArrayList<>(ids);
    }

    private List<DolphinDatasourceOption> safeListDatasources() {
        List<DolphinDatasourceOption> options = dolphinSchedulerService.listDatasources(null, null);
        if (options == null) {
            return Collections.emptyList();
        }
        return options;
    }

    private List<DolphinTaskGroupOption> safeListTaskGroups() {
        List<DolphinTaskGroupOption> options = dolphinSchedulerService.listTaskGroups(null);
        if (options == null) {
            return Collections.emptyList();
        }
        return options;
    }

    private String normalizeText(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String normalizeTaskPriority(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim();
        String uppercase = normalized.toUpperCase(Locale.ROOT);
        switch (uppercase) {
            case "HIGHEST":
            case "HIGH":
            case "MEDIUM":
            case "LOW":
            case "LOWEST":
                return uppercase;
            default:
                break;
        }

        Integer parsedPriority = null;
        try {
            parsedPriority = Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            // 解析失败时保留原始文本
            log.trace("解析优先级失败，保留原始文本: {}", normalized, e);
        }
        if (parsedPriority == null) {
            return normalized;
        }

        if (parsedPriority >= 0 && parsedPriority <= 4) {
            switch (parsedPriority) {
                case 0:
                    return "HIGHEST";
                case 1:
                    return "HIGH";
                case 2:
                    return "MEDIUM";
                case 3:
                    return "LOW";
                default:
                    return "LOWEST";
            }
        }

        if (parsedPriority >= 9) {
            return "HIGHEST";
        }
        if (parsedPriority >= 7) {
            return "HIGH";
        }
        if (parsedPriority >= 5) {
            return "MEDIUM";
        }
        if (parsedPriority >= 3) {
            return "LOW";
        }
        return "LOWEST";
    }

    private String normalizeDolphinFlag(String value) {
        if (!StringUtils.hasText(value)) {
            return "YES";
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "NO".equals(normalized) ? "NO" : "YES";
    }

    private long resolveProjectCode(Long projectCode) {
        if (projectCode != null && projectCode > 0) {
            return projectCode;
        }
        Long currentProjectCode = dolphinSchedulerService.getProjectCode();
        if (currentProjectCode == null || currentProjectCode <= 0) {
            throw new IllegalStateException("无法获取 Dolphin projectCode");
        }
        return currentProjectCode;
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

    @Data
    public static class DolphinRuntimeWorkflowPage {
        private Long total = 0L;
        private List<DolphinRuntimeWorkflowOption> records = new ArrayList<>();

        public static DolphinRuntimeWorkflowPage empty() {
            return new DolphinRuntimeWorkflowPage();
        }
    }
}
