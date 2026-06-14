package com.onedata.portal.agentapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.dto.AgentTaskUpsertRequest;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.service.DataTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

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
        return dataTaskService.create(task, nullSafe(request.getInputTableIds()), nullSafe(request.getOutputTableIds()));
    }

    @Override
    public Object updateTask(Long taskId, AgentTaskUpsertRequest request, String operator) {
        DataTask task = toTask(request);
        task.setId(taskId);
        applyAuditDefaults(task, operator, false);
        return dataTaskService.update(task, nullSafe(request.getInputTableIds()), nullSafe(request.getOutputTableIds()));
    }

    @Override
    public Object getTask(Long taskId) {
        return dataTaskService.getById(taskId);
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

    private List<Long> nullSafe(List<Long> ids) {
        return ids == null ? Collections.emptyList() : ids;
    }
}
