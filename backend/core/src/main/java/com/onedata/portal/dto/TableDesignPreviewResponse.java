package com.onedata.portal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * 表设计预览响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableDesignPreviewResponse {

    private String tableName;

    private String dorisDdl;
}
