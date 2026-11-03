package com.onedata.portal.agentapi.config;

import com.onedata.portal.agentapi.controller.*;
import com.onedata.portal.agentapi.dto.*;
import com.onedata.portal.agentapi.identity.AgentIdentityContext;
import com.onedata.portal.agentapi.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AgentControllerAuditIdentityTest {
    private final AgentWorkflowService workflows = mock(AgentWorkflowService.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final AgentTableService tables = mock(AgentTableService.class);
    private final AgentMetadataService metadata = mock(AgentMetadataService.class);

    @BeforeEach void authenticatedService() {
        AgentIdentityContext.set(null, "19");
    }

    @AfterEach void clearIdentity() {
        AgentIdentityContext.clear();
    }

    @Test void auditParametersUseAuthenticatedServiceAndSchedulesNeedNoUnusedOperator() {
        AgentWorkflowController workflow = new AgentWorkflowController(workflows);
        AgentWorkflowUpsertRequest definition = new AgentWorkflowUpsertRequest();
        AgentPublishRequest publish = new AgentPublishRequest();
        AgentScheduleUpsertRequest schedule = new AgentScheduleUpsertRequest();
        AgentScheduleOnlineRequest online = new AgentScheduleOnlineRequest();
        online.setPreviewToken("preview-token");
        workflow.create(definition);
        workflow.update(1L, definition);
        workflow.publish(1L, publish);
        workflow.upsertSchedule(1L, schedule);
        workflow.scheduleOnline(1L, online);
        workflow.scheduleOffline(1L);
        verify(workflows).createWorkflow(definition, "19");
        verify(workflows).updateWorkflow(1L, definition, "19");
        verify(workflows).publish(1L, publish, "19");
        verify(workflows).upsertSchedule(1L, schedule);
        verify(workflows).scheduleOnline(1L, "preview-token");
        verify(workflows).scheduleOffline(1L);

        AgentTaskController task = new AgentTaskController(tasks);
        AgentTaskUpsertRequest taskRequest = new AgentTaskUpsertRequest();
        task.create(taskRequest);
        task.update(2L, taskRequest);
        verify(tasks).createTask(taskRequest, "19");
        verify(tasks).updateTask(2L, taskRequest, "19");

        AgentTableController table = new AgentTableController(tables);
        AgentTableCreateRequest tableRequest = new AgentTableCreateRequest();
        table.preview(tableRequest);
        table.create(tableRequest);
        verify(tables).previewCreateTable(tableRequest, "19");
        verify(tables).createTable(tableRequest, "19");

        AgentMetadataCompleteRequest complete = new AgentMetadataCompleteRequest();
        new AgentMetadataController(metadata).complete(complete);
        verify(metadata).complete(complete, "19");
    }

    @Test void legacyHttpHeadersCannotOverrideControllerAuditIdentity() throws Exception {
        // Isolate MVC binding here; the production interceptor still rejects these writes with 403.
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AgentMetadataController(metadata),
                new AgentWorkflowController(workflows)).build();
        mvc.perform(post("/v1/ai/metadata/complete")
                .header("X-Agent-Operator", "admin").header("X-Agent-Id", "forged")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/ai/workflow/1/schedule/offline")
                .header("X-Agent-Operator", "admin").header("X-Agent-Id", "forged"))
                .andExpect(status().isOk());
        verify(metadata).complete(any(), eq("19"));
        verify(workflows).scheduleOffline(1L);
    }

    @Test void missingAuthenticatedContextNeverInventsAnAuditIdentity() {
        AgentIdentityContext.clear();
        assertThrows(IllegalArgumentException.class,
                () -> new AgentTaskController(tasks).create(new AgentTaskUpsertRequest()));
        verifyNoInteractions(tasks);
    }
}
