package com.onedata.portal.agentapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.dto.AgentPublishRequest;
import com.onedata.portal.agentapi.service.AgentPreviewTokenSupport;
import com.onedata.portal.agentapi.service.BackendAgentWorkflowService;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.service.WorkflowPublishService;
import com.onedata.portal.service.WorkflowScheduleService;
import com.onedata.portal.service.WorkflowService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackendAgentWorkflowServiceTest {

    @Mock
    private WorkflowService workflowService;
    @Mock
    private WorkflowPublishService workflowPublishService;
    @Mock
    private WorkflowScheduleService workflowScheduleService;
    @Mock
    private AgentPreviewTokenSupport previewTokenSupport;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private BackendAgentWorkflowService service() {
        return new BackendAgentWorkflowService(
                workflowService, workflowPublishService, workflowScheduleService,
                previewTokenSupport, objectMapper);
    }

    private void stubCurrentVersion(long workflowId, Long versionId) {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setCurrentVersionId(versionId);
        when(workflowService.getDetail(workflowId))
                .thenReturn(WorkflowDetailResponse.builder().workflow(workflow).build());
    }

    @Test
    void deployVerifiesPreviewTokenBeforePublishing() {
        BackendAgentWorkflowService svc = service();
        stubCurrentVersion(12L, 5L);
        AgentPublishRequest req = new AgentPublishRequest();
        req.setOperation("deploy");
        req.setPreviewToken("tok");

        svc.publish(12L, req, "agent:topic-1");

        verify(previewTokenSupport).verify(eq(12L), eq(5L), eq("tok"));
        verify(workflowPublishService).publish(eq(12L), any(WorkflowPublishRequest.class));
    }

    @Test
    void deployWithInvalidTokenDoesNotPublish() {
        BackendAgentWorkflowService svc = service();
        stubCurrentVersion(12L, 5L);
        org.mockito.Mockito.doThrow(new IllegalArgumentException("bad token"))
                .when(previewTokenSupport).verify(eq(12L), eq(5L), any());
        AgentPublishRequest req = new AgentPublishRequest();
        req.setOperation("online");
        req.setPreviewToken("bad");

        assertThrows(IllegalArgumentException.class, () -> svc.publish(12L, req, "agent:t"));
        verify(workflowPublishService, never()).publish(any(), any());
    }

    @Test
    void offlineDoesNotRequirePreviewToken() {
        BackendAgentWorkflowService svc = service();
        AgentPublishRequest req = new AgentPublishRequest();
        req.setOperation("offline");

        svc.publish(12L, req, "agent:t");

        verify(previewTokenSupport, never()).verify(any(Long.class), any(), any());
        verify(workflowPublishService).publish(eq(12L), any(WorkflowPublishRequest.class));
    }
}
