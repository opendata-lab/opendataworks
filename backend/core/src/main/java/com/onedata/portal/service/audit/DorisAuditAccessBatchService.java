package com.onedata.portal.service.audit;

import com.onedata.portal.entity.DorisAuditAccessCheckpoint;
import com.onedata.portal.entity.TableAccessDaily;
import com.onedata.portal.entity.TableAccessUserDaily;
import com.onedata.portal.mapper.DorisAuditAccessCheckpointMapper;
import com.onedata.portal.mapper.DorisAuditProcessedEventMapper;
import com.onedata.portal.mapper.TableAccessDailyMapper;
import com.onedata.portal.mapper.TableAccessUserDailyMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;



/**
 * 审计访问事件的 MySQL 幂等落库边界。
 */
@Service
@RequiredArgsConstructor
public class DorisAuditAccessBatchService {

    private static final int UPSERT_CHUNK_SIZE = 500;

    private final DorisAuditProcessedEventMapper processedEventMapper;
    private final TableAccessDailyMapper tableAccessDailyMapper;
    private final TableAccessUserDailyMapper tableAccessUserDailyMapper;
    private final DorisAuditAccessCheckpointMapper checkpointMapper;

    @Transactional
    public BatchApplyResult applyBatch(Long clusterId,
            String auditSource,
            LocalDateTime coverageStart,
            List<DorisAuditAccessEvent> events,
            LocalDateTime watermarkTime,
            String watermarkEventKey,
            String status) {
        Map<String, TableAccessDaily> dailyDeltas = new LinkedHashMap<>();
        Map<String, TableAccessUserDaily> userDeltas = new LinkedHashMap<>();
        int duplicateEvents = 0;
        int acceptedEvents = 0;

        for (DorisAuditAccessEvent event : events) {
            int inserted = processedEventMapper.insertIgnore(clusterId, event.getEventKey(), event.getEventTime());
            if (inserted == 0) {
                duplicateEvents++;
                continue;
            }
            acceptedEvents++;
            mergeEvent(clusterId, event, dailyDeltas, userDeltas);
        }

        upsertDailyInChunks(new ArrayList<>(dailyDeltas.values()));
        upsertUserInChunks(new ArrayList<>(userDeltas.values()));
        saveCheckpoint(clusterId, auditSource, coverageStart, watermarkTime, watermarkEventKey, status, "", true);
        return new BatchApplyResult(acceptedEvents, duplicateEvents, dailyDeltas.size(), userDeltas.size());
    }

    @Transactional
    public void initializeCheckpoint(Long clusterId,
            String auditSource,
            LocalDateTime coverageStart,
            LocalDateTime watermarkTime) {
        saveCheckpoint(
                clusterId, auditSource, coverageStart, watermarkTime, "", "BACKFILLING", "", false);
    }

    @Transactional
    public void advanceCheckpoint(Long clusterId,
            String auditSource,
            LocalDateTime coverageStart,
            LocalDateTime watermarkTime,
            String status) {
        saveCheckpoint(clusterId, auditSource, coverageStart, watermarkTime, "", status, "", true);
    }

    /**
     * 记录同步失败。水位保留，下一轮从原游标继续；可信覆盖范围重新累计。
     * <p>
     * 恢复时不重扫 overlap 窗口，故障期间可能已经漏掉迟到的事件，
     * 保留旧的覆盖起点会让追平后的 {@code READY} 把带缺口的区间当成连续 90 天，进而给出错误的冷表结论。
     * 代价是一次故障会让冷表暂停一个完整窗口，对非核心统计而言这比引入额外状态更划算。
     */
    @Transactional
    public void markFailure(Long clusterId, String auditSource, String error) {
        DorisAuditAccessCheckpoint checkpoint = checkpointMapper.selectById(clusterId);
        if (checkpoint == null) {
            checkpoint = new DorisAuditAccessCheckpoint();
            checkpoint.setClusterId(clusterId);
            checkpoint.setAuditSource(auditSource);
            checkpoint.setCoverageStart(LocalDateTime.now());
            checkpoint.setSyncStatus("UNAVAILABLE");
            checkpoint.setLastError(truncate(error, 1000));
            checkpointMapper.insert(checkpoint);
            return;
        }
        if (StringUtils.hasText(auditSource)) {
            checkpoint.setAuditSource(auditSource);
        }
        checkpoint.setCoverageStart(LocalDateTime.now());
        checkpoint.setSyncStatus(checkpoint.getLastSyncedAt() == null ? "UNAVAILABLE" : "DEGRADED");
        checkpoint.setLastError(truncate(error, 1000));
        checkpointMapper.updateById(checkpoint);
    }

    private void mergeEvent(Long clusterId,
            DorisAuditAccessEvent event,
            Map<String, TableAccessDaily> dailyDeltas,
            Map<String, TableAccessUserDaily> userDeltas) {
        LocalDate accessDate = event.getEventTime().toLocalDate();
        for (AuditTableReference reference : event.getTableReferences()) {
            String dailyKey = clusterId + "|" + accessDate + "|" + reference.getDatabaseName() + "|"
                    + reference.getTableName();
            TableAccessDaily daily = dailyDeltas.computeIfAbsent(dailyKey,
                    ignored -> newDaily(clusterId, accessDate, reference, event.getEventTime()));
            daily.setTotalAccessCount(daily.getTotalAccessCount() + 1L);
            if (reference.isRead()) {
                daily.setReadAccessCount(daily.getReadAccessCount() + 1L);
            }
            if (reference.isWrite()) {
                daily.setWriteAccessCount(daily.getWriteAccessCount() + 1L);
            }
            if (event.getQueryTimeMs() != null && event.getQueryTimeMs() >= 0L) {
                daily.setDurationSumMs(daily.getDurationSumMs() + event.getQueryTimeMs());
                daily.setDurationSampleCount(daily.getDurationSampleCount() + 1L);
            }
            if (event.getEventTime().isBefore(daily.getFirstAccessTime())) {
                daily.setFirstAccessTime(event.getEventTime());
            }
            if (event.getEventTime().isAfter(daily.getLastAccessTime())) {
                daily.setLastAccessTime(event.getEventTime());
            }

            if (StringUtils.hasText(event.getUserName())) {
                String userKey = dailyKey + "|" + event.getUserName();
                TableAccessUserDaily userDaily = userDeltas.computeIfAbsent(userKey,
                        ignored -> newUserDaily(clusterId, accessDate, reference, event));
                userDaily.setAccessCount(userDaily.getAccessCount() + 1L);
                if (event.getEventTime().isAfter(userDaily.getLastAccessTime())) {
                    userDaily.setLastAccessTime(event.getEventTime());
                }
            }
        }
    }

    private TableAccessDaily newDaily(Long clusterId,
            LocalDate accessDate,
            AuditTableReference reference,
            LocalDateTime eventTime) {
        TableAccessDaily daily = new TableAccessDaily();
        daily.setClusterId(clusterId);
        daily.setAccessDate(accessDate);
        daily.setDbName(reference.getDatabaseName());
        daily.setTableName(reference.getTableName());
        daily.setTotalAccessCount(0L);
        daily.setReadAccessCount(0L);
        daily.setWriteAccessCount(0L);
        daily.setDurationSumMs(0L);
        daily.setDurationSampleCount(0L);
        daily.setFirstAccessTime(eventTime);
        daily.setLastAccessTime(eventTime);
        return daily;
    }

    private TableAccessUserDaily newUserDaily(Long clusterId,
            LocalDate accessDate,
            AuditTableReference reference,
            DorisAuditAccessEvent event) {
        TableAccessUserDaily userDaily = new TableAccessUserDaily();
        userDaily.setClusterId(clusterId);
        userDaily.setAccessDate(accessDate);
        userDaily.setDbName(reference.getDatabaseName());
        userDaily.setTableName(reference.getTableName());
        userDaily.setUserName(event.getUserName());
        userDaily.setAccessCount(0L);
        userDaily.setLastAccessTime(event.getEventTime());
        return userDaily;
    }

    private void upsertDailyInChunks(List<TableAccessDaily> items) {
        for (int from = 0; from < items.size(); from += UPSERT_CHUNK_SIZE) {
            tableAccessDailyMapper.upsertBatch(
                    items.subList(from, Math.min(from + UPSERT_CHUNK_SIZE, items.size())));
        }
    }

    private void upsertUserInChunks(List<TableAccessUserDaily> items) {
        for (int from = 0; from < items.size(); from += UPSERT_CHUNK_SIZE) {
            tableAccessUserDailyMapper.upsertBatch(
                    items.subList(from, Math.min(from + UPSERT_CHUNK_SIZE, items.size())));
        }
    }

    private void saveCheckpoint(Long clusterId,
            String auditSource,
            LocalDateTime coverageStart,
            LocalDateTime watermarkTime,
            String watermarkEventKey,
            String status,
            String error,
            boolean updateLastSyncedAt) {
        DorisAuditAccessCheckpoint checkpoint = checkpointMapper.selectById(clusterId);
        boolean inserting = checkpoint == null;
        if (inserting) {
            checkpoint = new DorisAuditAccessCheckpoint();
            checkpoint.setClusterId(clusterId);
        }
        checkpoint.setAuditSource(auditSource);
        checkpoint.setCoverageStart(coverageStart);
        checkpoint.setWatermarkTime(watermarkTime);
        checkpoint.setWatermarkEventKey(watermarkEventKey);
        checkpoint.setSyncStatus(status);
        if (updateLastSyncedAt) {
            checkpoint.setLastSyncedAt(LocalDateTime.now());
        }
        checkpoint.setLastError(error);
        if (inserting) {
            checkpointMapper.insert(checkpoint);
        } else {
            checkpointMapper.updateById(checkpoint);
        }
    }

    private String truncate(String value, int length) {
        if (value == null || value.length() <= length) {
            return value;
        }
        return value.substring(0, length);
    }

    @Data
    @AllArgsConstructor
    public static class BatchApplyResult {
        private int acceptedEvents;
        private int duplicateEvents;
        private int dailyRows;
        private int userRows;
    }
}
