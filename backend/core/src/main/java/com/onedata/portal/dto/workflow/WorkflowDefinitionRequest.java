package com.onedata.portal.dto.workflow;

import java.util.List;
import lombok.Data;



/**
 * 工作流创建/更新请求
 */
@Data
public class WorkflowDefinitionRequest {

    private Long workflowId;

    private String workflowName;

    private String description;

    private String definitionJson;

    /**
     * 全局参数
     */
    private String globalParams;

    /**
     * 默认任务组（DolphinScheduler Task Group）
     */
    private String taskGroupName;

    private List<WorkflowTaskBinding> tasks;

    private String operator;

    private String triggerSource = "manual";

    private Long dolphinConfigId;

    private Long projectCode;
}
