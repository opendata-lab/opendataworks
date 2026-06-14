package com.onedata.portal.agentapi.service;

import com.onedata.portal.agentapi.dto.AgentSqlAnalyzeRequest;
import com.onedata.portal.agentapi.scope.AgentDataScopeContext;
import com.onedata.portal.dto.SqlAnalyzeRequest;
import com.onedata.portal.service.DataQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Agent-facing SQL analysis, delegating to {@link DataQueryService#analyzeQuery}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BackendAgentSqlService implements AgentSqlService {

    private final DataQueryService dataQueryService;

    @Override
    public Object analyze(AgentSqlAnalyzeRequest request) {
        if (StringUtils.hasText(request.getDatabase())) {
            AgentDataScopeContext.requireDatabaseNameAllowed(request.getDatabase());
        }
        SqlAnalyzeRequest delegate = new SqlAnalyzeRequest();
        delegate.setSql(request.getSql());
        delegate.setDatabase(request.getDatabase());
        delegate.setClusterId(request.getClusterId());
        return dataQueryService.analyzeQuery(delegate);
    }
}
