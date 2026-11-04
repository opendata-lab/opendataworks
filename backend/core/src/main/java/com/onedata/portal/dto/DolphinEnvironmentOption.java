package com.onedata.portal.dto;

import java.util.List;
import lombok.Data;



/**
 * DolphinScheduler 环境选项
 */
@Data
public class DolphinEnvironmentOption {

    private Long code;

    private String name;

    private List<String> workerGroups;
}

