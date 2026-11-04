package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;


/**
 * 数据表实体
 */
@Data
@TableName("data_table")
public class DataTable {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("cluster_id")
    private Long clusterId;

    private String tableName;

    private String tableType;

    private String originTableName;

    private String tableComment;

    private String layer; // ODS, DWD, DIM, DWS, ADS

    private String businessDomain;

    private String dataDomain;

    private String customIdentifier;

    private String statisticsCycle;

    private String updateType;

    private String tableModel;

    private Integer bucketNum;

    private Integer replicaNum;

    private String dbName;

    private String owner;

    private String status; // active, inactive, deprecated

    private LocalDateTime deprecatedAt;

    private LocalDateTime purgeAt;

    private Integer lifecycleDays;

    @TableField("partition_column")
    private String partitionColumn;

    private String distributionColumn;

    private String keyColumns;

    @TableField("doris_ddl")
    private String dorisDdl;

    private Integer isSynced;

    private LocalDateTime syncTime;

    private Long storageSize;

    private LocalDateTime dorisCreateTime;

    private Long rowCount;

    @TableField("doris_update_time")
    private LocalDateTime dorisUpdateTime;

    private String freshnessStatus;

    private LocalDateTime freshnessCheckedAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
