package com.onedata.portal.dto.dolphin;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;


/**
 * DolphinScheduler process instance information.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DolphinProcessInstance {

    private Long id;
    @JsonAlias("workflowDefinitionCode")
    private Long processDefinitionCode;
    @JsonAlias("workflowDefinitionName")
    private String processDefinitionName;
    @JsonAlias("workflowDefinitionVersion")
    private Integer processDefinitionVersion;
    private String state;
    private String startTime;
    private String endTime;
    private String runTimes;
    private String host;
    private String commandType;
    private String commandParam;
    private String duration;
    private String workerGroup;
    private String executorId;
    private String executorName;
    private String scheduleTime;
}
