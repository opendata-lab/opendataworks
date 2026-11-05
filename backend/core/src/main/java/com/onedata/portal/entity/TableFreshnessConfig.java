package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 表级数据新鲜度契约。
 *
 * <p>由用户显式指定时间字段取值方式与时效阈值，语义对齐 dbt source freshness。
 * {@code enabled = 0} 表示显式关闭该表的新鲜度检查（等价 dbt 的 {@code freshness: null}）。
 */
@Data
@TableName("table_freshness_config")
public class TableFreshnessConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tableId;

    /**
     * 取值模式: column | custom_sql | partition | metadata
     */
    private String mode;

    /**
     * column 模式的加载时间列名
     */
    private String loadedAtField;

    /**
     * custom_sql 模式的自定义查询，与 loadedAtField 互斥
     */
    private String loadedAtQuery;

    /**
     * partition 模式的分区值日期格式，如 yyyyMMdd
     */
    private String partitionFormat;

    /**
     * 可选 WHERE 谓词过滤
     */
    private String filterExpr;

    private Integer warnAfterCount;

    /**
     * minute | hour | day
     */
    private String warnAfterPeriod;

    private Integer errorAfterCount;

    private String errorAfterPeriod;

    private Boolean enabled;

    private String createdBy;

    private String updatedBy;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
