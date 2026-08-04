package com.onedata.portal.dto.workflow;

import lombok.Data;

/**
 * 导入时的 Dolphin 运行态归属结论
 */
@Data
public class WorkflowImportRuntimeBinding {

    /**
     * ADOPT：关联目标 Dolphin 中已有的运行态工作流
     * RESET：不关联，按全新工作流导入，发布时创建新的 Dolphin 定义
     */
    private String decision;

    private Long dolphinConfigId;

    private Long projectCode;

    /**
     * 关联的运行态工作流编码，RESET 时为空
     */
    private Long workflowCode;

    private String runtimeWorkflowName;

    private String releaseState;

    /**
     * 已占用该运行态的平台工作流，仅在冲突时填充
     */
    private Long conflictWorkflowId;

    private String conflictWorkflowName;

    /**
     * 面向用户的说明文案
     */
    private String message;
}
