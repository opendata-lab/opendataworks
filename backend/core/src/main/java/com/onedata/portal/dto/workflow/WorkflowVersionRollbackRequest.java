package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 版本回退请求
 */
@Data
public class WorkflowVersionRollbackRequest {

    private String operator;

    private String reason;
}
