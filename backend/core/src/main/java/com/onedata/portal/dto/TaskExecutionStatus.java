package com.onedata.portal.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;



/**
 * 任务执行状态 DTO
 * 用于在任务列表中显示最近一次执行信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TaskExecutionStatus {

    /**
     * 任务ID
     */
    private Long taskId;

    /**
     * 执行ID
     */
    private String executionId;

    /**
     * 执行状态: pending, running, success, failed, killed
     */
    private String status;

    /**
     * 开始时间
     */
    private LocalDateTime startTime;

    /**
     * 结束时间
     */
    private LocalDateTime endTime;

    /**
     * 持续时间(秒)
     */
    private Integer durationSeconds;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 日志URL
     */
    private String logUrl;

    /**
     * 触发类型: manual, schedule, api
     */
    private String triggerType;

    /**
     * DolphinScheduler 工作流代码
     */
    private Long dolphinWorkflowCode;

    /**
     * DolphinScheduler 任务代码
     */
    private Long dolphinTaskCode;

    /**
     * DolphinScheduler 工作流定义 Web UI URL (用于跳转到工作流)
     */
    private String dolphinWorkflowUrl;

    /**
     * DolphinScheduler 任务定义 Web UI URL (用于跳转到任务)
     */
    private String dolphinTaskUrl;

    /**
     * DolphinScheduler 项目名称
     */
    private String dolphinProjectName;
}
