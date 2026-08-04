package com.onedata.portal.dto.workflow;

import com.onedata.portal.dto.workflow.runtime.RuntimeRelationCompareDetail;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流 JSON 导入预检结果
 */
@Data
public class WorkflowImportPreviewResponse {

    private Boolean canImport = false;

    private String workflowName;

    private Integer taskCount = 0;

    private Boolean relationDecisionRequired = false;

    /**
     * 推荐值：DECLARED / INFERRED
     */
    private String suggestedRelationDecision;

    private RuntimeRelationCompareDetail relationCompareDetail;

    private List<String> errors = new ArrayList<>();

    private List<String> warnings = new ArrayList<>();

    /**
     * 规范化后的平台同构 JSON（默认使用 SQL 推断关系）
     */
    private String normalizedJson;

    /**
     * 本次导入的 Dolphin 运行态归属结论
     */
    private WorkflowImportRuntimeBinding runtimeBinding;
}
