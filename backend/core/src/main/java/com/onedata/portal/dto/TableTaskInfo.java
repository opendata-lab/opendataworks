package com.onedata.portal.dto;

import java.time.LocalDateTime;
import lombok.Data;



/**
 * 表关联任务信息
 */
@Data
public class TableTaskInfo {

    private Long id;
    private String taskName;
    private String taskCode;
    private String relationType; // read, write
    private String status;
    private String engine;
    private String scheduleCron;
    private LocalDateTime lastExecuted;
    private String lastExecutionStatus;
}
