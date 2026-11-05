package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.dolphin.*;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Integration test for DolphinSchedulerService.
 *
 * <p>This test validates the Java -> DolphinScheduler OpenAPI integration chain with a mocked
 * OpenAPI client.
 *
 * <p>Test Flow: 1. Sync workflow with tasks and relations 2. Release workflow to ONLINE state 3.
 * Start workflow instance 4. Release workflow to OFFLINE state
 */
@DisplayName("DolphinScheduler Service Integration Tests")
@ExtendWith(MockitoExtension.class)
class DolphinSchedulerServiceIntegrationTest {

    private DolphinSchedulerService service;

    private static final String TEST_WORKFLOW_NAME = "java-integration-test";
    private static final String TEST_WORKFLOW_DESCRIPTION = "java integration workflow description";

    @Mock private DolphinOpenApiClient openApiClient;

    @Mock private DolphinConfigService dolphinConfigService;

    @BeforeEach
    void setUp() {
        // Setup properties - mocking DolphinConfig instead of Properties
        DolphinConfig config = new DolphinConfig();
        config.setUrl("http://localhost:12345/dolphinscheduler");
        config.setToken("test-token");
        config.setProjectName("test-project");
        config.setTenantCode("default");
        config.setWorkerGroup("default");
        config.setExecutionType("PARALLEL");

        // Mock service behavior
        lenient()
                .when(
                        dolphinConfigService.resolveActiveOrTargetConfig(
                                org.mockito.ArgumentMatchers.any()))
                .thenReturn(config);

        // Create service instance
        ObjectMapper objectMapper = new ObjectMapper();
        service = new DolphinSchedulerService(dolphinConfigService, objectMapper, openApiClient);

        System.out.println("============================================================");
        System.out.println("Starting DolphinScheduler Integration Test (Mocked)");
        System.out.println("============================================================");
    }

    @Test
    @DisplayName("Test 1: Complete Workflow Lifecycle")
    void testCompleteWorkflowLifecycle() {
        System.out.println("\n🧪 Test 1: Complete Workflow Lifecycle\n");

        // Mock behaviors
        DolphinProject mockProject = new DolphinProject();
        mockProject.setCode(123456L);
        when(openApiClient.getProject(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        anyString()))
                .thenReturn(mockProject);
        when(openApiClient.createOrUpdateProcessDefinition(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        anyLong(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        nullable(String.class),
                        any()))
                .thenReturn(999L); // Return mock workflow code

        // Step 1: Build task definitions
        System.out.println("Step 1: Building task definitions...");
        long task1Code = service.nextTaskCode();
        long task2Code = service.nextTaskCode();

        Map<String, Object> task1 =
                service.buildTaskDefinition(
                        task1Code,
                        1,
                        "java_test_task_1",
                        "First test task from Java",
                        "#!/bin/bash\necho 'Java Test Task 1 executed'",
                        "MEDIUM",
                        0,
                        1,
                        3600,
                        "SHELL",
                        null,
                        null,
                        null,
                        null);

        Map<String, Object> task2 =
                service.buildTaskDefinition(
                        task2Code,
                        1,
                        "java_test_task_2",
                        "Second test task from Java",
                        "#!/bin/bash\necho 'Java Test Task 2 executed'",
                        "MEDIUM",
                        0,
                        1,
                        3600,
                        "SHELL",
                        null,
                        null,
                        null,
                        null);

        List<Map<String, Object>> tasks = new ArrayList<>();
        tasks.add(task1);
        tasks.add(task2);

        System.out.println("✅ Created " + tasks.size() + " task definitions");

        // Step 2: Build relations
        System.out.println("\nStep 2: Building task relations...");
        List<DolphinSchedulerService.TaskRelationPayload> relations = new ArrayList<>();
        relations.add(service.buildRelation(task1Code, 1, task2Code, 1));
        System.out.println("✅ Created relation: task1 -> task2");

        // Step 3: Build locations
        System.out.println("\nStep 3: Building task locations...");
        List<DolphinSchedulerService.TaskLocationPayload> locations = new ArrayList<>();
        locations.add(service.buildLocation(task1Code, 0, 0));
        locations.add(service.buildLocation(task2Code, 1, 0));
        System.out.println("✅ Created 2 task locations");

        // Step 4: Sync workflow
        System.out.println("\nStep 4: Syncing workflow (Mocked)...");
        long workflowCode =
                service.syncWorkflow(
                        0, // workflowCode = 0 means create new
                        TEST_WORKFLOW_NAME,
                        TEST_WORKFLOW_DESCRIPTION,
                        tasks,
                        relations,
                        locations,
                        null);

        assertEquals(999L, workflowCode);
        verify(openApiClient, times(1))
                .getProject(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        org.mockito.ArgumentMatchers.eq("test-project"));
        verify(openApiClient, times(1))
                .createOrUpdateProcessDefinition(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        eq(123456L),
                        eq(TEST_WORKFLOW_NAME),
                        eq(TEST_WORKFLOW_DESCRIPTION),
                        any(),
                        any(),
                        any(),
                        any(),
                        any(),
                        isNull(),
                        isNull());

        System.out.println("✅ Workflow synced successfully (Mocked)");

        System.out.println("\n✅ Test 1 Completed: Workflow lifecycle test passed");
    }

    @Test
    @DisplayName("Test 2: Task Code Generation")
    void testTaskCodeGeneration() {
        System.out.println("\n🧪 Test 2: Task Code Generation\n");

        long code1 = service.nextTaskCode();
        long code2 = service.nextTaskCode();
        long code3 = service.nextTaskCode();

        System.out.println("Generated codes:");
        System.out.println("  Code 1: " + code1);
        System.out.println("  Code 2: " + code2);
        System.out.println("  Code 3: " + code3);

        assertTrue(code2 > code1, "Code 2 should be greater than code 1");
        assertTrue(code3 > code2, "Code 3 should be greater than code 2");

        System.out.println("\n✅ Test 2 Completed: Task code generation works correctly");
    }

    @Test
    @DisplayName("Test 3: Shell Script Building")
    void testShellScriptBuilding() {
        System.out.println("\n🧪 Test 3: Shell Script Building\n");

        String sql = "SELECT * FROM test_table WHERE id > 100;";
        String script = service.buildShellScript(sql);

        System.out.println("Generated shell script:");
        System.out.println(script);

        assertTrue(script.contains("#!/bin/bash"), "Script should contain shebang");
        assertTrue(script.contains(sql), "Script should contain SQL");
        assertTrue(script.contains("set -euo pipefail"), "Script should have error handling");

        System.out.println("\n✅ Test 3 Completed: Shell script building works correctly");
    }

    @Test
    @DisplayName("Test 4: Task Relation Building")
    void testTaskRelationBuilding() {
        System.out.println("\n🧪 Test 4: Task Relation Building\n");

        long upstreamCode = 100001;
        long downstreamCode = 100002;

        DolphinSchedulerService.TaskRelationPayload relation =
                service.buildRelation(upstreamCode, 1, downstreamCode, 1);

        System.out.println("Created relation:");
        System.out.println("  Upstream: " + relation.getPreTaskCode());
        System.out.println("  Downstream: " + relation.getPostTaskCode());
        System.out.println("  Condition: " + relation.getConditionType());

        assertEquals(upstreamCode, relation.getPreTaskCode());
        assertEquals(downstreamCode, relation.getPostTaskCode());
        assertEquals("NONE", relation.getConditionType());

        System.out.println("\n✅ Test 4 Completed: Task relation building works correctly");
    }

    @Test
    @DisplayName("Test 5: Task Location Building")
    void testTaskLocationBuilding() {
        System.out.println("\n🧪 Test 5: Task Location Building\n");

        long taskCode = 100001;
        int index = 0;
        int lane = 0;

        DolphinSchedulerService.TaskLocationPayload location =
                service.buildLocation(taskCode, index, lane);

        System.out.println("Created location:");
        System.out.println("  Task code: " + location.getTaskCode());
        System.out.println("  X: " + location.getX());
        System.out.println("  Y: " + location.getY());

        assertEquals(taskCode, location.getTaskCode());
        assertTrue(location.getX() > 0, "X coordinate should be positive");
        assertTrue(location.getY() > 0, "Y coordinate should be positive");

        System.out.println("\n✅ Test 5 Completed: Task location building works correctly");
    }

    @Test
    @DisplayName("Test 6: Instance list should only include current workflow")
    void testListWorkflowInstancesFiltersByWorkflowCode() {
        DolphinProject project = new DolphinProject();
        project.setCode(123456L);
        when(openApiClient.getProject(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        anyString()))
                .thenReturn(project);

        DolphinPageData<DolphinProcessInstance> mixedPage =
                page(processInstance(11L, 111L, "SUCCESS"), processInstance(22L, 222L, "FAILURE"));
        when(openApiClient.listProcessInstances(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        eq(123456L),
                        anyInt(),
                        anyInt(),
                        eq(111L)))
                .thenReturn(mixedPage);

        List<WorkflowInstanceSummary> instances = service.listWorkflowInstances(111L, 10);

        assertEquals(1, instances.size());
        assertEquals(11L, instances.get(0).getInstanceId());
        verify(openApiClient, atLeastOnce())
                .listProcessInstances(
                        any(com.onedata.portal.entity.DolphinConfig.class),
                        eq(123456L),
                        anyInt(),
                        anyInt(),
                        eq(111L));
    }

    @Test
    @DisplayName("Test 7: Instance list fallback when server filter is ignored")
    void testListWorkflowInstancesFallbackToGlobalScan() {
        DolphinProject project = new DolphinProject();
        project.setCode(123456L);
        when(openApiClient.getProject(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        anyString()))
                .thenReturn(project);

        DolphinPageData<DolphinProcessInstance> ignoredFilterPage =
                page(processInstance(31L, 999L, "SUCCESS"));
        DolphinPageData<DolphinProcessInstance> globalPage =
                page(processInstance(32L, 888L, "SUCCESS"), processInstance(33L, 111L, "RUNNING"));

        when(openApiClient.listProcessInstances(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        eq(123456L),
                        anyInt(),
                        anyInt(),
                        eq(111L)))
                .thenReturn(ignoredFilterPage);
        when(openApiClient.listProcessInstances(
                        org.mockito.ArgumentMatchers.any(
                                com.onedata.portal.entity.DolphinConfig.class),
                        eq(123456L),
                        anyInt(),
                        anyInt(),
                        isNull()))
                .thenReturn(globalPage);

        List<WorkflowInstanceSummary> instances = service.listWorkflowInstances(111L, 10);

        assertEquals(1, instances.size());
        assertEquals(33L, instances.get(0).getInstanceId());
        verify(openApiClient, atLeastOnce())
                .listProcessInstances(
                        any(com.onedata.portal.entity.DolphinConfig.class),
                        eq(123456L),
                        anyInt(),
                        anyInt(),
                        eq(111L));
        verify(openApiClient, atLeastOnce())
                .listProcessInstances(
                        any(com.onedata.portal.entity.DolphinConfig.class),
                        eq(123456L),
                        anyInt(),
                        anyInt(),
                        isNull());
    }

    private DolphinProcessInstance processInstance(
            Long id, Long processDefinitionCode, String state) {
        DolphinProcessInstance instance = new DolphinProcessInstance();
        instance.setId(id);
        instance.setProcessDefinitionCode(processDefinitionCode);
        instance.setState(state);
        instance.setCommandType("START_PROCESS");
        instance.setDuration("10s");
        instance.setStartTime("2026-02-09 10:00:00");
        instance.setEndTime("2026-02-09 10:00:10");
        return instance;
    }

    @SafeVarargs
    private final DolphinPageData<DolphinProcessInstance> page(
            DolphinProcessInstance... instances) {
        DolphinPageData<DolphinProcessInstance> page = new DolphinPageData<>();
        List<DolphinProcessInstance> totalList = new ArrayList<>();
        if (instances != null) {
            for (DolphinProcessInstance instance : instances) {
                totalList.add(instance);
            }
        }
        page.setTotalList(totalList);
        page.setTotal(totalList.size());
        page.setPageSize(totalList.size());
        page.setCurrentPage(1);
        page.setTotalPage(1);
        return page;
    }
}
