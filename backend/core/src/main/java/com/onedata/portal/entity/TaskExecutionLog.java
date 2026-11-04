package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;


/**
 * 任务执行日志实体
 */
@Data
@TableName("task_execution_log")
public class TaskExecutionLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long taskId;

    private String executionId;

    private String status; // pending, running, success, failed, killed

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private Integer durationSeconds;

    private Long rowsOutput;

    private String errorMessage;

    private String logUrl;

    private String triggerType; // manual, schedule, api

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    // DolphinScheduler 相关字段 (不存储到数据库,仅用于API响应)
    @TableField(exist = false)
    private Long workflowCode; // DolphinScheduler 工作流代码

    @TableField(exist = false)
    private String workflowName; // DolphinScheduler 工作流名称

    @TableField(exist = false)
    private Long taskCode; // DolphinScheduler 任务代码

    @TableField(exist = false)
    private String workflowInstanceUrl; // DolphinScheduler WebUI 工作流实例链接

    @TableField(exist = false)
    private String taskDefinitionUrl; // DolphinScheduler WebUI 任务定义链接

    @TableField(exist = false)
    private Long dolphinInstanceId; // DolphinScheduler 实例ID

    @TableField(exist = false)
    private Long workflowId; // 平台工作流ID

    @TableField(exist = false)
    private String executionSource; // dolphin, cache, local

    @TableField(exist = false)
    private String dolphinState; // DolphinScheduler 原始状态

    @TableField(exist = false)
    private String dolphinStartTime; // DolphinScheduler 开始时间 (原始格式)

    @TableField(exist = false)
    private String dolphinEndTime; // DolphinScheduler 结束时间 (原始格式)

    @TableField(exist = false)
    private Integer dolphinDuration; // DolphinScheduler 持续时间(秒)

    @TableField(exist = false)
    private Integer runTimes; // 运行次数

    @TableField(exist = false)
    private String host; // 执行主机

    @TableField(exist = false)
    private String commandType; // 命令类型

    @TableField(exist = false)
    private Object dolphinInstanceDetail; // DolphinScheduler 实例详情 (来自实时查询,包含完整JSON)
}
