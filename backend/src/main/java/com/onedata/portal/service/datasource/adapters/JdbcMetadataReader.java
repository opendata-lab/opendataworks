package com.onedata.portal.service.datasource.adapters;

import com.onedata.portal.service.datasource.model.ColumnMetadata;
import com.onedata.portal.service.datasource.model.Namespace;
import com.onedata.portal.service.datasource.model.TableMetadata;
import com.onedata.portal.service.datasource.spi.DatasourceMetadataReader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class JdbcMetadataReader implements DatasourceMetadataReader {

    private static final String VISIBLE_COLUMNS_SQL =
            "SELECT table_schema, table_name, column_name FROM information_schema.columns";
    private static final Set<String> SYSTEM_NAMESPACES = new HashSet<>(Arrays.asList(
            "information_schema", "pg_catalog", "mysql", "performance_schema", "sys"));

    private final String namespacesSql;
    private final String tablesSql;
    private final String columnsSql;
    private final String objectsSql;
    private final String filteredObjectsSql;
    private final Set<String> ignoredNamespaces;
    private final boolean columnTypeFallback;
    private final boolean schemaNamespaces;

    @Override
    public List<Namespace> namespaces(Connection connection) {
        List<Namespace> result = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(namespacesSql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (!ignoredNamespaces.contains(name)) {
                    // MySQL 协议的 namespace 对应 catalog；PostgreSQL 对应当前库内 schema。
                    result.add(new Namespace(schemaNamespaces ? connection.getCatalog() : name,
                            schemaNamespaces ? name : null, name));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取数据库列表失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public List<TableMetadata> tables(Connection connection, String namespace) {
        List<TableMetadata> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(tablesSql)) {
            statement.setString(1, namespace);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    TableMetadata table = new TableMetadata();
                    table.setSchemaName(namespace);
                    table.setTableName(rs.getString("TABLE_NAME"));
                    table.setTableType(rs.getString("TABLE_TYPE"));
                    table.setTableComment(rs.getString("TABLE_COMMENT"));
                    table.setCreateTime(rs.getTimestamp("CREATE_TIME"));
                    table.setEngine(rs.getString("ENGINE"));
                    table.setUpdateTime(rs.getTimestamp("UPDATE_TIME"));
                    table.setTableRows(nullableLong(rs, "TABLE_ROWS"));
                    table.setDataLength(nullableLong(rs, "DATA_LENGTH"));
                    result.add(table);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取表列表失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public List<TableMetadata> objects(Connection connection, String keyword) {
        List<TableMetadata> result = new ArrayList<>();
        boolean filtered = StringUtils.hasText(keyword);
        try (PreparedStatement statement = connection.prepareStatement(filtered ? filteredObjectsSql : objectsSql)) {
            if (filtered) {
                String normalizedKeyword = keyword.trim().toLowerCase(Locale.ROOT);
                statement.setString(1, normalizedKeyword);
                statement.setString(2, normalizedKeyword);
            }
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    TableMetadata table = new TableMetadata();
                    table.setSchemaName(rs.getString("TABLE_SCHEMA"));
                    table.setTableName(rs.getString("TABLE_NAME"));
                    table.setTableType(rs.getString("TABLE_TYPE"));
                    table.setTableComment(rs.getString("TABLE_COMMENT"));
                    result.add(table);
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取 schema 对象列表失败: " + e.getMessage(), e);
        }
        return result;
    }

    @Override
    public List<ColumnMetadata> columns(Connection connection, String namespace, String table) {
        List<ColumnMetadata> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(columnsSql)) {
            statement.setString(1, namespace);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    ColumnMetadata column = new ColumnMetadata();
                    column.setColumnName(rs.getString("COLUMN_NAME"));
                    String fullType = rs.getString("COLUMN_TYPE");
                    column.setDataType(StringUtils.hasText(fullType) ? fullType : rs.getString("DATA_TYPE"));
                    column.setIsNullable("YES".equalsIgnoreCase(rs.getString("IS_NULLABLE")) ? 1 : 0);
                    column.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                    column.setColumnComment(rs.getString("COLUMN_COMMENT"));
                    column.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                    column.setColumnKey(rs.getString("COLUMN_KEY"));
                    column.setIsPrimary("PRI".equalsIgnoreCase(column.getColumnKey()) ? 1 : 0);
                    result.add(column);
                }
            }
            if (columnTypeFallback) {
                // 部分 Doris 版本未填充 COLUMN_TYPE，只为缺失的类型使用既有兼容路径。
                Map<String, String> types = loadColumnTypesByShowFullColumns(connection, namespace, table);
                for (ColumnMetadata column : result) {
                    if (!StringUtils.hasText(column.getDataType()) && types.containsKey(column.getColumnName())) {
                        column.setDataType(types.get(column.getColumnName()));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取列信息失败: " + e.getMessage(), e);
        }
        return result;
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        // 保留未知统计的 NULL 语义，不能将 getLong 返回的 0 当成已知值。
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private Map<String, String> loadColumnTypesByShowFullColumns(Connection connection, String namespace, String table) {
        Map<String, String> types = new HashMap<>();
        String sql = "SHOW FULL COLUMNS FROM `" + namespace.replace("`", "``") + "`.`" + table.replace("`", "``") + "`";
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) {
                types.put(rs.getString("Field"), rs.getString("Type"));
            }
        } catch (SQLException e) {
            log.warn("SHOW FULL COLUMNS fallback failed for {}.{}: {}", namespace, table, e.getMessage());
        }
        return types;
    }

    @Override
    public Map<String, Set<String>> visibleColumns(Connection connection) {
        // information_schema.columns 在 PostgreSQL、MySQL、Doris 上都只返回当前账号有权限的字段。
        Map<String, Set<String>> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(VISIBLE_COLUMNS_SQL)) {
            while (rs.next()) {
                String namespace = rs.getString(1);
                if (ignoredNamespaces.contains(namespace) || SYSTEM_NAMESPACES.contains(namespace)) {
                    continue;
                }
                result.computeIfAbsent(namespace + "\u0000" + rs.getString(2), key -> new LinkedHashSet<>())
                        .add(rs.getString(3));
            }
        } catch (SQLException e) {
            throw new RuntimeException("获取可见字段失败: " + e.getMessage(), e);
        }
        return result;
    }
}
