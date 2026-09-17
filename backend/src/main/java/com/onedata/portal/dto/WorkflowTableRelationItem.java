package com.onedata.portal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作流关联的表及其关系类型（read / write）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowTableRelationItem {

    private Long tableId;

    /** 关联类型: read | write */
    private String relationType;
}
