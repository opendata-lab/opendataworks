package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 工作流发布请求
 */
@Data
public class WorkflowPublishRequest {

    /**
     * deploy / online / offline
     */
    private String operation;

    private Long versionId;

    private String operator;

    /**
     * 是否需要审批，默认为 false
     */
    private Boolean requireApproval;

    /**
     * 当审批通过时由审批人赋值
     */
    private Boolean approved;

    private String approvalComment;

    /**
     * 发布前差异确认标记（仅 deploy 生效）
     */
    private Boolean confirmDiff;
}
