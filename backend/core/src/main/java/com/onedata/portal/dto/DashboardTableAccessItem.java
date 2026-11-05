package com.onedata.portal.dto;

import java.time.LocalDateTime;
import lombok.Data;



/**
 * Dashboard 表访问项
 */
@Data
public class DashboardTableAccessItem {

    private Long tableId;

    private Long clusterId;

    private String dbName;

    private String tableName;

    private String layer;

    private String owner;

    private Long accessCount;

    private LocalDateTime lastAccessTime;

    private Long daysSinceLastAccess;
}
