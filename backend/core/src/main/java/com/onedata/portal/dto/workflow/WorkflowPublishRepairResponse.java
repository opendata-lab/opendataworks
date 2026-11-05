package com.onedata.portal.dto.workflow;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 发布元数据修复响应
 */
@Data
public class WorkflowPublishRepairResponse {

    private Long workflowId;

    private Long workflowCode;

    private Boolean repaired = false;

    private Integer updatedTaskCount = 0;

    private List<String> updatedWorkflowFields = new ArrayList<>();
}

