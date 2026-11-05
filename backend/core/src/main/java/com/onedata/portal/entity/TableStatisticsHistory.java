package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;


/**
 * 表统计历史记录实体
 * 用于保存表的统计信息历史，支持趋势分析
 */
@Data
@TableName("table_statistics_history")
public class TableStatisticsHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 关联的表ID
     */
    private Long tableId;

    /**
     * Doris集群ID
     */
    private Long clusterId;

    /**
     * 数据库名
     */
    private String databaseName;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 数据行数
     */
    private Long rowCount;

    /**
     * 数据大小（字节）
     */
    private Long dataSize;

    /**
     * 分区数量
     */
    private Integer partitionCount;

    /**
     * 副本数量
     */
    private Integer replicationNum;

    /**
     * 分桶数量
     */
    private Integer bucketNum;

    /**
     * 表最后更新时间（来自Doris）
     */
    private LocalDateTime tableLastUpdateTime;

    /**
     * 统计时间
     */
    private LocalDateTime statisticsTime;

    /**
     * 记录创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
