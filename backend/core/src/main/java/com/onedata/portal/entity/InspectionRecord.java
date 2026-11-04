package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 巡检记录实体
 */
@Data
@TableName("inspection_record")
public class InspectionRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 巡检类型: table_naming, replica_count, tablet_count, task_failure, best_practice
     */
    private String inspectionType;

    /**
     * 巡检时间
     */
    private LocalDateTime inspectionTime;

    /**
     * 触发类型: manual, schedule
     */
    private String triggerType;

    /**
     * 检查项总数
     */
    private Integer totalItems;

    /**
     * 问题数量
     */
    private Integer issueCount;

    /**
     * 状态: running, completed, failed
     */
    private String status;

    /**
     * 执行时长(秒)
     */
    private Integer durationSeconds;

    /**
     * 创建人
     */
    private String createdBy;

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
