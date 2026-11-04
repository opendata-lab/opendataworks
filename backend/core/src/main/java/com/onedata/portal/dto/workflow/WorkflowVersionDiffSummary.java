package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 版本差异摘要
 */
@Data
public class WorkflowVersionDiffSummary {

    private Integer added = 0;

    private Integer removed = 0;

    private Integer modified = 0;

    private Integer unchanged = 0;
}
