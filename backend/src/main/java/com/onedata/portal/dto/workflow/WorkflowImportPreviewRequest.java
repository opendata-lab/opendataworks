package com.onedata.portal.dto.workflow;

import lombok.Data;

/**
 * 工作流 JSON 导入预检请求
 */
@Data
public class WorkflowImportPreviewRequest {

    /**
     * 导入来源：json / dolphin（默认 json）
     */
    private String sourceType;

    /**
     * 工作流定义 JSON 文本（Dolphin 导出或平台同构格式）
     */
    private String definitionJson;

    /**
     * Dolphin 项目编码（sourceType=dolphin 时生效）
     */
    private Long projectCode;

    /**
     * Dolphin 工作流编码（sourceType=dolphin 时必填）
     */
    private Long workflowCode;

    /**
     * 导入后工作流名称（可选，不传时自动取定义名称）
     */
    private String workflowName;

    /**
     * 目标 Dolphin 环境 id（必填）
     */
    private Long dolphinConfigId;

    /**
     * 关联的目标 Dolphin 运行态工作流编码；为空表示不关联，按全新工作流导入
     */
    private Long linkedWorkflowCode;

    private String operator;
}
