package com.onedata.portal.agentapi.service;

import com.onedata.portal.agentapi.dto.AgentPublishPreviewResponse;
import com.onedata.portal.agentapi.dto.AgentPublishRequest;
import com.onedata.portal.agentapi.dto.AgentScheduleUpsertRequest;
import com.onedata.portal.agentapi.dto.AgentWorkflowUpsertRequest;

/**
 * Agent-facing workflow write API. Implemented in the backend module by
 * delegating to WorkflowService / WorkflowPublishService / WorkflowScheduleService.
 * Deploy/online and schedule-online require a valid preview token (API-layer
 * "preview first" guard).
 */
public interface AgentWorkflowService {

    Object createWorkflow(AgentWorkflowUpsertRequest request, String operator);

    Object updateWorkflow(Long workflowId, AgentWorkflowUpsertRequest request, String operator);

    Object getWorkflow(Long workflowId);

    Object listWorkflows(String keyword, String status, int limit);

    AgentPublishPreviewResponse previewPublish(Long workflowId);

    Object publish(Long workflowId, AgentPublishRequest request, String operator);

    Object upsertSchedule(Long workflowId, AgentScheduleUpsertRequest request, String operator);

    Object scheduleOnline(Long workflowId, String previewToken, String operator);

    Object scheduleOffline(Long workflowId, String operator);
}
