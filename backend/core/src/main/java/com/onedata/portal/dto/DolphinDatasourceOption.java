package com.onedata.portal.dto;

import lombok.Data;


/**
 * DolphinScheduler 数据源选项
 */
@Data
public class DolphinDatasourceOption {

    private Long id;
    private String name;
    private String type;
    private String dbName;
    private String description;
}

