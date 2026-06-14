package com.onedata.portal.agentapi.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.Map;

/**
 * Agent-facing workflow create/update payload. {@code workflow} mirrors the
 * platform WorkflowDefinitionRequest (workflowName, tasks, edges, globalParams,
 * ...) and is mapped by the backend implementation.
 */
@Data
public class AgentWorkflowUpsertRequest {

    @NotNull(message = "workflow 不能为空")
    private Map<String, Object> workflow;
}
