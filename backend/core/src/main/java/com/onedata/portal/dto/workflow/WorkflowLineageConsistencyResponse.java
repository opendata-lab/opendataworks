package com.onedata.portal.dto.workflow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;



/**
 * 工作流血缘一致性只读报告。
 *
 * <p>用于在切换 {@code workflow.lineage-consistency.enforcement-mode} 之前扫描存量。
 */
@Data
public class WorkflowLineageConsistencyResponse {

    private Long workflowId;

    private String workflowName;

    /**
     * 当前生效的强制模式：{@code warn} 或 {@code block-missing}。
     */
    private String enforcementMode;

    /**
     * 当前模式下是否存在会阻断发布的问题。
     */
    private Boolean blocking = false;

    /**
     * 按问题 code 分类计数。
     */
    private Map<String, Integer> counts = new LinkedHashMap<>();

    private List<WorkflowPublishRepairIssue> issues = new ArrayList<>();
}
