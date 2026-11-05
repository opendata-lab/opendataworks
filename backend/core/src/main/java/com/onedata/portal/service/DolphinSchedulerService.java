package com.onedata.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.DolphinAlertGroupOption;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinEnvironmentOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.dolphin.*;
import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client wrapper around the DolphinScheduler OpenAPI.
 *
 * <p>Encapsulates request/response mapping and workflow helper utilities so the rest of the
 * codebase can manage tasks without depending on an additional Python layer.
 */
@Slf4j
@Service
public class DolphinSchedulerService {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final DolphinConfigService dolphinConfigService;
    private final ObjectMapper objectMapper;
    private final DolphinOpenApiClient openApiClient;
    private final AtomicLong taskCodeSequence = new AtomicLong(System.currentTimeMillis());

    // Cache for project code to avoid repeated API calls.
    //
    // Key 必须包含配置身份（URL + 项目名），不能只用配置 id：管理员改掉某个环境的地址或项目名后，
    // 缓存清理发生在事务提交、锁释放之后（Controller 里调 clearProjectCodeCache），
    // 等在锁上的导入可能恰好在这两者之间拿到锁，随后命中旧 id 对应的旧 projectCode，
    // 在新环境里绑定到编码碰巧相同的另一条运行态。把身份编进 key，配置一变缓存自然落空。
    private final Map<String, Long> cachedProjectCodeByConfigId = new ConcurrentHashMap<>();

    public DolphinSchedulerService(
            DolphinConfigService dolphinConfigService,
            ObjectMapper objectMapper,
            DolphinOpenApiClient openApiClient) {
        this.dolphinConfigService = dolphinConfigService;
        this.objectMapper = objectMapper;
        this.openApiClient = openApiClient;
    }

    /**
     * Query project code from DolphinScheduler by project name. Results are cached to avoid
     * repeated API calls.
     */
    public Long getProjectCode() {
        return getProjectCodeAtTarget(dolphinConfigService.resolveActiveOrTargetConfig(null));
    }

    public Long getProjectCodeAtTarget(DolphinConfig targetConfig) {
        return getProjectCodeAtTarget(targetConfig, false);
    }

    /** Query project code with option to force refresh the cache. */
    public Long getProjectCode(boolean forceRefresh) {
        return getProjectCodeAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), forceRefresh);
    }

    public Long getProjectCodeAtTarget(DolphinConfig targetConfig, boolean forceRefresh) {
        DolphinConfig activeConfig = targetConfig;
        String cacheKey = configCacheKey(activeConfig);
        if (!forceRefresh && cachedProjectCodeByConfigId.containsKey(cacheKey)) {
            return cachedProjectCodeByConfigId.get(cacheKey);
        }

        synchronized (this) {
            if (!forceRefresh && cachedProjectCodeByConfigId.containsKey(cacheKey)) {
                return cachedProjectCodeByConfigId.get(cacheKey);
            }

            String projectName = activeConfig.getProjectName();
            try {
                DolphinProject project = openApiClient.getProject(targetConfig, projectName);
                if (project != null) {
                    cachedProjectCodeByConfigId.put(cacheKey, project.getCode());
                    log.info("Queried project code for {}: {}", projectName, project.getCode());
                    return project.getCode();
                } else {
                    log.info("Project {} not found. Attempting to create it...", projectName);
                    try {
                        Long newCode =
                                openApiClient.createProject(
                                        targetConfig, projectName, "Auto-created by OpenDataWorks");
                        if (newCode != null && newCode > 0) {
                            cachedProjectCodeByConfigId.put(cacheKey, newCode);
                            log.info("Created project {}: {}", projectName, newCode);
                            return newCode;
                        }
                    } catch (Exception ex) {
                        log.error("Failed to auto-create project {}", projectName, ex);
                    }

                    log.warn("Project {} could not be found or created", projectName);
                    return null;
                }
            } catch (Exception e) {
                log.warn("Failed to query project code for {}: {}", projectName, e.getMessage());
                return null;
            }
        }
    }

    /** Clear the cached project code. Use this when DolphinScheduler is reset. */
    public void clearProjectCodeCache() {
        cachedProjectCodeByConfigId.clear();
        log.info("Cleared project code cache");
    }

    public Long getProjectCode(Long dolphinConfigId, boolean forceRefresh) {
        return getProjectCodeAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                forceRefresh);
    }

    public Long getProjectCodeAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, boolean forceRefresh) {
        return getProjectCodeAtTarget(targetConfig, forceRefresh);
    }

    /**
     * 只读解析项目编码：项目不存在时返回 null，不会自动创建。 导入预检这类只读路径必须用这个方法，避免 {@link #getProjectCode(boolean)}
     * 的自动建项目副作用。
     */
    public Long findProjectCode(Long dolphinConfigId) {
        return findProjectCodeAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId), dolphinConfigId);
    }

    public Long findProjectCodeAtTarget(DolphinConfig targetConfig, Long dolphinConfigId) {
        return lookupProjectCodeReadOnlyAtTarget(targetConfig);
    }

    private Long lookupProjectCodeReadOnlyAtTarget(DolphinConfig targetConfig) {
        DolphinConfig activeConfig = targetConfig;
        String cacheKey = configCacheKey(activeConfig);
        Long cached = cachedProjectCodeByConfigId.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        try {
            DolphinProject project =
                    openApiClient.getProject(targetConfig, activeConfig.getProjectName());
            if (project == null || project.getCode() == null) {
                return null;
            }
            cachedProjectCodeByConfigId.put(cacheKey, project.getCode());
            return project.getCode();
        } catch (Exception e) {
            log.warn(
                    "Failed to resolve project code for {}: {}",
                    activeConfig.getProjectName(),
                    e.getMessage());
            return null;
        }
    }

    public boolean testConnection(Long dolphinConfigId) {
        return testConnectionAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId), dolphinConfigId);
    }

    public boolean testConnectionAtTarget(DolphinConfig targetConfig, Long dolphinConfigId) {
        DolphinConfig config = dolphinConfigService.getEnabledConfig(dolphinConfigId);
        return openApiClient.testConnection(config);
    }

    private String configCacheKey(DolphinConfig config) {
        if (config == null) {
            return "-1";
        }
        return String.join(
                "|",
                String.valueOf(config.getId()),
                trimTrailingSlash(config.getUrl()),
                String.valueOf(config.getProjectName()));
    }

    private String trimTrailingSlash(String url) {
        if (!StringUtils.hasText(url)) {
            return "";
        }
        String trimmed = url.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    /**
     * Synchronise tasks, relations and locations with DolphinScheduler via OpenAPI. Returns the
     * actual workflow code (may differ from input if workflowCode was 0).
     */
    public long syncWorkflow(
            long workflowCode,
            String workflowName,
            String workflowDescription,
            List<Map<String, Object>> tasks,
            List<TaskRelationPayload> relations,
            List<TaskLocationPayload> locations,
            String globalParams) {
        return syncWorkflowAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null),
                workflowCode,
                workflowName,
                workflowDescription,
                tasks,
                relations,
                locations,
                globalParams);
    }

    public long syncWorkflowAtTarget(
            DolphinConfig targetConfig,
            long workflowCode,
            String workflowName,
            String workflowDescription,
            List<Map<String, Object>> tasks,
            List<TaskRelationPayload> relations,
            List<TaskLocationPayload> locations,
            String globalParams) {
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            throw new IllegalStateException("Cannot sync workflow: Project not found");
        }

        try {
            String taskJson = objectMapper.writeValueAsString(tasks);
            String relationJson =
                    objectMapper.writeValueAsString(
                            relations != null ? relations : Collections.emptyList());
            String locationJson =
                    objectMapper.writeValueAsString(
                            locations != null ? locations : Collections.emptyList());

            log.info("Syncing workflow '{}' to project {}", workflowName, projectCode);

            DolphinConfig config = targetConfig;
            return openApiClient.createOrUpdateProcessDefinition(
                    targetConfig,
                    projectCode,
                    workflowName,
                    StringUtils.hasText(workflowDescription) ? workflowDescription.trim() : "",
                    config.getTenantCode(),
                    config.getExecutionType(),
                    relationJson,
                    taskJson,
                    locationJson,
                    globalParams,
                    workflowCode > 0 ? workflowCode : null);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize workflow data", e);
        }
    }

    public long syncWorkflow(
            Long dolphinConfigId,
            long workflowCode,
            String workflowName,
            String workflowDescription,
            List<Map<String, Object>> tasks,
            List<TaskRelationPayload> relations,
            List<TaskLocationPayload> locations,
            String globalParams) {
        return syncWorkflowAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode,
                workflowName,
                workflowDescription,
                tasks,
                relations,
                locations,
                globalParams);
    }

    public long syncWorkflowAtTarget(
            DolphinConfig targetConfig,
            Long dolphinConfigId,
            long workflowCode,
            String workflowName,
            String workflowDescription,
            List<Map<String, Object>> tasks,
            List<TaskRelationPayload> relations,
            List<TaskLocationPayload> locations,
            String globalParams) {
        return syncWorkflowAtTarget(
                targetConfig,
                workflowCode,
                workflowName,
                workflowDescription,
                tasks,
                relations,
                locations,
                globalParams);
    }

    /** Update workflow release state (ONLINE/OFFLINE). */
    public void setWorkflowReleaseState(long workflowCode, String releaseState) {
        setWorkflowReleaseStateAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), workflowCode, releaseState);
    }

    public void setWorkflowReleaseStateAtTarget(
            DolphinConfig targetConfig, long workflowCode, String releaseState) {
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) return;

        openApiClient.releaseProcessDefinition(
                targetConfig, projectCode, workflowCode, releaseState);
        log.info("Updated Dolphin workflow {} release state to {}", workflowCode, releaseState);
    }

    public void setWorkflowReleaseState(
            Long dolphinConfigId, long workflowCode, String releaseState) {
        setWorkflowReleaseStateAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode,
                releaseState);
    }

    public void setWorkflowReleaseStateAtTarget(
            DolphinConfig targetConfig,
            Long dolphinConfigId,
            long workflowCode,
            String releaseState) {
        setWorkflowReleaseStateAtTarget(targetConfig, workflowCode, releaseState);
    }

    /** Create a DolphinScheduler schedule for the given workflow definition. */
    public Long createWorkflowSchedule(
            long workflowCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        return createWorkflowScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null),
                workflowCode,
                scheduleJson,
                warningType,
                failureStrategy,
                warningGroupId,
                processInstancePriority,
                workerGroup,
                tenantCode,
                environmentCode);
    }

    public Long createWorkflowScheduleAtTarget(
            DolphinConfig targetConfig,
            long workflowCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            throw new IllegalStateException("Cannot create schedule: Project not found");
        }
        Long scheduleId =
                openApiClient.createSchedule(
                        targetConfig,
                        projectCode,
                        workflowCode,
                        scheduleJson,
                        warningType,
                        failureStrategy,
                        warningGroupId,
                        processInstancePriority,
                        workerGroup,
                        tenantCode,
                        environmentCode);
        log.info("Created Dolphin schedule for workflow {} -> {}", workflowCode, scheduleId);
        return scheduleId;
    }

    public Long createWorkflowSchedule(
            Long dolphinConfigId,
            long workflowCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        return createWorkflowScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode,
                scheduleJson,
                warningType,
                failureStrategy,
                warningGroupId,
                processInstancePriority,
                workerGroup,
                tenantCode,
                environmentCode);
    }

    public Long createWorkflowScheduleAtTarget(
            DolphinConfig targetConfig,
            Long dolphinConfigId,
            long workflowCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        return createWorkflowScheduleAtTarget(
                targetConfig,
                workflowCode,
                scheduleJson,
                warningType,
                failureStrategy,
                warningGroupId,
                processInstancePriority,
                workerGroup,
                tenantCode,
                environmentCode);
    }

    /** Update an existing DolphinScheduler schedule. */
    public void updateWorkflowSchedule(
            long scheduleId,
            long workflowCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        updateWorkflowScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null),
                scheduleId,
                workflowCode,
                scheduleJson,
                warningType,
                failureStrategy,
                warningGroupId,
                processInstancePriority,
                workerGroup,
                tenantCode,
                environmentCode);
    }

    public void updateWorkflowScheduleAtTarget(
            DolphinConfig targetConfig,
            long scheduleId,
            long workflowCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            throw new IllegalStateException("Cannot update schedule: Project not found");
        }
        openApiClient.updateSchedule(
                targetConfig,
                projectCode,
                scheduleId,
                workflowCode,
                scheduleJson,
                warningType,
                failureStrategy,
                warningGroupId,
                processInstancePriority,
                workerGroup,
                tenantCode,
                environmentCode);
        log.info("Updated Dolphin schedule {} for workflow {}", scheduleId, workflowCode);
    }

    public void updateWorkflowSchedule(
            Long dolphinConfigId,
            long scheduleId,
            long workflowCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        updateWorkflowScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                scheduleId,
                workflowCode,
                scheduleJson,
                warningType,
                failureStrategy,
                warningGroupId,
                processInstancePriority,
                workerGroup,
                tenantCode,
                environmentCode);
    }

    public void updateWorkflowScheduleAtTarget(
            DolphinConfig targetConfig,
            Long dolphinConfigId,
            long scheduleId,
            long workflowCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        updateWorkflowScheduleAtTarget(
                targetConfig,
                scheduleId,
                workflowCode,
                scheduleJson,
                warningType,
                failureStrategy,
                warningGroupId,
                processInstancePriority,
                workerGroup,
                tenantCode,
                environmentCode);
    }

    /** Online a DolphinScheduler schedule. */
    public void onlineWorkflowSchedule(long scheduleId) {
        onlineWorkflowScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), scheduleId);
    }

    public void onlineWorkflowScheduleAtTarget(DolphinConfig targetConfig, long scheduleId) {
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            throw new IllegalStateException("Cannot online schedule: Project not found");
        }
        openApiClient.onlineSchedule(targetConfig, projectCode, scheduleId);
        log.info("Onlined Dolphin schedule {}", scheduleId);
    }

    public void onlineWorkflowSchedule(Long dolphinConfigId, long scheduleId) {
        onlineWorkflowScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                scheduleId);
    }

    public void onlineWorkflowScheduleAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, long scheduleId) {
        onlineWorkflowScheduleAtTarget(targetConfig, scheduleId);
    }

    /** Offline a DolphinScheduler schedule. */
    public void offlineWorkflowSchedule(long scheduleId) {
        offlineWorkflowScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), scheduleId);
    }

    public void offlineWorkflowScheduleAtTarget(DolphinConfig targetConfig, long scheduleId) {
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            throw new IllegalStateException("Cannot offline schedule: Project not found");
        }
        openApiClient.offlineSchedule(targetConfig, projectCode, scheduleId);
        log.info("Offlined Dolphin schedule {}", scheduleId);
    }

    public void offlineWorkflowSchedule(Long dolphinConfigId, long scheduleId) {
        offlineWorkflowScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                scheduleId);
    }

    public void offlineWorkflowScheduleAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, long scheduleId) {
        offlineWorkflowScheduleAtTarget(targetConfig, scheduleId);
    }

    /**
     * Query workflow schedule (timing) from DolphinScheduler, if any.
     *
     * <p>Some DolphinScheduler versions only return schedule (timing) info in the
     * process-definition list API, so this method falls back to list paging if the detail API does
     * not include "schedule".
     */
    public DolphinSchedule getWorkflowSchedule(long workflowCode) {
        return getWorkflowScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), workflowCode);
    }

    public DolphinSchedule getWorkflowScheduleAtTarget(
            DolphinConfig targetConfig, long workflowCode) {
        if (workflowCode <= 0) {
            return null;
        }
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            return null;
        }
        try {
            JsonNode definition =
                    openApiClient.getProcessDefinition(targetConfig, projectCode, workflowCode);
            DolphinSchedule schedule = parseScheduleFromDefinitionNode(definition);
            if (schedule != null) {
                return schedule;
            }

            JsonNode definitionFromList =
                    findProcessDefinitionFromListAtTarget(targetConfig, projectCode, workflowCode);
            schedule = parseScheduleFromDefinitionNode(definitionFromList);
            if (schedule != null) {
                return schedule;
            }
            return findScheduleFromScheduleListAtTarget(targetConfig, projectCode, workflowCode);
        } catch (Exception e) {
            log.debug("Failed to query workflow schedule {}: {}", workflowCode, e.getMessage());
            return null;
        }
    }

    public DolphinSchedule getWorkflowSchedule(Long dolphinConfigId, long workflowCode) {
        return getWorkflowScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode);
    }

    public DolphinSchedule getWorkflowScheduleAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, long workflowCode) {
        return getWorkflowScheduleAtTarget(targetConfig, workflowCode);
    }

    private DolphinSchedule parseScheduleFromDefinitionNode(JsonNode definition) {
        if (definition == null) {
            return null;
        }
        JsonNode scheduleNode = definition.path("schedule");
        if (scheduleNode.isMissingNode() || scheduleNode.isNull()) {
            return null;
        }
        try {
            DolphinSchedule schedule =
                    objectMapper.treeToValue(scheduleNode, DolphinSchedule.class);
            if (schedule == null || schedule.getId() == null || schedule.getId() <= 0) {
                return null;
            }
            if (!StringUtils.hasText(schedule.getReleaseState())) {
                String releaseState = definition.path("scheduleReleaseState").asText(null);
                if (StringUtils.hasText(releaseState)) {
                    schedule.setReleaseState(releaseState);
                }
            }
            return schedule;
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode findProcessDefinitionFromListAtTarget(
            DolphinConfig targetConfig, long projectCode, long workflowCode) {
        int pageNo = 1;
        int pageSize = 100;
        int maxPages = 20;

        while (pageNo <= maxPages) {
            JsonNode page =
                    openApiClient.listProcessDefinitions(
                            targetConfig, projectCode, pageNo, pageSize);
            if (page == null) {
                return null;
            }
            JsonNode list = page.path("totalList");
            if (list.isArray()) {
                for (JsonNode node : list) {
                    if (node != null && node.path("code").asLong(-1) == workflowCode) {
                        return node;
                    }
                }
            }

            int totalPage = page.path("totalPage").asInt(0);
            if (totalPage > 0 && pageNo >= totalPage) {
                return null;
            }
            if (!list.isArray() || list.size() < pageSize) {
                return null;
            }
            pageNo++;
        }
        return null;
    }

    private DolphinSchedule findScheduleFromScheduleListAtTarget(
            DolphinConfig targetConfig, long projectCode, long workflowCode) {
        int pageNo = 1;
        int pageSize = 100;
        int maxPages = 10;

        while (pageNo <= maxPages) {
            DolphinPageData<DolphinSchedule> page =
                    openApiClient.listSchedules(
                            targetConfig, projectCode, pageNo, pageSize, workflowCode);
            if (page == null || page.getTotalList() == null || page.getTotalList().isEmpty()) {
                return null;
            }

            DolphinSchedule unspecifiedCandidate = null;
            for (DolphinSchedule schedule : page.getTotalList()) {
                if (schedule == null || schedule.getId() == null || schedule.getId() <= 0) {
                    continue;
                }
                if (Objects.equals(schedule.getProcessDefinitionCode(), workflowCode)) {
                    return schedule;
                }
                if (schedule.getProcessDefinitionCode() == null && unspecifiedCandidate == null) {
                    unspecifiedCandidate = schedule;
                }
            }
            if (unspecifiedCandidate != null && page.getTotalList().size() == 1) {
                return unspecifiedCandidate;
            }

            if (page.getTotalPage() > 0 && pageNo >= page.getTotalPage()) {
                return null;
            }
            if (page.getTotalList().size() < pageSize) {
                return null;
            }
            pageNo++;
        }
        return null;
    }

    /** Check if workflow definition exists in DolphinScheduler. */
    public boolean checkWorkflowExists(long workflowCode) {
        return checkWorkflowExistsAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), workflowCode);
    }

    public boolean checkWorkflowExistsAtTarget(DolphinConfig targetConfig, long workflowCode) {
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            return false;
        }

        try {
            // Try to query the workflow definition
            // If it doesn't exist, the API will throw an exception
            openApiClient.getProcessDefinition(targetConfig, projectCode, workflowCode);
            return true;
        } catch (Exception e) {
            log.debug("Workflow {} does not exist: {}", workflowCode, e.getMessage());
            return false;
        }
    }

    public boolean checkWorkflowExists(Long dolphinConfigId, long workflowCode) {
        return checkWorkflowExistsAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode);
    }

    public boolean checkWorkflowExistsAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, long workflowCode) {
        return checkWorkflowExistsAtTarget(targetConfig, workflowCode);
    }

    /** Start workflow instance via DolphinScheduler OpenAPI. */
    public String startProcessInstance(Long workflowCode, String projectName, String workflowName) {
        return startProcessInstanceAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null),
                workflowCode,
                projectName,
                workflowName);
    }

    public String startProcessInstanceAtTarget(
            DolphinConfig targetConfig,
            Long workflowCode,
            String projectName,
            String workflowName) {
        if (workflowCode == null) {
            throw new IllegalArgumentException("workflowCode must not be null");
        }
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            throw new IllegalStateException("Cannot start workflow: Project not found");
        }

        DolphinConfig config = targetConfig;
        Long instanceId =
                openApiClient.startProcessInstance(
                        targetConfig,
                        projectCode,
                        workflowCode,
                        "", // scheduleTime
                        "CONTINUE", // failureStrategy
                        "NONE", // warningType
                        null, // warningGroupId
                        "START_PROCESS",
                        config.getWorkerGroup(),
                        config.getTenantCode());

        String executionId =
                instanceId != null
                        ? String.valueOf(instanceId)
                        : "exec-" + System.currentTimeMillis();
        log.info("Started workflow instance for definition {} -> {}", workflowCode, executionId);
        return executionId;
    }

    public String startProcessInstance(
            Long dolphinConfigId, Long workflowCode, String projectName, String workflowName) {
        return startProcessInstanceAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode,
                projectName,
                workflowName);
    }

    public String startProcessInstanceAtTarget(
            DolphinConfig targetConfig,
            Long dolphinConfigId,
            Long workflowCode,
            String projectName,
            String workflowName) {
        return startProcessInstanceAtTarget(targetConfig, workflowCode, projectName, workflowName);
    }

    /**
     * 补数（COMPLEMENT_DATA）执行工作流。
     *
     * <p>DolphinScheduler 3.x 补数通过 executors/start-process-instance 接口实现，scheduleTime 参数传 JSON： 1)
     * 范围：{"complementStartDate":"yyyy-MM-dd HH:mm:ss","complementEndDate":"yyyy-MM-dd HH:mm:ss"} 2)
     * 列表：{"complementScheduleDateList":"...,..."}
     */
    public String backfillProcessInstance(Long workflowCode, WorkflowBackfillRequest request) {
        return backfillProcessInstanceAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), workflowCode, request);
    }

    public String backfillProcessInstanceAtTarget(
            DolphinConfig targetConfig, Long workflowCode, WorkflowBackfillRequest request) {
        if (workflowCode == null) {
            throw new IllegalArgumentException("workflowCode must not be null");
        }
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            throw new IllegalStateException("Cannot backfill workflow: Project not found");
        }

        String scheduleTime = buildComplementScheduleTime(request);
        String failureStrategy =
                StringUtils.hasText(request.getFailureStrategy())
                        ? request.getFailureStrategy()
                        : "CONTINUE";
        String runMode =
                StringUtils.hasText(request.getRunMode())
                        ? request.getRunMode()
                        : "RUN_MODE_SERIAL";
        String complementDependentMode =
                StringUtils.hasText(request.getComplementDependentMode())
                        ? request.getComplementDependentMode()
                        : "OFF_MODE";
        boolean allLevelDependent = Boolean.TRUE.equals(request.getAllLevelDependent());
        String executionOrder =
                StringUtils.hasText(request.getExecutionOrder())
                        ? request.getExecutionOrder()
                        : "DESC_ORDER";

        DolphinConfig config = targetConfig;
        JsonNode data =
                openApiClient.startProcessInstanceRaw(
                        targetConfig,
                        projectCode,
                        workflowCode,
                        scheduleTime,
                        failureStrategy,
                        "NONE",
                        null,
                        "COMPLEMENT_DATA",
                        config.getWorkerGroup(),
                        config.getTenantCode(),
                        runMode,
                        request.getExpectedParallelismNumber(),
                        complementDependentMode,
                        allLevelDependent,
                        executionOrder);

        String triggerId = extractWorkflowInstanceId(data);
        if (!StringUtils.hasText(triggerId)) {
            triggerId = "trigger-" + System.currentTimeMillis();
        }
        log.info("Backfill workflow definition {} -> {}", workflowCode, triggerId);
        return triggerId;
    }

    String extractWorkflowInstanceId(JsonNode data) {
        if (data == null || data.isNull() || data.isMissingNode()) {
            return null;
        }
        if (data.isValueNode()) {
            String value = data.asText();
            return StringUtils.hasText(value) ? value : null;
        }
        for (String field :
                new String[] {
                    "workflowInstanceId", "processInstanceId", "processInstanceCode", "id"
                }) {
            if (data.hasNonNull(field) && StringUtils.hasText(data.path(field).asText())) {
                return data.path(field).asText();
            }
        }
        return null;
    }

    public String backfillProcessInstance(
            Long dolphinConfigId, Long workflowCode, WorkflowBackfillRequest request) {
        return backfillProcessInstanceAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode,
                request);
    }

    public String backfillProcessInstanceAtTarget(
            DolphinConfig targetConfig,
            Long dolphinConfigId,
            Long workflowCode,
            WorkflowBackfillRequest request) {
        return backfillProcessInstanceAtTarget(targetConfig, workflowCode, request);
    }

    /**
     * Build the {@code scheduleTime} payload for a complement run. Presence, format and ordering
     * are already enforced by {@link WorkflowBackfillValidator} before the request reaches this
     * point.
     */
    private String buildComplementScheduleTime(WorkflowBackfillRequest request) {
        Map<String, String> payload = new HashMap<>();

        if (WorkflowBackfillValidator.isListMode(request)) {
            payload.put("complementScheduleDateList", request.getScheduleDateList());
        } else {
            payload.put("complementStartDate", request.getStartTime());
            payload.put("complementEndDate", request.getEndTime());
        }

        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize complement scheduleTime", e);
        }
    }

    /** Delete workflow definition via DolphinScheduler OpenAPI. */
    public void deleteWorkflow(Long workflowCode) {
        deleteWorkflowAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), workflowCode);
    }

    public void deleteWorkflowAtTarget(DolphinConfig targetConfig, Long workflowCode) {
        if (workflowCode == null) return;

        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) return;

        // Ensure offline before delete
        try {
            setWorkflowReleaseStateAtTarget(targetConfig, workflowCode, "OFFLINE");
        } catch (Exception e) {
            // 删除前的下线操作失败不阻断删除（可能本就处于下线态）
            log.trace("删除前下线工作流 {} 失败，继续删除", workflowCode, e);
        }

        openApiClient.deleteProcessDefinition(targetConfig, projectCode, workflowCode);
        log.info("Deleted DolphinScheduler workflow {}", workflowCode);
    }

    public void deleteWorkflow(Long dolphinConfigId, Long workflowCode) {
        deleteWorkflowAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode);
    }

    public void deleteWorkflowAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, Long workflowCode) {
        deleteWorkflowAtTarget(targetConfig, workflowCode);
    }

    /** Get workflow instance status via DolphinScheduler OpenAPI. */
    public JsonNode getWorkflowInstanceStatus(Long workflowCode, String instanceId) {
        return getWorkflowInstanceStatusAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), workflowCode, instanceId);
    }

    public JsonNode getWorkflowInstanceStatusAtTarget(
            DolphinConfig targetConfig, Long workflowCode, String instanceId) {
        // Implementation note: converting DTO back to JsonNode to maintain
        // compatibility
        // with existing frontend/controller logic which expects raw JsonNode
        if (workflowCode == null || instanceId == null) return null;

        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) return null;

        try {
            // Note: instanceId in argument is string, but DS uses long ID.
            // If instanceId comes from our startProcessInstance mock return, we can't query
            // it.
            // Assuming instanceId is a valid numeric string here.
            long id = Long.parseLong(instanceId);
            DolphinProcessInstance instance =
                    openApiClient.getProcessInstance(targetConfig, projectCode, id);
            return objectMapper.valueToTree(instance);
        } catch (NumberFormatException e) {
            log.warn("Invalid instance ID format: {}", instanceId);
            return null;
        }
    }

    public JsonNode getWorkflowInstanceStatus(
            Long dolphinConfigId, Long workflowCode, String instanceId) {
        return getWorkflowInstanceStatusAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode,
                instanceId);
    }

    public JsonNode getWorkflowInstanceStatusAtTarget(
            DolphinConfig targetConfig,
            Long dolphinConfigId,
            Long workflowCode,
            String instanceId) {
        return getWorkflowInstanceStatusAtTarget(targetConfig, workflowCode, instanceId);
    }

    /** List workflow instances via DolphinScheduler OpenAPI. */
    public List<WorkflowInstanceSummary> listWorkflowInstances(Long workflowCode, int limit) {
        return listWorkflowInstancesAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), workflowCode, limit);
    }

    public List<WorkflowInstanceSummary> listWorkflowInstancesAtTarget(
            DolphinConfig targetConfig, Long workflowCode, int limit) {
        if (workflowCode == null || workflowCode <= 0) {
            return Collections.emptyList();
        }
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            throw new IllegalStateException("Cannot list workflow instances: Project not found");
        }

        int targetLimit = Math.min(Math.max(limit, 1), 100);
        int pageSize = Math.min(Math.max(targetLimit * 3, 20), 100);

        List<DolphinProcessInstance> filtered =
                collectWorkflowInstancesAtTarget(
                        targetConfig, projectCode, workflowCode, targetLimit, pageSize, true);

        // Fallback: if DS ignores server-side filter, scan global pages and filter locally.
        if (filtered.isEmpty()) {
            filtered =
                    collectWorkflowInstancesAtTarget(
                            targetConfig, projectCode, workflowCode, targetLimit, pageSize, false);
        }

        if (filtered.isEmpty()) {
            return Collections.emptyList();
        }

        List<WorkflowInstanceSummary> result = new ArrayList<>();
        for (DolphinProcessInstance instance : filtered) {
            if (result.size() >= targetLimit) {
                break;
            }
            result.add(toSummary(instance));
        }
        return result;
    }

    private WorkflowInstanceSummary toSummary(DolphinProcessInstance instance) {
        return WorkflowInstanceSummary.builder()
                .instanceId(instance.getId())
                .workflowCode(instance.getProcessDefinitionCode())
                .state(instance.getState())
                .commandType(instance.getCommandType())
                .startTime(instance.getStartTime())
                .endTime(instance.getEndTime())
                .scheduleTime(instance.getScheduleTime())
                .durationMs(parseDuration(instance.getDuration()))
                .build();
    }

    public List<WorkflowInstanceSummary> listWorkflowInstances(
            Long dolphinConfigId, Long workflowCode, int limit) {
        return listWorkflowInstancesAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode,
                limit);
    }

    public List<WorkflowInstanceSummary> listWorkflowInstancesAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, Long workflowCode, int limit) {
        return listWorkflowInstancesAtTarget(targetConfig, workflowCode, limit);
    }

    /**
     * List the most recent workflow instances of the whole project, without filtering by workflow
     * definition.
     *
     * <p>Used by the execution monitor, which shows「最近执行」across every workflow: one project-scoped
     * query replaces one query per workflow, so the call count follows the number of
     * DolphinScheduler configs rather than the number of workflows. Callers map instances back to
     * their own workflows via {@link WorkflowInstanceSummary#getWorkflowCode()} and drop the ones
     * they do not manage.
     */
    public List<WorkflowInstanceSummary> listRecentProjectInstances(int limit) {
        return listRecentProjectInstancesAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), limit);
    }

    public List<WorkflowInstanceSummary> listRecentProjectInstancesAtTarget(
            DolphinConfig targetConfig, int limit) {
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            throw new IllegalStateException("Cannot list workflow instances: Project not found");
        }

        int targetLimit = Math.min(Math.max(limit, 1), 100);
        int pageSize = Math.min(Math.max(targetLimit, 20), 100);
        int maxPages = 3;

        List<WorkflowInstanceSummary> result = new ArrayList<>();
        int pageNo = 1;
        while (pageNo <= maxPages && result.size() < targetLimit) {
            DolphinPageData<DolphinProcessInstance> page =
                    openApiClient.listProcessInstances(
                            targetConfig, projectCode, pageNo, pageSize, null);
            if (page == null || page.getTotalList() == null || page.getTotalList().isEmpty()) {
                break;
            }
            for (DolphinProcessInstance instance : page.getTotalList()) {
                if (instance == null || instance.getId() == null) {
                    continue;
                }
                result.add(toSummary(instance));
                if (result.size() >= targetLimit) {
                    break;
                }
            }
            if (shouldStopPaging(page, pageNo, pageSize)) {
                break;
            }
            pageNo++;
        }
        return result;
    }

    public List<WorkflowInstanceSummary> listRecentProjectInstances(
            Long dolphinConfigId, int limit) {
        return listRecentProjectInstancesAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                limit);
    }

    public List<WorkflowInstanceSummary> listRecentProjectInstancesAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, int limit) {
        return listRecentProjectInstancesAtTarget(targetConfig, limit);
    }

    /** List all task instances belonging to one workflow/process instance. */
    public List<DolphinTaskInstance> listTaskInstances(Long workflowInstanceId) {
        return listTaskInstancesAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), workflowInstanceId);
    }

    public List<DolphinTaskInstance> listTaskInstancesAtTarget(
            DolphinConfig targetConfig, Long workflowInstanceId) {
        if (workflowInstanceId == null || workflowInstanceId <= 0) {
            return Collections.emptyList();
        }
        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) {
            throw new IllegalStateException("Cannot list task instances: Project not found");
        }

        List<DolphinTaskInstance> result = new ArrayList<>();
        int pageNo = 1;
        int pageSize = 100;
        int maxPages = 100;
        while (pageNo <= maxPages) {
            DolphinPageData<DolphinTaskInstance> page =
                    openApiClient.listTaskInstances(
                            targetConfig, projectCode, pageNo, pageSize, workflowInstanceId, null);
            if (page == null || page.getTotalList() == null || page.getTotalList().isEmpty()) {
                break;
            }
            result.addAll(page.getTotalList());
            if ((page.getTotalPage() > 0 && pageNo >= page.getTotalPage())
                    || page.getTotalList().size() < pageSize) {
                break;
            }
            pageNo++;
        }
        return result;
    }

    public List<DolphinTaskInstance> listTaskInstances(
            Long dolphinConfigId, Long workflowInstanceId) {
        return listTaskInstancesAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowInstanceId);
    }

    public List<DolphinTaskInstance> listTaskInstancesAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, Long workflowInstanceId) {
        return listTaskInstancesAtTarget(targetConfig, workflowInstanceId);
    }

    private List<DolphinProcessInstance> collectWorkflowInstancesAtTarget(
            DolphinConfig targetConfig,
            Long projectCode,
            Long workflowCode,
            int limit,
            int pageSize,
            boolean useServerFilter) {
        List<DolphinProcessInstance> matched = new ArrayList<>();
        int pageNo = 1;
        int maxPages = 5;

        while (pageNo <= maxPages && matched.size() < limit) {
            DolphinPageData<DolphinProcessInstance> page =
                    openApiClient.listProcessInstances(
                            targetConfig,
                            projectCode,
                            pageNo,
                            pageSize,
                            useServerFilter ? workflowCode : null);

            if (page == null || page.getTotalList() == null || page.getTotalList().isEmpty()) {
                break;
            }

            for (DolphinProcessInstance instance : page.getTotalList()) {
                if (instance == null || instance.getId() == null) {
                    continue;
                }
                if (isWorkflowInstanceAtTarget(targetConfig, projectCode, workflowCode, instance)) {
                    matched.add(instance);
                    if (matched.size() >= limit) {
                        break;
                    }
                }
            }

            if (shouldStopPaging(page, pageNo, pageSize)) {
                break;
            }
            pageNo++;
        }

        return matched;
    }

    private boolean shouldStopPaging(
            DolphinPageData<DolphinProcessInstance> page, int currentPage, int pageSize) {
        if (page.getTotalPage() > 0 && currentPage >= page.getTotalPage()) {
            return true;
        }
        return page.getTotalList() == null || page.getTotalList().size() < pageSize;
    }

    private boolean isWorkflowInstanceAtTarget(
            DolphinConfig targetConfig,
            Long projectCode,
            Long workflowCode,
            DolphinProcessInstance instance) {
        if (Objects.equals(instance.getProcessDefinitionCode(), workflowCode)) {
            return true;
        }
        if (instance.getProcessDefinitionCode() != null) {
            return false;
        }
        try {
            DolphinProcessInstance detail =
                    openApiClient.getProcessInstance(targetConfig, projectCode, instance.getId());
            return detail != null
                    && Objects.equals(detail.getProcessDefinitionCode(), workflowCode);
        } catch (Exception ex) {
            return false;
        }
    }

    /** Generate DolphinScheduler Web UI URL for workflow definition. */
    public String getWorkflowDefinitionUrl(Long workflowCode) {
        return getWorkflowDefinitionUrlAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), workflowCode);
    }

    public String getWorkflowDefinitionUrlAtTarget(DolphinConfig targetConfig, Long workflowCode) {
        if (workflowCode == null) return null;

        String baseUrl = getWebuiBaseUrlAtTarget(targetConfig);
        if (baseUrl == null) return null;

        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) return null;

        return String.format(
                "%s/ui/projects/%d/workflow/definitions/%d", baseUrl, projectCode, workflowCode);
    }

    public String getWorkflowDefinitionUrl(Long dolphinConfigId, Long workflowCode) {
        return getWorkflowDefinitionUrlAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                workflowCode);
    }

    public String getWorkflowDefinitionUrlAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, Long workflowCode) {
        return getWorkflowDefinitionUrlAtTarget(targetConfig, workflowCode);
    }

    /** Generate DolphinScheduler Web UI URL for task instances. */
    public String getTaskDefinitionUrl(Long taskCode) {
        return getTaskDefinitionUrlAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), taskCode);
    }

    public String getTaskDefinitionUrlAtTarget(DolphinConfig targetConfig, Long taskCode) {
        if (taskCode == null) return null;

        String baseUrl = getWebuiBaseUrlAtTarget(targetConfig);
        if (baseUrl == null) return null;

        Long projectCode = getProjectCodeAtTarget(targetConfig);
        if (projectCode == null) return null;

        UriComponentsBuilder builder =
                UriComponentsBuilder.fromHttpUrl(
                                String.format(
                                        "%s/ui/projects/%d/task/instances", baseUrl, projectCode))
                        .queryParam("taskCode", taskCode);
        String projectName = targetConfig.getProjectName();
        if (StringUtils.hasText(projectName)) {
            builder.queryParam("projectName", projectName);
        }
        return builder.build(true).toUriString();
    }

    public String getTaskDefinitionUrl(Long dolphinConfigId, Long taskCode) {
        return getTaskDefinitionUrlAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                dolphinConfigId,
                taskCode);
    }

    public String getTaskDefinitionUrlAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId, Long taskCode) {
        return getTaskDefinitionUrlAtTarget(targetConfig, taskCode);
    }

    /** Return the configured DolphinScheduler Web UI base URL without trailing slashes. */
    public String getWebuiBaseUrl() {
        return getWebuiBaseUrlAtTarget(dolphinConfigService.resolveActiveOrTargetConfig(null));
    }

    public String getWebuiBaseUrlAtTarget(DolphinConfig targetConfig) {
        String url = targetConfig.getUrl();
        if (!StringUtils.hasText(url)) {
            return null;
        }
        return url.replaceAll("/+$", "");
    }

    public String getWebuiBaseUrl(Long dolphinConfigId) {
        return getWebuiBaseUrlAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId), dolphinConfigId);
    }

    public String getWebuiBaseUrlAtTarget(DolphinConfig targetConfig, Long dolphinConfigId) {
        return getWebuiBaseUrlAtTarget(targetConfig);
    }

    public String getDefaultTenantCode() {
        return getDefaultTenantCodeAtTarget(dolphinConfigService.resolveActiveOrTargetConfig(null));
    }

    public String getDefaultTenantCodeAtTarget(DolphinConfig targetConfig) {
        DolphinConfig config = targetConfig;
        return StringUtils.hasText(config.getTenantCode()) ? config.getTenantCode() : "default";
    }

    public String getDefaultWorkerGroup() {
        return getDefaultWorkerGroupAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null));
    }

    public String getDefaultWorkerGroupAtTarget(DolphinConfig targetConfig) {
        DolphinConfig config = targetConfig;
        return StringUtils.hasText(config.getWorkerGroup()) ? config.getWorkerGroup() : "default";
    }

    /** Generate the next DolphinScheduler task code locally. */
    public long nextTaskCode() {
        return taskCodeSequence.incrementAndGet();
    }

    /** Initialise internal sequence to avoid collisions with pre-existing codes. */
    public void initialiseSequence(long candidate) {
        taskCodeSequence.updateAndGet(current -> Math.max(current, candidate));
    }

    public void alignSequenceWithExistingTasks(List<Long> existingCodes) {
        if (existingCodes == null || existingCodes.isEmpty()) {
            return;
        }
        existingCodes.stream()
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .ifPresent(this::initialiseSequence);
    }

    /** Build task definition payload for DolphinScheduler SHELL task. */
    public Map<String, Object> buildTaskDefinition(
            long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds) {
        return buildTaskDefinitionAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null),
                taskCode,
                taskVersion,
                taskName,
                description,
                rawScript,
                taskPriority,
                retryTimes,
                retryInterval,
                timeoutSeconds);
    }

    public Map<String, Object> buildTaskDefinitionAtTarget(
            DolphinConfig targetConfig,
            long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds) {
        return buildTaskDefinitionAtTarget(
                targetConfig,
                taskCode,
                taskVersion,
                taskName,
                description,
                rawScript,
                taskPriority,
                retryTimes,
                retryInterval,
                timeoutSeconds,
                "SHELL",
                null,
                null,
                null,
                null);
    }

    /** Build task definition payload for DolphinScheduler with flexible task type. */
    public Map<String, Object> buildTaskDefinition(
            long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds,
            String nodeType,
            Long datasourceId,
            String datasourceType,
            Integer taskGroupId,
            Integer taskGroupPriority) {
        return buildTaskDefinitionAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null),
                taskCode,
                taskVersion,
                taskName,
                description,
                rawScript,
                taskPriority,
                retryTimes,
                retryInterval,
                timeoutSeconds,
                nodeType,
                datasourceId,
                datasourceType,
                taskGroupId,
                taskGroupPriority);
    }

    public Map<String, Object> buildTaskDefinitionAtTarget(
            DolphinConfig targetConfig,
            long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds,
            String nodeType,
            Long datasourceId,
            String datasourceType,
            Integer taskGroupId,
            Integer taskGroupPriority) {
        return buildTaskDefinitionAtTarget(
                targetConfig,
                taskCode,
                taskVersion,
                taskName,
                description,
                rawScript,
                taskPriority,
                retryTimes,
                retryInterval,
                timeoutSeconds,
                nodeType,
                datasourceId,
                datasourceType,
                null,
                null,
                null,
                null,
                null,
                null,
                taskGroupId,
                taskGroupPriority);
    }

    public Map<String, Object> buildTaskDefinition(
            long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds,
            String nodeType,
            Long datasourceId,
            String datasourceType,
            String dolphinFlag,
            Integer taskGroupId,
            Integer taskGroupPriority) {
        return buildTaskDefinitionAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null),
                taskCode,
                taskVersion,
                taskName,
                description,
                rawScript,
                taskPriority,
                retryTimes,
                retryInterval,
                timeoutSeconds,
                nodeType,
                datasourceId,
                datasourceType,
                dolphinFlag,
                taskGroupId,
                taskGroupPriority);
    }

    public Map<String, Object> buildTaskDefinitionAtTarget(
            DolphinConfig targetConfig,
            long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds,
            String nodeType,
            Long datasourceId,
            String datasourceType,
            String dolphinFlag,
            Integer taskGroupId,
            Integer taskGroupPriority) {
        return buildTaskDefinitionAtTarget(
                targetConfig,
                taskCode,
                taskVersion,
                taskName,
                description,
                rawScript,
                taskPriority,
                retryTimes,
                retryInterval,
                timeoutSeconds,
                nodeType,
                datasourceId,
                datasourceType,
                null,
                null,
                null,
                null,
                null,
                dolphinFlag,
                taskGroupId,
                taskGroupPriority);
    }

    /** Build task definition payload for DolphinScheduler with DataX support. */
    public Map<String, Object> buildTaskDefinition(
            long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds,
            String nodeType,
            Long datasourceId,
            String datasourceType,
            Long targetDatasourceId,
            String sourceTable,
            String targetTable,
            String customJson,
            Integer taskGroupId,
            Integer taskGroupPriority) {
        return buildTaskDefinitionAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null),
                taskCode,
                taskVersion,
                taskName,
                description,
                rawScript,
                taskPriority,
                retryTimes,
                retryInterval,
                timeoutSeconds,
                nodeType,
                datasourceId,
                datasourceType,
                targetDatasourceId,
                sourceTable,
                targetTable,
                customJson,
                taskGroupId,
                taskGroupPriority);
    }

    public Map<String, Object> buildTaskDefinitionAtTarget(
            DolphinConfig targetConfig,
            long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds,
            String nodeType,
            Long datasourceId,
            String datasourceType,
            Long targetDatasourceId,
            String sourceTable,
            String targetTable,
            String customJson,
            Integer taskGroupId,
            Integer taskGroupPriority) {
        return buildTaskDefinitionAtTarget(
                targetConfig,
                taskCode,
                taskVersion,
                taskName,
                description,
                rawScript,
                taskPriority,
                retryTimes,
                retryInterval,
                timeoutSeconds,
                nodeType,
                datasourceId,
                datasourceType,
                targetDatasourceId,
                null,
                sourceTable,
                targetTable,
                customJson,
                null,
                taskGroupId,
                taskGroupPriority);
    }

    /** Build task definition payload for DolphinScheduler with DataX support. */
    public Map<String, Object> buildTaskDefinition(
            long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds,
            String nodeType,
            Long datasourceId,
            String datasourceType,
            Long targetDatasourceId,
            String targetDatasourceType,
            String sourceTable,
            String targetTable,
            String customJson,
            String dolphinFlag,
            Integer taskGroupId,
            Integer taskGroupPriority) {
        return buildTaskDefinitionAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null),
                taskCode,
                taskVersion,
                taskName,
                description,
                rawScript,
                taskPriority,
                retryTimes,
                retryInterval,
                timeoutSeconds,
                nodeType,
                datasourceId,
                datasourceType,
                targetDatasourceId,
                targetDatasourceType,
                sourceTable,
                targetTable,
                customJson,
                dolphinFlag,
                taskGroupId,
                taskGroupPriority);
    }

    public Map<String, Object> buildTaskDefinitionAtTarget(
            DolphinConfig targetConfig,
            long taskCode,
            int taskVersion,
            String taskName,
            String description,
            String rawScript,
            String taskPriority,
            int retryTimes,
            int retryInterval,
            int timeoutSeconds,
            String nodeType,
            Long datasourceId,
            String datasourceType,
            Long targetDatasourceId,
            String targetDatasourceType,
            String sourceTable,
            String targetTable,
            String customJson,
            String dolphinFlag,
            Integer taskGroupId,
            Integer taskGroupPriority) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("code", taskCode);
        payload.put("name", taskName);
        payload.put("version", taskVersion);
        payload.put("description", description == null ? "" : description);
        payload.put("delayTime", "0");
        payload.put("failRetryInterval", String.valueOf(Math.max(retryInterval, 1)));
        payload.put("failRetryTimes", String.valueOf(Math.max(retryTimes, 0)));
        payload.put("flag", normalizeDolphinFlag(dolphinFlag));
        payload.put("taskPriority", taskPriority);
        DolphinConfig config = targetConfig;
        payload.put("workerGroup", config.getWorkerGroup());
        payload.put("environmentCode", -1);
        payload.put("taskType", nodeType == null ? "SHELL" : nodeType);
        payload.put("timeout", timeoutSeconds);
        payload.put("timeoutFlag", timeoutSeconds > 0 ? "OPEN" : "CLOSE");
        payload.put("timeoutNotifyStrategy", "FAILED");

        // Added missing fields based on user payload
        payload.put("cpuQuota", -1);
        payload.put("memoryMax", -1);
        payload.put("taskExecuteType", "BATCH");
        payload.put("isCache", "NO");
        payload.put("taskGroupId", taskGroupId == null ? 0 : taskGroupId);
        payload.put("taskGroupPriority", taskGroupPriority == null ? 0 : taskGroupPriority);

        try {
            if ("SQL".equalsIgnoreCase(nodeType)) {
                payload.put("taskParams", TaskParams.sql(rawScript, datasourceId, datasourceType));
            } else if ("DATAX".equalsIgnoreCase(nodeType)) {
                payload.put(
                        "taskParams",
                        buildDataxParams(
                                datasourceId,
                                datasourceType,
                                targetDatasourceId,
                                targetDatasourceType,
                                sourceTable,
                                targetTable,
                                customJson));
            } else {
                payload.put("taskParams", TaskParams.shell(rawScript));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Unable to construct task parameters", e);
        }
        return payload;
    }

    /**
     * Build DolphinScheduler DataX task params.
     *
     * <p>{@code columnMapping} 语义：
     *
     * <ul>
     *   <li>空 -> 向导模式，{@code SELECT * FROM sourceTable} 全列同步
     *   <li>完整 DataX 作业 JSON（含 {@code job} 键）-> 自定义模式（{@code customConfig=1}，原样放入 {@code json}）
     *   <li>列清单（JSON 数组 / JSON 对象映射 / 逗号分隔）-> 向导模式，生成 {@code SELECT <cols> FROM sourceTable}
     * </ul>
     */
    Map<String, Object> buildDataxParams(
            Long sourceDatasourceId,
            String sourceDatasourceType,
            Long targetDatasourceId,
            String targetDatasourceType,
            String sourceTable,
            String targetTable,
            String columnMapping) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("localParams", new ArrayList<>());
        params.put("resourceList", new ArrayList<>());

        String mapping = columnMapping == null ? "" : columnMapping.trim();
        // JSON-shaped mapping must be well-formed; reject loudly instead of silently degrading.
        JsonNode mappingNode = parseMappingJsonOrThrow(mapping);
        boolean customJson =
                mappingNode != null && mappingNode.isObject() && mappingNode.has("job");
        if (customJson) {
            params.put("customConfig", 1);
            params.put("json", columnMapping);
        } else {
            params.put("customConfig", 0);
            params.put("dsType", normalizeDataxDatasourceType(sourceDatasourceType));
            params.put("dataSource", sourceDatasourceId);
            params.put("dtType", normalizeDataxDatasourceType(targetDatasourceType));
            params.put("dataTarget", targetDatasourceId);
            params.put("sql", buildDataxExtractSql(sourceTable, mapping, mappingNode));
            params.put("targetTable", targetTable);
            params.put("preStatements", new ArrayList<>());
            params.put("postStatements", new ArrayList<>());
        }
        params.put("jobSpeedByte", 0);
        params.put("jobSpeedRecord", 1000);
        params.put("xms", 1);
        params.put("xmx", 1);
        return params;
    }

    /**
     * Parse a JSON-shaped column mapping. Returns {@code null} when the mapping is blank or a plain
     * comma-separated column list. Throws when it looks like JSON ({@code {}/[]}) but is malformed.
     */
    private JsonNode parseMappingJsonOrThrow(String mapping) {
        if (!StringUtils.hasText(mapping)
                || (!mapping.startsWith("{") && !mapping.startsWith("["))) {
            return null;
        }
        try {
            return objectMapper.readTree(mapping);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("DataX 列映射不是合法 JSON: " + e.getOriginalMessage(), e);
        }
    }

    private String buildDataxExtractSql(String sourceTable, String mapping, JsonNode mappingNode) {
        String columns = "*";
        if (StringUtils.hasText(mapping)) {
            List<String> cols = parseColumnList(mapping, mappingNode);
            if (!cols.isEmpty()) {
                columns = String.join(", ", cols);
            }
        }
        return "SELECT " + columns + " FROM " + sourceTable;
    }

    private List<String> parseColumnList(String mapping, JsonNode mappingNode) {
        List<String> columns = new ArrayList<>();
        if (mappingNode != null && mappingNode.isArray()) {
            for (JsonNode item : mappingNode) {
                if (item.isTextual()) {
                    addColumn(columns, item.asText());
                } else if (item.isObject()) {
                    String src =
                            firstNonEmpty(
                                    item.path("source").asText(""), item.path("from").asText(""));
                    String tgt =
                            firstNonEmpty(
                                    item.path("target").asText(""), item.path("to").asText(""));
                    if (StringUtils.hasText(src) && StringUtils.hasText(tgt) && !src.equals(tgt)) {
                        addColumn(columns, src + " AS " + tgt);
                    } else {
                        addColumn(columns, firstNonEmpty(src, tgt));
                    }
                }
            }
            return columns;
        }
        if (mappingNode != null && mappingNode.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = mappingNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String src = entry.getKey();
                String tgt = entry.getValue().asText("");
                if (StringUtils.hasText(tgt) && !tgt.equals(src)) {
                    addColumn(columns, src + " AS " + tgt);
                } else {
                    addColumn(columns, src);
                }
            }
            return columns;
        }
        for (String part : mapping.split(",")) {
            addColumn(columns, part);
        }
        return columns;
    }

    private void addColumn(List<String> columns, String col) {
        if (col != null && StringUtils.hasText(col.trim())) {
            columns.add(col.trim());
        }
    }

    private String firstNonEmpty(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private String normalizeDataxDatasourceType(String type) {
        return StringUtils.hasText(type) ? type.trim().toUpperCase(Locale.ROOT) : "MYSQL";
    }

    private String normalizeDolphinFlag(String dolphinFlag) {
        if (!StringUtils.hasText(dolphinFlag)) {
            return "YES";
        }
        String normalized = dolphinFlag.trim().toUpperCase(Locale.ROOT);
        return "NO".equals(normalized) ? "NO" : "YES";
    }

    public TaskRelationPayload buildRelation(
            long upstreamCode, int upstreamVersion, long downstreamCode, int downstreamVersion) {
        TaskRelationPayload relation = new TaskRelationPayload();
        relation.setName("");
        relation.setPreTaskCode(upstreamCode);
        relation.setPreTaskVersion(upstreamVersion);
        relation.setPostTaskCode(downstreamCode);
        relation.setPostTaskVersion(downstreamVersion);
        relation.setConditionType("NONE");
        relation.setConditionParams("{}");
        return relation;
    }

    public TaskLocationPayload buildLocation(long taskCode, int index, int lane) {
        TaskLocationPayload location = new TaskLocationPayload();
        location.setTaskCode(taskCode);
        location.setX(220 + index * 280);
        location.setY(140 + lane * 140);
        return location;
    }

    public String buildShellScript(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return "#!/bin/bash\n\necho \"No SQL provided\"";
        }
        String sanitised = sql.replace("\r\n", "\n");
        return "#!/bin/bash\nset -euo pipefail\ncat <<'SQL'\n" + sanitised + "\nSQL\n";
    }

    /** Retrieve datasource options from DolphinScheduler OpenAPI. */
    public List<DolphinDatasourceOption> listDatasources(String type, String keyword) {
        return listDatasourcesAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), type, keyword);
    }

    public List<DolphinDatasourceOption> listDatasourcesAtTarget(
            DolphinConfig targetConfig, String type, String keyword) {
        try {
            List<DolphinDatasource> rawList = openApiClient.listDatasources(targetConfig, 1, 100);

            List<DolphinDatasourceOption> result = new ArrayList<>();
            for (DolphinDatasource ds : rawList) {
                // Filter logic
                if (StringUtils.hasText(type) && !type.equalsIgnoreCase(ds.getType())) {
                    continue;
                }
                if (StringUtils.hasText(keyword) && !ds.getName().contains(keyword)) {
                    continue;
                }

                DolphinDatasourceOption option = new DolphinDatasourceOption();
                option.setId(ds.getId());
                option.setName(ds.getName());
                option.setType(ds.getType());
                option.setDbName(ds.getDatabase());
                option.setDescription(ds.getNote());
                result.add(option);
            }
            return result;
        } catch (Exception ex) {
            log.warn("Failed to load datasources from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<DolphinDatasourceOption> listDatasources(
            String type, String keyword, Long dolphinConfigId) {
        return listDatasourcesAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                type,
                keyword,
                dolphinConfigId);
    }

    public List<DolphinDatasourceOption> listDatasourcesAtTarget(
            DolphinConfig targetConfig, String type, String keyword, Long dolphinConfigId) {
        return listDatasourcesAtTarget(targetConfig, type, keyword);
    }

    /** Retrieve task group options from DolphinScheduler OpenAPI. */
    public List<DolphinTaskGroupOption> listTaskGroups(String keyword) {
        return listTaskGroupsAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), keyword);
    }

    public List<DolphinTaskGroupOption> listTaskGroupsAtTarget(
            DolphinConfig targetConfig, String keyword) {
        try {
            Long currentProjectCode = getProjectCodeAtTarget(targetConfig);
            DolphinPageData<DolphinTaskGroup> page =
                    openApiClient.listTaskGroups(targetConfig, 1, 200, keyword, null);
            if (page == null || page.getTotalList() == null) {
                return Collections.emptyList();
            }
            boolean hasProjectCode =
                    page.getTotalList().stream()
                            .filter(Objects::nonNull)
                            .anyMatch(group -> group.getProjectCode() != null);
            List<DolphinTaskGroupOption> result = new ArrayList<>();
            for (DolphinTaskGroup group : page.getTotalList()) {
                if (group == null) {
                    continue;
                }
                if (!StringUtils.hasText(group.getName())) {
                    continue;
                }
                if (StringUtils.hasText(keyword) && !group.getName().contains(keyword)) {
                    continue;
                }
                if (hasProjectCode && currentProjectCode != null) {
                    Long groupProjectCode = group.getProjectCode();
                    // projectCode=0 视为全局任务组，允许跨项目复用；其余项目级任务组需与当前项目一致。
                    if (groupProjectCode != null
                            && groupProjectCode > 0
                            && !Objects.equals(currentProjectCode, groupProjectCode)) {
                        continue;
                    }
                }
                DolphinTaskGroupOption option = new DolphinTaskGroupOption();
                option.setId(group.getId());
                option.setProjectCode(group.getProjectCode());
                option.setName(group.getName());
                option.setDescription(group.getDescription());
                option.setGroupSize(group.getGroupSize());
                option.setUseSize(group.getUseSize());
                option.setStatus(group.getStatus());
                result.add(option);
            }
            return result;
        } catch (Exception ex) {
            log.warn("Failed to load task groups from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<DolphinTaskGroupOption> listTaskGroups(String keyword, Long dolphinConfigId) {
        return listTaskGroupsAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                keyword,
                dolphinConfigId);
    }

    public List<DolphinTaskGroupOption> listTaskGroupsAtTarget(
            DolphinConfig targetConfig, String keyword, Long dolphinConfigId) {
        return listTaskGroupsAtTarget(targetConfig, keyword);
    }

    /** Retrieve worker group list from DolphinScheduler (project scoped). */
    public List<String> listWorkerGroups() {
        return listWorkerGroupsAtTarget(dolphinConfigService.resolveActiveOrTargetConfig(null));
    }

    public List<String> listWorkerGroupsAtTarget(DolphinConfig targetConfig) {
        try {
            Long projectCode = getProjectCodeAtTarget(targetConfig);
            if (projectCode == null) {
                return Collections.emptyList();
            }
            return openApiClient.listProjectWorkerGroups(targetConfig, projectCode);
        } catch (Exception ex) {
            log.warn("Failed to load worker groups from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<String> listWorkerGroups(Long dolphinConfigId) {
        return listWorkerGroupsAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId), dolphinConfigId);
    }

    public List<String> listWorkerGroupsAtTarget(DolphinConfig targetConfig, Long dolphinConfigId) {
        return listWorkerGroupsAtTarget(targetConfig);
    }

    /** Retrieve tenant code list from DolphinScheduler. */
    public List<String> listTenants() {
        return listTenantsAtTarget(dolphinConfigService.resolveActiveOrTargetConfig(null));
    }

    public List<String> listTenantsAtTarget(DolphinConfig targetConfig) {
        try {
            return openApiClient.listTenants(targetConfig);
        } catch (Exception ex) {
            log.warn("Failed to load tenants from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<String> listTenants(Long dolphinConfigId) {
        return listTenantsAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId), dolphinConfigId);
    }

    public List<String> listTenantsAtTarget(DolphinConfig targetConfig, Long dolphinConfigId) {
        return listTenantsAtTarget(targetConfig);
    }

    /** Retrieve alert group list from DolphinScheduler. */
    public List<DolphinAlertGroupOption> listAlertGroups() {
        return listAlertGroupsAtTarget(dolphinConfigService.resolveActiveOrTargetConfig(null));
    }

    public List<DolphinAlertGroupOption> listAlertGroupsAtTarget(DolphinConfig targetConfig) {
        try {
            return openApiClient.listAlertGroups(targetConfig);
        } catch (Exception ex) {
            log.warn("Failed to load alert groups from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<DolphinAlertGroupOption> listAlertGroups(Long dolphinConfigId) {
        return listAlertGroupsAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId), dolphinConfigId);
    }

    public List<DolphinAlertGroupOption> listAlertGroupsAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId) {
        return listAlertGroupsAtTarget(targetConfig);
    }

    /** Retrieve environment list from DolphinScheduler. */
    public List<DolphinEnvironmentOption> listEnvironments() {
        return listEnvironmentsAtTarget(dolphinConfigService.resolveActiveOrTargetConfig(null));
    }

    public List<DolphinEnvironmentOption> listEnvironmentsAtTarget(DolphinConfig targetConfig) {
        try {
            return openApiClient.listEnvironments(targetConfig);
        } catch (Exception ex) {
            log.warn("Failed to load environments from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<DolphinEnvironmentOption> listEnvironments(Long dolphinConfigId) {
        return listEnvironmentsAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId), dolphinConfigId);
    }

    public List<DolphinEnvironmentOption> listEnvironmentsAtTarget(
            DolphinConfig targetConfig, Long dolphinConfigId) {
        return listEnvironmentsAtTarget(targetConfig);
    }

    /** Preview next trigger times for a schedule JSON. */
    public List<String> previewSchedule(String scheduleJson) {
        return previewScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(null), scheduleJson);
    }

    public List<String> previewScheduleAtTarget(DolphinConfig targetConfig, String scheduleJson) {
        try {
            Long projectCode = getProjectCodeAtTarget(targetConfig);
            if (projectCode == null) {
                return Collections.emptyList();
            }
            return openApiClient.previewSchedule(targetConfig, projectCode, scheduleJson);
        } catch (Exception ex) {
            log.warn("Failed to preview schedule from DolphinScheduler: {}", ex.getMessage());
            return Collections.emptyList();
        }
    }

    public List<String> previewSchedule(String scheduleJson, Long dolphinConfigId) {
        return previewScheduleAtTarget(
                dolphinConfigService.resolveActiveOrTargetConfig(dolphinConfigId),
                scheduleJson,
                dolphinConfigId);
    }

    public List<String> previewScheduleAtTarget(
            DolphinConfig targetConfig, String scheduleJson, Long dolphinConfigId) {
        return previewScheduleAtTarget(targetConfig, scheduleJson);
    }

    private Long parseDuration(String value) {
        if (!StringUtils.hasText(value)) return null;
        // Basic parsing, assuming simple format or ms
        try {
            return Long.parseLong(value);
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Task relation payload describing dependencies. */
    @Getter
    public static class TaskRelationPayload {
        private String name;
        private long preTaskCode;
        private int preTaskVersion;
        private long postTaskCode;
        private int postTaskVersion;
        private String conditionType;
        private String conditionParams;

        public void setName(String name) {
            this.name = name;
        }

        public void setPreTaskCode(long preTaskCode) {
            this.preTaskCode = preTaskCode;
        }

        public void setPreTaskVersion(int preTaskVersion) {
            this.preTaskVersion = preTaskVersion;
        }

        public void setPostTaskCode(long postTaskCode) {
            this.postTaskCode = postTaskCode;
        }

        public void setPostTaskVersion(int postTaskVersion) {
            this.postTaskVersion = postTaskVersion;
        }

        public void setConditionType(String conditionType) {
            this.conditionType = conditionType;
        }

        public void setConditionParams(String conditionParams) {
            this.conditionParams = conditionParams;
        }
    }

    /** Location payload for visual DAG layout. */
    @Getter
    public static class TaskLocationPayload {
        private long taskCode;
        private int x;
        private int y;

        public void setTaskCode(long taskCode) {
            this.taskCode = taskCode;
        }

        public void setX(int x) {
            this.x = x;
        }

        public void setY(int y) {
            this.y = y;
        }
    }

    /**
     * Parameters for shell and SQL tasks. DataX params are built separately by {@link
     * #buildDataxParams} to match DolphinScheduler's native DataX schema.
     */
    @Getter
    public static class TaskParams {
        private final List<Object> localParams = new ArrayList<>();
        private final List<Object> resourceList = new ArrayList<>();
        private final String rawScript;
        private final String type;
        private final Long datasource;
        private final String sql;
        private final String sqlType;
        private final Integer displayRows;
        private final List<String> preStatements = new ArrayList<>();
        private final List<String> postStatements = new ArrayList<>();

        public static TaskParams shell(String script) {
            return new TaskParams(script, null, null, null);
        }

        public static TaskParams sql(String sql, Long datasourceId, String datasourceType) {
            return new TaskParams(null, datasourceId, datasourceType, sql);
        }

        private TaskParams(String rawScript, Long datasourceId, String datasourceType, String sql) {
            this.rawScript = rawScript;
            this.datasource = datasourceId;
            this.sql = sql;
            // SQL type: 0=QUERY, 1=NON_QUERY (as string). Default to NON_QUERY.
            this.sqlType = sql != null && sql.trim().toUpperCase().startsWith("SELECT") ? "0" : "1";
            this.displayRows = 10;
            // Don't default to MYSQL - let DolphinScheduler infer type from datasource name
            this.type = datasourceType;
        }
    }

    public DolphinConfig getConfig(Long id) {
        return dolphinConfigService.resolveActiveOrTargetConfig(id);
    }
}
