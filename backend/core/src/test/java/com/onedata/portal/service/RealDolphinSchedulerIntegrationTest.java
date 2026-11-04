package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.dolphin.DolphinProject;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.service.dolphin.DolphinOpenApiClient;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Real integration test for DolphinScheduler. Connects to a running DolphinScheduler instance. */
@Tag("integration")
@DisplayName("Real DolphinScheduler Integration Tests")
class RealDolphinSchedulerIntegrationTest {

    private DolphinSchedulerService service;
    private DolphinOpenApiClient openApiClient;

    // Configuration from environment to avoid hardcoding credentials
    private static final String SERVICE_URL = System.getenv("DS_BASE_URL");
    private static final String TOKEN = System.getenv("DS_TOKEN");
    private static final String PROJECT_NAME = System.getenv("DS_PROJECT_NAME");

    @BeforeEach
    void setUp() {
        Assumptions.assumeTrue(
                StringUtils.hasText(SERVICE_URL) && StringUtils.hasText(TOKEN),
                "Set DS_BASE_URL and DS_TOKEN to run real DolphinScheduler integration tests.");

        // Create config from env vars
        DolphinConfig config = new DolphinConfig();
        config.setUrl(SERVICE_URL);
        config.setToken(TOKEN);
        config.setProjectName(StringUtils.hasText(PROJECT_NAME) ? PROJECT_NAME : "opendataworks");
        config.setTenantCode("default");
        config.setWorkerGroup("default");
        config.setExecutionType("PARALLEL");
        config.setIsActive(true);

        // Mock config service
        DolphinConfigService configService = org.mockito.Mockito.mock(DolphinConfigService.class);
        org.mockito.Mockito.when(configService.getActiveConfig()).thenReturn(config);

        // Use real mapper/builder where possible or mocks
        ObjectMapper objectMapper = new ObjectMapper();
        RestClient.Builder builder = RestClient.builder();

        // Instantiate real client with mocked config service
        openApiClient = new DolphinOpenApiClient(objectMapper, builder);
        service = new DolphinSchedulerService(configService, objectMapper, openApiClient);
    }

    @Test
    @DisplayName("Test Connectivity and Project Info")
    void testConnectivity() {
        System.out.println("Connecting to DolphinScheduler at " + SERVICE_URL);

        try {
            Long projectCode = service.getProjectCode();
            System.out.println("Project Code for " + PROJECT_NAME + ": " + projectCode);

            DolphinProject project =
                    openApiClient.getProject(service.getConfig(null), PROJECT_NAME);
            if (project != null) {
                System.out.println(
                        "Found Project: " + project.getName() + ", Code: " + project.getCode());
                assertEquals(PROJECT_NAME, project.getName());
            } else {
                System.err.println(
                        "Project '"
                                + PROJECT_NAME
                                + "' not found. Ensure it exists in DolphinScheduler.");
            }

        } catch (Exception e) {
            fail("Failed to connect or retrieve project: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Test List Process Instances (Read Only)")
    void testListProcessInstances() {
        // This validates we can read from the API
        Long projectCode = service.getProjectCode();
        if (projectCode == null) {
            System.out.println("Skipping list instances test because project code not found.");
            return;
        }

        try {
            List<WorkflowInstanceSummary> summary = service.listWorkflowInstances(null, 10);
            System.out.println("Found " + summary.size() + " workflow instances.");
            assertNotNull(summary);
        } catch (Exception e) {
            fail("Failed to list process instances: " + e.getMessage());
        }
    }
}
