package com.onedata.portal.service.datasource.adapters.postgresql;

import com.onedata.portal.service.datasource.adapters.Descriptors;
import com.onedata.portal.service.datasource.adapters.JdbcMetadataReader;
import com.onedata.portal.service.datasource.adapters.QuotedLimitDialect;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.datasource.model.JdbcConnectionSpec;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import com.onedata.portal.service.datasource.spi.DatasourceMetadataReader;
import com.onedata.portal.service.datasource.spi.DatasourceSqlDialect;
import com.onedata.portal.util.JdbcUrlParts;
import com.onedata.portal.util.PostgresqlMetadataSql;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;

@Component
public class PostgresqlAdapter implements DatasourceAdapter {

    @Override
    public DatasourceDescriptor descriptor() {
        return Descriptors.builder("POSTGRESQL", "PostgreSQL")
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
                .capability("dorisProperties", false, "PostgreSQL 暂不支持此引擎操作")
                .capability("freshnessMetadata", false, "PostgreSQL 不提供表更新时间，请改用 column 或 custom_sql 模式")
                .capability("existingPrimaryKeyEdit", false, "暂不支持在线修改已有字段的主键")
                .capability("newPrimaryKeyEdit", false, "此引擎不支持在线修改主键列")
                .build();
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
        return new QuotedLimitDialect("\"");
    }

    @Override
    public DatasourceMetadataReader metadata() {
        return new JdbcMetadataReader(PostgresqlMetadataSql.SCHEMAS,
                PostgresqlMetadataSql.TABLES + " AND n.nspname = ? ORDER BY c.relname",
                PostgresqlMetadataSql.COLUMNS,
                PostgresqlMetadataSql.schemaObjects(false), PostgresqlMetadataSql.schemaObjects(true),
                Collections.emptySet(), false, true);
    }
}
