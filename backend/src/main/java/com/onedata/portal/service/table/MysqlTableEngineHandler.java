package com.onedata.portal.service.table;

import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * MySQL table handler. It owns MySQL physical DDL and metadata normalization so
 * Doris-only fields never leak into MySQL table records.
 */
@Component
@RequiredArgsConstructor
public class MysqlTableEngineHandler implements TableEngineHandler {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private final TableEngineJdbcExecutor jdbcExecutor;

    @Override
    public String sourceType() {
        return "MYSQL";
    }

    @Override
    public void alterTableComment(Long datasourceId, String database, String tableName, String comment) {
        String escapedComment = escapeSingleQuote(comment == null ? "" : comment);
        String sql = String.format("ALTER TABLE %s COMMENT = '%s'",
                qualifiedName(database, tableName), escapedComment);
        execute(datasourceId, database, sql);
    }

    @Override
    public void renameTable(Long datasourceId, String database, String oldTableName, String newTableName) {
        String sql = String.format("RENAME TABLE %s TO %s",
                qualifiedName(database, oldTableName), qualifiedName(database, newTableName));
        execute(datasourceId, database, sql);
    }

    @Override
    public void dropTable(Long datasourceId, String database, String tableName) {
        String sql = String.format("DROP TABLE IF EXISTS %s", qualifiedName(database, tableName));
        execute(datasourceId, database, sql);
    }

    @Override
    public void addColumn(Long datasourceId, DataTable table, String database, String tableName, DataField field) {
        String columnDefinition = buildColumnDefinition(field, false);
        String sql = String.format("ALTER TABLE %s ADD COLUMN %s",
                qualifiedName(database, tableName), columnDefinition);
        if (isPrimary(field)) {
            sql += ", ADD PRIMARY KEY (" + wrapIdentifier(field.getFieldName()) + ")";
        }
        execute(datasourceId, database, sql);
    }

    @Override
    public void updateColumn(Long datasourceId, DataTable table, String database, String tableName,
            DataField oldField, DataField newField) {
        if (!Objects.equals(oldField.getIsPrimary(), newField.getIsPrimary())) {
            throw new RuntimeException("MySQL 暂不支持在线修改主键列");
        }
        String currentColumnName = oldField.getFieldName();
        if (!Objects.equals(oldField.getFieldName(), newField.getFieldName())) {
            String renameSql = String.format("ALTER TABLE %s RENAME COLUMN %s TO %s",
                    qualifiedName(database, tableName),
                    wrapIdentifier(oldField.getFieldName()),
                    wrapIdentifier(newField.getFieldName()));
            execute(datasourceId, database, renameSql);
            currentColumnName = newField.getFieldName();
        }
        if (isColumnChanged(oldField, newField)) {
            DataField modifiedField = copyWithName(newField, currentColumnName);
            String modifySql = String.format("ALTER TABLE %s MODIFY COLUMN %s",
                    qualifiedName(database, tableName), buildColumnDefinition(modifiedField, false));
            execute(datasourceId, database, modifySql);
        }
    }

    @Override
    public void dropColumn(Long datasourceId, String database, String tableName, String columnName) {
        String sql = String.format("ALTER TABLE %s DROP COLUMN %s",
                qualifiedName(database, tableName), wrapIdentifier(columnName));
        execute(datasourceId, database, sql);
    }

    @Override
    public void modifyDistribution(Long datasourceId, DataTable table, String database, String tableName,
            Integer bucketNum) {
        throw new RuntimeException("MySQL 数据源不支持分桶设置");
    }

    @Override
    public void setReplicationNum(Long datasourceId, String database, String tableName, Integer replicaNum) {
        throw new RuntimeException("MySQL 数据源不支持副本数设置");
    }

    private void execute(Long datasourceId, String database, String sql) {
        jdbcExecutor.execute(datasourceId, database, sql, sourceType());
    }

    private String buildColumnDefinition(DataField field, boolean includePrimary) {
        StringBuilder builder = new StringBuilder();
        builder.append(wrapIdentifier(field.getFieldName())).append(" ").append(field.getFieldType());
        if (isPrimary(field) || (field.getIsNullable() != null && field.getIsNullable() == 0)) {
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
        if (includePrimary && isPrimary(field)) {
            builder.append(" PRIMARY KEY");
        }
        return builder.toString();
    }

    private boolean isColumnChanged(DataField oldField, DataField newField) {
        if (oldField == null || newField == null) {
            return false;
        }
        return !Objects.equals(normalize(oldField.getFieldType()), normalize(newField.getFieldType()))
                || !Objects.equals(normalize(oldField.getFieldComment()), normalize(newField.getFieldComment()))
                || !Objects.equals(normalize(oldField.getDefaultValue()), normalize(newField.getDefaultValue()))
                || !Objects.equals(normalize(oldField.getIsNullable()), normalize(newField.getIsNullable()));
    }

    // 空串与 null 都表示「未设置」，与 DorisTableEngineHandler 保持一致
    private Object normalize(Object value) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        return value;
    }

    private boolean isPrimary(DataField field) {
        return field != null && field.getIsPrimary() != null && field.getIsPrimary() == 1;
    }

    private DataField copyWithName(DataField source, String fieldName) {
        DataField copy = new DataField();
        copy.setId(source.getId());
        copy.setTableId(source.getTableId());
        copy.setFieldName(fieldName);
        copy.setFieldType(source.getFieldType());
        copy.setFieldComment(source.getFieldComment());
        copy.setIsNullable(source.getIsNullable());
        copy.setIsPrimary(source.getIsPrimary());
        copy.setIsPartition(source.getIsPartition());
        copy.setDefaultValue(source.getDefaultValue());
        copy.setFieldOrder(source.getFieldOrder());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }

    private String qualifiedName(String database, String tableName) {
        return wrapIdentifier(database) + "." + wrapIdentifier(tableName);
    }

    private String wrapIdentifier(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
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
}
