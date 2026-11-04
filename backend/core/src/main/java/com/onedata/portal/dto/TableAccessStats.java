package com.onedata.portal.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;



/**
 * 表访问统计
 */
@Data
public class TableAccessStats {

    private Long tableId;

    private Long clusterId;

    private String databaseName;

    private String tableName;

    /**
     * 全量访问次数
     */
    private Long totalAccessCount;

    /**
     * 最近窗口访问次数
     */
    private Long recentAccessCount;

    /**
     * 最近7天访问次数
     */
    private Long accessCount7d;

    /**
     * 最近30天访问次数
     */
    private Long accessCount30d;

    /**
     * 最近访问时间
     */
    private LocalDateTime lastAccessTime;

    /**
     * 首次访问时间
     */
    private LocalDateTime firstAccessTime;

    /**
     * 访问用户数
     */
    private Long distinctUserCount;

    /**
     * 平均执行耗时（毫秒）
     */
    private BigDecimal averageDurationMs;

    /**
     * 统计窗口（天）
     */
    private Integer recentDays;

    /**
     * 趋势窗口（天）
     */
    private Integer trendDays;

    /**
     * 访问趋势
     */
    private List<TableAccessTrendPoint> trend = new ArrayList<>();

    /**
     * 活跃用户（窗口内）
     */
    private List<TableAccessUserStat> topUsers = new ArrayList<>();

    /**
     * 是否启用 Doris 审计日志统计
     */
    private Boolean dorisAuditEnabled;

    /**
     * 审计来源（如 __internal_schema.audit_log / doris_audit_db__.doris_audit_tbl__）
     */
    private String dorisAuditSource;

    /**
     * READY/BACKFILLING/DEGRADED/DISABLED/UNAVAILABLE。
     */
    private String tableAccessSyncStatus;

    /**
     * 当前汇总的可信历史覆盖起点。
     */
    private LocalDateTime tableAccessCoverageStart;

    /**
     * 是否覆盖本次请求所需的完整历史窗口。
     */
    private Boolean tableAccessCoverageComplete;

    /**
     * 最近成功同步时间。
     */
    private LocalDateTime tableAccessLastSyncedAt;

    /**
     * 统计说明（降级场景）
     */
    private String note;
}
