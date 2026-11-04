package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 巡检问题实体
 */
@Data
@TableName("inspection_issue")
public class InspectionIssue {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 巡检记录ID
     */
    private Long recordId;

    /**
     * 数据源ID(集群)
     */
    private Long clusterId;

    /**
     * Schema/数据库名
     */
    private String dbName;

    /**
     * 问题类型
     */
    private String issueType;

    /**
     * 严重程度: critical, high, medium, low
     */
    private String severity;

    /**
     * 资源类型: table, task
     */
    private String resourceType;

    /**
     * 资源ID
     */
    private Long resourceId;

    /**
     * 资源名称
     */
    private String resourceName;

    /**
     * 问题描述
     */
    private String issueDescription;

    /**
     * 当前值
     */
    private String currentValue;

    /**
     * 期望值
     */
    private String expectedValue;

    /**
     * 建议
     */
    private String suggestion;

    /**
     * 状态: open, acknowledged, resolved, ignored
     */
    private String status;

    /**
     * 解决人
     */
    private String resolvedBy;

    /**
     * 解决时间
     */
    private LocalDateTime resolvedTime;

    /**
     * 解决说明
     */
    private String resolutionNote;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedTime;
}
