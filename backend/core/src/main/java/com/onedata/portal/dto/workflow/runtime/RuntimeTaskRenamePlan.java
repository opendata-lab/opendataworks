package com.onedata.portal.dto.workflow.runtime;

import lombok.Data;


/**
 * 任务重命名计划
 */
@Data
public class RuntimeTaskRenamePlan {

    private Long taskCode;

    private String originalName;

    private String resolvedName;

    private String reason;
}
