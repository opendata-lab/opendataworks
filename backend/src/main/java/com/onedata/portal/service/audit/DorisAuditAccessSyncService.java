package com.onedata.portal.service.audit;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.config.DorisAuditAccessSyncProperties;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisAuditAccessCheckpoint;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisAuditAccessCheckpointMapper;
import com.onedata.portal.mapper.DorisAuditProcessedEventMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.TableAccessDailyMapper;
import com.onedata.portal.mapper.TableAccessUserDailyMapper;
import com.onedata.portal.service.DorisConnectionService;
import com.onedata.portal.service.TableAccessSummaryCache;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 Doris 审计表增量构建访问汇总。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DorisAuditAccessSyncService {

    private static final long MAX_CLUSTER_RUN_MILLIS = 60_000L;

    private final DatasourceAdapterRegistry adapterRegistry;
    private final DorisAuditAccessSyncProperties properties;
    private final DorisConnectionService dorisConnectionService;
    private final DorisClusterMapper dorisClusterMapper;
    private final DataTableMapper dataTableMapper;
    private final DorisAuditAccessCheckpointMapper checkpointMapper;
    private final DorisAuditProcessedEventMapper processedEventMapper;
    private final TableAccessDailyMapper tableAccessDailyMapper;
    private final TableAccessUserDailyMapper tableAccessUserDailyMapper;
    private final DorisAuditSqlTableParser sqlTableParser;
    private final DorisAuditAccessBatchService batchService;
    private final TableAccessSummaryCache summaryCache;

    public void syncActiveClusters() {
        List<DorisCluster> clusters = dorisClusterMapper.selectList(
                new LambdaQueryWrapper<DorisCluster>()
                        .eq(DorisCluster::getStatus, "active")
                        .orderByDesc(DorisCluster::getIsDefault)
                        .orderByAsc(DorisCluster::getId));
        for (DorisCluster cluster : clusters) {
            if (!adapterRegistry.require(cluster.getSourceType()).descriptor().supports("auditAccess")
                    || !hasManagedTables(cluster.getId())) {
                continue;
            }
            syncCluster(cluster);
        }
    }

    public void syncCluster(DorisCluster cluster) {
        adapterRegistry.requireCapability(cluster.getSourceType(), "auditAccess");
        Long clusterId = cluster.getId();
        long startedAt = System.currentTimeMillis();
        String auditSourceName = null;
        try {
            AuditSource source = resolveAuditSource(clusterId);
            if (source == null) {
                batchService.markFailure(clusterId, null, "未检测到可查询的 Doris 审计表");
                return;
            }
            auditSourceName = source.qualifiedName();

            // 水位必须以审计源自身的时钟为准，否则后端与 Doris 的时区/时钟差会让水位越过尚未读取的事件。
            LocalDateTime safeUpperBound = queryAuditSourceNow(clusterId).minusMinutes(
                    Math.max(0, properties.getSafetyLagMinutes()));
            DorisAuditAccessCheckpoint checkpoint = checkpointMapper.selectById(clusterId);
            if (checkpoint == null || checkpoint.getWatermarkTime() == null) {
                checkpoint = initializeCheckpoint(clusterId, source, safeUpperBound);
            } else if (checkpoint.getWatermarkTime().isAfter(safeUpperBound)) {
                // 源时钟回拨。继续处理会把水位写回更早的时刻，之后重复扫描；
                // 去重记录过期后还会重复累计，因此保留游标并显式降级。
                batchService.markFailure(clusterId, auditSourceName,
                        "Doris 审计源时间早于已保存水位，疑似时钟回拨，已暂停推进");
                summaryCache.evictCluster(clusterId);
                log.warn("Doris audit source clock moved backwards, cluster={}, watermark={}, safeUpperBound={}",
                        clusterId, checkpoint.getWatermarkTime(), safeUpperBound);
                return;
            }

            boolean wasReady = "READY".equalsIgnoreCase(checkpoint.getSyncStatus());
            LocalDateTime localCursorTime = wasReady
                    ? checkpoint.getWatermarkTime().minusMinutes(Math.max(0, properties.getOverlapMinutes()))
                    : checkpoint.getWatermarkTime();
            if (checkpoint.getCoverageStart() != null && localCursorTime.isBefore(checkpoint.getCoverageStart())) {
                localCursorTime = checkpoint.getCoverageStart();
            }
            String localCursorKey = wasReady ? "" : nullToEmpty(checkpoint.getWatermarkEventKey());
            LocalDateTime persistedWatermarkTime = checkpoint.getWatermarkTime();
            String persistedWatermarkKey = nullToEmpty(checkpoint.getWatermarkEventKey());

            int totalAccepted = 0;
            int totalDuplicates = 0;
            int totalRawRows = 0;
            int totalDailyRows = 0;
            int totalUserRows = 0;
            int batches = 0;
            boolean caughtUp = false;

            while (System.currentTimeMillis() - startedAt < MAX_CLUSTER_RUN_MILLIS) {
                AuditBatch batch = queryBatch(
                        clusterId, source, localCursorTime, localCursorKey, safeUpperBound);
                if (batch.getRawRowCount() == 0) {
                    caughtUp = true;
                    break;
                }

                if (batch.getLastCursorTime().isAfter(persistedWatermarkTime)
                        || (batch.getLastCursorTime().isEqual(persistedWatermarkTime)
                        && batch.getLastCursorKey().compareTo(persistedWatermarkKey) > 0)) {
                    persistedWatermarkTime = batch.getLastCursorTime();
                    persistedWatermarkKey = batch.getLastCursorKey();
                }

                DorisAuditAccessBatchService.BatchApplyResult result = batchService.applyBatch(
                        clusterId,
                        auditSourceName,
                        checkpoint.getCoverageStart(),
                        batch.getEvents(),
                        persistedWatermarkTime,
                        persistedWatermarkKey,
                        wasReady ? "READY" : "BACKFILLING");
                totalRawRows += batch.getRawRowCount();
                totalAccepted += result.getAcceptedEvents();
                totalDuplicates += result.getDuplicateEvents();
                totalDailyRows += result.getDailyRows();
                totalUserRows += result.getUserRows();
                batches++;

                localCursorTime = batch.getLastCursorTime();
                localCursorKey = batch.getLastCursorKey();
                if (batch.getRawRowCount() < safeBatchSize()) {
                    caughtUp = true;
                    break;
                }
            }

            if (caughtUp) {
                batchService.advanceCheckpoint(
                        clusterId, auditSourceName, checkpoint.getCoverageStart(), safeUpperBound, "READY");
            }
            summaryCache.evictCluster(clusterId);
            long checkpointLagSeconds = caughtUp
                    ? 0L
                    : Math.max(0L, Duration.between(persistedWatermarkTime, safeUpperBound).getSeconds());
            log.info("Doris audit access sync finished, cluster={}, source={}, batches={}, rawRows={}, "
                            + "accepted={}, duplicates={}, dailyRows={}, userRows={}, checkpointLagSeconds={}, "
                            + "caughtUp={}, elapsedMs={}",
                    clusterId, auditSourceName, batches, totalRawRows, totalAccepted, totalDuplicates,
                    totalDailyRows, totalUserRows, checkpointLagSeconds, caughtUp,
                    System.currentTimeMillis() - startedAt);
        } catch (Exception e) {
            batchService.markFailure(clusterId, auditSourceName, e.getMessage());
            summaryCache.evictCluster(clusterId);
            log.warn("Doris audit access sync failed, cluster={}, source={}, reason={}",
                    clusterId, auditSourceName, e.getMessage(), e);
        }
    }

    public void cleanupExpiredData() {
        LocalDateTime eventThreshold = LocalDateTime.now()
                .minusDays(Math.max(1, properties.getProcessedEventRetentionDays()));
        LocalDate summaryThreshold = LocalDate.now()
                .minusDays(Math.max(1, properties.getSummaryRetentionDays()));
        int events = processedEventMapper.deleteBefore(eventThreshold);
        int daily = tableAccessDailyMapper.deleteBefore(summaryThreshold);
        int users = tableAccessUserDailyMapper.deleteBefore(summaryThreshold);
        log.info("Cleaned Doris audit access summaries, processedEvents={}, dailyRows={}, userRows={}",
                events, daily, users);
    }

    /**
     * 汇总只做增量累积，不从审计表回填历史。
     * <p>
     * 覆盖起点即同步启动时间。已有汇总的最早日期只能证明那天存在一条记录，
     * 不能证明此后连续完整，因此不用它推导可信覆盖——宁可重新攒满窗口，也不要基于可能有缺口的
     * 历史给出冷表结论。
     */
    private DorisAuditAccessCheckpoint initializeCheckpoint(Long clusterId,
            AuditSource source,
            LocalDateTime safeUpperBound) {
        batchService.initializeCheckpoint(
                clusterId, source.qualifiedName(), safeUpperBound, safeUpperBound);
        return checkpointMapper.selectById(clusterId);
    }

    private LocalDateTime queryAuditSourceNow(Long clusterId) throws SQLException {
        try (Connection connection = dorisConnectionService.getConnection(clusterId);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT NOW() AS source_now")) {
            LocalDateTime sourceNow = resultSet.next()
                    ? parseLocalDateTime(resultSet.getObject("source_now"))
                    : null;
            if (sourceNow == null) {
                throw new SQLException("无法读取 Doris 审计源当前时间");
            }
            long skewSeconds = Duration.between(LocalDateTime.now(), sourceNow).getSeconds();
            if (Math.abs(skewSeconds) > Math.max(0, properties.getOverlapMinutes()) * 60L) {
                log.warn("Doris audit source clock differs from backend, cluster={}, skewSeconds={}",
                        clusterId, skewSeconds);
            }
            return sourceNow;
        }
    }

    private AuditBatch queryBatch(Long clusterId,
            AuditSource source,
            LocalDateTime cursorTime,
            String cursorKey,
            LocalDateTime upperBound) throws SQLException {
        List<DorisAuditAccessEvent> events = new ArrayList<>();
        LocalDateTime lastCursorTime = cursorTime;
        String lastCursorKey = nullToEmpty(cursorKey);
        int rawRowCount = 0;
        String userSelect = source.userColumn == null ? "NULL" : wrapIdentifier(source.userColumn);
        String durationSelect = source.durationColumn == null ? "NULL" : wrapIdentifier(source.durationColumn);
        String statementIdSelect = source.statementIdColumn == null
                ? "NULL"
                : wrapIdentifier(source.statementIdColumn);
        String queryIdSelect = source.queryIdColumn == null
                ? "NULL"
                : wrapIdentifier(source.queryIdColumn);
        String cursorExpression = source.cursorExpression();
        String sql = "SELECT "
                + wrapIdentifier(source.timeColumn) + " AS event_time, "
                + wrapIdentifier(source.dbColumn) + " AS database_name, "
                + wrapIdentifier(source.statementColumn) + " AS statement_text, "
                + userSelect + " AS user_name, "
                + durationSelect + " AS query_time_ms, "
                + statementIdSelect + " AS statement_id, "
                + queryIdSelect + " AS query_id, "
                + cursorExpression + " AS cursor_key "
                + "FROM " + source.qualifiedName() + " WHERE ("
                + wrapIdentifier(source.timeColumn) + " > ? OR ("
                + wrapIdentifier(source.timeColumn) + " = ? AND " + cursorExpression + " > ?))"
                + " AND " + wrapIdentifier(source.timeColumn) + " <= ?"
                + " ORDER BY " + wrapIdentifier(source.timeColumn) + " ASC, " + cursorExpression + " ASC"
                + " LIMIT " + safeBatchSize();

        try (Connection connection = dorisConnectionService.getConnection(clusterId);
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setTimestamp(1, Timestamp.valueOf(cursorTime));
            statement.setTimestamp(2, Timestamp.valueOf(cursorTime));
            statement.setString(3, nullToEmpty(cursorKey));
            statement.setTimestamp(4, Timestamp.valueOf(upperBound));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rawRowCount++;
                    LocalDateTime eventTime = parseLocalDateTime(resultSet.getObject("event_time"));
                    String cursor = nullToEmpty(resultSet.getString("cursor_key"));
                    if (eventTime != null) {
                        lastCursorTime = eventTime;
                        lastCursorKey = cursor;
                    }
                    String sqlText = resultSet.getString("statement_text");
                    if (eventTime == null || !StringUtils.hasText(sqlText)) {
                        continue;
                    }
                    String defaultDatabase = normalizeIdentifier(resultSet.getString("database_name"));
                    List<AuditTableReference> references = sqlTableParser.parse(sqlText, defaultDatabase);
                    if (references.isEmpty()) {
                        continue;
                    }
                    String user = resultSet.getString("user_name");
                    String statementId = resultSet.getString("statement_id");
                    String queryId = resultSet.getString("query_id");

                    DorisAuditAccessEvent event = new DorisAuditAccessEvent();
                    event.setCursorKey(cursor);
                    event.setEventTime(eventTime);
                    event.setUserName(user);
                    event.setQueryTimeMs(parseNullableLong(resultSet.getObject("query_time_ms")));
                    event.setEventKey(StringUtils.hasText(queryId)
                            ? truncate(queryId, 128)
                            : hashEvent(clusterId, eventTime, statementId, user, sqlText));
                    event.setTableReferences(references);
                    events.add(event);
                }
            }
        }
        return new AuditBatch(events, rawRowCount, lastCursorTime, lastCursorKey);
    }

    private AuditSource resolveAuditSource(Long clusterId) throws SQLException {
        try (Connection connection = dorisConnectionService.getConnection(clusterId);
                Statement statement = connection.createStatement()) {
            String[][] candidates = {
                    {"__internal_schema", "audit_log"},
                    {"doris_audit_db__", "doris_audit_tbl__"}
            };
            for (String[] candidate : candidates) {
                String sql = "SELECT * FROM " + wrapTable(candidate[0], candidate[1]) + " LIMIT 1";
                try (ResultSet resultSet = statement.executeQuery(sql)) {
                    AuditSource source = buildAuditSource(candidate[0], candidate[1], resultSet.getMetaData());
                    if (source != null) {
                        return source;
                    }
                } catch (SQLException ignored) {
                    // 尝试下一个已知审计源。
                }
            }
        }
        return null;
    }

    private AuditSource buildAuditSource(String database, String table, ResultSetMetaData metadata) throws SQLException {
        Map<String, String> columns = new HashMap<>();
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            if (!StringUtils.hasText(label)) {
                label = metadata.getColumnName(index);
            }
            columns.put(label.toLowerCase(Locale.ROOT), label);
        }
        String time = firstPresent(columns, "time", "event_time", "log_time");
        String db = firstPresent(columns, "db", "database", "db_name");
        String statement = firstPresent(columns, "stmt", "statement", "sql");
        if (time == null || db == null || statement == null) {
            return null;
        }
        String queryId = firstPresent(columns, "query_id", "queryid");
        String statementId = firstPresent(columns, "stmt_id", "statement_id", "stmtid");
        return new AuditSource(
                database,
                table,
                time,
                db,
                statement,
                firstPresent(columns, "user", "qualified_user", "username"),
                firstPresent(columns, "query_time", "query_time_ms", "latency_ms"),
                queryId,
                statementId);
    }

    private boolean hasManagedTables(Long clusterId) {
        return dataTableMapper.selectCount(new LambdaQueryWrapper<DataTable>()
                .eq(DataTable::getClusterId, clusterId)
                .isNotNull(DataTable::getDbName)
                .isNotNull(DataTable::getTableName)
                .ne(DataTable::getStatus, "deprecated")) > 0L;
    }

    private int safeBatchSize() {
        return Math.max(100, Math.min(properties.getBatchSize(), 20_000));
    }

    private String hashEvent(Long clusterId,
            LocalDateTime eventTime,
            String statementId,
            String user,
            String sql) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String material = clusterId + "|" + eventTime + "|"
                    + nullToEmpty(user) + "|" + nullToEmpty(statementId) + "|" + sql;
            byte[] bytes = digest.digest(material.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                result.append(String.format("%02x", value & 0xff));
            }
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("无法生成审计事件标识", e);
        }
    }

    private String firstPresent(Map<String, String> columns, String... candidates) {
        for (String candidate : candidates) {
            String value = columns.get(candidate.toLowerCase(Locale.ROOT));
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private LocalDateTime parseLocalDateTime(Object value) {
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof Date) {
            return new Timestamp(((Date) value).getTime()).toLocalDateTime();
        }
        if (value != null) {
            try {
                return Timestamp.valueOf(String.valueOf(value)).toLocalDateTime();
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }

    private Long parseNullableLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String normalizeIdentifier(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.replace("`", "").trim().toLowerCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String truncate(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    private String wrapIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private String wrapTable(String database, String table) {
        return wrapIdentifier(database) + "." + wrapIdentifier(table);
    }

    private class AuditSource {
        private final String database;
        private final String table;
        private final String timeColumn;
        private final String dbColumn;
        private final String statementColumn;
        private final String userColumn;
        private final String durationColumn;
        private final String queryIdColumn;
        private final String statementIdColumn;

        private AuditSource(String database,
                String table,
                String timeColumn,
                String dbColumn,
                String statementColumn,
                String userColumn,
                String durationColumn,
                String queryIdColumn,
                String statementIdColumn) {
            this.database = database;
            this.table = table;
            this.timeColumn = timeColumn;
            this.dbColumn = dbColumn;
            this.statementColumn = statementColumn;
            this.userColumn = userColumn;
            this.durationColumn = durationColumn;
            this.queryIdColumn = queryIdColumn;
            this.statementIdColumn = statementIdColumn;
        }

        private String cursorExpression() {
            String user = userColumn == null ? "''" : "COALESCE(" + wrapIdentifier(userColumn) + ", '')";
            String statementId = statementIdColumn == null
                    ? "''"
                    : "COALESCE(CAST(" + wrapIdentifier(statementIdColumn) + " AS STRING), '')";
            String fallback = "MD5(CONCAT(CAST(" + wrapIdentifier(timeColumn)
                    + " AS STRING), '|', " + user + ", '|', " + statementId + ", '|', "
                    + "COALESCE(" + wrapIdentifier(statementColumn) + ", '')))";
            if (queryIdColumn != null) {
                return "COALESCE(NULLIF(CAST(" + wrapIdentifier(queryIdColumn)
                        + " AS STRING), ''), " + fallback + ")";
            }
            if (statementIdColumn != null) {
                return "COALESCE(NULLIF(CAST(" + wrapIdentifier(statementIdColumn)
                        + " AS STRING), ''), " + fallback + ")";
            }
            return fallback;
        }

        private String qualifiedName() {
            return wrapTable(database, table);
        }
    }

    private static class AuditBatch {
        private final List<DorisAuditAccessEvent> events;
        private final int rawRowCount;
        private final LocalDateTime lastCursorTime;
        private final String lastCursorKey;

        private AuditBatch(List<DorisAuditAccessEvent> events,
                int rawRowCount,
                LocalDateTime lastCursorTime,
                String lastCursorKey) {
            this.events = events;
            this.rawRowCount = rawRowCount;
            this.lastCursorTime = lastCursorTime;
            this.lastCursorKey = lastCursorKey;
        }

        private List<DorisAuditAccessEvent> getEvents() {
            return events;
        }

        private int getRawRowCount() {
            return rawRowCount;
        }

        private LocalDateTime getLastCursorTime() {
            return lastCursorTime;
        }

        private String getLastCursorKey() {
            return lastCursorKey;
        }
    }
}
