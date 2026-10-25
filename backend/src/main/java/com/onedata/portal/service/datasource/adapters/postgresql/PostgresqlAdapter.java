package com.onedata.portal.service.datasource.adapters.postgresql;

import com.onedata.portal.service.datasource.adapters.Descriptors;
import com.onedata.portal.service.datasource.adapters.JdbcMetadataReader;
import com.onedata.portal.service.datasource.adapters.QuotedLimitDialect;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.datasource.model.JdbcConnectionSpec;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import com.onedata.portal.service.datasource.spi.DatasourceMetadataReader;
import com.onedata.portal.service.datasource.spi.DatasourceReferenceDdlReader;
import com.onedata.portal.service.datasource.spi.DatasourceSqlDialect;
import com.onedata.portal.util.JdbcUrlParts;
import com.onedata.portal.util.PostgresqlMetadataSql;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Component
public class PostgresqlAdapter implements DatasourceAdapter, DatasourceReferenceDdlReader {

    private final DatasourceSqlDialect dialect = new QuotedLimitDialect("\"");
    private final DatasourceMetadataReader metadata = createMetadata();
    private final DatasourceDescriptor descriptor;

    public PostgresqlAdapter() {
        this.descriptor = Descriptors.builder("POSTGRESQL", "PostgreSQL")
                .agentEngine("postgresql")
                .defaultPort(5432)
                .namespaceLabel("Schema")
                .identifierQuote("\"")
                .editorDialect("postgresql")
                .driverClass("org.postgresql.Driver")
                .connectionDatabaseRequired(true)
                .capability("catalog", true)
                .capability("query", true)
                .capability("preview", true)
                .capability("statistics", true)
                .capability("freshnessColumn", true)
                .capability("freshnessCustomSql", true)
                .capability("ddlExport", false, "PostgreSQL 暂不支持在线 DDL 导出，请使用 pg_dump --schema-only")
                .capability("structureEdit", false, "PostgreSQL 暂不支持在线修改表结构，请在数据库中修改后重新同步元数据")
                .capability("partitions", false, "PostgreSQL 暂不支持此引擎操作")
                .capability("distribution", false, "PostgreSQL 暂不支持此引擎操作")
                .capability("replication", false, "PostgreSQL 暂不支持此引擎操作")
                .capability("backup", false, "PostgreSQL 暂不支持此引擎操作")
                .capability("engineProperties", false, "PostgreSQL 暂不支持此引擎操作")
                .capability("freshnessMetadata", false, "PostgreSQL 不提供表更新时间，请改用 column 或 custom_sql 模式")
                .capability("existingPrimaryKeyEdit", false, "暂不支持在线修改已有字段的主键")
                .capability("newPrimaryKeyEdit", false, "此引擎不支持在线修改主键列")
                .build();
    }

    @Override
    public DatasourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public void validate(ConnectionConfig config) {
        JdbcUrlParts.requireHost(config.getHost());
        if (!StringUtils.hasText(config.getDatabaseName())) {
            throw new IllegalArgumentException("PostgreSQL 连接数据库不能为空");
        }
        JdbcUrlParts.requireDatabase(config.getDatabaseName().trim());
    }

    @Override
    public JdbcConnectionSpec connectionSpec(ConnectionConfig config, String namespace) {
        validate(config);
        // 数据源固定连接一个 database；namespace 只用于设置库内 schema。
        return new JdbcConnectionSpec(
                PostgresqlMetadataSql.jdbcUrl(config.getHost(), config.getPort(), config.getDatabaseName()),
                descriptor().getDriverClass());
    }

    @Override
    public void initializeSession(Connection connection, String namespace) throws SQLException {
        if (StringUtils.hasText(namespace)) {
            connection.setSchema(namespace);
        }
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
        return new JdbcMetadataReader(PostgresqlMetadataSql.SCHEMAS,
                PostgresqlMetadataSql.TABLES + " AND n.nspname = ? ORDER BY c.relname",
                PostgresqlMetadataSql.COLUMNS,
                PostgresqlMetadataSql.schemaObjects(false), PostgresqlMetadataSql.schemaObjects(true),
                Collections.emptySet(), false, true);
    }

    /**
     * PostgreSQL 没有 SHOW CREATE TABLE；从系统目录生成仅含列、类型、默认值和注释的参考 DDL，
     * 供 SQL 编写参考，不作为可恢复的建表语句。
     */
    @Override
    public String referenceDdl(
            Connection connection,
            String database,
            String tableName,
            int timeoutSeconds
    ) {
        String qualifiedName = PostgresqlMetadataSql.quoteIdentifier(database) + "."
                + PostgresqlMetadataSql.quoteIdentifier(tableName);
        List<String> columns = new ArrayList<>();
        List<String> comments = new ArrayList<>();
        List<String> primaryKeys = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(PostgresqlMetadataSql.COLUMNS)) {
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

}
