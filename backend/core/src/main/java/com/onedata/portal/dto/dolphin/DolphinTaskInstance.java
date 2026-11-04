package com.onedata.portal.dto.dolphin;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;


/**
 * DolphinScheduler task instance information.
 *
 * <p>DolphinScheduler 3.2 uses process-instance field names while 3.4 uses
 * workflow-instance field names. Both variants are kept so callers can consume
 * either response without version-specific branching.</p>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DolphinTaskInstance {

    private Long id;
    private String name;
    private String taskType;
    private Long taskCode;
    private String state;
    private String startTime;
    private String endTime;
    private String host;
    private String logPath;
    private Integer retryTimes;
    private String executorName;
    private String duration;

    @JsonAlias("processInstanceId")
    private Long workflowInstanceId;

    @JsonAlias("processInstanceName")
    private String workflowInstanceName;
}
