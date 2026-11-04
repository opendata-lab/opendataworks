package com.onedata.portal.dto;

import lombok.Data;


/**
 * DolphinScheduler 任务组选项
 */
@Data
public class DolphinTaskGroupOption {

    private Integer id;
    private Long projectCode;
    private String name;
    private String description;
    private Integer groupSize;
    private Integer useSize;
    private String status;
}
