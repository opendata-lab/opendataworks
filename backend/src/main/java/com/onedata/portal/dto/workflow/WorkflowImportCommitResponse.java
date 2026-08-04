package com.onedata.portal.dto.workflow;

import lombok.Data;

/**
 * 工作流 JSON 导入提交结果
 */
@Data
public class WorkflowImportCommitResponse {

    private Long workflowId;

    private Long versionId;

    private Integer versionNo;

    private String workflowName;

    private Integer createdTaskCount;

    private String appliedRelationDecision;

    /**
     * 实际应用的运行态归属：ADOPT / RESET
     */
    private String appliedRuntimeBinding;
}
