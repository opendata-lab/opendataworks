package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 版本删除响应
 */
@Data
public class WorkflowVersionDeleteResponse {

    private Long workflowId;

    private Long deletedVersionId;

    private Integer deletedVersionNo;
}
