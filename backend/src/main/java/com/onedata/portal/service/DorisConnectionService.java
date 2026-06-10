package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.portal.dto.DorisCredential;
import com.onedata.portal.dto.TableStatistics;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.util.DorisCreateTableUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Doris 连接服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DorisConnectionService {

    private final DorisClusterMapper dorisClusterMapper;
    private final DorisJdbcProperties dorisJdbcProperties;
    private final UserMappingService userMappingService;
    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");
    private static final Pattern SIZE_WITH_UNIT_PATTERN = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*([KMGT]?B)?$",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> SYSTEM_DATABASES = new HashSet<>(
            Arrays.asList("information_schema", "mysql", "performance_schema", "sys"));

    /**
     * 执行 SQL (主要用于创建表等 DDL)
     */
    public void execute(Long clusterId, String sql) {
        execute(clusterId, null, sql);
    }

    /**
     * 在指定数据库中执行 SQL
     */
    public void execute(Long clusterId, String database, String sql) {
        DorisCluster cluster = resolveCluster(clusterId);
        String targetDb = StringUtils.hasText(database) ? database : dorisJdbcProperties.getDefaultDatabase();
        try (Connection connection = getConnection(cluster, database);
                Statement statement = connection.createStatement()) {
            log.info("Executing SQL on Doris cluster {} (db={}): {}", cluster.getClusterName(), targetDb,
                    abbreviate(sql));
            statement.execute(sql);
        } catch (SQLException e) {
            log.error("Failed to execute SQL on Doris cluster {} (db={})", cluster.getClusterName(), targetDb, e);
            throw new RuntimeException("执行 Doris SQL 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 测试连接
     */
    public boolean testConnection(Long clusterId) {
        DorisCluster cluster = resolveCluster(clusterId);
        try (Connection ignored = getConnection(cluster, null)) {
            return true;
        } catch (SQLException e) {
            log.warn("Doris connection test failed for cluster {}", cluster.getClusterName(), e);
            return false;
        }
    }

    /**
     * 获取连接
     */
    public Connection getConnection(Long clusterId) throws SQLException {
        DorisCluster cluster = resolveCluster(clusterId);
        return getConnection(cluster, null);
    }

    /**
     * 获取连接并指定数据库
     */
    public Connection getConnection(Long clusterId, String database) throws SQLException {
        DorisCluster cluster = resolveCluster(clusterId);
        return getConnection(cluster, database);
    }

    /**
     * 获取连接（使用用户上下文自动选择凭据）
     * 如果当前有用户上下文，则使用用户对应的Doris凭据
     * 否则使用集群默认凭据
     */
    private Connection getConnection(DorisCluster cluster, String database) throws SQLException {
        String targetDb = StringUtils.hasText(database) ? database : dorisJdbcProperties.getDefaultDatabase();
        String url = buildJdbcUrl(cluster, targetDb);

        // 尝试从用户上下文获取用户ID
        String userId = UserContextHolder.getCurrentUserId();

        String username;
        String password;

        if (userId != null && StringUtils.hasText(database)) {
            // 有用户上下文且指定了数据库，使用用户映射的Doris凭据
            try {
                DorisCredential credential = userMappingService.getDorisCredential(userId, cluster.getId(), database);
                username = credential.getUsername();
                password = credential.getPassword();
                log.debug("Using user-mapped Doris credential for user {} on database {}", userId, database);
            } catch (Exception e) {
                // 如果获取用户凭据失败，记录警告并使用集群默认凭据
                log.warn(
                        "Failed to get user-mapped credential for user {} on database {}, falling back to cluster default: {}",
                        userId, database, e.getMessage());
                username = cluster.getUsername();
                password = cluster.getPassword() == null ? "" : cluster.getPassword();
            }
        } else {
            // 没有用户上下文或未指定数据库，使用集群默认凭据
            username = cluster.getUsername();
            password = cluster.getPassword() == null ? "" : cluster.getPassword();
            if (userId == null) {
                log.debug("No user context found, using cluster default credential");
            } else {
                log.debug("No database specified, using cluster default credential");
            }
        }

        Connection connection = DriverManager.getConnection(url, username, password);
        applySessionCharset(connection);
        return connection;
    }

    private String buildJdbcUrl(DorisCluster cluster, String database) {
        String template = dorisJdbcProperties.getUrlTemplate();
        if (!StringUtils.hasText(template)) {
            throw new IllegalStateException("doris.jdbc.url-template 未配置，请在 application.yml 或环境变量中指定");
        }
        return String.format(template, cluster.getFeHost(), cluster.getFePort(), database);
    }

    private DorisCluster resolveCluster(Long clusterId) {
        if (clusterId == null) {
            throw new RuntimeException("请指定 Doris 集群");
        }
        DorisCluster cluster = dorisClusterMapper.selectById(clusterId);
        if (cluster == null) {
            throw new RuntimeException("未找到指定的 Doris 集群: " + clusterId);
        }
        return cluster;
    }

    public Optional<TableRuntimeStats> getTableRuntimeStats(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);

        try (Connection connection = getConnection(cluster, null)) {
            Optional<TableRuntimeStats> stats = queryTableStats(connection, database, tableName);
            if (stats.isPresent()) {
                return stats;
            }
            return queryTableStatsFallback(connection, database, tableName);
        } catch (SQLException e) {
            log.warn("Failed to fetch runtime stats for {}.{}, reason={}", database, tableName, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<TableRuntimeStats> queryTableStats(Connection connection, String database, String tableName) {
        String sql = String.format("SHOW TABLE STATS `%s`.`%s`", database, tableName);
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                Map<String, Object> row = extractRow(rs);
                TableRuntimeStats stats = new TableRuntimeStats();
                stats.setRowCount(getLong(row, "RowCount", "row_count", "rows"));
                stats.setDataSize(getLong(row, "DataSize", "data_size"));
                stats.setLastUpdate(
                        toTimestamp(row, "UpdateTime", "update_time", "LastAnalyzeTime", "last_update_time"));
                return Optional.of(stats);
            }
        } catch (SQLException e) {
            log.debug("SHOW TABLE STATS not available for {}.{}, fallback to information_schema.table_stats", database,
                    tableName);
        }
        return Optional.empty();
    }

    private Optional<TableRuntimeStats> queryTableStatsFallback(Connection connection, String database,
            String tableName) {
        String sql = "SELECT row_count, data_size, update_time FROM information_schema.table_stats WHERE table_schema = ? AND table_name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    TableRuntimeStats stats = new TableRuntimeStats();
                    stats.setRowCount(rs.getLong("row_count"));
                    stats.setDataSize(rs.getLong("data_size"));
                    stats.setLastUpdate(rs.getTimestamp("update_time"));
                    return Optional.of(stats);
                }
            }
        } catch (SQLException e) {
            log.debug("information_schema.table_stats not accessible for {}.{}, reason={}", database, tableName,
                    e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 获取表真实 Tablet 统计（基于 SHOW TABLETS）。
     * 说明：SHOW TABLETS 一般返回每个 Replica 行，这里按 TabletId 去重并取最大 DataSize 作为单 Tablet 大小。
     */
    public Optional<TableTabletStats> getTableTabletStats(Long clusterId, String database, String tableName) {
        if (clusterId == null || !StringUtils.hasText(database) || !StringUtils.hasText(tableName)) {
            return Optional.empty();
        }
        DorisCluster cluster = resolveCluster(clusterId);
        try (Connection connection = getConnection(cluster, null)) {
            return queryTableTabletStats(connection, database.trim(), tableName.trim());
        } catch (SQLException e) {
            log.warn("Failed to fetch tablet stats for {}.{}, reason={}", database, tableName, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<TableTabletStats> queryTableTabletStats(Connection connection, String database, String tableName) {
        String sql = String.format("SHOW TABLETS FROM `%s`.`%s`", database, tableName);
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            Map<Long, Long> tabletSizeMap = new HashMap<>();
            long rowCount = 0L;
            long syntheticTabletId = -1L;

            while (rs.next()) {
                rowCount++;
                Map<String, Object> row = extractRow(rs);
                Long tabletId = getLong(row, "tabletid", "tablet_id");
                Long dataSize = parseSizeToBytes(row.get("datasize"));
                if (dataSize == null || dataSize <= 0) {
                    continue;
                }
                long key = tabletId != null ? tabletId : syntheticTabletId--;
                tabletSizeMap.merge(key, dataSize, Math::max);
            }

            if (tabletSizeMap.isEmpty()) {
                return Optional.empty();
            }

            long totalSize = tabletSizeMap.values().stream().mapToLong(Long::longValue).sum();
            long tabletCount = tabletSizeMap.size();

            TableTabletStats stats = new TableTabletStats();
            stats.setTabletCount(tabletCount);
            stats.setReplicaRowCount(rowCount);
            stats.setTotalDataSizeBytes(totalSize);
            stats.setAvgTabletSizeBytes(totalSize / Math.max(1L, tabletCount));
            return Optional.of(stats);
        } catch (SQLException e) {
            log.warn("SHOW TABLETS failed for {}.{}, reason={}", database, tableName, e.getMessage());
            return Optional.empty();
        }
    }

    private Long parseSizeToBytes(Object rawValue) {
        if (rawValue == null) {
            return null;
        }
        if (rawValue instanceof Number) {
            return ((Number) rawValue).longValue();
        }

        String text = String.valueOf(rawValue).trim();
        if (!StringUtils.hasText(text)) {
            return null;
        }
        String normalized = text.replace(",", "").toUpperCase(Locale.ROOT);
        if (NUMERIC_PATTERN.matcher(normalized).matches()) {
            try {
                return (long) Double.parseDouble(normalized);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        Matcher matcher = SIZE_WITH_UNIT_PATTERN.matcher(normalized);
        if (!matcher.matches()) {
            return null;
        }
        double value;
        try {
            value = Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        String unit = matcher.group(2);
        long multiplier = 1L;
        if ("KB".equalsIgnoreCase(unit)) {
            multiplier = 1024L;
        } else if ("MB".equalsIgnoreCase(unit)) {
            multiplier = 1024L * 1024L;
        } else if ("GB".equalsIgnoreCase(unit)) {
            multiplier = 1024L * 1024L * 1024L;
        } else if ("TB".equalsIgnoreCase(unit)) {
            multiplier = 1024L * 1024L * 1024L * 1024L;
        }
        return (long) (value * multiplier);
    }

    private Map<String, Object> extractRow(ResultSet rs) throws SQLException {
        Map<String, Object> row = new HashMap<>();
        ResultSetMetaData metaData = rs.getMetaData();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String label = metaData.getColumnLabel(i);
            if (!StringUtils.hasText(label)) {
                label = metaData.getColumnName(i);
            }
            row.put(label.toLowerCase(Locale.ROOT), rs.getObject(i));
        }
        return row;
    }

    private Long getLong(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key.toLowerCase(Locale.ROOT));
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            if (value instanceof String && NUMERIC_PATTERN.matcher(((String) value).trim()).matches()) {
                try {
                    return (long) Double.parseDouble(((String) value).trim());
                } catch (NumberFormatException ignore) {
                    // ignore malformed numeric
                }
            }
        }
        return null;
    }

    private Timestamp toTimestamp(Map<String, Object> row, String... keys) {
        for (String key : keys) {
            Object value = row.get(key.toLowerCase(Locale.ROOT));
            if (value instanceof Timestamp) {
                return (Timestamp) value;
            }
            if (value instanceof java.util.Date) {
                return new Timestamp(((java.util.Date) value).getTime());
            }
            if (value instanceof String) {
                try {
                    return Timestamp.valueOf((String) value);
                } catch (IllegalArgumentException ignore) {
                    // ignore malformed timestamp
                }
            }
        }
        return null;
    }

    public static class TableRuntimeStats {
        private Long rowCount;
        private Long dataSize;
        private Timestamp lastUpdate;

        public Long getRowCount() {
            return rowCount;
        }

        public void setRowCount(Long rowCount) {
            this.rowCount = rowCount;
        }

        public Long getDataSize() {
            return dataSize;
        }

        public void setDataSize(Long dataSize) {
            this.dataSize = dataSize;
        }

        public Timestamp getLastUpdate() {
            return lastUpdate;
        }

        public void setLastUpdate(Timestamp lastUpdate) {
            this.lastUpdate = lastUpdate;
        }
    }

    public static class TableTabletStats {
        private long tabletCount;
        private long replicaRowCount;
        private long totalDataSizeBytes;
        private long avgTabletSizeBytes;

        public long getTabletCount() {
            return tabletCount;
        }

        public void setTabletCount(long tabletCount) {
            this.tabletCount = tabletCount;
        }

        public long getReplicaRowCount() {
            return replicaRowCount;
        }

        public void setReplicaRowCount(long replicaRowCount) {
            this.replicaRowCount = replicaRowCount;
        }

        public long getTotalDataSizeBytes() {
            return totalDataSizeBytes;
        }

        public void setTotalDataSizeBytes(long totalDataSizeBytes) {
            this.totalDataSizeBytes = totalDataSizeBytes;
        }

        public long getAvgTabletSizeBytes() {
            return avgTabletSizeBytes;
        }

        public void setAvgTabletSizeBytes(long avgTabletSizeBytes) {
            this.avgTabletSizeBytes = avgTabletSizeBytes;
        }
    }

    private void applySessionCharset(Connection connection) {
        if (!dorisJdbcProperties.isSessionCharsetEnabled()) {
            return;
        }
        String primaryCharset = dorisJdbcProperties.getSessionCharset();
        if (!StringUtils.hasText(primaryCharset)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SET NAMES " + primaryCharset);
        } catch (SQLException primaryEx) {
            String fallbackCharset = dorisJdbcProperties.getSessionCharsetFallback();
            if (!StringUtils.hasText(fallbackCharset) || fallbackCharset.equalsIgnoreCase(primaryCharset)) {
                log.warn("Failed to set Doris session charset to {}. reason={}", primaryCharset,
                        primaryEx.getMessage());
                return;
            }
            log.warn("Doris does not support {} charset, fallback to {}. reason={}", primaryCharset, fallbackCharset,
                    primaryEx.getMessage());
            try (Statement fallback = connection.createStatement()) {
                fallback.execute("SET NAMES " + fallbackCharset);
            } catch (SQLException secondaryEx) {
                log.warn("Failed to set Doris session charset to {}. reason={}", fallbackCharset,
                        secondaryEx.getMessage());
            }
        }
    }

    private String abbreviate(String sql) {
        if (!StringUtils.hasText(sql)) {
            return "";
        }
        String trimmed = sql.replaceAll("\\s+", " ").trim();
        return trimmed.length() > 200 ? trimmed.substring(0, 200) + "..." : trimmed;
    }

    /**
     * 修改表注释
     */
    public void alterTableComment(Long clusterId, String database, String tableName, String comment) {
        // Doris 修改表注释的语法: ALTER TABLE db.table MODIFY COMMENT 'xxx'
        String escapedComment = comment.replace("'", "''");
        String sql = String.format("ALTER TABLE `%s`.`%s` MODIFY COMMENT '%s'", database, tableName, escapedComment);
        execute(clusterId, database, sql);
        log.info("Altered table comment for {}.{}", database, tableName);
    }

    /**
     * 重命名表
     */
    public void renameTable(Long clusterId, String database, String oldTableName, String newTableName) {
        // Doris 重命名表的语法: ALTER TABLE db.old_table RENAME new_table
        String sql = String.format("ALTER TABLE `%s`.`%s` RENAME `%s`", database, oldTableName, newTableName);
        execute(clusterId, database, sql);
        log.info("Renamed table {}.{} to {}.{}", database, oldTableName, database, newTableName);
    }

    /**
     * 删除表
     */
    public void dropTable(Long clusterId, String database, String tableName) {
        String sql = String.format("DROP TABLE IF EXISTS `%s`.`%s`", database, tableName);
        execute(clusterId, database, sql);
        log.info("Dropped table {}.{}", database, tableName);
    }

    /**
     * 添加列
     */
    public void addColumn(Long clusterId, String database, String tableName, String columnDefinition) {
        String sql = String.format("ALTER TABLE `%s`.`%s` ADD COLUMN %s", database, tableName, columnDefinition);
        execute(clusterId, database, sql);
    }

    /**
     * 修改列（类型/注释/默认值等）
     */
    public void modifyColumn(Long clusterId, String database, String tableName, String columnDefinition) {
        String sql = String.format("ALTER TABLE `%s`.`%s` MODIFY COLUMN %s", database, tableName, columnDefinition);
        execute(clusterId, database, sql);
    }

    /**
     * 修改列注释（构造完整列定义）
     */
    public void modifyColumnComment(Long clusterId, String database, String tableName, String columnName, String comment) {
        String escapedComment = comment != null ? escapeSingleQuote(comment) : "";
        String sql = String.format("ALTER TABLE `%s`.`%s` MODIFY COLUMN `%s` COMMENT '%s'",
                database, tableName, columnName, escapedComment);
        execute(clusterId, database, sql);
    }

    /**
     * 删除列
     */
    public void dropColumn(Long clusterId, String database, String tableName, String columnName) {
        String sql = String.format("ALTER TABLE `%s`.`%s` DROP COLUMN `%s`", database, tableName, columnName);
        execute(clusterId, database, sql);
    }

    /**
     * 重命名列
     */
    public void renameColumn(Long clusterId, String database, String tableName, String oldName, String newName) {
        String sql = String.format("ALTER TABLE `%s`.`%s` RENAME COLUMN `%s` `%s`",
                database, tableName, oldName, newName);
        execute(clusterId, database, sql);
    }

    /**
     * 修改分布（分桶数）
     */
    public void modifyDistribution(Long clusterId, String database, String tableName, String distributionColumn, Integer bucketNum) {
        String columns = wrapColumnList(distributionColumn);
        String sql = String.format("ALTER TABLE `%s`.`%s` MODIFY DISTRIBUTION DISTRIBUTED BY HASH(%s) BUCKETS %d",
                database, tableName, columns, bucketNum);
        execute(clusterId, database, sql);
    }

    /**
     * 修改副本数
     */
    public void setReplicationNum(Long clusterId, String database, String tableName, Integer replicaNum) {
        String sql = String.format("ALTER TABLE `%s`.`%s` SET (\"replication_num\" = \"%d\")",
                database, tableName, replicaNum);
        execute(clusterId, database, sql);
    }

    /**
     * 生成 Doris 列定义
     */
    public String buildColumnDefinition(DataField field, boolean isKey) {
        StringBuilder builder = new StringBuilder();
        String fieldName = field.getFieldName();
        String fieldType = field.getFieldType();
        builder.append(wrapColumn(fieldName)).append(" ").append(fieldType);
        if (field.getIsNullable() != null && field.getIsNullable() == 0) {
            builder.append(" NOT NULL");
        } else {
            builder.append(" NULL");
        }
        if (StringUtils.hasText(field.getDefaultValue())) {
            builder.append(" DEFAULT ").append(formatDefaultValue(field.getDefaultValue()));
        }
        if (StringUtils.hasText(field.getFieldComment())) {
            builder.append(" COMMENT '").append(escapeSingleQuote(field.getFieldComment())).append("'");
        }
        return builder.toString();
    }

    private String wrapColumn(String column) {
        return "`" + column + "`";
    }

    private String wrapColumnList(String columns) {
        if (!StringUtils.hasText(columns)) {
            return "";
        }
        String[] parts = columns.split(",");
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String name = parts[i].trim();
            if (!StringUtils.hasText(name)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(wrapColumn(name));
        }
        return builder.toString();
    }

    private String formatDefaultValue(String defaultValue) {
        String value = defaultValue.trim();
        if ("null".equalsIgnoreCase(value)) {
            return "NULL";
        }
        if ("current_timestamp".equalsIgnoreCase(value) || value.toUpperCase().startsWith("NOW(")) {
            return value;
        }
        if (NUMERIC_PATTERN.matcher(value).matches()) {
            return value;
        }
        if (value.startsWith("'") && value.endsWith("'")) {
            return value;
        }
        return "'" + escapeSingleQuote(value) + "'";
    }

    private String escapeSingleQuote(String input) {
        return input.replace("'", "''");
    }

    /**
     * 获取表统计信息
     */
    public TableStatistics getTableStatistics(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);

        String sql = "SELECT " +
                "TABLE_SCHEMA, " +
                "TABLE_NAME, " +
                "TABLE_TYPE, " +
                "TABLE_COMMENT, " +
                "CREATE_TIME, " +
                "UPDATE_TIME, " +
                "TABLE_ROWS, " +
                "DATA_LENGTH, " +
                "ENGINE " +
                "FROM information_schema.tables " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

        try (Connection connection = getConnection(cluster, null);
                PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, database);
            stmt.setString(2, tableName);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    TableStatistics stats = new TableStatistics();
                    stats.setDatabaseName(rs.getString("TABLE_SCHEMA"));
                    stats.setTableName(rs.getString("TABLE_NAME"));
                    stats.setTableType(rs.getString("TABLE_TYPE"));
                    stats.setTableComment(rs.getString("TABLE_COMMENT"));

                    Timestamp createTimestamp = rs.getTimestamp("CREATE_TIME");
                    if (createTimestamp != null) {
                        stats.setCreateTime(createTimestamp.toLocalDateTime());
                    }

                    Timestamp updateTimestamp = rs.getTimestamp("UPDATE_TIME");
                    if (updateTimestamp != null) {
                        stats.setLastUpdateTime(updateTimestamp.toLocalDateTime());
                    }

                    stats.setRowCount(rs.getLong("TABLE_ROWS"));

                    long dataSize = rs.getLong("DATA_LENGTH");
                    stats.setDataSize(dataSize);
                    stats.setDataSizeReadable(formatBytes(dataSize));

                    stats.setEngine(rs.getString("ENGINE"));
                    stats.setAvailable(true);
                    stats.setLastCheckTime(LocalDateTime.now());

                    // 获取分区和副本信息
                    enrichTableDetails(connection, database, tableName, stats);

                    return stats;
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get table statistics for {}.{}", database, tableName, e);
            throw new RuntimeException("获取表统计信息失败: " + e.getMessage(), e);
        }

        throw new RuntimeException(String.format("表 %s.%s 不存在", database, tableName));
    }

    /**
     * 获取所有表的统计信息
     */
    public List<TableStatistics> getAllTableStatistics(Long clusterId, String database) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<TableStatistics> result = new ArrayList<>();

        String sql = "SELECT " +
                "TABLE_SCHEMA, " +
                "TABLE_NAME, " +
                "TABLE_TYPE, " +
                "TABLE_COMMENT, " +
                "CREATE_TIME, " +
                "UPDATE_TIME, " +
                "TABLE_ROWS, " +
                "DATA_LENGTH, " +
                "ENGINE " +
                "FROM information_schema.tables " +
                "WHERE TABLE_SCHEMA = ? " +
                "ORDER BY TABLE_NAME";

        try (Connection connection = getConnection(cluster, null);
                PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, database);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TableStatistics stats = new TableStatistics();
                    stats.setDatabaseName(rs.getString("TABLE_SCHEMA"));
                    stats.setTableName(rs.getString("TABLE_NAME"));
                    stats.setTableType(rs.getString("TABLE_TYPE"));
                    stats.setTableComment(rs.getString("TABLE_COMMENT"));

                    Timestamp createTimestamp = rs.getTimestamp("CREATE_TIME");
                    if (createTimestamp != null) {
                        stats.setCreateTime(createTimestamp.toLocalDateTime());
                    }

                    Timestamp updateTimestamp = rs.getTimestamp("UPDATE_TIME");
                    if (updateTimestamp != null) {
                        stats.setLastUpdateTime(updateTimestamp.toLocalDateTime());
                    }

                    stats.setRowCount(rs.getLong("TABLE_ROWS"));

                    long dataSize = rs.getLong("DATA_LENGTH");
                    stats.setDataSize(dataSize);
                    stats.setDataSizeReadable(formatBytes(dataSize));

                    stats.setEngine(rs.getString("ENGINE"));
                    stats.setAvailable(true);
                    stats.setLastCheckTime(LocalDateTime.now());

                    result.add(stats);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get all table statistics for database {}", database, e);
            throw new RuntimeException("获取数据库表统计信息失败: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * 丰富表详细信息（分区数、副本数、分桶数）
     */
    private void enrichTableDetails(Connection connection, String database, String tableName, TableStatistics stats) {
        // 查询分区信息
        String partitionSql = "SELECT COUNT(*) as partition_count FROM information_schema.partitions " +
                "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (PreparedStatement stmt = connection.prepareStatement(partitionSql)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.setPartitionCount(rs.getInt("partition_count"));
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to get partition count for {}.{}", database, tableName, e);
        }

        // 查询副本和分桶信息（从 SHOW CREATE TABLE 中解析）
        String showCreateSql = "SHOW CREATE TABLE " + database + "." + tableName;
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(showCreateSql)) {
            if (rs.next()) {
                String createTableSql = rs.getString(2);

                // 解析副本数（支持 replication_allocation / dynamic_partition.replication_allocation）
                Integer replicationNum = DorisCreateTableUtils.parseReplicationNum(createTableSql);
                if (replicationNum != null) {
                    stats.setReplicationNum(replicationNum);
                }

                // 解析分桶数
                if (createTableSql.contains("BUCKETS ")) {
                    int start = createTableSql.indexOf("BUCKETS ") + 8;
                    int end = start;
                    while (end < createTableSql.length() && Character.isDigit(createTableSql.charAt(end))) {
                        end++;
                    }
                    if (end > start) {
                        try {
                            stats.setBucketNum(Integer.parseInt(createTableSql.substring(start, end)));
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse bucket num", e);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("Failed to get table details from SHOW CREATE TABLE for {}.{}", database, tableName, e);
        }
    }

    /**
     * 获取表的建表语句（DDL）
     */
    public String getTableDdl(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);
        String showCreateSql = "SHOW CREATE TABLE `" + database + "`.`" + tableName + "`";

        try (Connection connection = getConnection(cluster, null);
                Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(showCreateSql)) {

            if (rs.next()) {
                return rs.getString(2);
            }
        } catch (SQLException e) {
            log.error("Failed to get DDL for {}.{}", database, tableName, e);
            throw new RuntimeException("获取建表语句失败: " + e.getMessage(), e);
        }

        throw new RuntimeException(String.format("表 %s.%s 不存在", database, tableName));
    }

    /**
     * 预览表数据
     */
    public List<Map<String, Object>> previewTableData(Long clusterId, String database, String tableName, int limit) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<Map<String, Object>> result = new ArrayList<>();

        // 限制最大预览行数
        int maxLimit = Math.min(limit, 1000);
        String sql = "SELECT * FROM `" + database + "`.`" + tableName + "` LIMIT " + maxLimit;

        try (Connection connection = getConnection(cluster, database);
                Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    Object value = rs.getObject(i);
                    row.put(columnName, value);
                }
                result.add(row);
            }
        } catch (SQLException e) {
            log.error("Failed to preview data for {}.{}", database, tableName, e);
            throw new RuntimeException("预览表数据失败: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * 格式化字节数为可读格式
     */
    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024) {
            return String.format("%.2f KB", kb);
        }
        double mb = kb / 1024.0;
        if (mb < 1024) {
            return String.format("%.2f MB", mb);
        }
        double gb = mb / 1024.0;
        if (gb < 1024) {
            return String.format("%.2f GB", gb);
        }
        double tb = gb / 1024.0;
        return String.format("%.2f TB", tb);
    }

    /**
     * 获取所有数据库列表
     */
    public List<String> getAllDatabases(Long clusterId) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<String> databases = new ArrayList<>();

        String sql = "SHOW DATABASES";

        try (Connection connection = getConnection(cluster, null);
                Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String dbName = rs.getString(1);
                // 过滤掉系统数据库
                if (!SYSTEM_DATABASES.contains(dbName)) {
                    databases.add(dbName);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get databases from cluster {}", cluster.getClusterName(), e);
            throw new RuntimeException("获取数据库列表失败: " + e.getMessage(), e);
        }

        return databases;
    }

    /**
     * 获取所有 schema 的对象列表（表/视图），支持关键字过滤。
     */
    public List<Map<String, Object>> getSchemaObjects(Long clusterId, String keyword) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<Map<String, Object>> objects = new ArrayList<>();

        StringBuilder sqlBuilder = new StringBuilder("SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_COMMENT ")
                .append("FROM information_schema.tables ")
                .append("WHERE TABLE_SCHEMA NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')");

        boolean hasKeyword = StringUtils.hasText(keyword);
        if (hasKeyword) {
            sqlBuilder.append(" AND (LOCATE(?, LOWER(TABLE_NAME)) > 0 ")
                    .append("OR LOCATE(?, LOWER(IFNULL(TABLE_COMMENT, ''))) > 0)");
        }
        sqlBuilder.append(" ORDER BY TABLE_SCHEMA, TABLE_NAME");

        try (Connection connection = getConnection(cluster, null);
                PreparedStatement stmt = connection.prepareStatement(sqlBuilder.toString())) {
            if (hasKeyword) {
                String normalizedKeyword = normalizeSearchKeyword(keyword);
                stmt.setString(1, normalizedKeyword);
                stmt.setString(2, normalizedKeyword);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> object = new HashMap<>();
                    object.put("schemaName", rs.getString("TABLE_SCHEMA"));
                    object.put("tableName", rs.getString("TABLE_NAME"));
                    object.put("tableType", rs.getString("TABLE_TYPE"));
                    object.put("tableComment", rs.getString("TABLE_COMMENT"));
                    objects.add(object);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list schema objects from cluster {}", cluster.getClusterName(), e);
            throw new RuntimeException("获取 schema 对象列表失败: " + e.getMessage(), e);
        }

        return objects;
    }

    /**
     * 获取指定数据库的所有表
     */
    public List<Map<String, Object>> getTablesInDatabase(Long clusterId, String database) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<Map<String, Object>> tables = new ArrayList<>();

        String sql = "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, CREATE_TIME, UPDATE_TIME, TABLE_ROWS, DATA_LENGTH " +
                "FROM information_schema.tables WHERE TABLE_SCHEMA = ?";

        try (Connection connection = getConnection(cluster, null);
                PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, database);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> table = new HashMap<>();
                    table.put("tableName", rs.getString("TABLE_NAME"));
                    table.put("tableType", rs.getString("TABLE_TYPE"));
                    table.put("tableComment", rs.getString("TABLE_COMMENT"));
                    table.put("createTime", rs.getTimestamp("CREATE_TIME"));
                    table.put("updateTime", rs.getTimestamp("UPDATE_TIME"));
                    table.put("tableRows", rs.getLong("TABLE_ROWS"));
                    table.put("dataLength", rs.getLong("DATA_LENGTH"));
                    tables.add(table);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get tables from database {}", database, e);
            throw new RuntimeException("获取表列表失败: " + e.getMessage(), e);
        }

        return tables;
    }

    /**
     * 获取指定表的所有列信息
     */
    public List<Map<String, Object>> getColumnsInTable(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<Map<String, Object>> columns = new ArrayList<>();

        boolean isMysql = "MYSQL".equalsIgnoreCase(cluster.getSourceType());
        String sql = "SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, ORDINAL_POSITION, COLUMN_KEY "
                + "FROM information_schema.columns WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";

        try (Connection connection = getConnection(cluster, null);
                PreparedStatement stmt = connection.prepareStatement(sql)) {

            stmt.setString(1, database);
            stmt.setString(2, tableName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> column = new HashMap<>();
                    column.put("columnName", rs.getString("COLUMN_NAME"));
                    column.put("dataType", resolveColumnDataType(rs));
                    column.put("isNullable", "YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? 1 : 0);
                    column.put("defaultValue", rs.getString("COLUMN_DEFAULT"));
                    column.put("columnComment", rs.getString("COLUMN_COMMENT"));
                    column.put("ordinalPosition", rs.getInt("ORDINAL_POSITION"));
                    column.put("columnKey", rs.getString("COLUMN_KEY"));
                    column.put("isPrimary", "PRI".equalsIgnoreCase(rs.getString("COLUMN_KEY")) ? 1 : 0);
                    columns.add(column);
                }
            }

            // Doris 某些版本中 information_schema.columns.COLUMN_TYPE 可能为空，回退到 SHOW FULL COLUMNS.Type。
            if (!isMysql) {
                Map<String, String> fallbackTypeMap = loadColumnTypesByShowFullColumns(connection, database, tableName);
                for (Map<String, Object> column : columns) {
                    String currentType = (String) column.get("dataType");
                    if (StringUtils.hasText(currentType)) {
                        continue;
                    }
                    String columnName = (String) column.get("columnName");
                    String fallbackType = fallbackTypeMap.get(columnName);
                    if (StringUtils.hasText(fallbackType)) {
                        column.put("dataType", fallbackType);
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get columns from table {}.{}", database, tableName, e);
            throw new RuntimeException("获取列信息失败: " + e.getMessage(), e);
        }

        return columns;
    }

    private String normalizeSearchKeyword(String keyword) {
        return String.valueOf(keyword).trim().toLowerCase(Locale.ROOT);
    }

    private String resolveColumnDataType(ResultSet rs) throws SQLException {
        String columnType = rs.getString("COLUMN_TYPE");
        if (StringUtils.hasText(columnType)) {
            return columnType;
        }
        return rs.getString("DATA_TYPE");
    }

    private Map<String, String> loadColumnTypesByShowFullColumns(Connection connection, String database, String tableName) {
        Map<String, String> typeMap = new HashMap<>();
        String sql = String.format("SHOW FULL COLUMNS FROM `%s`.`%s`", database, tableName);
        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = extractRow(rs);
                Object field = row.get("field");
                Object type = row.get("type");
                if (field == null || type == null) {
                    continue;
                }
                String fieldName = String.valueOf(field);
                String fullType = String.valueOf(type);
                if (!StringUtils.hasText(fieldName) || !StringUtils.hasText(fullType)) {
                    continue;
                }
                typeMap.put(fieldName, fullType);
            }
        } catch (SQLException e) {
            log.warn("SHOW FULL COLUMNS fallback failed for {}.{}, reason={}", database, tableName, e.getMessage());
        }
        return typeMap;
    }

    /**
     * 获取表的详细建表信息（解析 SHOW CREATE TABLE）
     */
    public Map<String, Object> getTableCreateInfo(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);
        Map<String, Object> info = new HashMap<>();

        String showCreateSql = "SHOW CREATE TABLE `" + database + "`.`" + tableName + "`";

        try (Connection connection = getConnection(cluster, null);
                Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(showCreateSql)) {

            if (rs.next()) {
                String createTableSql = rs.getString(2);
                info.put("createTableSql", createTableSql);
                if ("MYSQL".equalsIgnoreCase(cluster.getSourceType())) {
                    return info;
                }

                // 解析副本数（支持 replication_allocation / dynamic_partition.replication_allocation）
                Integer replicationNum = DorisCreateTableUtils.parseReplicationNum(createTableSql);
                if (replicationNum != null) {
                    info.put("replicationNum", replicationNum);
                }

                // 解析分桶数
                if (createTableSql.contains("BUCKETS ")) {
                    int start = createTableSql.indexOf("BUCKETS ") + 8;
                    int end = start;
                    while (end < createTableSql.length() && Character.isDigit(createTableSql.charAt(end))) {
                        end++;
                    }
                    if (end > start) {
                        try {
                            info.put("bucketNum", Integer.parseInt(createTableSql.substring(start, end)));
                        } catch (NumberFormatException e) {
                            log.warn("Failed to parse bucket num", e);
                        }
                    }
                }

                // 解析分区字段（兼容大小写/换行/不同分区类型）
                String partitionColumn = DorisCreateTableUtils.parsePartitionColumn(createTableSql);
                if (StringUtils.hasText(partitionColumn)) {
                    info.put("partitionColumn", partitionColumn);
                }

                // 解析分桶字段
                if (createTableSql.contains("DISTRIBUTED BY HASH")) {
                    int start = createTableSql.indexOf("DISTRIBUTED BY HASH(") + 20;
                    int end = createTableSql.indexOf(")", start);
                    if (start > 19 && end > start) {
                        String distributionColumn = createTableSql.substring(start, end).trim();
                        info.put("distributionColumn", distributionColumn);
                    }
                }

                // 解析 Key 类型和列
                if (createTableSql.contains("UNIQUE KEY")) {
                    int start = createTableSql.indexOf("UNIQUE KEY(") + 11;
                    int end = createTableSql.indexOf(")", start);
                    if (start > 10 && end > start) {
                        String keyColumns = createTableSql.substring(start, end).trim();
                        info.put("keyColumns", keyColumns);
                        info.put("tableModel", "UNIQUE");
                    }
                } else if (createTableSql.contains("AGGREGATE KEY")) {
                    int start = createTableSql.indexOf("AGGREGATE KEY(") + 14;
                    int end = createTableSql.indexOf(")", start);
                    if (start > 13 && end > start) {
                        String keyColumns = createTableSql.substring(start, end).trim();
                        info.put("keyColumns", keyColumns);
                        info.put("tableModel", "AGGREGATE");
                    }
                } else if (createTableSql.contains("DUPLICATE KEY")) {
                    int start = createTableSql.indexOf("DUPLICATE KEY(") + 14;
                    int end = createTableSql.indexOf(")", start);
                    if (start > 13 && end > start) {
                        String keyColumns = createTableSql.substring(start, end).trim();
                        info.put("keyColumns", keyColumns);
                        info.put("tableModel", "DUPLICATE");
                    }
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get create info for table {}.{}", database, tableName, e);
            throw new RuntimeException("获取建表信息失败: " + e.getMessage(), e);
        }

        return info;
    }
}
