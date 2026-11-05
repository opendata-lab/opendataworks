package com.onedata.portal.dto.execution;

import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Data;



/**
 * Unified workflow-instance row used by the execution monitor.
 */
@Data
@Builder
public class WorkflowInstanceExecution {

    private Long workflowId;
    private String workflowName;
    private Long workflowCode;
    private Long instanceId;
    private Long localExecutionLogId;
    private String status;
    private String dolphinState;
    private String commandType;
    private String triggerType;
    private String source;
    private String executionSource;
    /** DolphinScheduler 运行实例上的调度日期，补数实例上表示补的是哪一个调度周期。 */
    private LocalDateTime scheduleTime;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer durationSeconds;
    private String errorMessage;
    private Boolean expandable;
    /** DolphinScheduler Web UI 实例详情深链，缺少配置时为 null。 */
    private String dolphinInstanceUrl;
}
