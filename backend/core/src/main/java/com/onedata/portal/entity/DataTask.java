package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;


/**
 * 任务定义实体
 */
@Data
@TableName("data_task")
public class DataTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String taskName;

    private String taskCode;

    private String taskType; // batch, stream

    private String engine; // dolphin, dinky

    private String dolphinNodeType; // SHELL, SQL, PYTHON, SPARK, FLINK

    private String datasourceName; // datasource name for SQL node

    private String datasourceType; // datasource type: MYSQL, DORIS, etc.

    private String taskGroupName; // Dolphin task group name (null = inherit workflow default)

    // DataX-specific fields
    private String targetDatasourceName; // target datasource for DataX

    private String sourceTable; // source table name for DataX

    private String targetTable; // target table name for DataX

    private String columnMapping; // column mapping config for DataX (optional JSON)

    // clusterId and database removed during rollback

    @TableField("task_sql")
    private String taskSql;

    private String taskDesc;

    private String scheduleCron;

    private Integer priority;

    private Integer timeoutSeconds;

    private Integer retryTimes;

    private Integer retryInterval;

    private String owner;

    private String status; // draft, published, running, paused, failed

    private Long dolphinProcessCode;

    private Long dolphinScheduleId;

    private Long dolphinTaskCode;

    private Integer dolphinTaskVersion;

    private String dolphinFlag;

    private Long dinkyJobId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    @TableField(exist = false)
    private Long workflowId;

    @TableField(exist = false)
    private String workflowName;

    @TableField(exist = false)
    private Integer upstreamTaskCount;

    @TableField(exist = false)
    private Integer downstreamTaskCount;

    @TableField(exist = false)
    private com.onedata.portal.dto.TaskExecutionStatus executionStatus;
}
