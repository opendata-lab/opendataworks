package com.onedata.portal.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * Dashboard 表访问统计汇总
 */
@Data
public class DashboardTableAccessSummary {

    private Integer hotWindowDays;

    private Integer coldWindowDays;

    private List<DashboardTableAccessItem> hotTables = new ArrayList<>();

    private List<DashboardTableAccessItem> longUnusedTables = new ArrayList<>();

    private Boolean dorisAuditEnabled;

    private String dorisAuditSource;

    private String tableAccessSyncStatus;

    private LocalDateTime tableAccessCoverageStart;

    private Boolean tableAccessCoverageComplete;

    private LocalDateTime tableAccessLastSyncedAt;

    private String note;
}
