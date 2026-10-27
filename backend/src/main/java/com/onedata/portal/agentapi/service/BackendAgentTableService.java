package com.onedata.portal.agentapi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.dto.AgentTableCreateRequest;
import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.service.TableCreateService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent-facing table-creation API, delegating to the engine-aware
 * {@link TableCreateService}. Preview generates the target table name and
 * normalized DDL without side effects; create persists metadata and executes the
 * DDL on the engine (Doris). The X-Agent-Operator identity is recorded as the
 * table owner, and the target database is checked against the agent data scope.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackendAgentTableService implements AgentTableService {

    private final TableCreateService tableCreateService;
    private final ObjectMapper objectMapper;

    @Override
    public Object previewCreateTable(AgentTableCreateRequest request, String operator) {
        return tableCreateService.preview(toDomain(request, operator));
    }

    @Override
    public Object createTable(AgentTableCreateRequest request, String operator) {
        return tableCreateService.create(toDomain(request, operator));
    }

    private TableCreateRequest toDomain(AgentTableCreateRequest request, String operator) {
        TableCreateRequest domain = objectMapper.convertValue(request, TableCreateRequest.class);

        if (StringUtils.hasText(operator) && !StringUtils.hasText(domain.getOwner())) {
            domain.setOwner(operator);
        }
        return domain;
    }
}
