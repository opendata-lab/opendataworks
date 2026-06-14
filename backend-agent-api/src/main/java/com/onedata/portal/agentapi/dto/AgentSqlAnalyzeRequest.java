package com.onedata.portal.agentapi.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * Agent-facing SQL analysis request: input/output tables, operation type, and
 * risk warnings (used for SQL polish and lineage suggestion).
 */
@Data
public class AgentSqlAnalyzeRequest {

    @NotBlank(message = "sql 不能为空")
    private String sql;

    private String database;

    private Long clusterId;
}
