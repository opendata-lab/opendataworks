package com.onedata.portal.agentapi.dto;

import lombok.Data;

import javax.validation.constraints.NotNull;
import java.util.Map;

/**
 * Agent-facing schedule upsert payload. {@code schedule} mirrors the platform
 * WorkflowScheduleRequest fields (scheduleCron, scheduleTimezone, ...).
 */
@Data
public class AgentScheduleUpsertRequest {

    @NotNull(message = "schedule 不能为空")
    private Map<String, Object> schedule;
}
