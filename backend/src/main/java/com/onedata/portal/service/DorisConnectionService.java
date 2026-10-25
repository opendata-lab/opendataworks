package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.portal.dto.ColumnValueProfile;
import com.onedata.portal.dto.DorisCredential;
import com.onedata.portal.dto.TablePartitionInfo;
import com.onedata.portal.dto.TableStatistics;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.util.DorisCreateTableUtils;
import com.onedata.portal.util.JdbcUrlParts;
import com.onedata.portal.util.JdbcValues;
import com.onedata.portal.util.PostgresqlMetadataSql;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
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
    /** 单列取值统计的查询超时（秒）：候选列可能有十几个，单列必须快速失败 */
    private static final int COLUMN_PROFILE_TIMEOUT_SECONDS = 20;

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

        DorisCredential credential = null;
        if (userId != null && StringUtils.hasText(database)) {
            // 映射拒绝直接抛出：此时退回数据源账号，等于让用户访问未授权的库
            credential = userMappingService.getDorisCredential(userId, cluster.getId(), database);
        }
        if (credential != null) {
            username = credential.getUsername();
            password = credential.getPassword();
            log.debug("Using mapped credential for user {} on database {}", userId, database);
        } else {
            // 无用户上下文、未指定库，或该库未登记映射账号：使用数据源账号
            username = cluster.getUsername();
            password = cluster.getPassword() == null ? "" : cluster.getPassword();
        }

        Connection connection = DriverManager.getConnection(url, username, password);
        try {
            if (isPostgresql(cluster)) {
                if (StringUtils.hasText(database)) {
                    connection.setSchema(database);
                }
            } else {
                applySessionCharset(connection);
            }
            return connection;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }

    private String buildJdbcUrl(DorisCluster cluster, String database) {
        if (isPostgresql(cluster)) {
            if (!StringUtils.hasText(cluster.getDatabaseName())) {
                throw new IllegalArgumentException("PostgreSQL 连接数据库不能为空");
            }
            return PostgresqlMetadataSql.jdbcUrl(cluster.getFeHost(), cluster.getFePort(), cluster.getDatabaseName());
        }
        String template = dorisJdbcProperties.getUrlTemplate();
        if (!StringUtils.hasText(template)) {
            throw new IllegalStateException("doris.jdbc.url-template 未配置，请在 application.yml 或环境变量中指定");
        }
        return String.format(template, JdbcUrlParts.requireHost(cluster.getFeHost()), cluster.getFePort(),
                JdbcUrlParts.requireDatabase(database));
    }

    private boolean isPostgresql(DorisCluster cluster) {
        return "POSTGRESQL".equalsIgnoreCase(cluster.getSourceType());
    }

    private String quoteIdentifier(DorisCluster cluster, String identifier) {
        return isPostgresql(cluster) ? PostgresqlMetadataSql.quoteIdentifier(identifier)
                : "`" + identifier.replace("`", "``") + "`";
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
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

        if (isPostgresql(cluster)) {
            return Optional.empty(); // Catalog statistics are already included by getTablesInDatabase.
        }

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
        if (isPostgresql(cluster)) {
            return Optional.empty();
        }
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
     * 生成 Doris 列定义。
     *
     * <p>Key 列必须带 {@code KEY} 标记（紧跟类型之后）：Doris 的
     * {@code MODIFY COLUMN} 按定义重建该列，缺少标记会被当成 value 列，
     * 而 value 列排在 key 列之前时 FE 直接报
     * {@code Invalid column order. value should be after key. index[...]}。
     */
    public String buildColumnDefinition(DataField field, boolean isKey) {
        StringBuilder builder = new StringBuilder();
        String fieldName = field.getFieldName();
        String fieldType = field.getFieldType();
        builder.append(wrapColumn(fieldName)).append(" ").append(fieldType);
        if (isKey) {
            builder.append(" KEY");
        }
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

        if (isPostgresql(cluster)) {
            sql = PostgresqlMetadataSql.TABLES + " AND n.nspname = ? AND c.relname = ?";
        }

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

                    stats.setRowCount(nullableLong(rs, "TABLE_ROWS"));

                    long dataSize = rs.getLong("DATA_LENGTH");
                    stats.setDataSize(dataSize);
                    stats.setDataSizeReadable(formatBytes(dataSize));

                    stats.setEngine(rs.getString("ENGINE"));
                    stats.setAvailable(true);
                    stats.setLastCheckTime(LocalDateTime.now());

                    // 获取分区和副本信息
                    if (!isPostgresql(cluster)) {
                        enrichTableDetails(connection, database, tableName, stats);
                    }

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

        if (isPostgresql(cluster)) {
            sql = PostgresqlMetadataSql.TABLES + " AND n.nspname = ? ORDER BY c.relname";
        }

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

                    stats.setRowCount(nullableLong(rs, "TABLE_ROWS"));

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
        if (isPostgresql(cluster)) {
            throw new UnsupportedOperationException("PostgreSQL 暂不支持在线 DDL 导出，请使用 pg_dump --schema-only");
        }
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
     * 统计指定列的实测取值分布。
     *
     * <p>每列一条 {@code SELECT col, COUNT(*) ... GROUP BY col ORDER BY 2 DESC LIMIT n+1} ——
     * 多取一行用于判定「是否超过枚举上限」：超过即认为不是枚举列，直接丢弃，不返回截断结果，
     * 避免下游把不完整的取值集合当成完整枚举。
     *
     * <p>取值统一用 {@code getString} 读出，不做类型转换，因此对 Doris 与 MySQL 数据源同样适用。
     * 单列查询失败（权限、类型不可分组等）只跳过该列，不影响其余列。
     */
    public List<ColumnValueProfile> profileColumnValues(Long clusterId, String database, String tableName,
            List<String> columns, int maxDistinct) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<ColumnValueProfile> profiles = new ArrayList<>();
        if (CollectionUtils.isEmpty(columns)) {
            return profiles;
        }

        try (Connection connection = getConnection(cluster, database)) {
            for (String column : columns) {
                if (!StringUtils.hasText(column)) {
                    continue;
                }
                String quotedColumn = quoteIdentifier(cluster, column);
                String sql = String.format(
                        "SELECT %s AS v, COUNT(*) AS c FROM %s.%s WHERE %s IS NOT NULL "
                                + "GROUP BY %s ORDER BY c DESC LIMIT %d",
                        quotedColumn, quoteIdentifier(cluster, database), quoteIdentifier(cluster, tableName),
                        quotedColumn, quotedColumn, maxDistinct + 1);
                try (Statement stmt = connection.createStatement()) {
                    stmt.setQueryTimeout(COLUMN_PROFILE_TIMEOUT_SECONDS);
                    try (ResultSet rs = stmt.executeQuery(sql)) {
                        List<ColumnValueProfile.ColumnValueCount> values = new ArrayList<>();
                        while (rs.next()) {
                            values.add(new ColumnValueProfile.ColumnValueCount(rs.getString(1), rs.getLong(2)));
                        }
                        if (values.isEmpty() || values.size() > maxDistinct) {
                            continue;
                        }
                        ColumnValueProfile profile = new ColumnValueProfile();
                        profile.setFieldName(column);
                        profile.setDistinctCount(values.size());
                        profile.setValues(values);
                        profiles.add(profile);
                    }
                } catch (SQLException e) {
                    log.warn("Failed to profile column {}.{}.{}: {}", database, tableName, column, e.getMessage());
                }
            }
        } catch (SQLException e) {
            log.error("Failed to profile columns for {}.{}", database, tableName, e);
            throw new RuntimeException("统计字段取值失败: " + e.getMessage(), e);
        }

        return profiles;
    }

    /**
     * 预览表数据
     */
    public List<Map<String, Object>> previewTableData(Long clusterId, String database, String tableName, int limit) {
        DorisCluster cluster = resolveCluster(clusterId);
        List<Map<String, Object>> result = new ArrayList<>();

        // 限制最大预览行数
        int maxLimit = Math.min(limit, 1000);
        String sql = "SELECT * FROM " + quoteIdentifier(cluster, database) + "."
                + quoteIdentifier(cluster, tableName) + " LIMIT " + maxLimit;

        try (Connection connection = getConnection(cluster, database);
                Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    String columnName = metaData.getColumnName(i);
                    row.put(columnName, JdbcValues.read(rs, i));
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
     * 列出表的分区（分区名、范围、分桶、副本、大小、行数）。
     *
     * <p>基于 Doris {@code SHOW PARTITIONS}。不同 Doris 版本的返回列不完全一致，
     * 这里按结果集实际存在的列名读取，缺失列留空，避免因版本差异整体失败。
     * 非分区表通常也会返回一行默认分区。
     */
    public List<TablePartitionInfo> listPartitions(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);
        if (isPostgresql(cluster)) {
            throw new UnsupportedOperationException("PostgreSQL 暂不支持分区详情");
        }
        String sql = "SHOW PARTITIONS FROM `" + database + "`.`" + tableName + "`";
        List<TablePartitionInfo> partitions = new ArrayList<>();

        try (Connection connection = getConnection(cluster, null);
                Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            Set<String> columns = availableColumnLabels(rs.getMetaData());
            while (rs.next()) {
                TablePartitionInfo info = new TablePartitionInfo();
                info.setPartitionName(readString(rs, columns, "PartitionName"));
                info.setPartitionKey(readString(rs, columns, "PartitionKey"));
                info.setRange(readString(rs, columns, "Range"));
                info.setDistributionKey(readString(rs, columns, "DistributionKey"));
                info.setBuckets(readInteger(rs, columns, "Buckets"));
                info.setReplicationNum(readInteger(rs, columns, "ReplicationNum"));
                info.setDataSize(readString(rs, columns, "DataSize"));
                info.setRowCount(readLong(rs, columns, "RowCount"));
                info.setState(readString(rs, columns, "State"));
                info.setStorageMedium(readString(rs, columns, "StorageMedium"));
                info.setVisibleVersionTime(readString(rs, columns, "VisibleVersionTime"));
                partitions.add(info);
            }
        } catch (SQLException e) {
            log.error("Failed to list partitions for {}.{}", database, tableName, e);
            throw new RuntimeException("获取分区列表失败: " + e.getMessage(), e);
        }

        return partitions;
    }

    /**
     * 新鲜度探针：一次查询同时取「数据最后加载时间」与「快照时间」，二者同源同事务，
     * 消除门户与 Doris 的时钟偏差。{@code maxLoadedAt} 可为 null（空表 → 从未加载）。
     */
    public static final class FreshnessProbe {
        private final LocalDateTime maxLoadedAt;
        private final LocalDateTime snapshottedAt;

        public FreshnessProbe(LocalDateTime maxLoadedAt, LocalDateTime snapshottedAt) {
            this.maxLoadedAt = maxLoadedAt;
            this.snapshottedAt = snapshottedAt;
        }

        public LocalDateTime getMaxLoadedAt() {
            return maxLoadedAt;
        }

        public LocalDateTime getSnapshottedAt() {
            return snapshottedAt;
        }
    }

    /**
     * column 模式取值：{@code SELECT MAX(`col`) AS max_loaded_at, NOW() AS snapshotted_at
     * FROM `db`.`tbl` [WHERE filter]}。等价 dbt {@code loaded_at_field}。
     *
     * <p>SQL 失败/超时向上抛出，由调用方判为 {@code runtime_error}。
     */
    public FreshnessProbe probeMaxLoadedAt(Long clusterId, String database, String tableName,
                                           String loadedAtField, String filterExpr, int timeoutSeconds) {
        DorisCluster cluster = resolveCluster(clusterId);
        String column = requireSafeIdentifier(loadedAtField, "加载时间列");
        StringBuilder sql = new StringBuilder()
            .append("SELECT MAX(").append(quoteIdentifier(cluster, column))
            .append(") AS max_loaded_at, NOW() AS snapshotted_at FROM ")
            .append(quoteIdentifier(cluster, database)).append(".").append(quoteIdentifier(cluster, tableName));
        if (StringUtils.hasText(filterExpr)) {
            sql.append(" WHERE ").append(filterExpr);
        }
        return runProbe(cluster, database, sql.toString(), timeoutSeconds);
    }

    /**
     * custom_sql 模式取值：把用户查询包成标量子查询
     * {@code SELECT (<loadedAtQuery>) AS max_loaded_at, NOW() AS snapshotted_at}。
     * 形状照搬 dbt {@code default__collect_freshness_custom_sql}，用户查询只返回一个时间戳。
     */
    public FreshnessProbe probeMaxLoadedAtByQuery(Long clusterId, String database,
                                                  String loadedAtQuery, int timeoutSeconds) {
        DorisCluster cluster = resolveCluster(clusterId);
        String sql = "SELECT (" + loadedAtQuery + ") AS max_loaded_at, NOW() AS snapshotted_at";
        return runProbe(cluster, database, sql, timeoutSeconds);
    }

    /**
     * metadata 模式取值：实时查 {@code information_schema.tables.UPDATE_TIME}。
     * 只能发现「没人写了」，发现不了「写入了旧数据」。表不存在时抛出 → {@code runtime_error}。
     */
    public FreshnessProbe probeMetadataUpdateTime(Long clusterId, String database, String tableName,
                                                  int timeoutSeconds) {
        DorisCluster cluster = resolveCluster(clusterId);
        if (isPostgresql(cluster)) {
            throw new UnsupportedOperationException("PostgreSQL 不提供表更新时间，请改用 column 或 custom_sql 模式");
        }
        String sql = "SELECT UPDATE_TIME AS max_loaded_at, NOW() AS snapshotted_at "
            + "FROM information_schema.tables WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";
        try (Connection connection = getConnection(cluster, null);
                PreparedStatement stmt = connection.prepareStatement(sql)) {
            if (timeoutSeconds > 0) {
                stmt.setQueryTimeout(timeoutSeconds);
            }
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new RuntimeException(String.format("表 %s.%s 不存在", database, tableName));
                }
                return readProbe(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("查询元数据更新时间失败: " + e.getMessage(), e);
        }
    }

    private FreshnessProbe runProbe(DorisCluster cluster, String database, String sql, int timeoutSeconds) {
        try (Connection connection = getConnection(cluster, database);
                Statement stmt = connection.createStatement()) {
            if (timeoutSeconds > 0) {
                stmt.setQueryTimeout(timeoutSeconds);
            }
            try (ResultSet rs = stmt.executeQuery(sql)) {
                if (!rs.next()) {
                    // 理论上恒有一行；无行按无数据处理
                    return new FreshnessProbe(null, LocalDateTime.now());
                }
                return readProbe(rs);
            }
        } catch (SQLException e) {
            throw new RuntimeException("新鲜度取数失败: " + e.getMessage(), e);
        }
    }

    private FreshnessProbe readProbe(ResultSet rs) throws SQLException {
        Timestamp maxLoaded = rs.getTimestamp("max_loaded_at");
        Timestamp snapshot = rs.getTimestamp("snapshotted_at");
        LocalDateTime snapshotAt = snapshot != null ? snapshot.toLocalDateTime() : LocalDateTime.now();
        LocalDateTime maxLoadedAt = maxLoaded != null ? maxLoaded.toLocalDateTime() : null;
        return new FreshnessProbe(maxLoadedAt, snapshotAt);
    }

    /**
     * 校验标识符不含反引号，防止拼接逃逸。列名合法性（是否为真实列）由服务层保存时校验。
     */
    private String requireSafeIdentifier(String identifier, String label) {
        if (!StringUtils.hasText(identifier) || identifier.indexOf('`') >= 0) {
            throw new IllegalArgumentException("非法的" + label + ": " + identifier);
        }
        return identifier;
    }

    private Set<String> availableColumnLabels(ResultSetMetaData metaData) throws SQLException {
        Set<String> labels = new HashSet<>();
        for (int i = 1; i <= metaData.getColumnCount(); i++) {
            String label = metaData.getColumnLabel(i);
            if (StringUtils.hasText(label)) {
                labels.add(label.toLowerCase(Locale.ROOT));
            }
        }
        return labels;
    }

    private String readString(ResultSet rs, Set<String> columns, String label) throws SQLException {
        if (!columns.contains(label.toLowerCase(Locale.ROOT))) {
            return null;
        }
        String value = rs.getString(label);
        return value == null ? null : value.trim();
    }

    private Integer readInteger(ResultSet rs, Set<String> columns, String label) throws SQLException {
        String value = readString(rs, columns, label);
        if (!StringUtils.hasText(value) || !NUMERIC_PATTERN.matcher(value).matches()) {
            return null;
        }
        return (int) Double.parseDouble(value);
    }

    private Long readLong(ResultSet rs, Set<String> columns, String label) throws SQLException {
        String value = readString(rs, columns, label);
        if (!StringUtils.hasText(value) || !NUMERIC_PATTERN.matcher(value).matches()) {
            return null;
        }
        return (long) Double.parseDouble(value);
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

        String sql = isPostgresql(cluster) ? PostgresqlMetadataSql.SCHEMAS : "SHOW DATABASES";

        try (Connection connection = getConnection(cluster, null);
                Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                String dbName = rs.getString(1);
                // 过滤掉系统数据库
                if (isPostgresql(cluster) || !SYSTEM_DATABASES.contains(dbName)) {
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

        String sql = isPostgresql(cluster) ? PostgresqlMetadataSql.schemaObjects(hasKeyword) : sqlBuilder.toString();
        try (Connection connection = getConnection(cluster, null);
                PreparedStatement stmt = connection.prepareStatement(sql)) {
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

        if (isPostgresql(cluster)) {
            sql = PostgresqlMetadataSql.TABLES + " AND n.nspname = ? ORDER BY c.relname";
        }

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
                    table.put("tableRows", nullableLong(rs, "TABLE_ROWS"));
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

        if (isPostgresql(cluster)) {
            sql = PostgresqlMetadataSql.COLUMNS;
        }

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
            if (!isMysql && !isPostgresql(cluster)) {
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

        if (isPostgresql(cluster)) {
            return info; // PostgreSQL has no SHOW CREATE TABLE; do not persist an incomplete reconstruction.
        }

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
