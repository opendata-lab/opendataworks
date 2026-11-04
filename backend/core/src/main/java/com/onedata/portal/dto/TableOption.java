package com.onedata.portal.dto;

import lombok.Data;


/**
 * 表下拉选项数据
 */
@Data
public class TableOption {

    private Long id;
    private Long clusterId;
    private String clusterName;
    private String sourceType;
    private String tableName;
    private String tableComment;
    private String layer;
    private String dbName;
    private String qualifiedName;
}
