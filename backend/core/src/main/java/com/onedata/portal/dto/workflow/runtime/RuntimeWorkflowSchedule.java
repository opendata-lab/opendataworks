package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;


/**
 * Dolphin 运行态调度信息
 */
@Data
public class RuntimeWorkflowSchedule {

    private Long scheduleId;

    private String releaseState;

    private String crontab;

    private String timezoneId;

    private String startTime;

    private String endTime;

    private String failureStrategy;

    private String warningType;

    private Long warningGroupId;

    private String processInstancePriority;

    private String workerGroup;

    private String tenantCode;

    private Long environmentCode;
}
