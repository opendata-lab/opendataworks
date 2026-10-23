package com.onedata.portal.agentapi.service;

import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.util.JdbcUrlParts;
import com.onedata.portal.util.JdbcValues;
import com.onedata.portal.util.PostgresqlMetadataSql;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AgentJdbcExecutor {

    private final DorisJdbcProperties dorisJdbcProperties;

    public QueryExecutionResult executeReadOnlyQuery(
            AgentDatasourceResolution datasource,
            String sql,
            int limit,
            int timeoutSeconds
    ) {
        long start = System.currentTimeMillis();
        try (Connection connection = openConnection(datasource, datasource.getDatabase());
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            // 只多取一行用于判断 has_more；不设上限时驱动会把整个结果集读进内存
            statement.setMaxRows(limit + 1);
            boolean hasResultSet = statement.execute(sql);
            if (!hasResultSet) {
                throw new IllegalArgumentException("只读 SQL 必须返回结果集");
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            boolean hasMore = false;
            try (ResultSet resultSet = statement.getResultSet()) {
                ResultSetMetaData metaData = resultSet.getMetaData();
                int columnCount = metaData.getColumnCount();
                while (resultSet.next()) {
                    if (rows.size() >= limit) {
                        hasMore = true;
                        break;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= columnCount; i++) {
                        row.put(metaData.getColumnLabel(i), JdbcValues.read(resultSet, i));
                    }
                    rows.add(row);
                }
            }

            QueryExecutionResult result = new QueryExecutionResult();
            result.setRows(rows);
            result.setRowCount(rows.size());
            result.setHasMore(hasMore);
            result.setDurationMs((int) (System.currentTimeMillis() - start));
            return result;
        } catch (SQLException e) {
            throw new RuntimeException("执行只读 SQL 失败: " + e.getMessage(), e);
        }
    }

    public String fetchTableDdl(
            AgentDatasourceResolution datasource,
            String database,
            String tableName,
            int timeoutSeconds
    ) {
        if (isPostgresql(datasource)) {
            return fetchPostgresqlColumnDdl(datasource, database, tableName, timeoutSeconds);
        }
        String sql = "SHOW CREATE TABLE `" + escapeIdentifier(database) + "`.`" + escapeIdentifier(tableName) + "`";
        try (Connection connection = openConnection(datasource, database);
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(timeoutSeconds);
            try (ResultSet resultSet = statement.executeQuery(sql)) {
                if (resultSet.next()) {
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    int ddlColumn = metaData.getColumnCount() >= 2 ? 2 : 1;
                    return resultSet.getString(ddlColumn);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取建表语句失败: " + e.getMessage(), e);
        }
        throw new RuntimeException(String.format("表 %s.%s 不存在", database, tableName));
    }

    /**
     * PostgreSQL 没有 SHOW CREATE TABLE；从系统目录生成仅含列、类型、默认值和注释的参考 DDL，
     * 供 SQL 编写参考，不作为可恢复的建表语句。
     */
    private String fetchPostgresqlColumnDdl(
            AgentDatasourceResolution datasource,
            String database,
            String tableName,
            int timeoutSeconds
    ) {
        String qualifiedName = PostgresqlMetadataSql.quoteIdentifier(database) + "."
                + PostgresqlMetadataSql.quoteIdentifier(tableName);
        List<String> columns = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        try (Connection connection = openConnection(datasource, database);
             PreparedStatement statement = connection.prepareStatement(PostgresqlMetadataSql.COLUMNS)) {
            statement.setQueryTimeout(timeoutSeconds);
            statement.setString(1, database);
            statement.setString(2, tableName);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String column = PostgresqlMetadataSql.quoteIdentifier(resultSet.getString("COLUMN_NAME"));
                    StringBuilder definition = new StringBuilder("  ").append(column).append(' ')
                            .append(resultSet.getString("COLUMN_TYPE"));
                    if ("NO".equals(resultSet.getString("IS_NULLABLE"))) {
                        definition.append(" NOT NULL");
                    }
                    String defaultValue = resultSet.getString("COLUMN_DEFAULT");
                    if (StringUtils.hasText(defaultValue)) {
                        definition.append(" DEFAULT ").append(defaultValue);
                    }
                    columns.add(definition.toString());
                    if ("PRI".equals(resultSet.getString("COLUMN_KEY"))) {
                        primaryKeys.add(column);
                    }
                    String comment = resultSet.getString("COLUMN_COMMENT");
                    if (StringUtils.hasText(comment)) {
                        comments.add("COMMENT ON COLUMN " + qualifiedName + "." + column + " IS '"
                                + comment.replace("'", "''") + "';");
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取建表语句失败: " + e.getMessage(), e);
        }
        if (columns.isEmpty()) {
            throw new RuntimeException(String.format("表 %s.%s 不存在", database, tableName));
        }
        if (!primaryKeys.isEmpty()) {
            columns.add("  PRIMARY KEY (" + String.join(", ", primaryKeys) + ")");
        }
        StringBuilder ddl = new StringBuilder("-- 由 PostgreSQL 系统目录生成，仅含列定义，不含索引/约束/分区等完整信息\n")
                .append("CREATE TABLE ").append(qualifiedName).append(" (\n")
                .append(String.join(",\n", columns)).append("\n);");
        for (String comment : comments) {
            ddl.append('\n').append(comment);
        }
        return ddl.toString();
    }

    private Connection openConnection(AgentDatasourceResolution datasource, String database) throws SQLException {
        String targetDatabase = StringUtils.hasText(database) ? database.trim() : datasource.getDatabase();
        String url = buildJdbcUrl(datasource, targetDatabase);
        Connection connection = DriverManager.getConnection(
                url,
                trimToEmpty(datasource.getUser()),
                trimToEmpty(datasource.getPassword())
        );
        try {
            if (isPostgresql(datasource)) {
                // 只读事务由 PostgreSQL 服务端强制，叠加在上游只读 SQL 校验之上。
                connection.setReadOnly(true);
                connection.setSchema(targetDatabase);
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

    private boolean isPostgresql(AgentDatasourceResolution datasource) {
        return "postgresql".equals(datasource.getEngine());
    }

    private String buildJdbcUrl(AgentDatasourceResolution datasource, String database) {
        if (isPostgresql(datasource)) {
            if (!StringUtils.hasText(datasource.getConnectionDatabase())) {
                throw new IllegalArgumentException("PostgreSQL 连接数据库不能为空");
            }
            int port = datasource.getPort() == null ? 5432 : datasource.getPort();
            return PostgresqlMetadataSql.jdbcUrl(trimToEmpty(datasource.getHost()), port,
                    datasource.getConnectionDatabase().trim());
        }
        String template = dorisJdbcProperties.getUrlTemplate();
        if (!StringUtils.hasText(template)) {
            throw new IllegalStateException("doris.jdbc.url-template 未配置");
        }
        String host = StringUtils.hasText(datasource.getHost()) ? datasource.getHost().trim() : "localhost";
        int port = datasource.getPort() == null ? 3306 : datasource.getPort();
        String targetDatabase = StringUtils.hasText(database) ? database.trim() : datasource.getDatabase();
        if (!StringUtils.hasText(targetDatabase)) {
            throw new IllegalArgumentException("database 不能为空");
        }
        return String.format(template, JdbcUrlParts.requireHost(host), port, JdbcUrlParts.requireDatabase(targetDatabase));
    }

    private void applySessionCharset(Connection connection) {
        if (!dorisJdbcProperties.isSessionCharsetEnabled()) {
            return;
        }
        String primaryCharset = dorisJdbcProperties.getSessionCharset();
        if (!StringUtils.hasText(primaryCharset)) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET NAMES " + primaryCharset);
        } catch (SQLException primaryEx) {
            String fallbackCharset = dorisJdbcProperties.getSessionCharsetFallback();
            if (!StringUtils.hasText(fallbackCharset) || fallbackCharset.equalsIgnoreCase(primaryCharset)) {
                log.warn("Failed to set agent JDBC charset to {}. reason={}", primaryCharset, primaryEx.getMessage());
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET NAMES " + fallbackCharset);
            } catch (SQLException fallbackEx) {
                log.warn("Failed to set agent JDBC fallback charset to {}. reason={}", fallbackCharset, fallbackEx.getMessage());
            }
        }
    }

    private String escapeIdentifier(String value) {
        return String.valueOf(value == null ? "" : value).replace("`", "``");
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    @Data
    public static class QueryExecutionResult {
        private List<Map<String, Object>> rows = new ArrayList<>();
        private int rowCount;
        private boolean hasMore;
        private int durationMs;
    }
}
