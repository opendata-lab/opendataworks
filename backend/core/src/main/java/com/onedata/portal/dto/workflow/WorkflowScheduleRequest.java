package com.onedata.portal.dto.workflow;

import lombok.Data;


/**
 * 工作流定时调度配置请求
 *
 * <p>
 * 对齐 DolphinScheduler Schedule 的核心参数：
 * schedule JSON: startTime/endTime/timezoneId/crontab
 * 以及 form 参数：warningType/failureStrategy/warningGroupId
 * </p>
 */
@Data
public class WorkflowScheduleRequest {

    /**
     * Quartz CRON 表达式（7 段），例如：0 0 * * * ? *
     */
    private String scheduleCron;

    /**
     * 时区 ID，例如：Asia/Shanghai
     */
    private String scheduleTimezone;

    /**
     * 开始时间，建议格式：yyyy-MM-dd HH:mm:ss
     */
    private String scheduleStartTime;

    /**
     * 结束时间，建议格式：yyyy-MM-dd HH:mm:ss
     */
    private String scheduleEndTime;

    /**
     * 失败策略：CONTINUE / END
     */
    private String scheduleFailureStrategy;

    /**
     * 告警类型：NONE / SUCCESS / FAILURE / SUCCESS_FAILURE
     */
    private String scheduleWarningType;

    /**
     * 告警组 ID（可选，默认 0）
     */
    private Long scheduleWarningGroupId;

    /**
     * 流程实例优先级：HIGHEST / HIGH / MEDIUM / LOW / LOWEST
     */
    private String scheduleProcessInstancePriority;

    /**
     * Worker 分组（可选，默认使用 Dolphin 配置中的 workerGroup）
     */
    private String scheduleWorkerGroup;

    /**
     * 租户编码（可选，默认使用 Dolphin 配置中的 tenantCode）
     */
    private String scheduleTenantCode;

    /**
     * 环境编码（可选，-1 表示默认/不指定）
     */
    private Long scheduleEnvironmentCode;

    /**
     * 工作流上线后是否自动上线调度
     */
    private Boolean scheduleAutoOnline;

    /**
     * 是否启用调度（true=上线，false=下线；null=不改变状态）
     */
    private Boolean enabled;
}
