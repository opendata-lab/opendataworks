package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.TableLineageItem;
import com.onedata.portal.dto.TableLocation;
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
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.lineage.TaskLineageWriteService;
import com.onedata.portal.service.table.TableEngineHandler;
import com.onedata.portal.service.table.TableEngineHandlerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
    private final TableEngineHandlerRegistry tableEngineHandlerRegistry;
    private final DatasourceAdapterRegistry datasourceAdapterRegistry;
    private final TableMetadataVersionService tableMetadataVersionService;
    private final TaskLineageWriteService taskLineageWriteService;

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
        normalizeCreateMetadata(dataTable);

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

    private void normalizeCreateMetadata(DataTable dataTable) {
        if (dataTable == null) {
            return;
        }
        Long clusterId = dataTable.getClusterId();
        if (clusterId == null) {
            TableEngineHandler.clearDorisPhysicalMetadata(dataTable);
            return;
        }
        DorisCluster cluster = dorisClusterMapper.selectById(clusterId);
        if (cluster == null) {
            throw new RuntimeException("数据源不存在: " + clusterId);
        }
        String sourceType = DatasourceAdapterRegistry.normalize(cluster.getSourceType());
        if (datasourceAdapterRegistry.require(sourceType).descriptor().supports("engineProperties")) {
            return;
        }
        TableEngineHandler handler = tableEngineHandlerRegistry.find(sourceType);
        if (handler != null) {
            handler.prepareCreateMetadata(dataTable, null, null);
            return;
        }
        TableEngineHandler.clearDorisPhysicalMetadata(dataTable);
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
        // 必须在删除关系之前收集受影响的工作流：删完就查不到归属了。
        Set<Long> affectedTaskIds = taskLineageWriteService.findTaskIdsByTableId(tableId);
        Set<Long> affectedWorkflowIds = taskLineageWriteService.findWorkflowIdsByTaskIds(affectedTaskIds);

        dataFieldMapper.delete(new LambdaQueryWrapper<DataField>()
                .eq(DataField::getTableId, tableId));
        tableTaskRelationMapper.delete(new LambdaQueryWrapper<TableTaskRelation>()
                .eq(TableTaskRelation::getTableId, tableId));
        dataLineageMapper.delete(new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getUpstreamTableId, tableId));
        dataLineageMapper.delete(new LambdaQueryWrapper<DataLineage>()
                .eq(DataLineage::getDownstreamTableId, tableId));
        dataTableMapper.deleteById(tableId);

        // 删表会让引用它的任务少掉一条边，definitionJson 必须同步重建，否则会留下指向已删表的孤边。
        taskLineageWriteService.refreshWorkflowDefinitions(affectedWorkflowIds, null);
        log.info("Purged table metadata, tableId={}, refreshedWorkflows={}", tableId, affectedWorkflowIds);
    }

    /**
     * 按所属数据源类型清理物理表；未同步表仅清理平台元数据。
     */
    public void dropPhysicalTableIfRequired(DataTable table) {
        ResolvedTableEngine engine = resolveSyncedPhysicalEngine(table, null);
        if (engine == null) {
            return;
        }
        TableRef tableRef = resolveTableRef(table);
        if (tableRef == null) {
            throw new RuntimeException("缺少数据库名或表名");
        }
        engine.handler.dropTable(engine.clusterId, tableRef.database, tableRef.tableName);
    }

    /**
     * 解析表的物理位置（库名 + 去前缀后的实际表名）。
     * 优先使用 dbName 字段，否则从 {@code db.table} 形式的表名解析；无法解析时抛出标准提示。
     */
    public TableLocation requireTableLocation(DataTable table) {
        String database;
        String actualTableName;
        if (table.getDbName() != null && !table.getDbName().isEmpty()) {
            database = table.getDbName();
            actualTableName = table.getTableName().contains(".")
                    ? table.getTableName().split("\\.", 2)[1]
                    : table.getTableName();
        } else if (table.getTableName().contains(".")) {
            String[] parts = table.getTableName().split("\\.", 2);
            database = parts[0];
            actualTableName = parts[1];
        } else {
            throw new RuntimeException("表未配置数据库名，请先设置 dbName 字段");
        }
        return new TableLocation(database, actualTableName);
    }

    /**
     * 更新表（含 Doris 改名/注释/分桶/副本同步）。
     */
    @Transactional
    public DataTable updateTable(Long id, DataTable dataTable, Long clusterId) {
        DataTable existing = getById(id);
        if (existing == null) {
            throw new RuntimeException("表不存在");
        }
        if (!StringUtils.hasText(dataTable.getLayer())) {
            throw new RuntimeException("数据分层不能为空");
        }
        dataTable.setLayer(normalizeLayer(dataTable.getLayer(), true));
        dataTable.setId(id);
        String newTableName = StringUtils.hasText(dataTable.getTableName())
                ? dataTable.getTableName()
                : existing.getTableName();
        TableRef tableRef = resolveTableRef(existing);
        String oldTableName = tableRef != null
                ? tableRef.tableName
                : extractActualTableName(existing.getDbName(), existing.getTableName());
        String newActualName = extractActualTableName(tableRef != null ? tableRef.database : existing.getDbName(),
                newTableName);
        boolean physicalTableUpdate = hasPhysicalTableUpdate(existing, dataTable, oldTableName, newActualName);
        ResolvedTableEngine engine = physicalTableUpdate ? resolveSyncedPhysicalEngine(existing, clusterId) : null;
        if (engine != null && tableRef == null) {
            throw new RuntimeException("表未配置数据库名，请先设置 dbName 字段");
        }
        if (engine != null) {
            try {
                if (StringUtils.hasText(newTableName) && !Objects.equals(oldTableName, newActualName)) {
                    engine.handler.renameTable(engine.clusterId, tableRef.database, oldTableName, newActualName);
                }
                if (StringUtils.hasText(dataTable.getTableComment())
                        && !Objects.equals(existing.getTableComment(), dataTable.getTableComment())) {
                    engine.handler.alterTableComment(engine.clusterId, tableRef.database, newActualName,
                            dataTable.getTableComment());
                }
                if (dataTable.getBucketNum() != null && !Objects.equals(existing.getBucketNum(), dataTable.getBucketNum())) {
                    engine.handler.modifyDistribution(engine.clusterId, existing, tableRef.database, newActualName,
                            dataTable.getBucketNum());
                }
                if (dataTable.getReplicaNum() != null && !Objects.equals(existing.getReplicaNum(), dataTable.getReplicaNum())) {
                    engine.handler.setReplicationNum(engine.clusterId, tableRef.database, newActualName,
                            dataTable.getReplicaNum());
                }
            } catch (Exception e) {
                if ("缺少分桶字段，无法同步分桶数到 Doris".equals(e.getMessage())) {
                    throw new RuntimeException(e.getMessage());
                }
                if (e.getMessage() != null && (e.getMessage().startsWith("暂不支持同步 ")
                        || e.getMessage().startsWith("MySQL 数据源不支持"))) {
                    throw new RuntimeException(e.getMessage());
                }
                String engineName = datasourceAdapterRegistry.require(engine.handler.sourceType()).descriptor().getLabel();
                throw new RuntimeException("同步 " + engineName + " 失败: " + e.getMessage());
            }
        } else if (physicalTableUpdate && isSyncedPhysicalTable(existing)) {
            try {
                resolveSyncedPhysicalEngine(existing, clusterId);
            } catch (Exception e) {
                throw new RuntimeException(e.getMessage());
            }
        }
        dataTable.setId(id);
        return update(dataTable);
    }

    private boolean hasPhysicalTableUpdate(DataTable existing, DataTable incoming,
            String oldActualTableName, String newActualTableName) {
        return (StringUtils.hasText(incoming.getTableName())
                && !Objects.equals(oldActualTableName, newActualTableName))
                || (StringUtils.hasText(incoming.getTableComment())
                        && !Objects.equals(existing.getTableComment(), incoming.getTableComment()))
                || (incoming.getBucketNum() != null && !Objects.equals(existing.getBucketNum(), incoming.getBucketNum()))
                || (incoming.getReplicaNum() != null && !Objects.equals(existing.getReplicaNum(), incoming.getReplicaNum()));
    }

    /**
     * 修改表注释（同时更新 Doris 和本地）。
     */
    @Transactional
    public void updateTableComment(Long id, String comment, Long clusterId) {
        if (comment == null) {
            throw new RuntimeException("注释内容不能为空");
        }
        DataTable table = getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        ResolvedTableEngine engine = resolveSyncedPhysicalEngine(table, clusterId);
        try {
            if (engine != null) {
                TableLocation location = requireTableLocation(table);
                engine.handler.alterTableComment(engine.clusterId,
                        location.getDatabase(), location.getTableName(), comment);
            }

            // 更新本地表注释
            table.setTableComment(comment);
            update(table);
        } catch (Exception e) {
            throw new RuntimeException("修改表注释失败: " + e.getMessage());
        }
    }

    /**
     * 软删除表（重命名为 tableName_deprecated_时间戳）。
     */
    @Transactional
    public void softDeleteTable(Long id, Long clusterId, String confirmTableName) {
        DataTable table = getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        if ("deprecated".equalsIgnoreCase(table.getStatus())) {
            throw new RuntimeException("表已处于待删除状态");
        }
        ResolvedTableEngine engine = resolveSyncedPhysicalEngine(table, clusterId);

        TableLocation location = requireTableLocation(table);
        String database = location.getDatabase();
        String actualTableName = location.getTableName();
        if (!isConfirmTableNameMatched(confirmTableName, actualTableName)) {
            throw new RuntimeException("确认失败：请输入正确的表名 " + actualTableName);
        }

        try {
            // 生成新表名
            LocalDateTime now = LocalDateTime.now();
            String timestamp = now.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
            String newTableName = actualTableName + "_deprecated_" + timestamp;

            if (engine != null) {
                engine.handler.renameTable(engine.clusterId, database, actualTableName, newTableName);
            }

            // 更新本地记录
            table.setOriginTableName(actualTableName);
            table.setTableName(newTableName);
            table.setStatus("deprecated");
            table.setDeprecatedAt(now);
            table.setPurgeAt(now.plusDays(30));
            update(table);
        } catch (Exception e) {
            throw new RuntimeException("删除表失败: " + e.getMessage());
        }
    }

    /**
     * 恢复待删除表。
     */
    @Transactional
    public DataTable restoreTable(Long id, Long clusterId) {
        DataTable table = getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        if (!"deprecated".equalsIgnoreCase(table.getStatus())) {
            throw new RuntimeException("仅支持恢复已废弃表");
        }

        TableRef tableRef = resolveTableRef(table);
        if (tableRef == null) {
            throw new RuntimeException("表未配置数据库名，请先设置 dbName 字段");
        }

        String restoreTableName = resolveRestoreTableName(table, tableRef.tableName);
        if (!StringUtils.hasText(restoreTableName)) {
            throw new RuntimeException("恢复失败：缺少原始表名");
        }
        DataTable duplicate = getByDbAndTableName(table.getClusterId(), tableRef.database, restoreTableName);
        if (duplicate != null && !Objects.equals(duplicate.getId(), table.getId())) {
            throw new RuntimeException("恢复失败：目标表名冲突 " + restoreTableName);
        }

        ResolvedTableEngine engine = resolveSyncedPhysicalEngine(table, clusterId);
        try {
            if (engine != null) {
                engine.handler.renameTable(engine.clusterId, tableRef.database, tableRef.tableName, restoreTableName);
            }
            return restoreDeprecatedTable(table, restoreTableName);
        } catch (Exception e) {
            throw new RuntimeException("恢复表失败: " + e.getMessage());
        }
    }

    /**
     * 立即物理删除表。
     */
    @Transactional
    public void purgeTableNow(Long id, Long clusterId, String confirmTableName) {
        DataTable table = getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        if (!"deprecated".equalsIgnoreCase(table.getStatus())) {
            throw new RuntimeException("仅支持清理已废弃表");
        }

        TableRef tableRef = resolveTableRef(table);
        if (tableRef == null) {
            throw new RuntimeException("表未配置数据库名，请先设置 dbName 字段");
        }
        if (!isConfirmTableNameMatched(confirmTableName, tableRef.tableName)) {
            throw new RuntimeException("确认失败：请输入正确的表名 " + tableRef.tableName);
        }
        ResolvedTableEngine engine = resolveSyncedPhysicalEngine(table, clusterId);

        try {
            if (engine != null) {
                engine.handler.dropTable(engine.clusterId, tableRef.database, tableRef.tableName);
            }
            purgeTableMetadata(id);
        } catch (Exception e) {
            throw new RuntimeException("立即清理失败: " + e.getMessage());
        }
    }

    /**
     * 待删除表视图（含剩余天数计算），用于列表展示。
     */
    public List<Map<String, Object>> listPendingDeletionView(Long clusterId) {
        List<DataTable> tables = listPendingDeletion(clusterId);
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> result = new ArrayList<>(tables.size());
        for (DataTable table : tables) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", table.getId());
            item.put("clusterId", table.getClusterId());
            item.put("dbName", table.getDbName());
            item.put("tableName", table.getTableName());
            item.put("originTableName", table.getOriginTableName());
            item.put("tableComment", table.getTableComment());
            item.put("status", table.getStatus());
            item.put("isSynced", table.getIsSynced());
            item.put("deprecatedAt", table.getDeprecatedAt());
            item.put("purgeAt", table.getPurgeAt());
            item.put("remainingDays", calculateRemainingDays(now, table.getPurgeAt()));
            result.add(item);
        }
        return result;
    }

    /**
     * 确认表名是否匹配（用于删除/清理二次确认）。
     */
    public static boolean isConfirmTableNameMatched(String confirmTableName, String expectedTableName) {
        if (!StringUtils.hasText(confirmTableName) || !StringUtils.hasText(expectedTableName)) {
            return false;
        }
        return confirmTableName.trim().equals(expectedTableName.trim());
    }

    private String resolveRestoreTableName(DataTable table, String currentActualTableName) {
        if (table != null && StringUtils.hasText(table.getOriginTableName())) {
            return table.getOriginTableName().trim();
        }
        if (!StringUtils.hasText(currentActualTableName)) {
            return null;
        }
        return currentActualTableName.replaceFirst("_deprecated_\\d{14}$", "");
    }

    private Long calculateRemainingDays(LocalDateTime now, LocalDateTime purgeAt) {
        if (now == null || purgeAt == null) {
            return null;
        }
        long seconds = Duration.between(now, purgeAt).getSeconds();
        if (seconds <= 0) {
            return 0L;
        }
        return (seconds + 86_399) / 86_400;
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

        ResolvedTableEngine engine = resolveSyncedPhysicalEngine(table, clusterId);
        if (engine != null) {
            TableRef tableRef = resolveTableRef(table);
            if (tableRef == null) {
                throw new RuntimeException("表未配置数据库名，请先设置 dbName 字段");
            }
            engine.handler.addColumn(engine.clusterId, table, tableRef.database, tableRef.tableName, field);
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

        ResolvedTableEngine engine = resolveSyncedPhysicalEngine(table, clusterId);
        if (engine != null) {
            TableRef tableRef = resolveTableRef(table);
            if (tableRef == null) {
                throw new RuntimeException("表未配置数据库名，请先设置 dbName 字段");
            }
            engine.handler.updateColumn(engine.clusterId, table, tableRef.database, tableRef.tableName, exists, toUpdate);
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
        ResolvedTableEngine engine = resolveSyncedPhysicalEngine(table, clusterId);
        if (engine != null) {
            TableRef tableRef = resolveTableRef(table);
            if (tableRef == null) {
                throw new RuntimeException("表未配置数据库名，请先设置 dbName 字段");
            }
            engine.handler.dropColumn(engine.clusterId, tableRef.database, tableRef.tableName, exists.getFieldName());
        }
        dataFieldMapper.deleteById(fieldId);
        log.info("Deleted field: {}", fieldId);
        tableMetadataVersionService.captureVersion(exists.getTableId(),
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);
    }

    /**
     * 新增字段（按表 ID，含表存在性与 Doris 集群校验）。
     */
    @Transactional
    public DataField createField(Long tableId, DataField field, Long clusterId) {
        DataTable table = getById(tableId);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        field.setTableId(tableId);
        return createField(table, field, clusterId);
    }

    /**
     * 更新字段（按表 ID，含表存在性与 Doris 集群校验）。
     */
    @Transactional
    public DataField updateField(Long tableId, Long fieldId, DataField field, Long clusterId) {
        DataTable table = getById(tableId);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        field.setId(fieldId);
        field.setTableId(tableId);
        return updateField(table, field, clusterId);
    }

    /**
     * 删除字段（按表 ID，含表存在性与 Doris 集群校验）。
     */
    @Transactional
    public void deleteField(Long tableId, Long fieldId, Long clusterId) {
        DataTable table = getById(tableId);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        deleteField(table, fieldId, clusterId);
    }

    public boolean requiresDorisPhysicalSync(DataTable table) {
        return requiresDorisPhysicalSync(table, null);
    }

    public boolean requiresDorisPhysicalSync(DataTable table, Long clusterId) {
        ResolvedTableEngine engine = resolveSyncedPhysicalEngine(table, clusterId);
        return engine != null && datasourceAdapterRegistry.require(engine.handler.sourceType())
                .descriptor().supports("engineProperties");
    }

    public boolean isSyncedPhysicalTable(DataTable table) {
        if (table == null) {
            return false;
        }
        return table.getIsSynced() != null && table.getIsSynced() == 1;
    }

    private Long resolveActualClusterId(DataTable table, Long clusterId) {
        if (clusterId != null) {
            return clusterId;
        }
        return table == null ? null : table.getClusterId();
    }

    private ResolvedTableEngine resolveSyncedPhysicalEngine(DataTable table, Long clusterId) {
        if (!isSyncedPhysicalTable(table)) {
            return null;
        }
        Long actualClusterId = resolveActualClusterId(table, clusterId);
        if (actualClusterId == null) {
            throw new RuntimeException("请指定数据源");
        }
        DorisCluster cluster = dorisClusterMapper.selectById(actualClusterId);
        if (cluster == null) {
            throw new RuntimeException("数据源不存在: " + actualClusterId);
        }
        datasourceAdapterRegistry.requireCapability(cluster.getSourceType(), "structureEdit");
        String sourceType = DatasourceAdapterRegistry.normalize(cluster.getSourceType());
        TableEngineHandler handler = tableEngineHandlerRegistry.require(sourceType);
        return new ResolvedTableEngine(handler, actualClusterId);
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

    public String extractActualTableName(String database, String tableName) {
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

    private static class ResolvedTableEngine {
        private final TableEngineHandler handler;
        private final Long clusterId;

        private ResolvedTableEngine(TableEngineHandler handler, Long clusterId) {
            this.handler = handler;
            this.clusterId = clusterId;
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
