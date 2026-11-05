package com.onedata.portal.dto;

import lombok.Data;


/**
 * 表名生成请求
 */
@Data
public class TableNameGenerateRequest {

    private String layer;

    private String businessDomain;

    private String dataDomain;

    private String customIdentifier;

    private String statisticsCycle;

    private String updateType;
}
