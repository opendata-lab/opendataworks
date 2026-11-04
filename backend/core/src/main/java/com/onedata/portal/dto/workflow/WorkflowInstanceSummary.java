package com.onedata.portal.dto.workflow;

import lombok.Builder;
import lombok.Data;


/**
 * Simplified DolphinScheduler workflow instance summary returned by OpenAPI calls.
 */
@Data
@Builder
public class WorkflowInstanceSummary {

    private Long instanceId;
    private Long workflowCode;
    private String state;
    private String commandType;
    private Long durationMs;
    private String startTime;
    private String endTime;
    /** DolphinScheduler 运行实例上的调度日期，补数实例上表示补的是哪一个调度周期。 */
    private String scheduleTime;
    private String rawJson;
}
