package com.onedata.portal.dto.workflow.runtime;

import java.time.LocalDateTime;
import lombok.Data;



/**
 * 同步记录详情
 */
@Data
public class RuntimeSyncRecordDetailResponse {

    private Long id;

    private Long workflowId;

    private Long projectCode;

    private Long workflowCode;

    private Long versionId;

    private String status;

    /**
     * 运行态定义采集模式（当前固定为 export_only）
     */
    private String ingestMode;

    /**
     * 原始运行态定义JSON（导出结果）
     */
    private String rawDefinitionJson;

    private String snapshotHash;

    private String snapshotJson;

    private RuntimeDiffSummary diffSummary;

    private String errorCode;

    private String errorMessage;

    private String operator;

    private LocalDateTime createdAt;
}
