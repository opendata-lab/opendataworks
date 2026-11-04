package com.onedata.portal.dto.workflow.runtime;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 执行运行态同步响应
 */
@Data
public class RuntimeSyncExecuteResponse {

    private Boolean success = false;

    /**
     * 运行态定义采集模式（当前固定为 export_only）
     */
    private String ingestMode;

    private Long workflowId;

    private Integer versionNo;

    private Long syncRecordId;

    private List<RuntimeSyncIssue> warnings = new ArrayList<>();

    private List<RuntimeSyncIssue> errors = new ArrayList<>();

    private RuntimeDiffSummary diffSummary;

    /**
     * 声明关系与 SQL 推断关系是否不一致（不一致时要求用户选择轨道）
     */
    private Boolean relationDecisionRequired = false;

    /**
     * 声明关系与 SQL 推断关系比对详情
     */
    private RuntimeRelationCompareDetail relationCompareDetail;
}
