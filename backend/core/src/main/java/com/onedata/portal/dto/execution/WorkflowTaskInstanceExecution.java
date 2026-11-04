package com.onedata.portal.dto.execution;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;



/**
 * Task-instance row displayed when a workflow execution is expanded.
 */
@Data
@Builder
public class WorkflowTaskInstanceExecution {

    private Long platformTaskId;
    private Long dolphinTaskCode;
    private Long taskInstanceId;
    private String taskName;
    private String taskType;
    private String status;
    private String dolphinState;
    private String host;
    private Integer retryTimes;
    private String executorName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationSeconds;
}
