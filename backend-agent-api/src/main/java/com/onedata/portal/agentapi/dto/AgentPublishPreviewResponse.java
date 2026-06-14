package com.onedata.portal.agentapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

/**
 * Wraps the platform publish-preview result and adds a one-time {@code previewToken}
 * that must be passed back to deploy/online (API-layer "preview first" guard).
 */
@Data
public class AgentPublishPreviewResponse {

    /** The platform WorkflowPublishPreviewResponse, serialized as-is. */
    private Object preview;

    @JsonProperty("preview_token")
    private String previewToken;

    @JsonProperty("can_publish")
    private boolean canPublish;
}
