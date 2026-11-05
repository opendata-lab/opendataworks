package com.onedata.portal.dto.workflow.runtime;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 运行态同步预检响应
 */
@Data
public class RuntimeSyncPreviewResponse {

    private Boolean canSync = false;

    /**
     * 运行态定义采集模式（当前固定为 export_only）
     */
    private String ingestMode;

    private List<RuntimeSyncIssue> errors = new ArrayList<>();

    private List<RuntimeSyncIssue> warnings = new ArrayList<>();

    private RuntimeDiffSummary diffSummary;

    private List<RuntimeTaskRenamePlan> renamePlan = new ArrayList<>();

    /**
     * 声明关系与 SQL 推断关系是否不一致（不一致时要求用户选择轨道）
     */
    private Boolean relationDecisionRequired = false;

    /**
     * 声明关系与 SQL 推断关系比对详情
     */
    private RuntimeRelationCompareDetail relationCompareDetail;
}
