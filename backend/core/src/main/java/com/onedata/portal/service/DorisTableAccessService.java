package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.config.DorisAuditAccessSyncProperties;
import com.onedata.portal.dto.DashboardTableAccessAggregate;
import com.onedata.portal.dto.DashboardTableAccessItem;
import com.onedata.portal.dto.DashboardTableAccessSummary;
import com.onedata.portal.dto.TableAccessAggregate;
import com.onedata.portal.dto.TableAccessStats;
import com.onedata.portal.dto.TableAccessTrendPoint;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisAuditAccessCheckpoint;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisAuditAccessCheckpointMapper;
import com.onedata.portal.mapper.TableAccessDailyMapper;
import com.onedata.portal.mapper.TableAccessUserDailyMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;



/**
 * 基于 MySQL 每日汇总的 Doris 表访问统计查询服务。
 */
@Service
@RequiredArgsConstructor
public class DorisTableAccessService {

    private final DorisAuditAccessSyncProperties properties;
    private final DataTableMapper dataTableMapper;
    private final DorisAuditAccessCheckpointMapper checkpointMapper;
    private final TableAccessDailyMapper tableAccessDailyMapper;
    private final TableAccessUserDailyMapper tableAccessUserDailyMapper;
    private final TableAccessSummaryCache summaryCache;

    public TableAccessStats getTableAccessStats(DataTable table,
            Long requestedClusterId,
            int recentDays,
            int trendDays,
            int topUsers) {
        if (table == null) {
            throw new IllegalArgumentException("表信息不能为空");
        }
        if (!StringUtils.hasText(table.getDbName()) || !StringUtils.hasText(table.getTableName())) {
            throw new IllegalArgumentException("表缺少 database/tableName 信息");
        }
        Long clusterId = resolveClusterId(table, requestedClusterId);
        int safeRecentDays = Math.max(1, Math.min(recentDays, 365));
        int safeTrendDays = Math.max(1, Math.min(trendDays, 90));
        int safeTopUsers = Math.max(1, Math.min(topUsers, 20));
        String database = normalizeIdentifier(table.getDbName());
        String tableName = normalizeIdentifier(extractTableName(table.getTableName()));
        String cacheKey = "cluster:" + clusterId + ":table:" + table.getId()
                + ":" + safeRecentDays + ":" + safeTrendDays + ":" + safeTopUsers;
        return summaryCache.getOrLoad(cacheKey, () -> loadTableAccessStats(
                table, clusterId, database, tableName, safeRecentDays, safeTrendDays, safeTopUsers));
    }

    public DashboardTableAccessSummary getDashboardAccessSummary(Long clusterId,
            int hotDays,
            int hotLimit,
            int coldDays,
            int coldLimit) {
        int safeHotDays = Math.max(1, Math.min(hotDays, 365));
        int safeColdDays = Math.max(1, Math.min(coldDays, 365));
        int safeHotLimit = Math.max(1, Math.min(hotLimit, 50));
        int safeColdLimit = Math.max(1, Math.min(coldLimit, 50));
        String token = clusterId == null ? "all" : String.valueOf(clusterId);
        String cacheKey = "cluster:" + token + ":dashboard:" + safeHotDays + ":" + safeHotLimit
                + ":" + safeColdDays + ":" + safeColdLimit;
        return summaryCache.getOrLoad(cacheKey,
                () -> loadDashboardSummary(clusterId, safeHotDays, safeHotLimit, safeColdDays, safeColdLimit));
    }

    public void evictCache(Long clusterId) {
        summaryCache.evictCluster(clusterId);
    }

    private TableAccessStats loadTableAccessStats(DataTable table,
            Long clusterId,
            String database,
            String tableName,
            int recentDays,
            int trendDays,
            int topUsers) {
        LocalDateTime now = LocalDateTime.now();
        int requiredDays = Math.max(30, Math.max(recentDays, trendDays));
        DorisAuditAccessCheckpoint checkpoint = checkpointMapper.selectById(clusterId);
        AccessState state = buildState(checkpoint, requiredDays, now);

        TableAccessStats stats = new TableAccessStats();
        stats.setTableId(table.getId());
        stats.setClusterId(clusterId);
        stats.setDatabaseName(database);
        stats.setTableName(tableName);
        stats.setRecentDays(recentDays);
        stats.setTrendDays(trendDays);
        applyState(stats, state);

        LocalDate today = now.toLocalDate();
        TableAccessAggregate aggregate = tableAccessDailyMapper.selectTableAggregate(
                clusterId,
                database,
                tableName,
                windowStart(today, requiredDays),
                windowStart(today, recentDays),
                windowStart(today, 7),
                windowStart(today, 30));
        if (aggregate == null) {
            aggregate = new TableAccessAggregate();
        }
        stats.setTotalAccessCount(zeroIfNull(aggregate.getTotalAccessCount()));
        stats.setRecentAccessCount(zeroIfNull(aggregate.getRecentAccessCount()));
        stats.setAccessCount7d(zeroIfNull(aggregate.getAccessCount7d()));
        stats.setAccessCount30d(zeroIfNull(aggregate.getAccessCount30d()));
        stats.setFirstAccessTime(aggregate.getFirstAccessTime());
        stats.setLastAccessTime(aggregate.getLastAccessTime());

        long durationSamples = zeroIfNull(aggregate.getDurationSampleCount());
        if (durationSamples > 0L) {
            stats.setAverageDurationMs(BigDecimal.valueOf(zeroIfNull(aggregate.getDurationSumMs()))
                    .divide(BigDecimal.valueOf(durationSamples), 2, RoundingMode.HALF_UP));
        }

        Long distinctUsers = tableAccessUserDailyMapper.countDistinctUsers(
                clusterId, database, tableName, windowStart(today, recentDays));
        stats.setDistinctUserCount(zeroIfNull(distinctUsers));
        stats.setTopUsers(tableAccessUserDailyMapper.selectTopUsers(
                clusterId, database, tableName, windowStart(today, recentDays), topUsers));

        Map<String, Long> trendByDate = tableAccessDailyMapper.selectTableTrend(
                        clusterId, database, tableName, windowStart(today, trendDays))
                .stream()
                .collect(Collectors.toMap(
                        TableAccessTrendPoint::getDate,
                        point -> zeroIfNull(point.getAccessCount()),
                        Long::sum));
        List<TableAccessTrendPoint> trend = new ArrayList<>();
        for (int offset = trendDays - 1; offset >= 0; offset--) {
            String date = today.minusDays(offset).toString();
            TableAccessTrendPoint point = new TableAccessTrendPoint();
            point.setDate(date);
            point.setAccessCount(trendByDate.getOrDefault(date, 0L));
            trend.add(point);
        }
        stats.setTrend(trend);
        return stats;
    }

    private DashboardTableAccessSummary loadDashboardSummary(Long clusterId,
            int hotDays,
            int hotLimit,
            int coldDays,
            int coldLimit) {
        DashboardTableAccessSummary summary = new DashboardTableAccessSummary();
        summary.setHotWindowDays(hotDays);
        summary.setColdWindowDays(coldDays);

        LambdaQueryWrapper<DataTable> tableQuery = new LambdaQueryWrapper<DataTable>()
                .select(
                        DataTable::getId,
                        DataTable::getClusterId,
                        DataTable::getDbName,
                        DataTable::getTableName,
                        DataTable::getLayer,
                        DataTable::getOwner,
                        DataTable::getDorisCreateTime,
                        DataTable::getCreatedAt)
                .isNotNull(DataTable::getClusterId)
                .isNotNull(DataTable::getDbName)
                .isNotNull(DataTable::getTableName)
                .ne(DataTable::getStatus, "deprecated");
        if (clusterId != null) {
            tableQuery.eq(DataTable::getClusterId, clusterId);
        }
        List<DataTable> tables = dataTableMapper.selectList(tableQuery);
        if (tables.isEmpty()) {
            summary.setDorisAuditEnabled(false);
            summary.setTableAccessSyncStatus(properties.isEnabled() ? "UNAVAILABLE" : "DISABLED");
            summary.setTableAccessCoverageComplete(false);
            summary.setNote("暂无可统计的数据表。");
            return summary;
        }

        List<Long> clusterIds = tables.stream()
                .map(DataTable::getClusterId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, DorisAuditAccessCheckpoint> checkpoints = loadCheckpoints(clusterIds);
        AccessState overallState = buildOverallState(clusterIds, checkpoints, coldDays, LocalDateTime.now());
        applyState(summary, overallState);

        Set<Long> clustersWithSummary = checkpoints.values().stream()
                .filter(checkpoint -> checkpoint.getLastSyncedAt() != null)
                .map(DorisAuditAccessCheckpoint::getClusterId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (clustersWithSummary.isEmpty()) {
            return summary;
        }

        LocalDate today = LocalDate.now();
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime coldThreshold = now.minusDays(coldDays);
        LocalDate hotStart = windowStart(today, hotDays);
        // 冷表阈值是精确时刻，聚合窗口必须回看到它所在的自然日，
        // 否则阈值当天下午的访问会落在窗口外，把活跃表误判成冷表。
        LocalDate historyStart = coldThreshold.toLocalDate().isBefore(hotStart)
                ? coldThreshold.toLocalDate()
                : hotStart;
        List<DashboardTableAccessAggregate> aggregates = tableAccessDailyMapper.selectDashboardAggregates(
                new ArrayList<>(clustersWithSummary), hotStart, historyStart);
        Map<String, DashboardTableAccessAggregate> aggregateIndex = new HashMap<>();
        for (DashboardTableAccessAggregate aggregate : aggregates) {
            aggregateIndex.put(key(aggregate.getClusterId(), aggregate.getDbName(), aggregate.getTableName()), aggregate);
        }

        List<DashboardTableAccessItem> hotItems = new ArrayList<>();
        List<DashboardTableAccessItem> coldItems = new ArrayList<>();
        for (DataTable table : tables) {
            if (!clustersWithSummary.contains(table.getClusterId())) {
                continue;
            }
            DashboardTableAccessAggregate aggregate = aggregateIndex.get(
                    key(table.getClusterId(), table.getDbName(), extractTableName(table.getTableName())));
            long count = aggregate == null ? 0L : zeroIfNull(aggregate.getAccessCount());
            LocalDateTime lastAccess = aggregate == null ? null : aggregate.getLastAccessTime();
            hotItems.add(toDashboardItem(table, count, lastAccess, now));

            if (overallState.coverageComplete && "READY".equals(overallState.status)
                    && isCold(table, lastAccess, coldThreshold)) {
                coldItems.add(toDashboardItem(table, count, lastAccess, now));
            }
        }

        hotItems.sort(Comparator
                .comparing((DashboardTableAccessItem item) -> zeroIfNull(item.getAccessCount()))
                .reversed()
                .thenComparing(DashboardTableAccessItem::getTableId));
        summary.setHotTables(hotItems.stream().limit(hotLimit).collect(Collectors.toList()));

        coldItems.sort(Comparator
                .comparing(DashboardTableAccessItem::getLastAccessTime,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(DashboardTableAccessItem::getTableId));
        summary.setLongUnusedTables(coldItems.stream().limit(coldLimit).collect(Collectors.toList()));
        return summary;
    }

    private Map<Long, DorisAuditAccessCheckpoint> loadCheckpoints(List<Long> clusterIds) {
        if (clusterIds.isEmpty()) {
            return new HashMap<>();
        }
        return checkpointMapper.selectList(new LambdaQueryWrapper<DorisAuditAccessCheckpoint>()
                        .in(DorisAuditAccessCheckpoint::getClusterId, clusterIds))
                .stream()
                .collect(Collectors.toMap(DorisAuditAccessCheckpoint::getClusterId, checkpoint -> checkpoint));
    }

    private AccessState buildState(DorisAuditAccessCheckpoint checkpoint,
            int requiredDays,
            LocalDateTime now) {
        if (!properties.isEnabled()) {
            return stateFromCheckpoint("DISABLED", checkpoint, requiredDays, now,
                    checkpoint == null
                            ? "Doris 审计访问同步已关闭，暂无可用汇总数据。"
                            : "Doris 审计访问同步已关闭，当前展示截至最近同步时间的历史汇总。");
        }
        if (checkpoint == null) {
            return stateFromCheckpoint("UNAVAILABLE", null, requiredDays, now,
                    "尚无 Doris 审计访问汇总数据。");
        }
        String status = StringUtils.hasText(checkpoint.getSyncStatus())
                ? checkpoint.getSyncStatus().toUpperCase(Locale.ROOT)
                : "UNAVAILABLE";
        String note = buildNote(status, checkpoint, requiredDays, now);
        return stateFromCheckpoint(status, checkpoint, requiredDays, now, note);
    }

    private AccessState buildOverallState(List<Long> clusterIds,
            Map<Long, DorisAuditAccessCheckpoint> checkpoints,
            int requiredDays,
            LocalDateTime now) {
        if (!properties.isEnabled()) {
            AccessState state = combineStates(clusterIds, checkpoints, requiredDays, now, "DISABLED");
            state.note = "Doris 审计访问同步已关闭，当前展示截至最近同步时间的历史汇总；冷表统计暂停。";
            return state;
        }
        if (checkpoints.isEmpty()) {
            return new AccessState("UNAVAILABLE", null, false, null, false, null,
                    "尚无 Doris 审计访问汇总数据。");
        }
        boolean missingCluster = clusterIds.stream().anyMatch(id -> !checkpoints.containsKey(id));
        Collection<DorisAuditAccessCheckpoint> values = checkpoints.values();
        String status;
        if (missingCluster || values.stream().anyMatch(item -> "UNAVAILABLE".equalsIgnoreCase(item.getSyncStatus()))) {
            status = values.stream().anyMatch(item -> item.getLastSyncedAt() != null) ? "DEGRADED" : "UNAVAILABLE";
        } else if (values.stream().anyMatch(item -> "DEGRADED".equalsIgnoreCase(item.getSyncStatus()))) {
            status = "DEGRADED";
        } else if (values.stream().anyMatch(item -> "BACKFILLING".equalsIgnoreCase(item.getSyncStatus()))) {
            status = "BACKFILLING";
        } else {
            status = "READY";
        }
        AccessState state = combineStates(clusterIds, checkpoints, requiredDays, now, status);
        state.note = buildOverallNote(state, missingCluster);
        return state;
    }

    private AccessState combineStates(List<Long> clusterIds,
            Map<Long, DorisAuditAccessCheckpoint> checkpoints,
            int requiredDays,
            LocalDateTime now,
            String status) {
        LocalDateTime coverageStart = null;
        LocalDateTime lastSyncedAt = null;
        boolean coverageComplete = checkpoints.size() == clusterIds.size();
        boolean auditEnabled = false;
        String auditSource = null;
        for (Long id : clusterIds) {
            DorisAuditAccessCheckpoint checkpoint = checkpoints.get(id);
            if (checkpoint == null) {
                coverageComplete = false;
                continue;
            }
            auditEnabled = auditEnabled || StringUtils.hasText(checkpoint.getAuditSource());
            if (auditSource == null && StringUtils.hasText(checkpoint.getAuditSource())) {
                auditSource = checkpoint.getAuditSource();
            }
            if (checkpoint.getCoverageStart() == null) {
                coverageComplete = false;
            } else {
                if (coverageStart == null || checkpoint.getCoverageStart().isAfter(coverageStart)) {
                    coverageStart = checkpoint.getCoverageStart();
                }
                coverageComplete = coverageComplete
                        && !checkpoint.getCoverageStart().isAfter(now.minusDays(requiredDays));
            }
            if (checkpoint.getLastSyncedAt() != null
                    && (lastSyncedAt == null || checkpoint.getLastSyncedAt().isBefore(lastSyncedAt))) {
                lastSyncedAt = checkpoint.getLastSyncedAt();
            }
        }
        return new AccessState(status, coverageStart, coverageComplete, lastSyncedAt,
                auditEnabled, auditSource, null);
    }

    private AccessState stateFromCheckpoint(String status,
            DorisAuditAccessCheckpoint checkpoint,
            int requiredDays,
            LocalDateTime now,
            String note) {
        LocalDateTime coverageStart = checkpoint == null ? null : checkpoint.getCoverageStart();
        boolean coverageComplete = coverageStart != null && !coverageStart.isAfter(now.minusDays(requiredDays));
        return new AccessState(
                status,
                coverageStart,
                coverageComplete,
                checkpoint == null ? null : checkpoint.getLastSyncedAt(),
                checkpoint != null && StringUtils.hasText(checkpoint.getAuditSource()),
                checkpoint == null ? null : checkpoint.getAuditSource(),
                note);
    }

    private String buildNote(String status,
            DorisAuditAccessCheckpoint checkpoint,
            int requiredDays,
            LocalDateTime now) {
        if ("BACKFILLING".equals(status)) {
            return "Doris 审计访问历史正在回填，当前结果仅覆盖已同步范围，冷表统计暂停。";
        }
        if ("DEGRADED".equals(status)) {
            return "Doris 审计访问同步异常，当前展示最近一次成功汇总，冷表统计暂停。"
                    + errorSuffix(checkpoint);
        }
        if ("UNAVAILABLE".equals(status)) {
            return "Doris 审计访问汇总不可用，无法生成访问统计。" + errorSuffix(checkpoint);
        }
        if (checkpoint.getCoverageStart() == null
                || checkpoint.getCoverageStart().isAfter(now.minusDays(requiredDays))) {
            return "审计历史覆盖不足 " + requiredDays + " 天，当前结果仅供参考，冷表统计暂停。";
        }
        return null;
    }

    private String buildOverallNote(AccessState state, boolean missingCluster) {
        if ("BACKFILLING".equals(state.status)) {
            return "部分 Doris 审计历史正在回填，热点结果仅覆盖已同步范围，冷表统计暂停。";
        }
        if ("DEGRADED".equals(state.status)) {
            return missingCluster
                    ? "部分数据源尚无访问汇总或同步异常，当前展示可用历史数据，冷表统计暂停。"
                    : "部分数据源同步异常，当前展示最近一次成功汇总，冷表统计暂停。";
        }
        if ("UNAVAILABLE".equals(state.status)) {
            return "Doris 审计访问汇总不可用，无法生成热点表或冷表。";
        }
        if (!state.coverageComplete) {
            return "审计历史覆盖不足，热点结果仅供参考，冷表统计暂停。";
        }
        return null;
    }

    private String errorSuffix(DorisAuditAccessCheckpoint checkpoint) {
        return checkpoint != null && StringUtils.hasText(checkpoint.getLastError())
                ? " 原因：" + checkpoint.getLastError()
                : "";
    }

    private void applyState(TableAccessStats stats, AccessState state) {
        stats.setDorisAuditEnabled(state.auditEnabled);
        stats.setDorisAuditSource(state.auditSource);
        stats.setTableAccessSyncStatus(state.status);
        stats.setTableAccessCoverageStart(state.coverageStart);
        stats.setTableAccessCoverageComplete(state.coverageComplete);
        stats.setTableAccessLastSyncedAt(state.lastSyncedAt);
        stats.setNote(state.note);
    }

    private void applyState(DashboardTableAccessSummary summary, AccessState state) {
        summary.setDorisAuditEnabled(state.auditEnabled);
        summary.setDorisAuditSource(state.auditSource);
        summary.setTableAccessSyncStatus(state.status);
        summary.setTableAccessCoverageStart(state.coverageStart);
        summary.setTableAccessCoverageComplete(state.coverageComplete);
        summary.setTableAccessLastSyncedAt(state.lastSyncedAt);
        summary.setNote(state.note);
    }

    private DashboardTableAccessItem toDashboardItem(DataTable table,
            Long count,
            LocalDateTime lastAccess,
            LocalDateTime now) {
        DashboardTableAccessItem item = new DashboardTableAccessItem();
        item.setTableId(table.getId());
        item.setClusterId(table.getClusterId());
        item.setDbName(table.getDbName());
        item.setTableName(extractTableName(table.getTableName()));
        item.setLayer(table.getLayer());
        item.setOwner(table.getOwner());
        item.setAccessCount(zeroIfNull(count));
        item.setLastAccessTime(lastAccess);
        if (lastAccess != null) {
            item.setDaysSinceLastAccess(Duration.between(lastAccess, now).toDays());
        }
        return item;
    }

    private boolean isCold(DataTable table, LocalDateTime lastAccess, LocalDateTime threshold) {
        if (lastAccess != null) {
            return lastAccess.isBefore(threshold);
        }
        LocalDateTime createdAt = table.getDorisCreateTime() != null
                ? table.getDorisCreateTime()
                : table.getCreatedAt();
        return createdAt != null && createdAt.isBefore(threshold);
    }

    private Long resolveClusterId(DataTable table, Long requestedClusterId) {
        if (requestedClusterId != null) {
            return requestedClusterId;
        }
        if (table.getClusterId() != null) {
            return table.getClusterId();
        }
        throw new IllegalArgumentException("未指定 clusterId，且表未绑定 clusterId");
    }

    private LocalDate windowStart(LocalDate today, int days) {
        return today.minusDays(Math.max(0, days - 1L));
    }

    private String key(Long clusterId, String database, String table) {
        return clusterId + "::" + normalizeIdentifier(database) + "." + normalizeIdentifier(table);
    }

    private String normalizeIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("`", "").trim().toLowerCase(Locale.ROOT);
    }

    private String extractTableName(String fullName) {
        if (!StringUtils.hasText(fullName)) {
            return fullName;
        }
        String cleaned = fullName.replace("`", "").trim();
        int index = cleaned.indexOf('.');
        return index >= 0 && index < cleaned.length() - 1 ? cleaned.substring(index + 1) : cleaned;
    }

    private long zeroIfNull(Long value) {
        return value == null ? 0L : value;
    }

    private static class AccessState {
        private final String status;
        private final LocalDateTime coverageStart;
        private final boolean coverageComplete;
        private final LocalDateTime lastSyncedAt;
        private final boolean auditEnabled;
        private final String auditSource;
        private String note;

        private AccessState(String status,
                LocalDateTime coverageStart,
                boolean coverageComplete,
                LocalDateTime lastSyncedAt,
                boolean auditEnabled,
                String auditSource,
                String note) {
            this.status = status;
            this.coverageStart = coverageStart;
            this.coverageComplete = coverageComplete;
            this.lastSyncedAt = lastSyncedAt;
            this.auditEnabled = auditEnabled;
            this.auditSource = auditSource;
            this.note = note;
        }
    }
}
