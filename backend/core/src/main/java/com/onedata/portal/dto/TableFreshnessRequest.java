package com.onedata.portal.dto;

import lombok.Data;


/**
 * 表级新鲜度契约保存请求。
 */
@Data
public class TableFreshnessRequest {

    /** column | custom_sql | partition | metadata */
    private String mode;

    private String loadedAtField;

    private String loadedAtQuery;

    private String partitionFormat;

    private String filterExpr;

    private Integer warnAfterCount;

    /** minute | hour | day */
    private String warnAfterPeriod;

    private Integer errorAfterCount;

    private String errorAfterPeriod;

    /** 显式关闭该表新鲜度检查时置 false，默认 true。 */
    private Boolean enabled = true;
}
