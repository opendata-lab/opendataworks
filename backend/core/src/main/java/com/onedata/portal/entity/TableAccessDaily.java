package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;



@Data
@TableName("table_access_daily")
public class TableAccessDaily {

    private Long clusterId;
    private LocalDate accessDate;
    private String dbName;
    private String tableName;
    private Long totalAccessCount;
    private Long readAccessCount;
    private Long writeAccessCount;
    private Long durationSumMs;
    private Long durationSampleCount;
    private LocalDateTime firstAccessTime;
    private LocalDateTime lastAccessTime;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
