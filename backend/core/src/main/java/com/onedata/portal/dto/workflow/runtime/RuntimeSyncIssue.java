package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;


/**
 * 运行态同步问题描述
 */
@Data
public class RuntimeSyncIssue {

    /**
     * 失败/告警码
     */
    private String code;

    /**
     * ERROR / WARNING
     */
    private String severity;

    private String message;

    private Long workflowCode;

    private String workflowName;

    private Long taskCode;

    private String taskName;

    private String nodeType;

    private String rawName;

    public static RuntimeSyncIssue error(String code, String message) {
        RuntimeSyncIssue issue = new RuntimeSyncIssue();
        issue.setCode(code);
        issue.setSeverity("ERROR");
        issue.setMessage(message);
        return issue;
    }

    public static RuntimeSyncIssue warning(String code, String message) {
        RuntimeSyncIssue issue = new RuntimeSyncIssue();
        issue.setCode(code);
        issue.setSeverity("WARNING");
        issue.setMessage(message);
        return issue;
    }
}
