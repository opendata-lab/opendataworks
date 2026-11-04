package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * DolphinScheduler 工作流补数（COMPLEMENT_DATA）请求参数
 */
@Data
public class WorkflowBackfillRequest {

    /**
     * range / list
     */
    private String mode;

    /**
     * 当 mode=range 时必填，格式建议为 yyyy-MM-dd HH:mm:ss
     */
    private String startTime;

    /**
     * 当 mode=range 时必填，格式建议为 yyyy-MM-dd HH:mm:ss
     */
    private String endTime;

    /**
     * 当 mode=list 时必填，多个时间以逗号分隔，格式建议为 yyyy-MM-dd HH:mm:ss
     */
    private String scheduleDateList;

    /**
     * RUN_MODE_SERIAL / RUN_MODE_PARALLEL
     */
    private String runMode;

    /**
     * 并行补数期望并发数（runMode=RUN_MODE_PARALLEL 时可选）
     */
    private Integer expectedParallelismNumber;

    /**
     * OFF_MODE / ALL_DEPENDENT
     */
    private String complementDependentMode;

    /**
     * 是否全层级依赖（与 complementDependentMode 配合）
     */
    private Boolean allLevelDependent;

    /**
     * DESC_ORDER / ASC_ORDER
     */
    private String executionOrder;

    /**
     * CONTINUE / END
     */
    private String failureStrategy;
}

