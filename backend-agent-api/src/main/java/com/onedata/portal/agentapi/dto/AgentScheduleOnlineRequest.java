package com.onedata.portal.agentapi.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/**
 * Bringing a schedule online is high-risk and requires a preview token, like publish.
 */
@Data
public class AgentScheduleOnlineRequest {

    @NotBlank(message = "previewToken 不能为空")
    private String previewToken;
}
