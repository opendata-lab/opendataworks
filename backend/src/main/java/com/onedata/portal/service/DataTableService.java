package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.TableLineageItem;
import com.onedata.portal.dto.TableOption;
import com.onedata.portal.dto.TableRelatedLineageResponse;
import com.onedata.portal.dto.TableRelatedTasksResponse;
import com.onedata.portal.dto.TableTaskInfo;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 数据表服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataTableService {

    private static final Set<String> VALID_LAYERS = new HashSet<>(Arrays.asList("ODS", "DWD", "DIM", "DWS", "ADS"));

    private final DataTableMapper dataTableMapper;
    private final DataFieldMapper dataFieldMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final DataTaskMapper dataTaskMapper;
    private final TaskExecutionLogMapper taskExecutionLogMapper;
    private final DataLineageMapper dataLineageMapper;
    private final DorisClusterMapper dorisClusterMapper;
    private final DorisConnectionService dorisConnectionService;
    private final TableMetadataVersionService tableMetadataVersionService;

    /**
     * 分页查询表列表
     */
    public Page<DataTable> list(int pageNum, int pageSize, String layer, String keyword, String sortField,
            String sortOrder, Long clusterId) {
        Page<DataTable> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<>();

        if (clusterId != null) {
            wrapper.eq(DataTable::getClusterId, clusterId);
        }
        if (layer != null && !layer.isEmpty()) {
            wrapper.eq(DataTable::getLayer, layer);
        }
        if (keyword != null && !keyword.isEmpty()) {
            wrapper.and(w -> w.like(DataTable::getTableName, keyword)
                    .or().like(DataTable::getTableComment, keyword));
        }

        // 排除已软删除的表
        wrapper.ne(DataTable::getStatus, "deprecated");

        // 应用排序
        applySorting(wrapper, sortField, sortOrder);

        return dataTableMapper.selectPage(page, wrapper);
    }

    /**
     * 获取所有数据库列表
     */
    public List<String> listDatabases(Long clusterId) {
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<DataTable>()
                .ne(DataTable::getStatus, "deprecated");
        if (clusterId != null) {
            wrapper.eq(DataTable::getClusterId, clusterId);
        }
        List<DataTable> allTables = dataTableMapper.selectList(wrapper);
        return allTables.stream()
                .map(DataTable::getDbName)
                .filter(dbName -> dbName != null && !dbName.isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * 根据数据库获取表列表
     */
    public List<DataTable> listByDatabase(String database, String sortField, String sortOrder, Long clusterId) {
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(DataTable::getDbName, database);
        wrapper.ne(DataTable::getStatus, "deprecated");
        if (clusterId != null) {
            wrapper.eq(DataTable::getClusterId, clusterId);
        }

        // 应用排序
        applySorting(wrapper, sortField, sortOrder);

        return dataTableMapper.selectList(wrapper);
    }

    /**
     * 获取软删除表键集合（db::table）。
     */
    public Set<String> listSoftDeletedTableKeys(Long clusterId) {
        return listSoftDeletedTableKeys(clusterId, null);
    }

    /**
     * 获取软删除表键集合（db::table）。
     */
    public Set<String> listSoftDeletedTableKeys(Long clusterId, String dbName) {
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<DataTable>()
                .select(DataTable::getDbName, DataTable::getTableName)
                .eq(DataTable::getStatus, "deprecated");
        if (clusterId != null) {
            wrapper.eq(DataTable::getClusterId, clusterId);
        }
        if (StringUtils.hasText(dbName)) {
            wrapper.eq(DataTable::getDbName, dbName.trim());
        }
        List<DataTable> tables = dataTableMapper.selectList(wrapper);
        Set<String> result = new HashSet<>(tables.size());
        for (DataTable table : tables) {
            String key = buildDbTableKey(table.getDbName(), table.getTableName());
            if (key != null) {
                result.add(key);
            }
        }
        return result;
    }

    public static String buildDbTableKey(String dbName, String tableName) {
        if (!StringUtils.hasText(dbName) || !StringUtils.hasText(tableName)) {
            return null;
        }
        return dbName.trim().toLowerCase(Locale.ROOT) + "::" + tableName.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 应用排序逻辑
     */
    private void applySorting(LambdaQueryWrapper<DataTable> wrapper, String sortField, String sortOrder) {
        boolean isAsc = "asc".equalsIgnoreCase(sortOrder);

        if (sortField == null || sortField.isEmpty()) {
            wrapper.orderByDesc(DataTable::getCreatedAt);
            return;
        }

        switch (sortField) {
            case "tableName":
                if (isAsc)
                    wrapper.orderByAsc(DataTable::getTableName);
                else
                    wrapper.orderByDesc(DataTable::getTableName);
                break;
            case "createdAt":
                if (isAsc)
                    wrapper.orderByAsc(DataTable::getDorisCreateTime).orderByAsc(DataTable::getCreatedAt);
                else
                    wrapper.orderByDesc(DataTable::getDorisCreateTime).orderByDesc(DataTable::getCreatedAt);
                break;
            case "updatedAt":
                if (isAsc)
                    wrapper.orderByAsc(DataTable::getUpdatedAt);
                else
                    wrapper.orderByDesc(DataTable::getUpdatedAt);
                break;
            case "dorisUpdateTime":
                if (isAsc)
                    wrapper.orderByAsc(DataTable::getDorisUpdateTime);
                else
                    wrapper.orderByDesc(DataTable::getDorisUpdateTime);
                break;
            case "rowCount":
                if (isAsc)
                    wrapper.orderByAsc(DataTable::getRowCount);
                else
                    wrapper.orderByDesc(DataTable::getRowCount);
                break;
            case "storageSize":
                if (isAsc)
                    wrapper.orderByAsc(DataTable::getStorageSize);
                else
                    wrapper.orderByDesc(DataTable::getStorageSize);
                break;
            default:
                wrapper.orderByDesc(DataTable::getCreatedAt);
        }
    }

    /**
     * 根据ID获取表信息
     */
    public DataTable getById(Long id) {
        return dataTableMapper.selectById(id);
    }

    /**
     * 根据数据库和表名获取表信息
     */
    public DataTable getByDbAndTableName(Long clusterId, String dbName, String tableName) {
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<DataTable>()
                .eq(DataTable::getDbName, dbName)
                .eq(DataTable::getTableName, tableName);
        if (clusterId != null) {
            wrapper.eq(DataTable::getClusterId, clusterId);
        }
        return dataTableMapper.selectOne(wrapper);
    }

    /**
     * 创建表
     */
    @Transactional
    public DataTable create(DataTable dataTable) {
        dataTable.setLayer(normalizeLayer(dataTable.getLayer(), true));

        // 检查表名是否已存在（在同一数据库下）
        DataTable exists = getByDbAndTableName(dataTable.getClusterId(), dataTable.getDbName(), dataTable.getTableName());
        if (exists != null) {
            throw new RuntimeException("该数据库下已存在同名表: " + dataTable.getTableName());
        }

        dataTableMapper.insert(dataTable);
        log.info("Created data table: {}", dataTable.getTableName());
        tableMetadataVersionService.captureVersion(dataTable.getId(),
                TableMetadataVersionService.TRIGGER_TABLE_CREATE, null);
        return dataTable;
    }

    /**
     * 更新表
     */
    @Transactional
    public DataTable update(DataTable dataTable) {
        DataTable exists = dataTableMapper.selectById(dataTable.getId());
        if (exists == null) {
            throw new RuntimeException("表不存在");
        }

        if (dataTable.getLayer() != null) {
            dataTable.setLayer(normalizeLayer(dataTable.getLayer(), true));
        }

        // 检查表名是否发生变化且是否重复
        String newTableName = dataTable.getTableName();
        String newDbName = dataTable.getDbName();
        Long targetClusterId = dataTable.getClusterId() != null ? dataTable.getClusterId() : exists.getClusterId();
        if (StringUtils.hasText(newTableName)) {
            LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<DataTable>()
                    .eq(DataTable::getDbName, newDbName)
                    .eq(DataTable::getTableName, newTableName)
                    .ne(DataTable::getId, dataTable.getId());
            if (targetClusterId != null) {
                wrapper.eq(DataTable::getClusterId, targetClusterId);
            }
            DataTable duplicate = dataTableMapper.selectOne(wrapper);
            if (duplicate != null) {
                throw new RuntimeException("该数据库下已存在同名表: " + newTableName);
            }
        }

        dataTableMapper.updateById(dataTable);
        log.info("Updated data table: {}", dataTable.getTableName());
        tableMetadataVersionService.captureVersion(dataTable.getId(),
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);
        return dataTable;
    }

    /**
     * 删除表
     */
    @Transactional
    public void delete(Long id) {
        dataTableMapper.deleteById(id);
        log.info("Deleted data table: {}", id);
    }

    /**
     * 校验并标准化数据分层
     */
    public String normalizeLayer(String layer, boolean required) {
        if (!StringUtils.hasText(layer)) {
            if (required) {
                throw new RuntimeException("数据分层不能为空，且必须是 ODS/DWD/DIM/DWS/ADS 之一");
            }
            return null;
        }
        String normalized = layer.trim().toUpperCase(Locale.ROOT);
        if (!VALID_LAYERS.contains(normalized)) {
            throw new RuntimeException("数据分层非法，仅支持 ODS/DWD/DIM/DWS/ADS");
        }
        return normalized;
    }

    /**
     * 查询待删除表列表（deprecated 且存在 purge_at）
     */
    public List<DataTable> listPendingDeletion(Long clusterId) {
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<DataTable>()
                .eq(DataTable::getStatus, "deprecated")
                .isNotNull(DataTable::getPurgeAt)
                .orderByAsc(DataTable::getPurgeAt)
                .orderByAsc(DataTable::getId);
        if (clusterId != null) {
            wrapper.eq(DataTable::getClusterId, clusterId);
        }
        return dataTableMapper.selectList(wrapper);
    }

    /**
     * 查询已到期可物理清理的表
     */
    public List<DataTable> listDueForPurge(LocalDateTime now, int limit) {
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<DataTable>()
                .eq(DataTable::getStatus, "deprecated")
                .isNotNull(DataTable::getPurgeAt)
                .le(DataTable::getPurgeAt, now)
                .orderByAsc(DataTable::getPurgeAt)
                .orderByAsc(DataTable::getId);
        if (limit > 0) {
            wrapper.last("LIMIT " + limit);
        }
        return dataTableMapper.selectList(wrapper);
    }

    /**
     * 恢复废弃表元数据
     */
    @Transactional
    public DataTable restoreDeprecatedTable(DataTable table, String restoredTableName) {
        if (table == null || table.getId() == null) {
            throw new RuntimeException("表不存在");
        }
        if (!StringUtils.hasText(restoredTableName)) {
            throw new RuntimeException("恢复失败：原始表名为空");
        }

        LambdaQueryWrapper<DataTable> duplicateWrapper = new LambdaQueryWrapper<DataTable>()
                .eq(DataTable::getDbName, table.getDbName())
                .eq(DataTable::getTableName, restoredTableName)
                .ne(DataTable::getId, table.getId())
                .last("LIMIT 1");
        if (table.getClusterId() == null) {
            duplicateWrapper.isNull(DataTable::getClusterId);
        } else {
            duplicateWrapper.eq(DataTable::getClusterId, table.getClusterId());
        }
        DataTable duplicate = dataTableMapper.selectOne(duplicateWrapper);
        if (duplicate != null) {
            throw new RuntimeException("恢复失败：目标表名已存在 " + restoredTableName);
        }

        DataTable update = new DataTable();
        update.setId(table.getId());
        update.setTableName(restoredTableName);
        update.setStatus("active");
        update.setOriginTableName(null);
        update.setDeprecatedAt(null);
        update.setPurgeAt(null);
        dataTableMapper.updateById(update);
        tableMetadataVersionService.captureVersion(table.getId(),
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);
        return dataTableMapper.selectById(table.getId());
    }

    /**
     * 立即清理平台侧表元数据（逻辑删除）
     */
    @Transactional
    public void purgeTableMetadata(Long tableId) {
        dataFieldMapper.delete(new LambdaQueryWrapper<DataField>()
                .eq(DataField::getTableId, tableId));
        tableTaskRelationMapper.delete(new LambdaQueryWrapper<TableTaskRelation>()
                .eq(TableTaskRelation::getTableId, tableId));
        dataLineageMapper.delete(new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getUpstreamTableId, tableId));
        dataLineageMapper.delete(new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getDownstreamTableId, tableId));
        dataTableMapper.deleteById(tableId);
        log.info("Purged table metadata, tableId={}", tableId);
    }

    /**
     * 获取所有表（用于任务配置）
     */
    public List<DataTable> listAll() {
        return dataTableMapper.selectList(
                new LambdaQueryWrapper<DataTable>()
                        .eq(DataTable::getStatus, "active")
                        .orderByAsc(DataTable::getLayer, DataTable::getTableName));
    }

    /**
     * 远程搜索表选项
     */
    public List<TableOption> searchTableOptions(String keyword, Integer limit, String layer, String dbName) {
        return searchTableOptions(keyword, limit, layer, dbName, null);
    }

    /**
     * 远程搜索表选项
     */
    public List<TableOption> searchTableOptions(String keyword, Integer limit, String layer, String dbName, Long clusterId) {
        if (!StringUtils.hasText(keyword)) {
            return Collections.emptyList();
        }

        String trimmed = keyword.trim();
        int pageSize = (limit != null && limit > 0) ? Math.min(limit, 100) : 50;

        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<>();
        wrapper.and(w -> w.like(DataTable::getTableName, trimmed)
                .or().like(DataTable::getTableComment, trimmed));

        if (StringUtils.hasText(layer)) {
            wrapper.eq(DataTable::getLayer, layer.trim());
        }

        if (StringUtils.hasText(dbName)) {
            wrapper.eq(DataTable::getDbName, dbName.trim());
        }

        if (clusterId != null) {
            wrapper.eq(DataTable::getClusterId, clusterId);
        }

        wrapper.ne(DataTable::getStatus, "deprecated");

        wrapper.orderByAsc(DataTable::getTableName);

        Page<DataTable> page = new Page<>(1, pageSize);
        Page<DataTable> result = dataTableMapper.selectPage(page, wrapper);

        Set<Long> clusterIds = result.getRecords().stream()
                .map(DataTable::getClusterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, DorisCluster> clusterMap = clusterIds.isEmpty()
                ? Collections.emptyMap()
                : dorisClusterMapper.selectBatchIds(clusterIds).stream()
                        .collect(Collectors.toMap(DorisCluster::getId, c -> c));

        return result.getRecords().stream()
                .map(table -> toTableOption(table, clusterMap))
                .collect(Collectors.toList());
    }

    private TableOption toTableOption(DataTable table, Map<Long, DorisCluster> clusterMap) {
        TableOption option = new TableOption();
        option.setId(table.getId());
        option.setClusterId(table.getClusterId());
        DorisCluster cluster = table.getClusterId() == null ? null : clusterMap.get(table.getClusterId());
        option.setClusterName(cluster != null ? cluster.getClusterName() : null);
        option.setSourceType(cluster != null ? cluster.getSourceType() : null);
        option.setTableName(table.getTableName());
        option.setTableComment(table.getTableComment());
        option.setLayer(table.getLayer());
        option.setDbName(table.getDbName());
        option.setQualifiedName(StringUtils.hasText(table.getDbName())
                ? table.getDbName() + "." + table.getTableName()
                : table.getTableName());
        return option;
    }

    /**
     * 获取表字段列表
     */
    public List<DataField> listFields(Long tableId) {
        return dataFieldMapper.selectList(
                new LambdaQueryWrapper<DataField>()
                        .eq(DataField::getTableId, tableId)
                        .orderByAsc(DataField::getFieldOrder, DataField::getId));
    }

    /**
     * 创建字段
     */
    @Transactional
    public DataField createField(DataTable table, DataField field, Long clusterId) {
        // 检查字段名是否已存在
        DataField exists = dataFieldMapper.selectOne(
                new LambdaQueryWrapper<DataField>()
                        .eq(DataField::getTableId, field.getTableId())
                        .eq(DataField::getFieldName, field.getFieldName()));
        if (exists != null) {
            throw new RuntimeException("字段名已存在: " + field.getFieldName());
        }
        if (!StringUtils.hasText(field.getFieldName()) || !StringUtils.hasText(field.getFieldType())) {
            throw new RuntimeException("字段名和类型不能为空");
        }

        if (isDorisTable(table)) {
            TableRef tableRef = resolveTableRef(table);
            if (tableRef == null) {
                throw new RuntimeException("表未配置数据库名，请先设置 dbName 字段");
            }
            if (isAggregateTable(table)) {
                throw new RuntimeException("AGGREGATE 表字段变更需指定聚合方式，暂不支持同步");
            }
            boolean isKey = isKeyColumn(table, field);
            if (isKey) {
                throw new RuntimeException("Doris 不支持在线新增主键列");
            }
            String columnDef = dorisConnectionService.buildColumnDefinition(field, isKey);
            dorisConnectionService.addColumn(clusterId, tableRef.database, tableRef.tableName, columnDef);
        }

        dataFieldMapper.insert(field);
        log.info("Created field: {} for table: {}", field.getFieldName(), field.getTableId());
        tableMetadataVersionService.captureVersion(field.getTableId(),
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);
        return field;
    }

    /**
     * 更新字段
     */
    @Transactional
    public DataField updateField(DataTable table, DataField field, Long clusterId) {
        DataField exists = dataFieldMapper.selectById(field.getId());
        if (exists == null) {
            throw new RuntimeException("字段不存在");
        }

        String newFieldName = StringUtils.hasText(field.getFieldName()) ? field.getFieldName() : exists.getFieldName();
        if (StringUtils.hasText(newFieldName) && !newFieldName.equals(exists.getFieldName())) {
            DataField duplicate = dataFieldMapper.selectOne(
                    new LambdaQueryWrapper<DataField>()
                            .eq(DataField::getTableId, field.getTableId())
                            .eq(DataField::getFieldName, newFieldName)
                            .ne(DataField::getId, field.getId()));
            if (duplicate != null) {
                throw new RuntimeException("字段名已存在: " + newFieldName);
            }
        }

        DataField toUpdate = mergeField(exists, field);

        if (isDorisTable(table)) {
            TableRef tableRef = resolveTableRef(table);
            if (tableRef == null) {
                throw new RuntimeException("表未配置数据库名，请先设置 dbName 字段");
            }
            if (!Objects.equals(exists.getIsPrimary(), toUpdate.getIsPrimary())) {
                throw new RuntimeException("Doris 不支持在线修改主键列");
            }
            boolean nameChanged = !Objects.equals(exists.getFieldName(), toUpdate.getFieldName());
            if (isAggregateTable(table)) {
                if (nameChanged || hasNonCommentChanges(exists, toUpdate)) {
                    throw new RuntimeException("AGGREGATE 表字段变更需指定聚合方式，暂不支持同步");
                }
                if (onlyCommentChanged(exists, toUpdate)) {
                    dorisConnectionService.modifyColumnComment(clusterId, tableRef.database, tableRef.tableName,
                            toUpdate.getFieldName(), toUpdate.getFieldComment());
                }
            } else {
                if (nameChanged) {
                    dorisConnectionService.renameColumn(clusterId, tableRef.database, tableRef.tableName,
                            exists.getFieldName(), toUpdate.getFieldName());
                }
                if (isColumnChanged(exists, toUpdate)) {
                    boolean isKey = isKeyColumn(table, toUpdate);
                    String columnDef = dorisConnectionService.buildColumnDefinition(toUpdate, isKey);
                    dorisConnectionService.modifyColumn(clusterId, tableRef.database, tableRef.tableName, columnDef);
                }
            }
        }

        dataFieldMapper.updateById(toUpdate);
        log.info("Updated field: {}", toUpdate.getFieldName());
        tableMetadataVersionService.captureVersion(toUpdate.getTableId(),
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);
        return toUpdate;
    }

    /**
     * 删除字段
     */
    @Transactional
    public void deleteField(DataTable table, Long fieldId, Long clusterId) {
        DataField exists = dataFieldMapper.selectById(fieldId);
        if (exists == null) {
            throw new RuntimeException("字段不存在");
        }
        if (isDorisTable(table)) {
            TableRef tableRef = resolveTableRef(table);
            if (tableRef == null) {
                throw new RuntimeException("表未配置数据库名，请先设置 dbName 字段");
            }
            dorisConnectionService.dropColumn(clusterId, tableRef.database, tableRef.tableName, exists.getFieldName());
        }
        dataFieldMapper.deleteById(fieldId);
        log.info("Deleted field: {}", fieldId);
        tableMetadataVersionService.captureVersion(exists.getTableId(),
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);
    }

    private boolean isDorisTable(DataTable table) {
        if (table == null) {
            return false;
        }
        if (table.getIsSynced() != null && table.getIsSynced() == 1) {
            return true;
        }
        return StringUtils.hasText(table.getTableModel())
                || isPositive(table.getBucketNum())
                || isPositive(table.getReplicaNum())
                || StringUtils.hasText(table.getDistributionColumn())
                || StringUtils.hasText(table.getKeyColumns())
                || StringUtils.hasText(table.getPartitionColumn());
    }

    private boolean isPositive(Integer value) {
        return value != null && value > 0;
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

    private Object normalize(Object value) {
        if (value instanceof String) {
            return ((String) value).trim();
        }
        return value;
    }

    private DataField mergeField(DataField exists, DataField incoming) {
        DataField next = new DataField();
        next.setId(exists.getId());
        next.setTableId(exists.getTableId());
        next.setFieldName(StringUtils.hasText(incoming.getFieldName()) ? incoming.getFieldName() : exists.getFieldName());
        next.setFieldType(StringUtils.hasText(incoming.getFieldType()) ? incoming.getFieldType() : exists.getFieldType());
        next.setFieldComment(incoming.getFieldComment() != null ? incoming.getFieldComment() : exists.getFieldComment());
        next.setIsNullable(incoming.getIsNullable() != null ? incoming.getIsNullable() : exists.getIsNullable());
        next.setIsPrimary(incoming.getIsPrimary() != null ? incoming.getIsPrimary() : exists.getIsPrimary());
        next.setIsPartition(incoming.getIsPartition() != null ? incoming.getIsPartition() : exists.getIsPartition());
        next.setDefaultValue(incoming.getDefaultValue() != null ? incoming.getDefaultValue() : exists.getDefaultValue());
        next.setFieldOrder(incoming.getFieldOrder() != null ? incoming.getFieldOrder() : exists.getFieldOrder());
        next.setCreatedAt(exists.getCreatedAt());
        next.setUpdatedAt(LocalDateTime.now());
        return next;
    }

    private TableRef resolveTableRef(DataTable table) {
        if (table == null) {
            return null;
        }
        String database = table.getDbName();
        String tableName = table.getTableName();
        if (StringUtils.hasText(database)) {
            String actual = extractActualTableName(database, tableName);
            if (!StringUtils.hasText(actual)) {
                return null;
            }
            return new TableRef(database, actual);
        }
        if (StringUtils.hasText(tableName) && tableName.contains(".")) {
            String[] parts = tableName.split("\\.", 2);
            if (parts.length == 2 && StringUtils.hasText(parts[0]) && StringUtils.hasText(parts[1])) {
                return new TableRef(parts[0], parts[1]);
            }
        }
        return null;
    }

    private String extractActualTableName(String database, String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return null;
        }
        if (tableName.contains(".")) {
            String[] parts = tableName.split("\\.", 2);
            if (parts.length == 2 && StringUtils.hasText(parts[1])) {
                return parts[1];
            }
        }
        return tableName;
    }

    private static class TableRef {
        private final String database;
        private final String tableName;

        private TableRef(String database, String tableName) {
            this.database = database;
            this.tableName = tableName;
        }
    }

    /**
     * 获取表的关联任务
     */
    public TableRelatedTasksResponse getRelatedTasks(Long tableId) {
        TableRelatedTasksResponse response = new TableRelatedTasksResponse();
        List<TableTaskRelation> relations = tableTaskRelationMapper.selectList(
                new LambdaQueryWrapper<TableTaskRelation>()
                        .eq(TableTaskRelation::getTableId, tableId));
        if (relations.isEmpty()) {
            return response;
        }

        Set<Long> taskIds = relations.stream()
                .map(TableTaskRelation::getTaskId)
                .collect(Collectors.toSet());
        if (taskIds.isEmpty()) {
            return response;
        }

        List<DataTask> tasks = dataTaskMapper.selectBatchIds(taskIds);
        Map<Long, DataTask> taskMap = tasks.stream()
                .collect(Collectors.toMap(DataTask::getId, t -> t));

        for (TableTaskRelation relation : relations) {
            DataTask task = taskMap.get(relation.getTaskId());
            if (task == null) {
                continue;
            }
            TableTaskInfo info = buildTaskInfo(task, relation.getRelationType());
            if ("write".equalsIgnoreCase(relation.getRelationType())) {
                response.getWriteTasks().add(info);
            } else {
                response.getReadTasks().add(info);
            }
        }

        sortTasks(response.getWriteTasks());
        sortTasks(response.getReadTasks());
        return response;
    }

    /**
     * 获取表上下游
     */
    public TableRelatedLineageResponse getRelatedLineage(Long tableId) {
        TableRelatedLineageResponse response = new TableRelatedLineageResponse();

        // 1. 找到所有写入当前表的任务（这些任务读取的表是上游表）
        List<TableTaskRelation> writeRelations = tableTaskRelationMapper.selectList(
                new LambdaQueryWrapper<TableTaskRelation>()
                        .eq(TableTaskRelation::getTableId, tableId)
                        .eq(TableTaskRelation::getRelationType, "write"));

        Set<Long> writeTasks = writeRelations.stream()
                .map(TableTaskRelation::getTaskId)
                .collect(Collectors.toSet());

        // 2. 找到所有从当前表读取的任务（这些任务写入的表是下游表）
        List<TableTaskRelation> readRelations = tableTaskRelationMapper.selectList(
                new LambdaQueryWrapper<TableTaskRelation>()
                        .eq(TableTaskRelation::getTableId, tableId)
                        .eq(TableTaskRelation::getRelationType, "read"));

        Set<Long> readTasks = readRelations.stream()
                .map(TableTaskRelation::getTaskId)
                .collect(Collectors.toSet());

        // 3. 获取上游表ID（写入任务读取的表）
        Set<Long> upstreamIds = new LinkedHashSet<>();
        if (!writeTasks.isEmpty()) {
            List<TableTaskRelation> upstreamRelations = tableTaskRelationMapper.selectList(
                    new LambdaQueryWrapper<TableTaskRelation>()
                            .in(TableTaskRelation::getTaskId, writeTasks)
                            .eq(TableTaskRelation::getRelationType, "read"));
            upstreamIds = upstreamRelations.stream()
                    .map(TableTaskRelation::getTableId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // 4. 获取下游表ID（读取任务写入的表）
        Set<Long> downstreamIds = new LinkedHashSet<>();
        if (!readTasks.isEmpty()) {
            List<TableTaskRelation> downstreamRelations = tableTaskRelationMapper.selectList(
                    new LambdaQueryWrapper<TableTaskRelation>()
                            .in(TableTaskRelation::getTaskId, readTasks)
                            .eq(TableTaskRelation::getRelationType, "write"));
            downstreamIds = downstreamRelations.stream()
                    .map(TableTaskRelation::getTableId)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        }

        // 5. 查询表详情
        Set<Long> allIds = new HashSet<>();
        allIds.addAll(upstreamIds);
        allIds.addAll(downstreamIds);

        if (allIds.isEmpty()) {
            return response;
        }

        List<DataTable> tables = dataTableMapper.selectList(
                new LambdaQueryWrapper<DataTable>()
                        .in(DataTable::getId, allIds)
                        .ne(DataTable::getStatus, "deprecated"));
        Map<Long, DataTable> tableMap = tables.stream()
                .collect(Collectors.toMap(DataTable::getId, t -> t));

        // 6. 构建响应
        for (Long id : upstreamIds) {
            DataTable table = tableMap.get(id);
            if (table != null) {
                response.getUpstreamTables().add(buildLineageItem(table));
            }
        }
        for (Long id : downstreamIds) {
            DataTable table = tableMap.get(id);
            if (table != null) {
                response.getDownstreamTables().add(buildLineageItem(table));
            }
        }
        return response;
    }

    private TableTaskInfo buildTaskInfo(DataTask task, String relationType) {
        TableTaskInfo info = new TableTaskInfo();
        info.setId(task.getId());
        info.setTaskName(task.getTaskName());
        info.setTaskCode(task.getTaskCode());
        info.setRelationType(relationType);
        info.setStatus(task.getStatus());
        info.setEngine(task.getEngine());
        info.setScheduleCron(task.getScheduleCron());

        TaskExecutionLog lastLog = taskExecutionLogMapper.selectOne(
                new LambdaQueryWrapper<TaskExecutionLog>()
                        .eq(TaskExecutionLog::getTaskId, task.getId())
                        .orderByDesc(TaskExecutionLog::getStartTime)
                        .last("LIMIT 1"));
        if (lastLog != null) {
            LocalDateTime executedAt = lastLog.getEndTime() != null ? lastLog.getEndTime() : lastLog.getStartTime();
            info.setLastExecuted(executedAt);
            info.setLastExecutionStatus(lastLog.getStatus());
        }
        return info;
    }

    private void sortTasks(List<TableTaskInfo> tasks) {
        tasks.sort((a, b) -> {
            LocalDateTime timeA = a.getLastExecuted();
            LocalDateTime timeB = b.getLastExecuted();
            if (timeA == null && timeB == null) {
                return 0;
            }
            if (timeA == null) {
                return 1;
            }
            if (timeB == null) {
                return -1;
            }
            return timeB.compareTo(timeA);
        });
    }

    private TableLineageItem buildLineageItem(DataTable table) {
        TableLineageItem item = new TableLineageItem();
        item.setId(table.getId());
        item.setTableName(table.getTableName());
        item.setTableComment(table.getTableComment());
        item.setLayer(table.getLayer());
        item.setBusinessDomain(table.getBusinessDomain());
        item.setDataDomain(table.getDataDomain());
        return item;
    }
}
