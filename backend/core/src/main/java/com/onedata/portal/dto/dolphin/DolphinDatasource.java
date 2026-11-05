package com.onedata.portal.dto.dolphin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;


/**
 * DolphinScheduler datasource information.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DolphinDatasource {

    private Long id;
    private String name;
    private String type;
    private String note;
    private Integer userId;
    private String userName;
    private String connectionParams;
    private String createTime;
    private String updateTime;

    // Parsed from connectionParams
    private String database;
    private String host;
    private Integer port;
}
