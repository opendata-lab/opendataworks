package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 审批 deploy/online 等操作
 */
@Data
public class WorkflowApprovalRequest {

    private Boolean approved;

    private String approver;

    private String comment;
}
