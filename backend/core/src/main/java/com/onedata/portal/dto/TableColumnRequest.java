package com.onedata.portal.dto;

import lombok.Data;


/**
 * 表列定义请求
 */
@Data
public class TableColumnRequest {

    private String columnName;

    private String dataType;

    private String typeParams;

    private Boolean nullable;

    private Boolean primaryKey;

    private Boolean partitionColumn;

    private String defaultValue;

    private String comment;
}
