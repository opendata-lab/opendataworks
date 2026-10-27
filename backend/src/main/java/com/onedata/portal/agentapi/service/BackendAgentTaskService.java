package com.onedata.portal.agentapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.dto.AgentTaskUpsertRequest;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.exception.BusinessException;
import com.onedata.portal.service.DataTaskService;
import com.onedata.portal.service.lineage.LineageValidationMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent-facing task write API, delegating to {@link DataTaskService}. Tasks are
 * created as drafts; the X-Agent-Operator identity is recorded as the owner.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackendAgentTaskService implements AgentTaskService {

    private static final int MAX_LIMIT = 200;

    private final DataTaskService dataTaskService;
    private final ObjectMapper objectMapper;

    @Override
    public Object createTask(AgentTaskUpsertRequest request, String operator) {
        DataTask task = toTask(request);
        applyAuditDefaults(task, operator, true);
        return dataTaskService.create(
                task,
                request.getInputTableIds(),
                request.getOutputTableIds(),
                LineageValidationMode.STRICT);
    }

    @Override
    public Object updateTask(Long taskId, AgentTaskUpsertRequest request, String operator) {
        DataTask task = toTask(request);
        task.setId(taskId);
        // DataTask.workflowId is a relation-management field, not a persisted
        // column: DataTaskService.update() treats a null value as "detach the
        // task from its workflow". The agent write contract only documents task
        // fields (see opendataworks-data-dev skill), so agent payloads never
        // include workflowId; without this, every agent-driven update silently
        // dropped the task's workflow binding. Preserve the current binding
        // unless the caller explicitly sets workflowId (including to null).
        if (!request.getTask().containsKey("workflowId")) {
            DataTask existing = dataTaskService.getById(taskId);
            if (existing != null) {
                task.setWorkflowId(existing.getWorkflowId());
            }
        }
        applyAuditDefaults(task, operator, false);
        // 血缘列表原样下传，不做 null -> emptyList 转换：
        // null 表示"本次未提供该侧血缘，保留原值"，一旦转成空列表就等于静默清空。
        return dataTaskService.update(
                task,
                request.getInputTableIds(),
                request.getOutputTableIds(),
                LineageValidationMode.STRICT);
    }

    @Override
    public Object getTask(Long taskId) {
        DataTask task = dataTaskService.getById(taskId);
        if (task == null) {
            // A missing task would otherwise serialize as a null body (empty
            // response), which the portal MCP client rejects as "not valid JSON".
            // Throw so the global handler returns a proper JSON error instead.
            throw new BusinessException("任务不存在: " + taskId);
        }
        return task;
    }

    @Override
    public Object listTasks(String keyword, String status, int limit) {
        int pageSize = limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
        return dataTaskService.list(1, pageSize, null, null,
                StringUtils.hasText(status) ? status : null,
                StringUtils.hasText(keyword) ? keyword : null,
                null, null, null);
    }

    private DataTask toTask(AgentTaskUpsertRequest request) {
        return objectMapper.convertValue(request.getTask(), DataTask.class);
    }

    private void applyAuditDefaults(DataTask task, String operator, boolean creating) {
        if (StringUtils.hasText(operator)) {
            task.setOwner(operator);
        }
        if (creating && !StringUtils.hasText(task.getStatus())) {
            task.setStatus("draft");
        }
    }


}
