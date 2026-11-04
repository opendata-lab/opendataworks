package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 数据新鲜度检查结果。
 *
 * <p>每次检查都留一行（{@code pass} 也留），是 dbt {@code sources.json} 的持久化等价物，
 * 用于回答「上次检查是什么时候」并支撑新鲜度趋势。
 */
@Data
@TableName("table_freshness_result")
public class TableFreshnessResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tableId;

    private Long clusterId;

    private String dbName;

    private String tableName;

    private String mode;

    /**
     * pass | warn | error | runtime_error
     */
    private String status;

    /**
     * 细分原因，如 never_loaded
     */
    private String reason;

    private LocalDateTime maxLoadedAt;

    private LocalDateTime snapshottedAt;

    private Long ageSeconds;

    private Long warnAfterSeconds;

    private Long errorAfterSeconds;

    private String errorMessage;

    /**
     * manual | schedule | inspection | workflow
     */
    private String triggerType;

    /**
     * 触发本次检查的工作流实例ID（Dolphin 实例），用于按「每次运行」聚合并反查执行。
     * 按需/单表检查为 null。
     */
    private Long workflowInstanceId;

    private String checkedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
