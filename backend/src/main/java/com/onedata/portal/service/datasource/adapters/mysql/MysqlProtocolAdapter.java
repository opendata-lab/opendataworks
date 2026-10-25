package com.onedata.portal.service.datasource.adapters.mysql;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.service.datasource.adapters.JdbcMetadataReader;
import com.onedata.portal.service.datasource.adapters.QuotedLimitDialect;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.JdbcConnectionSpec;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import com.onedata.portal.service.datasource.spi.DatasourceDdlReader;
import com.onedata.portal.service.datasource.spi.DatasourceMetadataReader;
import com.onedata.portal.service.datasource.spi.DatasourceReferenceDdlReader;
import com.onedata.portal.service.datasource.spi.DatasourceSqlDialect;
import com.onedata.portal.util.DorisCreateTableUtils;
import com.onedata.portal.util.JdbcUrlParts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

@Slf4j
public abstract class MysqlProtocolAdapter implements DatasourceAdapter, DatasourceDdlReader,
        DatasourceReferenceDdlReader {

    private final DorisJdbcProperties properties;
    private final boolean physical;
    private final DatasourceSqlDialect dialect = new QuotedLimitDialect("`");
    private final DatasourceMetadataReader metadata;

    protected MysqlProtocolAdapter(DorisJdbcProperties properties, boolean physical) {
        this.properties = properties;
        this.physical = physical;
        this.metadata = createMetadata();
    }

    @Override
    public void validate(ConnectionConfig config) {
        JdbcUrlParts.requireHost(config.getHost());
    }

    @Override
    public JdbcConnectionSpec connectionSpec(ConnectionConfig config, String namespace) {
        String template = properties.getUrlTemplate();
        if (!StringUtils.hasText(template)) {
            throw new IllegalStateException("doris.jdbc.url-template 未配置，请在 application.yml 或环境变量中指定");
        }
        String database = StringUtils.hasText(namespace) ? namespace : properties.getDefaultDatabase();
        return new JdbcConnectionSpec(
                String.format(template, JdbcUrlParts.requireHost(config.getHost()), config.getPort(),
                        JdbcUrlParts.requireDatabase(database)),
                descriptor().getDriverClass());
    }

    @Override
    public void initializeSession(Connection connection, String namespace) {
        applySessionCharset(connection);
    }

    @Override
    public DatasourceSqlDialect dialect() {
        return dialect;
    }

    @Override
    public DatasourceMetadataReader metadata() {
        return metadata;
    }

    private DatasourceMetadataReader createMetadata() {
        String objects = "SELECT TABLE_SCHEMA, TABLE_NAME, TABLE_TYPE, TABLE_COMMENT FROM information_schema.tables "
                + "WHERE TABLE_SCHEMA NOT IN ('information_schema', 'mysql', 'performance_schema', 'sys')";
        return new JdbcMetadataReader("SHOW DATABASES",
                "SELECT TABLE_NAME, TABLE_TYPE, TABLE_COMMENT, CREATE_TIME, UPDATE_TIME, TABLE_ROWS, DATA_LENGTH, ENGINE "
                        + "FROM information_schema.tables WHERE TABLE_SCHEMA = ?",
                "SELECT COLUMN_NAME, COLUMN_TYPE, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_COMMENT, "
                        + "ORDINAL_POSITION, COLUMN_KEY FROM information_schema.columns "
                        + "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION",
                objects + " ORDER BY TABLE_SCHEMA, TABLE_NAME",
                objects + " AND (LOCATE(?, LOWER(TABLE_NAME)) > 0 OR LOCATE(?, LOWER(IFNULL(TABLE_COMMENT, ''))) > 0) "
                        + "ORDER BY TABLE_SCHEMA, TABLE_NAME",
                new HashSet<>(Arrays.asList("information_schema", "mysql", "performance_schema", "sys")),
                physical, false);
    }

    private void applySessionCharset(Connection connection) {
        if (!properties.isSessionCharsetEnabled()) {
            return;
        }
        String primaryCharset = properties.getSessionCharset();
        if (!StringUtils.hasText(primaryCharset)) {
            return;
        }
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("SET NAMES " + primaryCharset);
        } catch (SQLException primaryEx) {
            String fallbackCharset = properties.getSessionCharsetFallback();
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

    @Override
    public Map<String, Object> createInfo(Connection connection, String database, String tableName) {
        Map<String, Object> info = new HashMap<>();

        String showCreateSql = "SHOW CREATE TABLE " + dialect().quoteIdentifier(database)
                + "." + dialect().quoteIdentifier(tableName);

        try (Statement stmt = connection.createStatement();
                ResultSet rs = stmt.executeQuery(showCreateSql)) {

            if (rs.next()) {
                String createTableSql = rs.getString(2);
                info.put("createTableSql", createTableSql);
                if (!physical) {
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

    @Override
    public String referenceDdl(Connection connection, String database, String tableName, int timeoutSeconds) {
        String sql = "SHOW CREATE TABLE " + dialect.quoteIdentifier(database) + "." + dialect.quoteIdentifier(tableName);
        try (Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet rs = statement.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getString(rs.getMetaData().getColumnCount() >= 2 ? 2 : 1);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取建表语句失败: " + e.getMessage(), e);
        }
        throw new RuntimeException(String.format("表 %s.%s 不存在", database, tableName));
    }
}
