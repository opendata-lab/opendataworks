package com.onedata.portal.dto.dolphin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;


/**
 * DolphinScheduler schedule (timing) information.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DolphinSchedule {

    private Long id;
    private Long processDefinitionCode;
    private String startTime;
    private String endTime;
    private String timezoneId;
    private String crontab;
    private String failureStrategy;
    private String warningType;
    private Long warningGroupId;
    private String processInstancePriority;
    private String workerGroup;
    private String tenantCode;
    private Long environmentCode;
    private String releaseState;
}

