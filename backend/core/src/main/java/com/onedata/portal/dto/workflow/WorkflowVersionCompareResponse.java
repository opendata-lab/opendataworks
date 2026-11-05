package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 版本比对响应
 */
@Data
public class WorkflowVersionCompareResponse {

    private Long leftVersionId;

    private Integer leftVersionNo;

    private Long rightVersionId;

    private Integer rightVersionNo;

    private Boolean changed = false;

    private WorkflowVersionDiffSection added = new WorkflowVersionDiffSection();

    private WorkflowVersionDiffSection removed = new WorkflowVersionDiffSection();

    private WorkflowVersionDiffSection modified = new WorkflowVersionDiffSection();

    private WorkflowVersionDiffSection unchanged = new WorkflowVersionDiffSection();

    private WorkflowVersionDiffSummary summary = new WorkflowVersionDiffSummary();

    /**
     * 原始快照文本差异（unified diff）
     */
    private String rawDiff;
}
