package com.onedata.portal.agentapi.service;

import com.onedata.portal.agentapi.dto.AgentTaskUpsertRequest;

/**
 * Agent-facing data task write API. Implemented in the backend module by
 * delegating to the existing DataTaskService; agent-api stays free of business
 * logic. {@code operator} is the audit identity carried by the X-Agent-Operator
 * header.
 */
public interface AgentTaskService {

    Object createTask(AgentTaskUpsertRequest request, String operator);

    Object updateTask(Long taskId, AgentTaskUpsertRequest request, String operator);

    Object getTask(Long taskId);

    Object listTasks(String keyword, String status, int limit);
}
