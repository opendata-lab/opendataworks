package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 工作流调度引擎切换请求
 */
@Data
public class WorkflowSchedulerEngineRequest {

    private Long dolphinConfigId;

    private String operator;
}
