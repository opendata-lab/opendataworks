package com.onedata.portal.dto;

import lombok.Data;


/**
 * 表血缘节点信息
 */
@Data
public class TableLineageItem {

    private Long id;
    private String tableName;
    private String tableComment;
    private String layer;
    private String businessDomain;
    private String dataDomain;
}
