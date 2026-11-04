package com.onedata.portal.dto;

import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;



/**
 * Doris表统计信息DTO
 * 用于展示表的元数据和统计数据
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableStatistics {

    /**
     * 数据库名
     */
    private String databaseName;

    /**
     * 表名
     */
    private String tableName;

    /**
     * 表类型 (OLAP/AGGREGATE/UNIQUE/DUPLICATE)
     */
    private String tableType;

    /**
     * 表注释
     */
    private String tableComment;

    /**
     * 表创建时间
     */
    private LocalDateTime createTime;

    /**
     * 表最后更新时间
     */
    private LocalDateTime lastUpdateTime;

    /**
     * 数据行数
     */
    private Long rowCount;

    /**
     * 数据大小（字节）
     */
    private Long dataSize;

    /**
     * 数据大小（可读格式，如 10.5 GB）
     */
    private String dataSizeReadable;

    /**
     * 副本数量
     */
    private Integer replicationNum;

    /**
     * 分区数量
     */
    private Integer partitionCount;

    /**
     * 分桶数量
     */
    private Integer bucketNum;

    /**
     * 表引擎 (OLAP)
     */
    private String engine;

    /**
     * 是否可用
     */
    private Boolean available;

    /**
     * 统计信息最后收集时间
     */
    private LocalDateTime lastCheckTime;
}
