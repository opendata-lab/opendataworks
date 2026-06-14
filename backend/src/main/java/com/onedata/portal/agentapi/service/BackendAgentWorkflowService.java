package com.onedata.portal.agentapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.dto.AgentPublishPreviewResponse;
import com.onedata.portal.agentapi.dto.AgentPublishRequest;
import com.onedata.portal.agentapi.dto.AgentScheduleUpsertRequest;
import com.onedata.portal.agentapi.dto.AgentWorkflowUpsertRequest;
import com.onedata.portal.agentapi.scope.AgentDataScopeContext;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.dto.workflow.WorkflowScheduleRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.service.WorkflowPublishService;
import com.onedata.portal.service.WorkflowScheduleService;
import com.onedata.portal.service.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent-facing workflow write API, delegating to the existing workflow services.
 * Deploy/online and schedule-online require a valid preview token bound to the
 * workflow's current version, so a publish can never run without a fresh preview.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackendAgentWorkflowService implements AgentWorkflowService {

    private static final int MAX_LIMIT = 200;

    private final WorkflowService workflowService;
    private final WorkflowPublishService workflowPublishService;
    private final WorkflowScheduleService workflowScheduleService;
    private final AgentPreviewTokenSupport previewTokenSupport;
    private final ObjectMapper objectMapper;

    @Override
    public Object createWorkflow(AgentWorkflowUpsertRequest request, String operator) {
        validateWorkflowDataScope(request);
        WorkflowDefinitionRequest definition = toDefinition(request, operator);
        return workflowService.createWorkflow(definition);
    }

    @Override
    public Object updateWorkflow(Long workflowId, AgentWorkflowUpsertRequest request, String operator) {
        validateWorkflowDataScope(request);
        WorkflowDefinitionRequest definition = toDefinition(request, operator);
        return workflowService.updateWorkflow(workflowId, definition);
    }

    @Override
    public Object getWorkflow(Long workflowId) {
        return workflowService.getDetail(workflowId);
    }

    @Override
    public Object listWorkflows(String keyword, String status, int limit) {
        WorkflowQueryRequest query = new WorkflowQueryRequest();
        query.setPageNum(1);
        query.setPageSize(limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT));
        if (StringUtils.hasText(keyword)) {
            query.setKeyword(keyword);
        }
        if (StringUtils.hasText(status)) {
            query.setStatus(status);
        }
        return workflowService.list(query);
    }

    @Override
    public AgentPublishPreviewResponse previewPublish(Long workflowId) {
        Object preview = workflowPublishService.previewPublish(workflowId);
        AgentPublishPreviewResponse response = new AgentPublishPreviewResponse();
        response.setPreview(preview);
        response.setPreviewToken(previewTokenSupport.issue(workflowId, currentVersionId(workflowId)));
        response.setCanPublish(readCanPublish(preview));
        return response;
    }

    @Override
    public Object publish(Long workflowId, AgentPublishRequest request, String operator) {
        String operation = normalize(request.getOperation());
        if (requiresPreviewToken(operation)) {
            previewTokenSupport.verify(workflowId, currentVersionId(workflowId), request.getPreviewToken());
        }
        WorkflowPublishRequest publishRequest = new WorkflowPublishRequest();
        publishRequest.setOperation(operation);
        publishRequest.setOperator(operator);
        publishRequest.setConfirmDiff(Boolean.TRUE);
        return workflowPublishService.publish(workflowId, publishRequest);
    }

    @Override
    public Object upsertSchedule(Long workflowId, AgentScheduleUpsertRequest request, String operator) {
        WorkflowScheduleRequest scheduleRequest =
                objectMapper.convertValue(request.getSchedule(), WorkflowScheduleRequest.class);
        return workflowScheduleService.upsertSchedule(workflowId, scheduleRequest);
    }

    @Override
    public Object scheduleOnline(Long workflowId, String previewToken, String operator) {
        previewTokenSupport.verify(workflowId, currentVersionId(workflowId), previewToken);
        return workflowScheduleService.onlineSchedule(workflowId);
    }

    @Override
    public Object scheduleOffline(Long workflowId, String operator) {
        return workflowScheduleService.offlineSchedule(workflowId);
    }

    @SuppressWarnings("unchecked")
    private void validateWorkflowDataScope(AgentWorkflowUpsertRequest request) {
        if (!AgentDataScopeContext.isActive() || request.getWorkflow() == null) {
            return;
        }
        Object tasks = request.getWorkflow().get("tasks");
        if (!(tasks instanceof List)) {
            return;
        }
        for (Object item : (List<?>) tasks) {
            if (!(item instanceof Map)) {
                continue;
            }
            Object ds = ((Map<String, Object>) item).get("datasourceName");
            if (ds instanceof String && StringUtils.hasText((String) ds)) {
                AgentDataScopeContext.requireDatabaseNameAllowed((String) ds);
            }
        }
    }

    private WorkflowDefinitionRequest toDefinition(AgentWorkflowUpsertRequest request, String operator) {
        WorkflowDefinitionRequest definition =
                objectMapper.convertValue(request.getWorkflow(), WorkflowDefinitionRequest.class);
        if (StringUtils.hasText(operator)) {
            definition.setOperator(operator);
        }
        return definition;
    }

    private Long currentVersionId(Long workflowId) {
        WorkflowDetailResponse detail = workflowService.getDetail(workflowId);
        DataWorkflow workflow = detail == null ? null : detail.getWorkflow();
        return workflow == null ? null : workflow.getCurrentVersionId();
    }

    private boolean requiresPreviewToken(String operation) {
        return "deploy".equals(operation) || "online".equals(operation);
    }

    private boolean readCanPublish(Object preview) {
        try {
            return Boolean.TRUE.equals(objectMapper.convertValue(preview, java.util.Map.class).get("canPublish"));
        } catch (Exception e) {
            return false;
        }
    }

    private String normalize(String operation) {
        return operation == null ? "" : operation.trim().toLowerCase(Locale.ROOT);
    }
}
