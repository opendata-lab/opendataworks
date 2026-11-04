package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 版本回退响应
 */
@Data
public class WorkflowVersionRollbackResponse {

    private Long workflowId;

    private Long newVersionId;

    private Integer newVersionNo;

    private Long rollbackFromVersionId;

    private Integer rollbackFromVersionNo;
}
