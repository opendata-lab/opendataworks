package com.onedata.portal.dto;

import java.time.LocalDateTime;
import lombok.Data;



/**
 * Dashboard 表访问聚合查询投影。
 */
@Data
public class DashboardTableAccessAggregate {

    private Long clusterId;
    private String dbName;
    private String tableName;
    private Long accessCount;
    private LocalDateTime lastAccessTime;
}
