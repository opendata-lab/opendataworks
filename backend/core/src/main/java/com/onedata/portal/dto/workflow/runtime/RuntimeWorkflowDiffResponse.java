package com.onedata.portal.dto.workflow.runtime;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 已同步工作流的运行态差异响应
 */
@Data
public class RuntimeWorkflowDiffResponse {

    private Long workflowId;

    private Long projectCode;

    private Long workflowCode;

    private RuntimeDiffSummary diffSummary;

    private List<RuntimeSyncIssue> warnings = new ArrayList<>();

    private List<RuntimeSyncIssue> errors = new ArrayList<>();
}
