package com.onedata.portal.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


/**
 * 表分区信息 DTO。
 *
 * <p>对应 Doris {@code SHOW PARTITIONS} 的一行。不同 Doris 版本返回的列不完全一致，
 * 缺失列以 {@code null} 表达，不参与展示。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TablePartitionInfo {

    /**
     * 分区名
     */
    private String partitionName;

    /**
     * 分区键（分区列）
     */
    private String partitionKey;

    /**
     * 分区范围
     */
    private String range;

    /**
     * 分桶键
     */
    private String distributionKey;

    /**
     * 分桶数
     */
    private Integer buckets;

    /**
     * 副本数
     */
    private Integer replicationNum;

    /**
     * 分区数据大小（Doris 返回的可读字符串，如 {@code 1.234 GB}）
     */
    private String dataSize;

    /**
     * 分区行数；老版本 Doris 不返回该列时为 null
     */
    private Long rowCount;

    /**
     * 分区状态
     */
    private String state;

    /**
     * 存储介质
     */
    private String storageMedium;

    /**
     * 可见版本时间
     */
    private String visibleVersionTime;
}
