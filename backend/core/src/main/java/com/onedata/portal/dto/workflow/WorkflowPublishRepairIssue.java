package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 发布预检中的可修复元数据问题
 */
@Data
public class WorkflowPublishRepairIssue {

    private String code;

    private String severity;

    private String message;

    private Boolean repairable = true;

    private String field;

    private Long taskCode;

    private String taskName;

    private String before;

    private String after;
}

