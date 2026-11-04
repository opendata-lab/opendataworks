package com.onedata.portal.dto.dolphin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;


/**
 * DolphinScheduler task group information.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DolphinTaskGroup {

    private Integer id;
    private Long projectCode;
    private String name;
    private String description;
    private Integer groupSize;
    private Integer useSize;
    private String status;
    private String createTime;
    private String updateTime;
}
