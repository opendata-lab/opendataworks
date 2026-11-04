package com.onedata.portal.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.onedata.portal.dto.execution.WorkflowExecutionPage;
import com.onedata.portal.service.WorkflowExecutionMonitorService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

@ExtendWith(MockitoExtension.class)
class TaskExecutionControllerTest {

    @Mock private WorkflowExecutionMonitorService workflowExecutionMonitorService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc =
                MockMvcBuilders.standaloneSetup(
                                new TaskExecutionController(workflowExecutionMonitorService))
                        .build();
    }

    @Test
    void workflowInstanceEndpointPassesFiltersAndReturnsUnifiedPage() throws Exception {
        WorkflowExecutionPage response =
                WorkflowExecutionPage.builder()
                        .total(0)
                        .pageNum(2)
                        .pageSize(20)
                        .records(Collections.emptyList())
                        .statistics(Collections.emptyMap())
                        .build();
        when(workflowExecutionMonitorService.listWorkflowInstances(10L, "failed", true, 2, 20))
                .thenReturn(response);

        mockMvc.perform(
                        get("/v1/executions/workflow-instances")
                                .param("workflowId", "10")
                                .param("status", "failed")
                                .param("refresh", "true")
                                .param("pageNum", "2")
                                .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageNum").value(2))
                .andExpect(jsonPath("$.data.statistics").isMap());

        verify(workflowExecutionMonitorService).listWorkflowInstances(10L, "failed", true, 2, 20);
    }
}
