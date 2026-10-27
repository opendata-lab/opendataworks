package com.onedata.portal.service.table;

import com.onedata.portal.dto.TableColumnRequest;
import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.service.DorisConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Doris-specific table metadata and physical DDL operations.
 */
@Component
@RequiredArgsConstructor
public class DorisTableEngineHandler implements TableEngineHandler {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private final DorisConnectionService dorisConnectionService;

    @Override
    public String sourceType() {
        return "DORIS";
    }

    @Override
    public void prepareCreateMetadata(DataTable table, TableCreateRequest request, String ddl) {
        table.setClusterId(request.getDorisClusterId());
        table.setTableModel(resolveTableModel(request.getTableModel()));
        table.setBucketNum(request.getBucketNum() != null ? request.getBucketNum() : 10);
        table.setReplicaNum(request.getReplicaNum() != null ? request.getReplicaNum() : 3);
        table.setPartitionColumn(request.getPartitionColumn());
        table.setDistributionColumn(joinColumns(request.getDistributionColumns()));
        table.setKeyColumns(joinColumns(request.getKeyColumns()));
        table.setDorisDdl(ddl);
    }

    @Override
    public String buildCreateDdl(String tableName, TableCreateRequest request) {
        List<String> columnDefinitions = request.getColumns().stream()
                .map(this::buildColumnDefinition)
                .collect(Collectors.toList());

        String tableModel = resolveTableModel(request.getTableModel());
        List<String> keyColumns = normalizeList(request.getKeyColumns());
        String tableModelClause = buildTableModelClause(tableModel, keyColumns);

        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE `").append(request.getDbName()).append("`.`").append(tableName).append("` (\n  ");
        ddl.append(String.join(",\n  ", columnDefinitions));
        ddl.append("\n) ENGINE=OLAP\n");

        if (StringUtils.hasText(tableModelClause)) {
            ddl.append(tableModelClause).append("\n");
        }

        if (StringUtils.hasText(request.getTableComment())) {
            ddl.append("COMMENT '").append(escapeSingleQuote(request.getTableComment())).append("'\n");
        }

        if (StringUtils.hasText(request.getPartitionColumn())) {
            ddl.append("PARTITION BY RANGE(").append(wrapColumn(request.getPartitionColumn())).append(") ()\n");
        }

        List<String> distributionColumns = normalizeList(request.getDistributionColumns());
        if (!distributionColumns.isEmpty()) {
            ddl.append("DISTRIBUTED BY HASH(")
                    .append(distributionColumns.stream().map(this::wrapColumn).collect(Collectors.joining(", ")))
                    .append(") BUCKETS ")
                    .append(request.getBucketNum() != null ? request.getBucketNum() : 10)
                    .append("\n");
        }

        ddl.append("PROPERTIES (\n");
        ddl.append("  \"replication_num\" = \"")
                .append(request.getReplicaNum() != null ? request.getReplicaNum() : 3)
                .append("\"\n");
        ddl.append(");");
        return ddl.toString();
    }

    @Override
    public void executeCreateTable(Long datasourceId, String database, String ddl) {
        dorisConnectionService.execute(datasourceId, database, ddl);
    }

    @Override
    public void alterTableComment(Long datasourceId, String database, String tableName, String comment) {
        dorisConnectionService.alterTableComment(datasourceId, database, tableName, comment);
    }

    @Override
    public void renameTable(Long datasourceId, String database, String oldTableName, String newTableName) {
        dorisConnectionService.renameTable(datasourceId, database, oldTableName, newTableName);
    }

    @Override
    public void dropTable(Long datasourceId, String database, String tableName) {
        dorisConnectionService.dropTable(datasourceId, database, tableName);
    }

    @Override
    public void addColumn(Long datasourceId, DataTable table, String database, String tableName, DataField field) {
        if (isAggregateTable(table)) {
            throw new RuntimeException("AGGREGATE 表字段变更需指定聚合方式，暂不支持同步");
        }
        boolean isKey = isKeyColumn(table, field);
        if (isKey) {
            throw new RuntimeException("Doris 不支持在线新增主键列");
        }
        String columnDef = dorisConnectionService.buildColumnDefinition(field, false);
        dorisConnectionService.addColumn(datasourceId, database, tableName, columnDef);
    }

    @Override
    public void updateColumn(Long datasourceId, DataTable table, String database, String tableName,
            DataField oldField, DataField newField) {
        if (!Objects.equals(oldField.getIsPrimary(), newField.getIsPrimary())) {
            throw new RuntimeException("Doris 不支持在线修改主键列");
        }
        boolean nameChanged = !Objects.equals(oldField.getFieldName(), newField.getFieldName());
        if (isAggregateTable(table)) {
            if (nameChanged || hasNonCommentChanges(oldField, newField)) {
                throw new RuntimeException("AGGREGATE 表字段变更需指定聚合方式，暂不支持同步");
            }
            if (onlyCommentChanged(oldField, newField)) {
                dorisConnectionService.modifyColumnComment(datasourceId, database, tableName,
                        newField.getFieldName(), newField.getFieldComment());
            }
            return;
        }
        if (nameChanged) {
            dorisConnectionService.renameColumn(datasourceId, database, tableName,
                    oldField.getFieldName(), newField.getFieldName());
        }
        // 只改注释走轻量 MODIFY COLUMN ... COMMENT：不重建列定义，
        // 也就不依赖平台侧 key 列元数据是否准确（智能元数据采纳走的正是这条路径）
        if (onlyCommentChanged(oldField, newField)) {
            dorisConnectionService.modifyColumnComment(datasourceId, database, tableName,
                    newField.getFieldName(), newField.getFieldComment());
            return;
        }
        if (isColumnChanged(oldField, newField)) {
            boolean isKey = isKeyColumn(table, newField);
            String columnDef = dorisConnectionService.buildColumnDefinition(newField, isKey);
            dorisConnectionService.modifyColumn(datasourceId, database, tableName, columnDef);
        }
    }

    @Override
    public void dropColumn(Long datasourceId, String database, String tableName, String columnName) {
        dorisConnectionService.dropColumn(datasourceId, database, tableName, columnName);
    }

    @Override
    public void modifyDistribution(Long datasourceId, DataTable table, String database, String tableName,
            Integer bucketNum) {
        if (!StringUtils.hasText(table.getDistributionColumn())) {
            throw new RuntimeException("缺少分桶字段，无法同步分桶数到 Doris");
        }
        dorisConnectionService.modifyDistribution(datasourceId, database, tableName,
                table.getDistributionColumn(), bucketNum);
    }

    @Override
    public void setReplicationNum(Long datasourceId, String database, String tableName, Integer replicaNum) {
        dorisConnectionService.setReplicationNum(datasourceId, database, tableName, replicaNum);
    }

    private String buildColumnDefinition(TableColumnRequest column) {
        StringBuilder builder = new StringBuilder();
        builder.append(wrapColumn(column.getColumnName())).append(" ").append(buildColumnType(column));
        if (Boolean.FALSE.equals(column.getNullable())) {
            builder.append(" NOT NULL");
        } else {
            builder.append(" NULL");
        }
        if (StringUtils.hasText(column.getDefaultValue())) {
            builder.append(" DEFAULT ").append(formatDefaultValue(column.getDefaultValue()));
        }
        if (StringUtils.hasText(column.getComment())) {
            builder.append(" COMMENT '").append(escapeSingleQuote(column.getComment())).append("'");
        }
        return builder.toString();
    }

    private String buildColumnType(TableColumnRequest column) {
        String dataType = column.getDataType() != null ? column.getDataType().toUpperCase() : "";
        String typeParams = column.getTypeParams();
        if (StringUtils.hasText(typeParams)) {
            String trimmed = typeParams.trim();
            if (trimmed.startsWith("(") && trimmed.endsWith(")")) {
                return dataType + trimmed;
            }
            return dataType + "(" + trimmed + ")";
        }
        return dataType;
    }

    private String resolveTableModel(String model) {
        if (!StringUtils.hasText(model)) {
            return "DUPLICATE";
        }
        String upper = model.trim().toUpperCase();
        if (upper.endsWith(" KEY")) {
            upper = upper.substring(0, upper.length() - 4).trim();
        }
        return upper;
    }

    private String buildTableModelClause(String model, List<String> keyColumns) {
        if (!StringUtils.hasText(model)) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        builder.append(model).append(" KEY");
        if (!CollectionUtils.isEmpty(keyColumns)) {
            builder.append("(")
                    .append(keyColumns.stream().map(this::wrapColumn).collect(Collectors.joining(", ")))
                    .append(")");
        }
        return builder.toString();
    }

    private String joinColumns(List<String> columns) {
        if (CollectionUtils.isEmpty(columns)) {
            return null;
        }
        return columns.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.joining(","));
    }

    private List<String> normalizeList(List<String> columns) {
        if (CollectionUtils.isEmpty(columns)) {
            return Collections.emptyList();
        }
        return columns.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .collect(Collectors.toList());
    }

    private boolean isAggregateTable(DataTable table) {
        return table != null && StringUtils.hasText(table.getTableModel())
                && "AGGREGATE".equalsIgnoreCase(table.getTableModel());
    }

    private boolean isKeyColumn(DataTable table, DataField field) {
        if (field != null && field.getIsPrimary() != null && field.getIsPrimary() == 1) {
            return true;
        }
        if (table == null || !StringUtils.hasText(table.getKeyColumns()) || field == null
                || !StringUtils.hasText(field.getFieldName())) {
            return false;
        }
        String[] keys = table.getKeyColumns().split(",");
        for (String key : keys) {
            if (field.getFieldName().equalsIgnoreCase(key.trim())) {
                return true;
            }
        }
        return false;
    }

    private boolean isColumnChanged(DataField oldField, DataField newField) {
        if (oldField == null || newField == null) {
            return false;
        }
        return !Objects.equals(normalize(oldField.getFieldType()), normalize(newField.getFieldType()))
                || !Objects.equals(normalize(oldField.getFieldComment()), normalize(newField.getFieldComment()))
                || !Objects.equals(normalize(oldField.getDefaultValue()), normalize(newField.getDefaultValue()))
                || !Objects.equals(normalize(oldField.getIsNullable()), normalize(newField.getIsNullable()))
                || !Objects.equals(normalize(oldField.getIsPrimary()), normalize(newField.getIsPrimary()));
    }

    private boolean hasNonCommentChanges(DataField oldField, DataField newField) {
        if (oldField == null || newField == null) {
            return false;
        }
        return !Objects.equals(normalize(oldField.getFieldType()), normalize(newField.getFieldType()))
                || !Objects.equals(normalize(oldField.getDefaultValue()), normalize(newField.getDefaultValue()))
                || !Objects.equals(normalize(oldField.getIsNullable()), normalize(newField.getIsNullable()))
                || !Objects.equals(normalize(oldField.getIsPrimary()), normalize(newField.getIsPrimary()));
    }

    private boolean onlyCommentChanged(DataField oldField, DataField newField) {
        if (oldField == null || newField == null) {
            return false;
        }
        return Objects.equals(normalize(oldField.getFieldType()), normalize(newField.getFieldType()))
                && Objects.equals(normalize(oldField.getDefaultValue()), normalize(newField.getDefaultValue()))
                && Objects.equals(normalize(oldField.getIsNullable()), normalize(newField.getIsNullable()))
                && Objects.equals(normalize(oldField.getIsPrimary()), normalize(newField.getIsPrimary()))
                && !Objects.equals(normalize(oldField.getFieldComment()), normalize(newField.getFieldComment()));
    }

    // 空串与 null 都表示「未设置」：前端表单会把缺省值/注释回填成空串，
    // 若按字面比较会把「注释未变」误判成定义变更，进而触发无谓的 MODIFY COLUMN
    private Object normalize(Object value) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        return value;
    }

    private String wrapColumn(String column) {
        return "`" + column + "`";
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
