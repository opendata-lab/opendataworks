package com.onedata.portal.dto.dolphin;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;


/**
 * DolphinScheduler project information.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class DolphinProject {

    private Long id;
    private Long code;
    private String name;
    private String description;
    private Integer userId;
    private String userName;
    private String createTime;
    private String updateTime;
}
