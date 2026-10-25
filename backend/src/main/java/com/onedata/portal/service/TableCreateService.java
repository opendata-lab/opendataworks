package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.TableColumnRequest;
import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.dto.TableDesignPreviewResponse;
import com.onedata.portal.dto.TableNameGenerateRequest;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.table.TableEngineHandler;
import com.onedata.portal.service.table.TableEngineHandlerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 表创建服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableCreateService {

    private final DataTableMapper dataTableMapper;
    private final DataFieldMapper dataFieldMapper;
    private final DorisClusterMapper dorisClusterMapper;
    private final TableNameGeneratorService tableNameGeneratorService;
    private final TableEngineHandlerRegistry tableEngineHandlerRegistry;
    private final TableMetadataVersionService tableMetadataVersionService;

    /**
     * 预览表设计（生成表名与 DDL）
     */
    public TableDesignPreviewResponse preview(TableCreateRequest request) {
        validateRequest(request);
        TableNameComponentsWrapper components = buildComponents(request);
        TableEngineHandler dorisHandler = tableEngineHandlerRegistry.require("DORIS");
        String ddl = dorisHandler.buildCreateDdl(components.getTableName(), request);
        return new TableDesignPreviewResponse(components.getTableName(), ddl);
    }

    /**
     * 创建表: 保存元数据并在 Doris 中执行建表
     */
    @Transactional
    public DataTable create(TableCreateRequest request) {
        validateRequest(request);
        validateDorisDatasource(request);
        TableNameComponentsWrapper components = buildComponents(request);
        TableEngineHandler dorisHandler = tableEngineHandlerRegistry.require("DORIS");

        ensureTableNotExists(components.getTableName(), request.getDbName(), request.getDorisClusterId());

        String ddl = StringUtils.hasText(request.getDorisDdl())
            ? request.getDorisDdl().trim()
            : dorisHandler.buildCreateDdl(components.getTableName(), request);

        DataTable dataTable = buildDataTableEntity(request, components);
        dorisHandler.prepareCreateMetadata(dataTable, request, ddl);
        dataTableMapper.insert(dataTable);

        persistColumns(dataTable.getId(), request);

        if (!Boolean.FALSE.equals(request.getSyncToDoris())) {
            dorisHandler.executeCreateTable(request.getDorisClusterId(), request.getDbName(), ddl);
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

    private void validateDorisDatasource(TableCreateRequest request) {
        Long datasourceId = request.getDorisClusterId();
        if (datasourceId == null) {
            if (!Boolean.FALSE.equals(request.getSyncToDoris())) {
                throw new RuntimeException("请指定 Doris 数据源");
            }
            return;
        }
        DorisCluster datasource = dorisClusterMapper.selectById(datasourceId);
        if (datasource == null) {
            throw new RuntimeException("数据源不存在: " + datasourceId);
        }
        String sourceType = DatasourceAdapterRegistry.normalize(datasource.getSourceType());
        if (!"DORIS".equals(sourceType)) {
            throw new RuntimeException("表设计器仅支持 Doris 数据源: " + sourceType);
        }
    }

    private TableNameComponentsWrapper buildComponents(TableCreateRequest request) {
        TableNameGenerateRequest generateRequest = tableNameGeneratorService.fromCreateRequest(request);
        TableNameGeneratorService.TableNameComponents components = tableNameGeneratorService.buildComponents(generateRequest);
        return new TableNameComponentsWrapper(components.getTableName(), components.getLayer(),
            components.getBusinessDomain(), components.getDataDomain(),
            components.getCustomIdentifier(), components.getStatisticsCycle(), components.getUpdateType());
    }

    private DataTable buildDataTableEntity(TableCreateRequest request, TableNameComponentsWrapper components) {
        DataTable table = new DataTable();
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
