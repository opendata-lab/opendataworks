package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 工作流列表查询参数
 */
@Data
public class WorkflowQueryRequest {

    private Integer pageNum = 1;

    private Integer pageSize = 20;

    private String keyword;

    private String status;
}
