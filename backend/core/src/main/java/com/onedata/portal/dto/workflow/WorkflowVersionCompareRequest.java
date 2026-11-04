package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 版本比对请求
 */
@Data
public class WorkflowVersionCompareRequest {

    /**
     * 左侧基线版本ID，可为空（空基线）
     */
    private Long leftVersionId;

    /**
     * 右侧目标版本ID，必填
     */
    private Long rightVersionId;

    private String operator;
}
