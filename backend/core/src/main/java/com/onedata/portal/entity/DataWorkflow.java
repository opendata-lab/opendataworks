package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 平台侧工作流定义
 */
@Data
@TableName("data_workflow")
public class DataWorkflow {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long dolphinConfigId;

    private Long workflowCode;

    private Long projectCode;

    private String workflowName;

    private String status;

    private String publishStatus;

    private Long currentVersionId;

    private Long lastPublishedVersionId;

    private String definitionJson;

    private String entryTaskIds;

    private String exitTaskIds;

    private String description;

    private String createdBy;

    private String updatedBy;

    /**
     * 全局参数 (JSON format: [{"prop": "key", "value": "val", ...}])
     */
    private String globalParams;

    /**
     * 默认任务组（DolphinScheduler Task Group）
     */
    private String taskGroupName;

    /**
     * DolphinScheduler 定时调度配置
     */
    private Long dolphinScheduleId;

    private String scheduleState;

    private String scheduleCron;

    private String scheduleTimezone;

    private LocalDateTime scheduleStartTime;

    private LocalDateTime scheduleEndTime;

    private String scheduleFailureStrategy;

    private String scheduleWarningType;

    private Long scheduleWarningGroupId;

    private String scheduleProcessInstancePriority;

    private String scheduleWorkerGroup;

    private String scheduleTenantCode;

    private Long scheduleEnvironmentCode;

    /**
     * 工作流上线后是否自动上线调度
     */
    private Boolean scheduleAutoOnline;

    /**
     * 同步来源: manual/runtime
     */
    private String syncSource;

    /**
     * 最近一次运行态同步快照哈希
     */
    private String runtimeSyncHash;

    /**
     * 最近一次运行态同步状态: success/failed
     */
    private String runtimeSyncStatus;

    /**
     * 最近一次运行态同步信息
     */
    private String runtimeSyncMessage;

    /**
     * 最近一次运行态同步时间
     */
    private LocalDateTime runtimeSyncAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;

    @TableField(exist = false)
    private Long latestInstanceId;

    @TableField(exist = false)
    private String latestInstanceState;

    @TableField(exist = false)
    private LocalDateTime latestInstanceStartTime;

    @TableField(exist = false)
    private LocalDateTime latestInstanceEndTime;

    @TableField(exist = false)
    private Integer currentVersionNo;
}
