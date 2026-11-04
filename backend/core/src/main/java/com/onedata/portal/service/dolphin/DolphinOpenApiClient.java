package com.onedata.portal.service.dolphin;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.DolphinAlertGroupOption;
import com.onedata.portal.dto.DolphinEnvironmentOption;
import com.onedata.portal.dto.dolphin.*;
import com.onedata.portal.entity.DolphinConfig;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct client for DolphinScheduler OpenAPI. Handles authentication, request building, and
 * response parsing. Now supports dynamic configuration via DolphinConfigService.
 */
@Slf4j
@Component
public class DolphinOpenApiClient {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);

    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    // Cache the RestClient instance to avoid recreating it for every request
    // Key: url + token
    private final Map<String, RestClient> restClientCache = new ConcurrentHashMap<>();

    public DolphinOpenApiClient(ObjectMapper objectMapper, RestClient.Builder builder) {

        this.objectMapper = objectMapper;
        this.restClientBuilder = builder;
    }

    private RestClient getRestClient(DolphinConfig config) {
        String key = config.getUrl() + "::" + config.getToken();
        return restClientCache.computeIfAbsent(
                key,
                k -> {
                    log.info("Creating new RestClient for DolphinScheduler at {}", config.getUrl());
                    return restClientBuilder
                            .clone() // Clone builder to avoid side effects
                            .baseUrl(config.getUrl())
                            .defaultHeader("token", config.getToken())
                            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .build();
                });
    }

    /** Test connection with provided configuration. */
    public boolean testConnection(DolphinConfig config) {
        try {
            if (config == null || !StringUtils.hasText(config.getUrl())) {
                return false;
            }
            // Use a temporary RestClient for testing
            RestClient client =
                    restClientBuilder
                            .clone()
                            .baseUrl(config.getUrl())
                            .defaultHeader("token", config.getToken())
                            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                            .build();

            // Try to list projects (lightweight) or just checking system info if possible.
            // Using list projects as it's common.
            String path = "/projects";
            Map<String, String> params = new HashMap<>();
            params.put("pageNo", "1");
            params.put("pageSize", "1");

            MultiValueMap<String, String> multiMap = new LinkedMultiValueMap<>();
            params.forEach(multiMap::add);

            JsonNode result =
                    executeRequest(
                            client,
                            client.get()
                                    .uri(
                                            uriBuilder -> {
                                                uriBuilder.path(path);
                                                uriBuilder.queryParams(multiMap);
                                                return uriBuilder.build();
                                            }));

            return result != null;
        } catch (Exception e) {
            log.warn("Connection test failed: {}", e.getMessage());
            return false;
        }
    }

    /** Query project code by name. */
    public DolphinProject getProject(DolphinConfig targetConfig, String projectName) {
        try {
            String path = "/projects";
            Map<String, String> params = new HashMap<>();
            params.put("searchVal", projectName);
            params.put("pageNo", "1");
            params.put("pageSize", "100");
            JsonNode data = get(targetConfig, path, params);

            if (data == null) return null;

            // API returns a page structure or list
            JsonNode totalList = data.path("totalList");
            if (totalList.isArray()) {
                for (JsonNode node : totalList) {
                    if (projectName.equals(node.path("name").asText())) {
                        return objectMapper.treeToValue(node, DolphinProject.class);
                    }
                }
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to get project {}", projectName, e);
            throw new RuntimeException("Failed to get project: " + e.getMessage());
        }
    }

    /** Create a new project. */
    public Long createProject(DolphinConfig targetConfig, String projectName, String description) {
        try {
            String path = "/projects";
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("projectName", projectName);
            formData.add("description", description != null ? description : "");

            JsonNode data = postForm(targetConfig, path, formData);
            return data.path("code").asLong();
        } catch (Exception e) {
            log.error("Failed to create project {}", projectName, e);
            throw new RuntimeException("Failed to create project: " + e.getMessage());
        }
    }

    /** List datasources. */
    public List<DolphinDatasource> listDatasources(
            DolphinConfig targetConfig, Integer pageNo, Integer pageSize) {
        try {
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            queryParams.add("pageNo", String.valueOf(pageNo != null ? pageNo : 1));
            queryParams.add("pageSize", String.valueOf(pageSize != null ? pageSize : 100));

            JsonNode data = getWithParams(targetConfig, "/datasources", queryParams);
            if (data == null) return Collections.emptyList();

            JsonNode list = data.path("totalList");
            return objectMapper.readValue(
                    list.traverse(), new TypeReference<List<DolphinDatasource>>() {});
        } catch (Exception e) {
            log.warn("Failed to list datasources", e);
            return Collections.emptyList();
        }
    }

    /** List task groups. */
    public DolphinPageData<DolphinTaskGroup> listTaskGroups(
            DolphinConfig targetConfig,
            Integer pageNo,
            Integer pageSize,
            String name,
            Integer status) {
        try {
            MultiValueMap<String, String> queryParams = new LinkedMultiValueMap<>();
            queryParams.add("pageNo", String.valueOf(pageNo != null ? pageNo : 1));
            queryParams.add("pageSize", String.valueOf(pageSize != null ? pageSize : 100));
            if (StringUtils.hasText(name)) {
                queryParams.add("name", name);
            }
            if (status != null) {
                queryParams.add("status", String.valueOf(status));
            }

            JsonNode data = getWithParams(targetConfig, "/task-group/list-paging", queryParams);
            if (data == null) {
                return new DolphinPageData<>();
            }
            return objectMapper
                    .readerFor(new TypeReference<DolphinPageData<DolphinTaskGroup>>() {})
                    .readValue(data);
        } catch (Exception e) {
            log.warn("Failed to list task groups", e);
            return new DolphinPageData<>();
        }
    }

    /**
     * List worker groups assigned to the given project.
     *
     * <p>Endpoint: GET /projects/{projectCode}/worker-group
     */
    public List<String> listProjectWorkerGroups(DolphinConfig targetConfig, long projectCode) {
        try {
            String path = String.format("/projects/%d/worker-group", projectCode);
            JsonNode data = get(targetConfig, path, null);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode node : data) {
                String workerGroup = node.path("workerGroup").asText(null);
                if (StringUtils.hasText(workerGroup)) {
                    result.add(workerGroup);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to list project worker groups for {}", projectCode, e);
            return Collections.emptyList();
        }
    }

    /**
     * List tenants.
     *
     * <p>Endpoint: GET /tenants/list
     */
    public List<String> listTenants(DolphinConfig targetConfig) {
        try {
            JsonNode data = get(targetConfig, "/tenants/list", null);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            List<String> result = new ArrayList<>();
            for (JsonNode node : data) {
                String tenantCode = node.path("tenantCode").asText(null);
                if (StringUtils.hasText(tenantCode)) {
                    result.add(tenantCode);
                }
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to list tenants", e);
            return Collections.emptyList();
        }
    }

    /**
     * List alert groups.
     *
     * <p>Endpoint: GET /alert-groups/list
     */
    public List<DolphinAlertGroupOption> listAlertGroups(DolphinConfig targetConfig) {
        try {
            JsonNode data = get(targetConfig, "/alert-groups/list", null);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(
                    data.traverse(), new TypeReference<List<DolphinAlertGroupOption>>() {});
        } catch (Exception e) {
            log.warn("Failed to list alert groups", e);
            return Collections.emptyList();
        }
    }

    /**
     * List environments.
     *
     * <p>Endpoint: GET /environment/query-environment-list
     */
    public List<DolphinEnvironmentOption> listEnvironments(DolphinConfig targetConfig) {
        try {
            JsonNode data = get(targetConfig, "/environment/query-environment-list", null);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(
                    data.traverse(), new TypeReference<List<DolphinEnvironmentOption>>() {});
        } catch (Exception e) {
            log.warn("Failed to list environments", e);
            return Collections.emptyList();
        }
    }

    /**
     * Preview next trigger times for a given schedule.
     *
     * <p>Endpoint: POST /projects/{projectCode}/schedules/preview Body: {"schedule":
     * "{...json...}"}
     */
    public List<String> previewSchedule(
            DolphinConfig targetConfig, long projectCode, String scheduleJson) {
        try {
            String path = String.format("/projects/%d/schedules/preview", projectCode);
            Map<String, Object> payload = new HashMap<>();
            payload.put("schedule", StringUtils.hasText(scheduleJson) ? scheduleJson : "{}");
            JsonNode data = postJson(targetConfig, path, payload);
            if (data == null || !data.isArray()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(data.traverse(), new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Failed to preview schedule for project {}", projectCode, e);
            return Collections.emptyList();
        }
    }

    /**
     * Create or update process definition. Note: DS 3.x uses different endpoints for create and
     * update.
     */
    public Long createOrUpdateProcessDefinition(
            DolphinConfig targetConfig,
            long projectCode,
            String name,
            String description,
            String tenantCode,
            String executionType,
            String taskRelationJson,
            String taskDefinitionJson,
            String locations,
            String globalParams,
            Long existingCode) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("name", name);
        formData.add("description", description);
        formData.add("tenantCode", tenantCode);
        formData.add("executionType", executionType);
        formData.add("taskRelationJson", taskRelationJson);
        formData.add("taskDefinitionJson", taskDefinitionJson);
        if (StringUtils.hasText(locations)) {
            formData.add("locations", locations);
        } else {
            // Provide empty locations to avoid errors if required
            formData.add("locations", "[]");
        }
        if (StringUtils.hasText(globalParams)) {
            formData.add("globalParams", globalParams);
        } else {
            formData.add("globalParams", "[]");
        }

        try {
            if (existingCode != null && existingCode > 0) {
                // Update
                String path =
                        String.format(
                                "/projects/%d/process-definition/%d", projectCode, existingCode);
                formData.add("releaseState", "OFFLINE"); // Ensure offline before update
                putForm(targetConfig, path, formData);
                return existingCode;
            } else {
                // Create
                String path = String.format("/projects/%d/process-definition", projectCode);
                JsonNode data = postForm(targetConfig, path, formData);
                return data.path("code").asLong();
            }
        } catch (Exception e) {
            log.error("Failed to save process definition {}", name, e);
            throw new RuntimeException("Failed to save process definition: " + e.getMessage());
        }
    }

    /** Release process definition (ONLINE/OFFLINE). */
    public void releaseProcessDefinition(
            DolphinConfig targetConfig, long projectCode, long processCode, String state) {
        try {
            String path =
                    String.format(
                            "/projects/%d/process-definition/%d/release", projectCode, processCode);
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("name", ""); // Some versions require name param even if unused
            formData.add("releaseState", state);
            postForm(targetConfig, path, formData);
        } catch (Exception e) {
            log.error("Failed to release process {} to {}", processCode, state, e);
            throw new RuntimeException("Failed to update release state: " + e.getMessage());
        }
    }

    /** Query process definition. */
    public JsonNode getProcessDefinition(
            DolphinConfig targetConfig, long projectCode, long processCode) {
        try {
            String path =
                    String.format("/projects/%d/process-definition/%d", projectCode, processCode);
            return executeRequest(
                    getRestClient(targetConfig), getRestClient(targetConfig).get().uri(path));
        } catch (Exception e) {
            log.error("Failed to query process definition {}", processCode, e);
            throw new RuntimeException("Failed to query process definition: " + e.getMessage());
        }
    }

    /**
     * Query process definition node list by definition code.
     *
     * <p>Endpoint: GET /projects/{projectCode}/process-definition/{code}/tasks
     */
    public JsonNode getProcessDefinitionTasks(
            DolphinConfig targetConfig, long projectCode, long processCode) {
        try {
            String path =
                    String.format(
                            "/projects/%d/process-definition/%d/tasks", projectCode, processCode);
            return get(targetConfig, path, null);
        } catch (Exception e) {
            log.warn("Failed to query process definition tasks for {}", processCode, e);
            return null;
        }
    }

    /**
     * Query task definition list by process definition code.
     *
     * <p>Endpoint: GET /projects/{projectCode}/process-definition/query-task-definition-list
     */
    public JsonNode queryTaskDefinitionList(
            DolphinConfig targetConfig, long projectCode, long processDefinitionCode) {
        try {
            String path =
                    String.format(
                            "/projects/%d/process-definition/query-task-definition-list",
                            projectCode);
            MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
            query.add("processDefinitionCode", String.valueOf(processDefinitionCode));
            return getWithParams(targetConfig, path, query);
        } catch (Exception e) {
            log.warn(
                    "Failed to query task definition list for process {}",
                    processDefinitionCode,
                    e);
            return null;
        }
    }

    /**
     * Export runtime definition JSON by workflow/process code.
     *
     * <p>DS 3.4.x path: POST /projects/{projectCode}/workflow-definition/batch-export
     *
     * <p>DS 3.2.x path: POST /projects/{projectCode}/process-definition/batch-export
     *
     * <p>Response is a JSON file stream (blob), not regular code/data envelope.
     */
    public JsonNode exportDefinitionByCode(
            DolphinConfig targetConfig, long projectCode, long workflowCode) {
        MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
        formData.add("codes", String.valueOf(workflowCode));

        List<String> paths = new ArrayList<>();
        // Prefer DS 3.4+ endpoint, fallback to DS 3.2.x endpoint for compatibility.
        paths.add(String.format("/projects/%d/workflow-definition/batch-export", projectCode));
        paths.add(String.format("/projects/%d/process-definition/batch-export", projectCode));

        List<String> attemptErrors = new ArrayList<>();
        for (String path : paths) {
            try {
                byte[] payload = postFormRaw(targetConfig, path, formData);
                JsonNode exported = parseExportPayload(payload);
                if (exported != null && !exported.isNull() && !exported.isMissingNode()) {
                    return exported;
                }
                attemptErrors.add(path + " [empty payload]");
            } catch (Exception ex) {
                log.debug("Failed to export definition from {}", path, ex);
                attemptErrors.add(formatExportAttemptError(path, ex));
            }
        }

        String detail =
                attemptErrors.isEmpty() ? "no attempt detail" : String.join("; ", attemptErrors);
        throw new RuntimeException("Failed to export workflow definition: " + detail);
    }

    /**
     * List process definitions (paged).
     *
     * <p>Endpoint: GET /projects/{projectCode}/process-definition?pageNo=1&pageSize=10
     *
     * <p>Note: In some DolphinScheduler versions, the schedule (timing) information is only present
     * in this list API response (e.g. fields: scheduleReleaseState, schedule{...}).
     */
    public JsonNode listProcessDefinitions(
            DolphinConfig targetConfig, long projectCode, int pageNo, int pageSize) {
        return listProcessDefinitions(targetConfig, projectCode, pageNo, pageSize, null);
    }

    /** List process definitions (paged) with optional keyword. */
    public JsonNode listProcessDefinitions(
            DolphinConfig targetConfig,
            long projectCode,
            int pageNo,
            int pageSize,
            String searchVal) {
        try {
            String path = String.format("/projects/%d/process-definition", projectCode);
            MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
            query.add("pageNo", String.valueOf(pageNo > 0 ? pageNo : 1));
            query.add("pageSize", String.valueOf(pageSize > 0 ? pageSize : 100));
            if (StringUtils.hasText(searchVal)) {
                query.add("searchVal", searchVal.trim());
            }
            return getWithParams(targetConfig, path, query);
        } catch (Exception e) {
            log.warn("Failed to list process definitions for project {}", projectCode, e);
            return null;
        }
    }

    /**
     * List schedules (timing definitions) in project.
     *
     * <p>Endpoint: GET /projects/{projectCode}/schedules Different DS versions may use
     * processDefinitionCode / workflowDefinitionCode.
     */
    public DolphinPageData<DolphinSchedule> listSchedules(
            DolphinConfig targetConfig,
            long projectCode,
            int pageNo,
            int pageSize,
            Long workflowCode) {
        try {
            String path = String.format("/projects/%d/schedules", projectCode);
            MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
            query.add("pageNo", String.valueOf(pageNo > 0 ? pageNo : 1));
            query.add("pageSize", String.valueOf(pageSize > 0 ? pageSize : 100));
            if (workflowCode != null && workflowCode > 0) {
                String codeText = String.valueOf(workflowCode);
                query.add("processDefinitionCode", codeText);
                query.add("workflowDefinitionCode", codeText);
            }
            JsonNode data = getWithParams(targetConfig, path, query);
            if (data == null) {
                return new DolphinPageData<>();
            }
            return objectMapper
                    .readerFor(new TypeReference<DolphinPageData<DolphinSchedule>>() {})
                    .readValue(data);
        } catch (Exception e) {
            log.debug("Failed to list schedules for project {}", projectCode, e);
            return new DolphinPageData<>();
        }
    }

    /** Start process instance. */
    public Long startProcessInstance(
            DolphinConfig targetConfig,
            long projectCode,
            long processDefinitionCode,
            String scheduleTime,
            String failureStrategy,
            String warningType,
            String warningGroupId,
            String execType,
            String workerGroup,
            String tenantCode) {
        try {
            JsonNode data =
                    startProcessInstanceRaw(
                            targetConfig,
                            projectCode,
                            processDefinitionCode,
                            scheduleTime,
                            failureStrategy,
                            warningType,
                            warningGroupId,
                            execType,
                            workerGroup,
                            tenantCode,
                            null,
                            null,
                            null,
                            null,
                            null);
            if (data == null) {
                return null;
            }
            // Different DS versions expose different workflow/process instance keys.
            if (data.hasNonNull("workflowInstanceId")) {
                return data.path("workflowInstanceId").asLong();
            }
            if (data.hasNonNull("processInstanceId")) {
                return data.path("processInstanceId").asLong();
            }
            if (data.hasNonNull("processInstanceCode")) {
                return data.path("processInstanceCode").asLong();
            }
            if (data.hasNonNull("id")) {
                return data.path("id").asLong();
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to start process definition {}", processDefinitionCode, e);
            throw new RuntimeException("Failed to start process: " + e.getMessage());
        }
    }

    /**
     * Start process instance with extended options.
     *
     * <p>For DolphinScheduler 3.x, complement data (补数) is also triggered via this endpoint with
     * execType=COMPLEMENT_DATA.
     */
    public JsonNode startProcessInstanceRaw(
            DolphinConfig targetConfig,
            long projectCode,
            long processDefinitionCode,
            String scheduleTime,
            String failureStrategy,
            String warningType,
            String warningGroupId,
            String execType,
            String workerGroup,
            String tenantCode,
            String runMode,
            Integer expectedParallelismNumber,
            String complementDependentMode,
            Boolean allLevelDependent,
            String executionOrder) {
        try {
            String path =
                    String.format("/projects/%d/executors/start-process-instance", projectCode);
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("processDefinitionCode", String.valueOf(processDefinitionCode));
            formData.add("scheduleTime", scheduleTime != null ? scheduleTime : "");
            formData.add("failureStrategy", failureStrategy != null ? failureStrategy : "CONTINUE");
            formData.add("warningType", warningType != null ? warningType : "NONE");
            formData.add("warningGroupId", warningGroupId != null ? warningGroupId : "0");
            formData.add("execType", execType != null ? execType : "START_PROCESS");
            formData.add("workerGroup", workerGroup != null ? workerGroup : "default");
            formData.add("tenantCode", tenantCode != null ? tenantCode : "default");
            formData.add("dryRun", "0");

            if (StringUtils.hasText(runMode)) {
                formData.add("runMode", runMode);
            }
            if (expectedParallelismNumber != null) {
                formData.add(
                        "expectedParallelismNumber", String.valueOf(expectedParallelismNumber));
            }
            if (StringUtils.hasText(complementDependentMode)) {
                formData.add("complementDependentMode", complementDependentMode);
            }
            if (allLevelDependent != null) {
                formData.add("allLevelDependent", String.valueOf(allLevelDependent));
            }
            if (StringUtils.hasText(executionOrder)) {
                formData.add("executionOrder", executionOrder);
            }

            return postForm(targetConfig, path, formData);
        } catch (Exception e) {
            log.error("Failed to start process instance {}", processDefinitionCode, e);
            throw new RuntimeException("Failed to start process: " + e.getMessage());
        }
    }

    /** List process instances. */
    public DolphinPageData<DolphinProcessInstance> listProcessInstances(
            DolphinConfig targetConfig,
            long projectCode,
            int pageNo,
            int pageSize,
            Long processDefinitionCode) {
        try {
            String path = String.format("/projects/%d/process-instances", projectCode);
            MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
            query.add("pageNo", String.valueOf(pageNo));
            query.add("pageSize", String.valueOf(pageSize));
            if (processDefinitionCode != null) {
                // DS versions have inconsistent query key names, send both for compatibility.
                query.add("processDefinitionCode", String.valueOf(processDefinitionCode));
                query.add("processDefineCode", String.valueOf(processDefinitionCode));
            }

            JsonNode data = getWithParams(targetConfig, path, query);
            if (data == null) return new DolphinPageData<>();

            return objectMapper
                    .readerFor(new TypeReference<DolphinPageData<DolphinProcessInstance>>() {})
                    .readValue(data);
        } catch (Exception e) {
            log.warn("Failed to list process instances", e);
            throw new RuntimeException("Failed to list process instances: " + e.getMessage(), e);
        }
    }

    /**
     * List task instances for one workflow/process instance.
     *
     * <p>DolphinScheduler 3.2 expects {@code processInstanceId}, while 3.4 expects {@code
     * workflowInstanceId}. Unknown query parameters are ignored by both versions, so both names are
     * sent with the same value.
     */
    public DolphinPageData<DolphinTaskInstance> listTaskInstances(
            DolphinConfig targetConfig,
            long projectCode,
            int pageNo,
            int pageSize,
            Long workflowInstanceId,
            Long taskCode) {
        try {
            String path = String.format("/projects/%d/task-instances", projectCode);
            MultiValueMap<String, String> query =
                    buildTaskInstanceQuery(pageNo, pageSize, workflowInstanceId, taskCode);
            JsonNode data = getWithParams(targetConfig, path, query);
            if (data == null) {
                return new DolphinPageData<>();
            }
            return objectMapper
                    .readerFor(new TypeReference<DolphinPageData<DolphinTaskInstance>>() {})
                    .readValue(data);
        } catch (Exception e) {
            log.warn(
                    "Failed to list task instances for workflow instance {}",
                    workflowInstanceId,
                    e);
            throw new RuntimeException("Failed to list task instances: " + e.getMessage(), e);
        }
    }

    static MultiValueMap<String, String> buildTaskInstanceQuery(
            int pageNo, int pageSize, Long workflowInstanceId, Long taskCode) {
        MultiValueMap<String, String> query = new LinkedMultiValueMap<>();
        query.add("pageNo", String.valueOf(pageNo > 0 ? pageNo : 1));
        query.add("pageSize", String.valueOf(pageSize > 0 ? pageSize : 100));
        if (workflowInstanceId != null && workflowInstanceId > 0) {
            String instanceId = String.valueOf(workflowInstanceId);
            query.add("processInstanceId", instanceId);
            query.add("workflowInstanceId", instanceId);
        }
        if (taskCode != null && taskCode > 0) {
            query.add("taskCode", String.valueOf(taskCode));
        }
        return query;
    }

    /** Get single process instance. */
    public DolphinProcessInstance getProcessInstance(
            DolphinConfig targetConfig, long projectCode, long instanceId) {
        try {
            String path =
                    String.format("/projects/%d/process-instances/%d", projectCode, instanceId);
            JsonNode data = get(targetConfig, path, null);
            return objectMapper.treeToValue(data, DolphinProcessInstance.class);
        } catch (Exception e) {
            log.warn("Failed to get process instance {}", instanceId, e);
            return null;
        }
    }

    /** Delete process definition. */
    public void deleteProcessDefinition(
            DolphinConfig targetConfig, long projectCode, long processDefinitionCode) {
        try {
            String path =
                    String.format(
                            "/projects/%d/process-definition/%d",
                            projectCode, processDefinitionCode);
            delete(targetConfig, path);
        } catch (Exception e) {
            log.error("Failed to delete process definition {}", processDefinitionCode, e);
            throw new RuntimeException("Failed to delete process: " + e.getMessage());
        }
    }

    /**
     * Create a schedule for a workflow definition.
     *
     * <p>Endpoint: POST /projects/{projectCode}/schedules Params (version dependent): - DS 3.x
     * commonly uses workflowDefinitionCode - DS 2.x commonly uses processDefinitionCode To be
     * compatible across versions we send both keys with the same value.
     *
     * <p>Additional commonly-required form fields in the WebUI: processInstancePriority,
     * workerGroup, tenantCode, environmentCode, warningGroupId
     *
     * @return schedule id if available
     */
    public Long createSchedule(
            DolphinConfig targetConfig,
            long projectCode,
            long workflowDefinitionCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        try {
            String path = String.format("/projects/%d/schedules", projectCode);
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            String definitionCode = String.valueOf(workflowDefinitionCode);
            // DS 3.x
            formData.add("workflowDefinitionCode", definitionCode);
            // DS 2.x
            formData.add("processDefinitionCode", definitionCode);
            formData.add("schedule", scheduleJson != null ? scheduleJson : "{}");
            formData.add("warningType", StringUtils.hasText(warningType) ? warningType : "NONE");
            formData.add(
                    "failureStrategy",
                    StringUtils.hasText(failureStrategy) ? failureStrategy : "CONTINUE");
            formData.add(
                    "warningGroupId", String.valueOf(warningGroupId != null ? warningGroupId : 0L));

            DolphinConfig config = targetConfig;
            String resolvedTenantCode =
                    StringUtils.hasText(tenantCode)
                            ? tenantCode.trim()
                            : (config != null && StringUtils.hasText(config.getTenantCode())
                                    ? config.getTenantCode()
                                    : "default");
            String resolvedWorkerGroup =
                    StringUtils.hasText(workerGroup)
                            ? workerGroup.trim()
                            : (config != null && StringUtils.hasText(config.getWorkerGroup())
                                    ? config.getWorkerGroup()
                                    : "default");
            String resolvedPriority =
                    StringUtils.hasText(processInstancePriority)
                            ? processInstancePriority.trim()
                            : "MEDIUM";
            Long resolvedEnvironmentCode = environmentCode != null ? environmentCode : -1L;

            formData.add("processInstancePriority", resolvedPriority);
            formData.add("workflowInstancePriority", resolvedPriority);
            formData.add("workerGroup", resolvedWorkerGroup);
            formData.add("tenantCode", resolvedTenantCode);
            // -1 means default/no environment in DolphinScheduler
            formData.add("environmentCode", String.valueOf(resolvedEnvironmentCode));

            JsonNode data = postForm(targetConfig, path, formData);
            if (data == null) {
                return null;
            }
            if (data.isIntegralNumber()) {
                return data.asLong();
            }
            if (data.hasNonNull("id")) {
                return data.path("id").asLong();
            }
            if (data.hasNonNull("scheduleId")) {
                return data.path("scheduleId").asLong();
            }
            return null;
        } catch (Exception e) {
            log.error("Failed to create schedule for workflow {}", workflowDefinitionCode, e);
            throw new RuntimeException("Failed to create schedule: " + e.getMessage());
        }
    }

    /**
     * Update an existing schedule.
     *
     * <p>Endpoint: PUT /projects/{projectCode}/schedules/{id}
     */
    public void updateSchedule(
            DolphinConfig targetConfig,
            long projectCode,
            long scheduleId,
            long workflowDefinitionCode,
            String scheduleJson,
            String warningType,
            String failureStrategy,
            Long warningGroupId,
            String processInstancePriority,
            String workerGroup,
            String tenantCode,
            Long environmentCode) {
        try {
            String path = String.format("/projects/%d/schedules/%d", projectCode, scheduleId);
            MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
            formData.add("id", String.valueOf(scheduleId));
            String definitionCode = String.valueOf(workflowDefinitionCode);
            // DS 3.x
            formData.add("workflowDefinitionCode", definitionCode);
            // DS 2.x
            formData.add("processDefinitionCode", definitionCode);
            formData.add("schedule", scheduleJson != null ? scheduleJson : "{}");
            formData.add("warningType", StringUtils.hasText(warningType) ? warningType : "NONE");
            formData.add(
                    "failureStrategy",
                    StringUtils.hasText(failureStrategy) ? failureStrategy : "CONTINUE");
            formData.add(
                    "warningGroupId", String.valueOf(warningGroupId != null ? warningGroupId : 0L));

            DolphinConfig config = targetConfig;
            String resolvedTenantCode =
                    StringUtils.hasText(tenantCode)
                            ? tenantCode.trim()
                            : (config != null && StringUtils.hasText(config.getTenantCode())
                                    ? config.getTenantCode()
                                    : "default");
            String resolvedWorkerGroup =
                    StringUtils.hasText(workerGroup)
                            ? workerGroup.trim()
                            : (config != null && StringUtils.hasText(config.getWorkerGroup())
                                    ? config.getWorkerGroup()
                                    : "default");
            String resolvedPriority =
                    StringUtils.hasText(processInstancePriority)
                            ? processInstancePriority.trim()
                            : "MEDIUM";
            Long resolvedEnvironmentCode = environmentCode != null ? environmentCode : -1L;

            formData.add("processInstancePriority", resolvedPriority);
            formData.add("workflowInstancePriority", resolvedPriority);
            formData.add("workerGroup", resolvedWorkerGroup);
            formData.add("tenantCode", resolvedTenantCode);
            formData.add("environmentCode", String.valueOf(resolvedEnvironmentCode));

            putForm(targetConfig, path, formData);
        } catch (Exception e) {
            log.error(
                    "Failed to update schedule {} for workflow {}",
                    scheduleId,
                    workflowDefinitionCode,
                    e);
            throw new RuntimeException("Failed to update schedule: " + e.getMessage());
        }
    }

    /** Online a schedule. */
    public void onlineSchedule(DolphinConfig targetConfig, long projectCode, long scheduleId) {
        try {
            String path =
                    String.format("/projects/%d/schedules/%d/online", projectCode, scheduleId);
            post(targetConfig, path);
        } catch (Exception e) {
            log.error("Failed to online schedule {}", scheduleId, e);
            throw new RuntimeException("Failed to online schedule: " + e.getMessage());
        }
    }

    /** Offline a schedule. */
    public void offlineSchedule(DolphinConfig targetConfig, long projectCode, long scheduleId) {
        try {
            String path =
                    String.format("/projects/%d/schedules/%d/offline", projectCode, scheduleId);
            post(targetConfig, path);
        } catch (Exception e) {
            log.error("Failed to offline schedule {}", scheduleId, e);
            throw new RuntimeException("Failed to offline schedule: " + e.getMessage());
        }
    }

    // --- Private Helpers ---

    private JsonNode getWithParams(
            DolphinConfig targetConfig, String path, MultiValueMap<String, String> queryParams) {
        return executeRequest(
                getRestClient(targetConfig),
                getRestClient(targetConfig)
                        .get()
                        .uri(
                                uriBuilder -> {
                                    uriBuilder.path(path);
                                    if (queryParams != null) {
                                        uriBuilder.queryParams(queryParams);
                                    }
                                    return uriBuilder.build();
                                }));
    }

    private JsonNode get(DolphinConfig targetConfig, String path, Map<String, String> queryParams) {
        MultiValueMap<String, String> multiMap = new LinkedMultiValueMap<>();
        if (queryParams != null) {
            queryParams.forEach(multiMap::add);
        }
        return getWithParams(targetConfig, path, multiMap);
    }

    private JsonNode postForm(
            DolphinConfig targetConfig, String path, MultiValueMap<String, String> formData) {
        return executeRequest(
                getRestClient(targetConfig),
                getRestClient(targetConfig)
                        .post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(formData));
    }

    private byte[] postFormRaw(
            DolphinConfig targetConfig, String path, MultiValueMap<String, String> formData) {
        try {
            byte[] response =
                    getRestClient(targetConfig)
                            .post()
                            .uri(path)
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .body(formData)
                            .retrieve()
                            .body(byte[].class);
            if (response == null || response.length == 0) {
                throw new RuntimeException("empty response");
            }
            return response;
        } catch (Exception ex) {
            throw new RuntimeException(ex.getMessage(), ex);
        }
    }

    private JsonNode postJson(DolphinConfig targetConfig, String path, Object payload) {
        return executeRequest(
                getRestClient(targetConfig),
                getRestClient(targetConfig)
                        .post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(payload));
    }

    private JsonNode post(DolphinConfig targetConfig, String path) {
        return executeRequest(
                getRestClient(targetConfig), getRestClient(targetConfig).post().uri(path));
    }

    private JsonNode putForm(
            DolphinConfig targetConfig, String path, MultiValueMap<String, String> formData) {
        return executeRequest(
                getRestClient(targetConfig),
                getRestClient(targetConfig)
                        .put()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(formData));
    }

    private JsonNode delete(DolphinConfig targetConfig, String path) {
        return executeRequest(
                getRestClient(targetConfig), getRestClient(targetConfig).delete().uri(path));
    }

    private JsonNode executeRequest(
            RestClient client, RestClient.RequestHeadersSpec<?> requestSpec) {
        try {
            String response = requestSpec.retrieve().body(String.class);

            if (!StringUtils.hasText(response)) return null;

            JsonNode root = objectMapper.readTree(response);
            int code = root.path("code").asInt(-1);
            if (code != 0) {
                String msg = root.path("msg").asText("Unknown error");
                throw new RuntimeException("API Error " + code + ": " + msg);
            }
            return root.path("data");
        } catch (Exception e) {
            // Wrap in RuntimeException to keep signatures clean
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private JsonNode parseExportPayload(byte[] payload) {
        String raw = new String(payload, StandardCharsets.UTF_8).trim();
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root == null || root.isNull()) {
                return null;
            }
            if (root.isArray()) {
                return root.size() > 0 ? root.get(0) : null;
            }
            JsonNode codeNode = root.get("code");
            if (codeNode != null && codeNode.isIntegralNumber() && codeNode.asInt() != 0) {
                String msg = root.path("msg").asText("unknown export error");
                throw new RuntimeException("API Error " + codeNode.asInt() + ": " + msg);
            }
            if (root.has("data") && !root.get("data").isNull()) {
                JsonNode data = root.get("data");
                if (data.isArray()) {
                    return data.size() > 0 ? data.get(0) : null;
                }
                if (data.isObject()) {
                    return data;
                }
            }
            return root;
        } catch (IOException ex) {
            throw new RuntimeException("Invalid export payload json", ex);
        }
    }

    private String formatExportAttemptError(String path, Exception ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }

        if (root instanceof RestClientResponseException) {
            RestClientResponseException webEx = (RestClientResponseException) root;
            String body = sanitizeErrorMessage(webEx.getResponseBodyAsString());
            if (!StringUtils.hasText(body)) {
                body = sanitizeErrorMessage(webEx.getStatusText());
            }
            return String.format(
                    "%s [status=%d, message=%s]", path, webEx.getRawStatusCode(), body);
        }

        String message = sanitizeErrorMessage(root.getMessage());
        if (!StringUtils.hasText(message)) {
            message = sanitizeErrorMessage(ex.getMessage());
        }
        return path + " [message=" + message + "]";
    }

    private String sanitizeErrorMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return "unknown";
        }
        String normalized = message.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() <= 240) {
            return normalized;
        }
        return normalized.substring(0, 237) + "...";
    }
}
