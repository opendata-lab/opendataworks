package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;


/**
 * 执行运行态同步请求
 */
@Data
public class RuntimeSyncExecuteRequest {

    private Long projectCode;

    private Long workflowCode;

    private String operator;

    /**
     * 声明关系与 SQL 推断关系不一致时，人工选择落盘轨道:
     * DECLARED / INFERRED
     */
    private String relationDecision;
}
