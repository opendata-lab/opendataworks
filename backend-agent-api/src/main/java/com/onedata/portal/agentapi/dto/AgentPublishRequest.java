package com.onedata.portal.agentapi.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * Agent-facing workflow publish request. {@code operation} is deploy/online/offline.
 * {@code previewToken} is the one-time token returned by the publish preview and is
 * required for deploy/online so a publish can never run without a fresh preview.
 */
@Data
public class AgentPublishRequest {

    @NotBlank(message = "operation 不能为空")
    private String operation;

    private String previewToken;
}
