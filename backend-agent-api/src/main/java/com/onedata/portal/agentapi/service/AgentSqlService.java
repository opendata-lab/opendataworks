package com.onedata.portal.agentapi.service;

import com.onedata.portal.agentapi.dto.AgentSqlAnalyzeRequest;

/**
 * Agent-facing SQL analysis API. Implemented in the backend module by delegating
 * to DataQueryService.analyzeQuery.
 */
public interface AgentSqlService {

    Object analyze(AgentSqlAnalyzeRequest request);
}
