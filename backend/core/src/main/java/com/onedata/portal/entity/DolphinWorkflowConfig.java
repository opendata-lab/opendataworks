package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.util.Date;
import lombok.Data;



/**
 * DolphinScheduler工作流配置实体
 */
@Data
@TableName("dolphin_workflow_config")
public class DolphinWorkflowConfig {
    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 工作流编码
     */
    private Long workflowCode;

    /**
     * 工作流名称
     */
    private String workflowName;

    /**
     * 项目编码
     */
    private Long projectCode;

    /**
     * 描述
     */
    private String description;

    /**
     * 是否默认工作流
     */
    private Boolean isDefault;

    /**
     * 发布状态: ONLINE/OFFLINE
     */
    private String releaseState;

    /**
     * 任务数量
     */
    private Integer taskCount;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private Date createdAt;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Date updatedAt;
}
