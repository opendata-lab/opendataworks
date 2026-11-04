package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;


/**
 * 运行态同步预检请求
 */
@Data
public class RuntimeSyncPreviewRequest {

    private Long projectCode;

    private Long workflowCode;

    private String operator;
}
