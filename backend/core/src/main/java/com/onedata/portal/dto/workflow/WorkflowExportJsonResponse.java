package com.onedata.portal.dto.workflow;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 工作流 JSON 导出响应
 */
@Data
public class WorkflowExportJsonResponse {

    private String fileName;

    private String content;

    /**
     * 导出时检测到的血缘一致性问题。
     *
     * <p>只作提示，不阻断导出：导出是只读的诊断与备份手段，坏定义恰恰是最需要导出来排查的。
     */
    private List<WorkflowPublishRepairIssue> consistencyIssues = new ArrayList<>();
}
