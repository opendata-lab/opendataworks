package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.TableColumnRequest;
import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.dto.TableDesignPreviewResponse;
import com.onedata.portal.dto.TableNameGenerateRequest;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 表创建服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableCreateService {

    private static final Pattern NUMERIC_PATTERN = Pattern.compile("^-?\\d+(\\.\\d+)?$");

    private final DataTableMapper dataTableMapper;
    private final DataFieldMapper dataFieldMapper;
    private final TableNameGeneratorService tableNameGeneratorService;
    private final DorisConnectionService dorisConnectionService;
    private final TableMetadataVersionService tableMetadataVersionService;

    /**
     * 预览表设计（生成表名与 DDL）
     */
    public TableDesignPreviewResponse preview(TableCreateRequest request) {
        validateRequest(request);
        TableNameComponentsWrapper components = buildComponents(request);
        String ddl = buildCreateDdl(components.getTableName(), request);
        return new TableDesignPreviewResponse(components.getTableName(), ddl);
    }

    /**
     * 创建表: 保存元数据并在 Doris 中执行建表
     */
    @Transactional
    public DataTable create(TableCreateRequest request) {
        validateRequest(request);
        TableNameComponentsWrapper components = buildComponents(request);

        ensureTableNotExists(components.getTableName(), request.getDbName(), request.getDorisClusterId());

        String ddl = StringUtils.hasText(request.getDorisDdl())
            ? request.getDorisDdl().trim()
            : buildCreateDdl(components.getTableName(), request);

        DataTable dataTable = buildDataTableEntity(request, components, ddl);
        dataTableMapper.insert(dataTable);

        persistColumns(dataTable.getId(), request);

        if (!Boolean.FALSE.equals(request.getSyncToDoris())) {
            dorisConnectionService.execute(request.getDorisClusterId(), request.getDbName(), ddl);
            dataTable.setIsSynced(1);
            dataTable.setSyncTime(LocalDateTime.now());
        }

        dataTableMapper.updateById(dataTable);
        log.info("Created data table {} and synchronized to Doris: {}", dataTable.getTableName(), dataTable.getIsSynced());
        tableMetadataVersionService.captureVersion(dataTable.getId(),
                TableMetadataVersionService.TRIGGER_TABLE_CREATE, null);
        return dataTableMapper.selectById(dataTable.getId());
    }

    private void ensureTableNotExists(String tableName, String dbName, Long clusterId) {
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getTableName, tableName);
        if (StringUtils.hasText(dbName)) {
            wrapper.eq(DataTable::getDbName, dbName);
        }
        if (clusterId != null) {
            wrapper.eq(DataTable::getClusterId, clusterId);
        }
        DataTable exists = dataTableMapper.selectOne(wrapper);
        if (exists != null) {
            throw new RuntimeException("表名已存在: " + tableName);
        }
    }

    private void validateRequest(TableCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("表创建请求不能为空");
        }
        if (CollectionUtils.isEmpty(request.getColumns())) {
            throw new RuntimeException("表字段定义不能为空");
        }
        if (!StringUtils.hasText(request.getDbName())) {
            throw new RuntimeException("数据库名不能为空");
        }

        for (TableColumnRequest column : request.getColumns()) {
            if (!StringUtils.hasText(column.getColumnName())) {
                throw new RuntimeException("字段名不能为空");
            }
            if (!StringUtils.hasText(column.getDataType())) {
                throw new RuntimeException("字段类型不能为空");
            }
        }
    }

    private TableNameComponentsWrapper buildComponents(TableCreateRequest request) {
        TableNameGenerateRequest generateRequest = tableNameGeneratorService.fromCreateRequest(request);
        TableNameGeneratorService.TableNameComponents components = tableNameGeneratorService.buildComponents(generateRequest);
        return new TableNameComponentsWrapper(components.getTableName(), components.getLayer(),
            components.getBusinessDomain(), components.getDataDomain(),
            components.getCustomIdentifier(), components.getStatisticsCycle(), components.getUpdateType());
    }

    private DataTable buildDataTableEntity(TableCreateRequest request, TableNameComponentsWrapper components, String ddl) {
        DataTable table = new DataTable();
        table.setClusterId(request.getDorisClusterId());
        table.setTableName(components.getTableName());
        table.setTableComment(request.getTableComment());
        table.setLayer(components.getLayer());
        table.setBusinessDomain(components.getBusinessDomain());
        table.setDataDomain(components.getDataDomain());
        table.setCustomIdentifier(components.getCustomIdentifier());
        table.setStatisticsCycle(components.getStatisticsCycle());
        table.setUpdateType(components.getUpdateType());
        table.setDbName(request.getDbName());
        table.setOwner(request.getOwner());
        table.setTableModel(resolveTableModel(request.getTableModel()));
        table.setBucketNum(request.getBucketNum() != null ? request.getBucketNum() : 10);
        table.setReplicaNum(request.getReplicaNum() != null ? request.getReplicaNum() : 3);
        table.setPartitionColumn(request.getPartitionColumn());
        table.setDistributionColumn(joinColumns(request.getDistributionColumns()));
        table.setKeyColumns(joinColumns(request.getKeyColumns()));
        table.setDorisDdl(ddl);
        table.setIsSynced(0);
        table.setStatus("active");
        return table;
    }

    private void persistColumns(Long tableId, TableCreateRequest request) {
        List<TableColumnRequest> columns = request.getColumns();
        List<String> keyColumns = normalizeList(request.getKeyColumns());
        String partitionColumn = StringUtils.hasText(request.getPartitionColumn())
            ? request.getPartitionColumn().trim()
            : null;

        for (int i = 0; i < columns.size(); i++) {
            TableColumnRequest column = columns.get(i);
            DataField field = new DataField();
            field.setTableId(tableId);
            field.setFieldName(column.getColumnName());
            field.setFieldType(buildColumnType(column));
            field.setFieldComment(column.getComment());
            field.setIsNullable(Boolean.FALSE.equals(column.getNullable()) ? 0 : 1);
            boolean isPrimary = (column.getPrimaryKey() != null && column.getPrimaryKey())
                || containsIgnoreCase(keyColumns, column.getColumnName());
            field.setIsPrimary(isPrimary ? 1 : 0);
            boolean isPartition = Boolean.TRUE.equals(column.getPartitionColumn())
                || (partitionColumn != null && partitionColumn.equalsIgnoreCase(column.getColumnName()));
            field.setIsPartition(isPartition ? 1 : 0);
            field.setDefaultValue(column.getDefaultValue());
            field.setFieldOrder(i + 1);
            dataFieldMapper.insert(field);
        }
    }

    private String buildCreateDdl(String tableName, TableCreateRequest request) {
        List<String> columnDefinitions = new ArrayList<>();
        for (TableColumnRequest column : request.getColumns()) {
            columnDefinitions.add(buildColumnDefinition(column));
        }

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
        ddl.append("  \"replication_num\" = \"").append(request.getReplicaNum() != null ? request.getReplicaNum() : 3).append("\",\n");
        ddl.append("  \"storage_format\" = \"V2\",\n");
        ddl.append("  \"compression\" = \"LZ4\"\n");
        ddl.append(");");

        return ddl.toString();
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

    private boolean containsIgnoreCase(List<String> list, String value) {
        if (CollectionUtils.isEmpty(list) || !StringUtils.hasText(value)) {
            return false;
        }
        for (String item : list) {
            if (value.equalsIgnoreCase(item)) {
                return true;
            }
        }
        return false;
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

    /**
     * 包装结构, 携带表名及拆分后的组件
     */
    private static class TableNameComponentsWrapper {
        private final String tableName;
        private final String layer;
        private final String businessDomain;
        private final String dataDomain;
        private final String customIdentifier;
        private final String statisticsCycle;
        private final String updateType;

        TableNameComponentsWrapper(String tableName, String layer, String businessDomain, String dataDomain,
                                   String customIdentifier, String statisticsCycle, String updateType) {
            this.tableName = tableName;
            this.layer = layer;
            this.businessDomain = businessDomain;
            this.dataDomain = dataDomain;
            this.customIdentifier = customIdentifier;
            this.statisticsCycle = statisticsCycle;
            this.updateType = updateType;
        }

        public String getTableName() {
            return tableName;
        }

        public String getLayer() {
            return layer;
        }

        public String getBusinessDomain() {
            return businessDomain;
        }

        public String getDataDomain() {
            return dataDomain;
        }

        public String getCustomIdentifier() {
            return customIdentifier;
        }

        public String getStatisticsCycle() {
            return statisticsCycle;
        }

        public String getUpdateType() {
            return updateType;
        }
    }
}
