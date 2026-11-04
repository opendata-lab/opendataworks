package com.onedata.portal.dto.workflow;

import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeSyncIssue;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 工作流发布前差异预检响应
 */
@Data
public class WorkflowPublishPreviewResponse {

    private Long workflowId;

    private Long projectCode;

    private Long workflowCode;

    private Boolean canPublish = true;

    private Boolean requireConfirm = false;

    private RuntimeDiffSummary diffSummary;

    private List<WorkflowPublishRepairIssue> repairIssues = new ArrayList<>();

    private List<RuntimeSyncIssue> warnings = new ArrayList<>();

    private List<RuntimeSyncIssue> errors = new ArrayList<>();
}
