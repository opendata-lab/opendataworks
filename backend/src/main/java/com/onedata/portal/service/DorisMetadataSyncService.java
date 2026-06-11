package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.TableStatisticsHistory;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.TableStatisticsHistoryMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.util.TableNameUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Doris 元数据同步服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DorisMetadataSyncService {

    private final DorisConnectionService dorisConnectionService;
    private final DorisClusterMapper dorisClusterMapper;
    private final DataTableMapper dataTableMapper;
    private final DataFieldMapper dataFieldMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final DataLineageMapper dataLineageMapper;
    private final TableStatisticsHistoryMapper tableStatisticsHistoryMapper;
    private final TableMetadataVersionService tableMetadataVersionService;

    private static final Set<String> IGNORED_DATABASES = new HashSet<>(Arrays.asList("performance_schema", "sys"));
    private static final int MAX_COMMENT_LENGTH = 5000;

    /**
     * 同步结果
     */
    public static class SyncResult {
        private int newTables = 0;
        private int updatedTables = 0;
        private int newFields = 0;
        private int updatedFields = 0;
        private int deletedFields = 0;
        private int deletedTables = 0;
        private int blockedDeletedTables = 0;
        private int inactivatedTables = 0;
        private List<String> errors = new ArrayList<>();
        private final ChangeDetails changeDetails = new ChangeDetails();

        public void addNewTable() {
            newTables++;
        }

        public void addUpdatedTable() {
            updatedTables++;
        }

        public void addNewField() {
            newFields++;
        }

        public void addUpdatedField() {
            updatedFields++;
        }

        public void addDeletedField() {
            deletedFields++;
        }

        public void addDeletedTable() {
            deletedTables++;
        }

        public void addBlockedDeletedTable() {
            blockedDeletedTables++;
        }

        public void addInactivatedTable() {
            inactivatedTables++;
        }

        public void addError(String error) {
            errors.add(error);
        }

        public void addAddedTableDetail(String database, String tableName, String summary) {
            changeDetails.addAdded(new ChangeItem("TABLE", database, tableName, null, summary, Collections.emptyMap()));
        }

        public void addUpdatedTableDetail(String database, String tableName, String summary, Map<String, Object> changes) {
            changeDetails.addUpdated(new ChangeItem("TABLE", database, tableName, null, summary, sanitizeChanges(changes)));
        }

        public void addDeletedTableDetail(String database, String tableName, String summary) {
            changeDetails.addDeleted(new ChangeItem("TABLE", database, tableName, null, summary, Collections.emptyMap()));
        }

        public void addAddedFieldDetail(String database,
                String tableName,
                String fieldName,
                Map<String, Object> changes) {
            changeDetails.addAdded(new ChangeItem("FIELD", database, tableName, fieldName, "新增字段", sanitizeChanges(changes)));
        }

        public void addUpdatedFieldDetail(String database,
                String tableName,
                String fieldName,
                Map<String, Object> changes) {
            changeDetails.addUpdated(new ChangeItem("FIELD", database, tableName, fieldName, "字段属性更新", sanitizeChanges(changes)));
        }

        public void addDeletedFieldDetail(String database, String tableName, String fieldName) {
            changeDetails.addDeleted(new ChangeItem("FIELD", database, tableName, fieldName, "删除字段", Collections.emptyMap()));
        }

        public ChangeDetails getChangeDetails() {
            return changeDetails;
        }

        private Map<String, Object> sanitizeChanges(Map<String, Object> changes) {
            if (changes == null || changes.isEmpty()) {
                return Collections.emptyMap();
            }
            return new LinkedHashMap<>(changes);
        }

        public int getNewTables() {
            return newTables;
        }

        public int getUpdatedTables() {
            return updatedTables;
        }

        public int getNewFields() {
            return newFields;
        }

        public int getUpdatedFields() {
            return updatedFields;
        }

        public int getDeletedFields() {
            return deletedFields;
        }

        public int getDeletedTables() {
            return deletedTables;
        }

        public int getInactivatedTables() {
            return inactivatedTables;
        }

        public int getBlockedDeletedTables() {
            return blockedDeletedTables;
        }

        public List<String> getErrors() {
            return errors;
        }

        public String getStatus() {
            if (errors == null || errors.isEmpty()) {
                return "SUCCESS";
            }
            int totalApplied = newTables + updatedTables + deletedTables + inactivatedTables
                    + newFields + updatedFields + deletedFields + blockedDeletedTables;
            return totalApplied > 0 ? "PARTIAL" : "FAILED";
        }

        @Override
        public String toString() {
            return String.format(
                    "SyncResult{status=%s, newTables=%d, updatedTables=%d, deletedTables=%d, blockedDeletedTables=%d, inactivatedTables=%d, newFields=%d, updatedFields=%d, deletedFields=%d, errors=%d, changeDetails={added=%d, updated=%d, deleted=%d}}",
                    getStatus(), newTables, updatedTables, deletedTables, blockedDeletedTables, inactivatedTables,
                    newFields, updatedFields, deletedFields, errors.size(),
                    changeDetails.getAdded().size(), changeDetails.getUpdated().size(), changeDetails.getDeleted().size());
        }
    }

    /**
     * 同步变更明细
     */
    public static class ChangeDetails {
        private final List<ChangeItem> added = new ArrayList<>();
        private final List<ChangeItem> updated = new ArrayList<>();
        private final List<ChangeItem> deleted = new ArrayList<>();

        public void addAdded(ChangeItem item) {
            if (item != null) {
                added.add(item);
            }
        }

        public void addUpdated(ChangeItem item) {
            if (item != null) {
                updated.add(item);
            }
        }

        public void addDeleted(ChangeItem item) {
            if (item != null) {
                deleted.add(item);
            }
        }

        public List<ChangeItem> getAdded() {
            return added;
        }

        public List<ChangeItem> getUpdated() {
            return updated;
        }

        public List<ChangeItem> getDeleted() {
            return deleted;
        }
    }

    /**
     * 同步变更项
     */
    public static class ChangeItem {
        private final String objectType;
        private final String database;
        private final String tableName;
        private final String fieldName;
        private final String summary;
        private final Map<String, Object> changes;

        public ChangeItem(String objectType,
                String database,
                String tableName,
                String fieldName,
                String summary,
                Map<String, Object> changes) {
            this.objectType = objectType;
            this.database = database;
            this.tableName = tableName;
            this.fieldName = fieldName;
            this.summary = summary;
            this.changes = changes == null ? Collections.emptyMap() : changes;
        }

        public String getObjectType() {
            return objectType;
        }

        public String getDatabase() {
            return database;
        }

        public String getTableName() {
            return tableName;
        }

        public String getFieldName() {
            return fieldName;
        }

        public String getSummary() {
            return summary;
        }

        public Map<String, Object> getChanges() {
            return changes;
        }
    }

    /**
     * 稽核/比对结果
     */
    public static class AuditResult {
        private List<TableDifference> tableDifferences = new ArrayList<>();
        private List<String> errors = new ArrayList<>();
        private int totalDifferences = 0;
        private int statisticsSynced = 0; // 自动同步的统计信息数量

        public void addTableDifference(TableDifference diff) {
            tableDifferences.add(diff);
            totalDifferences++;
        }

        public void addError(String error) {
            errors.add(error);
        }

        public void incrementStatisticsSynced() {
            statisticsSynced++;
        }

        public List<TableDifference> getTableDifferences() {
            return tableDifferences;
        }

        public List<String> getErrors() {
            return errors;
        }

        public int getTotalDifferences() {
            return totalDifferences;
        }

        public int getStatisticsSynced() {
            return statisticsSynced;
        }

        public boolean hasDifferences() {
            return totalDifferences > 0;
        }

        @Override
        public String toString() {
            return String.format(
                    "AuditResult{totalDifferences=%d, statisticsSynced=%d, errors=%d}",
                    totalDifferences, statisticsSynced, errors.size());
        }
    }

    /**
     * 表差异信息
     */
    public static class TableDifference {
        private String database;
        private String tableName;
        private DifferenceType type; // NEW, UPDATED, MISSING
        private List<String> changes = new ArrayList<>();
        private List<FieldDifference> fieldDifferences = new ArrayList<>();

        public TableDifference(String database, String tableName, DifferenceType type) {
            this.database = database;
            this.tableName = tableName;
            this.type = type;
        }

        public void addChange(String change) {
            changes.add(change);
        }

        public void addFieldDifference(FieldDifference diff) {
            fieldDifferences.add(diff);
        }

        public String getDatabase() {
            return database;
        }

        public String getTableName() {
            return tableName;
        }

        public DifferenceType getType() {
            return type;
        }

        public List<String> getChanges() {
            return changes;
        }

        public List<FieldDifference> getFieldDifferences() {
            return fieldDifferences;
        }
    }

    /**
     * 字段差异信息
     */
    public static class FieldDifference {
        private String fieldName;
        private DifferenceType type; // NEW, UPDATED, REMOVED
        private Map<String, Object> changes = new HashMap<>();

        public FieldDifference(String fieldName, DifferenceType type) {
            this.fieldName = fieldName;
            this.type = type;
        }

        public void addChange(String field, Object oldValue, Object newValue) {
            Map<String, Object> diff = new HashMap<>(2);
            diff.put("old", oldValue);
            diff.put("new", newValue);
            changes.put(field, diff);
        }

        public String getFieldName() {
            return fieldName;
        }

        public DifferenceType getType() {
            return type;
        }

        public Map<String, Object> getChanges() {
            return changes;
        }
    }

    /**
     * 差异类型
     */
    public enum DifferenceType {
        NEW, // Doris 有但平台没有
        UPDATED, // 两边都有但信息不同
        REMOVED // 平台有但 Doris 没有（冗余）
    }

    /**
     * 稽核指定集群的所有元数据（只比对不同步）
     */
    public AuditResult auditAllMetadata(Long clusterId) {
        resolveCluster(clusterId);
        AuditResult result = new AuditResult();
        log.info("Starting metadata audit for cluster: {}", clusterId);

        try {
            // 获取所有数据库
            List<String> databases = dorisConnectionService.getAllDatabases(clusterId).stream()
                    .filter(db -> !IGNORED_DATABASES.contains(db))
                    .collect(Collectors.toList());
            log.info("Found {} databases to audit (filtered)", databases.size());

            appendInaccessibleDatabaseWarnings(clusterId, databases, result);

            for (String database : databases) {
                try {
                    auditDatabase(clusterId, database, result);
                } catch (Exception e) {
                    log.error("Failed to audit database: {}", database, e);
                    result.addError("稽核数据库 " + database + " 失败: " + e.getMessage());
                }
            }

            log.info("Metadata audit completed: {}", result);
        } catch (Exception e) {
            log.error("Failed to audit metadata", e);
            throw new RuntimeException("Failed to audit metadata: " + e.getMessage(), e);
        }

        return result;
    }

    /**
     * 稽核指定数据库的元数据
     */
    public AuditResult auditDatabase(Long clusterId, String database, AuditResult result) {
        if (result == null) {
            result = new AuditResult();
        }

        log.info("Auditing database: {}", database);

        // 获取 Doris 中的所有表
        List<Map<String, Object>> dorisTables = dorisConnectionService.getTablesInDatabase(clusterId, database);

        // 获取本地已存在的表
        List<DataTable> localTables = dataTableMapper.selectList(
                new LambdaQueryWrapper<DataTable>()
                        .eq(DataTable::getDbName, database)
                        .eq(DataTable::getClusterId, clusterId));

        Map<String, DataTable> localTableMap = localTables.stream()
                .collect(Collectors.toMap(DataTable::getTableName, t -> t));

        Set<String> dorisTableNames = new HashSet<>();

        // 遍历 Doris 中的表，检查差异
        for (Map<String, Object> dorisTable : dorisTables) {
            String tableName = (String) dorisTable.get("tableName");
            dorisTableNames.add(tableName);

            try {
                DataTable localTable = localTableMap.get(tableName);

                if (localTable == null) {
                    // 新表：Doris 有但平台没有
                    TableDifference diff = new TableDifference(database, tableName, DifferenceType.NEW);
                    diff.addChange("表在 Doris 中存在，但平台未记录");

                    String tableComment = (String) dorisTable.get("tableComment");
                    if (tableComment != null && !tableComment.isEmpty()) {
                        diff.addChange("表注释: " + tableComment);
                    }

                    result.addTableDifference(diff);
                } else {
                    // 已存在表：只比对结构差异，不在稽核路径写入统计信息。
                    TableDifference diff = compareTable(clusterId, database, tableName, dorisTable, localTable);
                    if (diff != null && (!diff.getChanges().isEmpty() || !diff.getFieldDifferences().isEmpty())) {
                        result.addTableDifference(diff);
                    }
                }
            } catch (Exception e) {
                log.error("Failed to audit table {}.{}", database, tableName, e);
                result.addError("稽核表 " + database + "." + tableName + " 失败: " + e.getMessage());
            }
        }

        // 检查平台有但 Doris 没有的表（冗余表）
        for (DataTable localTable : localTables) {
            if (!dorisTableNames.contains(localTable.getTableName())) {
                TableDifference diff = new TableDifference(database, localTable.getTableName(), DifferenceType.REMOVED);
                diff.addChange("表在平台中存在，但 Doris 中已删除");
                result.addTableDifference(diff);
            }
        }

        return result;
    }

    private boolean applyTableStatistics(Long clusterId,
            String database,
            String tableName,
            Map<String, Object> dorisTable,
            DataTable localTable,
            SyncResult result) {
        Map<String, Object> tableChanges = new LinkedHashMap<>();

        // 优先使用 runtime stats（SHOW TABLE STATS / information_schema.table_stats）
        DorisConnectionService.TableRuntimeStats runtimeStats = null;
        if (StringUtils.hasText(database)) {
            runtimeStats = dorisConnectionService
                    .getTableRuntimeStats(clusterId, database, tableName)
                    .orElse(null);
        }

        if (runtimeStats != null) {
            if (runtimeStats.getRowCount() != null
                    && !Objects.equals(runtimeStats.getRowCount(), localTable.getRowCount())) {
                addChangedValue(tableChanges, "rowCount", localTable.getRowCount(), runtimeStats.getRowCount());
                localTable.setRowCount(runtimeStats.getRowCount());
            }

            if (runtimeStats.getDataSize() != null
                    && !Objects.equals(runtimeStats.getDataSize(), localTable.getStorageSize())) {
                addChangedValue(tableChanges, "storageSize", localTable.getStorageSize(), runtimeStats.getDataSize());
                localTable.setStorageSize(runtimeStats.getDataSize());
            }

            Timestamp lastUpdate = runtimeStats.getLastUpdate();
            if (lastUpdate != null) {
                LocalDateTime updateDateTime = lastUpdate.toLocalDateTime();
                if (!Objects.equals(updateDateTime, localTable.getDorisUpdateTime())) {
                    addChangedValue(tableChanges, "dorisUpdateTime", localTable.getDorisUpdateTime(), updateDateTime);
                    localTable.setDorisUpdateTime(updateDateTime);
                }
            }
        } else {
            // 后备：使用 information_schema.tables 中的粗略统计
            Long tableRows = (Long) dorisTable.get("tableRows");
            if (tableRows != null && !Objects.equals(tableRows, localTable.getRowCount())) {
                addChangedValue(tableChanges, "rowCount", localTable.getRowCount(), tableRows);
                localTable.setRowCount(tableRows);
            }

            Long dataLength = (Long) dorisTable.get("dataLength");
            if (dataLength != null && !Objects.equals(dataLength, localTable.getStorageSize())) {
                addChangedValue(tableChanges, "storageSize", localTable.getStorageSize(), dataLength);
                localTable.setStorageSize(dataLength);
            }

            Timestamp updateTime = (Timestamp) dorisTable.get("updateTime");
            if (updateTime != null) {
                LocalDateTime updateDateTime = updateTime.toLocalDateTime();
                if (!Objects.equals(updateDateTime, localTable.getDorisUpdateTime())) {
                    addChangedValue(tableChanges, "dorisUpdateTime", localTable.getDorisUpdateTime(), updateDateTime);
                    localTable.setDorisUpdateTime(updateDateTime);
                }
            }
        }

        if (tableChanges.isEmpty()) {
            return false;
        }

        localTable.setSyncTime(LocalDateTime.now());
        dataTableMapper.updateById(localTable);
        result.addUpdatedTable();
        result.addUpdatedTableDetail(database, tableName, "表统计信息更新", tableChanges);
        recordStatisticsSnapshot(clusterId, database, tableName, localTable);
        log.debug("Synced statistics for table: {}.{}", database, tableName);
        return true;
    }

    /**
     * 比对单个表的结构差异（只比对结构，不比对统计信息）
     */
    private TableDifference compareTable(Long clusterId, String database, String tableName,
            Map<String, Object> dorisTable, DataTable localTable) {
        TableDifference diff = new TableDifference(database, tableName, DifferenceType.UPDATED);

        try {
            // 获取表的详细信息
            Map<String, Object> tableCreateInfo = dorisConnectionService.getTableCreateInfo(clusterId, database,
                    tableName);
            List<Map<String, Object>> dorisColumns = dorisConnectionService.getColumnsInTable(clusterId, database,
                    tableName);
            String dorisTableType = normalizeTableType((String) dorisTable.get("tableType"));
            boolean viewType = isViewType(dorisTableType);

            if (!Objects.equals(dorisTableType, normalizeTableType(localTable.getTableType()))) {
                diff.addChange(String.format("表类型不同: 平台='%s', 数据源='%s'",
                        localTable.getTableType(), dorisTableType));
            }

            // 比对表注释
            String dorisComment = (String) dorisTable.get("tableComment");
            if (!Objects.equals(dorisComment, localTable.getTableComment())) {
                diff.addChange(String.format("表注释不同: 平台='%s', Doris='%s'",
                        localTable.getTableComment(), dorisComment));
            }

            if (!viewType) {
                // 比对分桶数
                if (tableCreateInfo.containsKey("bucketNum")) {
                    Integer dorisBucketNum = (Integer) tableCreateInfo.get("bucketNum");
                    if (!Objects.equals(dorisBucketNum, localTable.getBucketNum())) {
                        diff.addChange(String.format("分桶数不同: 平台=%d, Doris=%d",
                                localTable.getBucketNum(), dorisBucketNum));
                    }
                }

                // 比对副本数
                if (tableCreateInfo.containsKey("replicationNum")) {
                    Integer dorisReplicationNum = (Integer) tableCreateInfo.get("replicationNum");
                    if (!Objects.equals(dorisReplicationNum, localTable.getReplicaNum())) {
                        diff.addChange(String.format("副本数不同: 平台=%d, Doris=%d",
                                localTable.getReplicaNum(), dorisReplicationNum));
                    }
                }

                // 比对分区字段
                if (tableCreateInfo.containsKey("partitionColumn")) {
                    String dorisPartitionColumn = (String) tableCreateInfo.get("partitionColumn");
                    if (!Objects.equals(dorisPartitionColumn, localTable.getPartitionColumn())) {
                        diff.addChange(String.format("分区字段不同: 平台='%s', Doris='%s'",
                                localTable.getPartitionColumn(), dorisPartitionColumn));
                    }
                }
            }

            // 比对字段差异
            compareTableFields(localTable.getId(), dorisColumns, diff);

        } catch (Exception e) {
            log.error("Failed to compare table {}.{}", database, tableName, e);
            diff.addChange("比对失败: " + e.getMessage());
        }

        return diff;
    }

    /**
     * 比对表字段差异
     */
    private void compareTableFields(Long tableId, List<Map<String, Object>> dorisColumns, TableDifference tableDiff) {
        // 获取本地字段
        List<DataField> localFields = dataFieldMapper.selectList(
                new LambdaQueryWrapper<DataField>()
                        .eq(DataField::getTableId, tableId));

        Map<String, DataField> localFieldMap = localFields.stream()
                .collect(Collectors.toMap(DataField::getFieldName, f -> f));

        Set<String> dorisFieldNames = new HashSet<>();

        // 遍历 Doris 中的字段
        for (Map<String, Object> dorisColumn : dorisColumns) {
            String fieldName = (String) dorisColumn.get("columnName");
            dorisFieldNames.add(fieldName);

            DataField localField = localFieldMap.get(fieldName);

            if (localField == null) {
                // 新字段
                FieldDifference fieldDiff = new FieldDifference(fieldName, DifferenceType.NEW);
                fieldDiff.addChange("type", null, dorisColumn.get("dataType"));
                fieldDiff.addChange("comment", null, dorisColumn.get("columnComment"));
                tableDiff.addFieldDifference(fieldDiff);
            } else {
                // 检查字段差异
                FieldDifference fieldDiff = new FieldDifference(fieldName, DifferenceType.UPDATED);
                boolean hasChanges = false;

                String dorisType = (String) dorisColumn.get("dataType");
                if (!Objects.equals(dorisType, localField.getFieldType())) {
                    fieldDiff.addChange("type", localField.getFieldType(), dorisType);
                    hasChanges = true;
                }

                String dorisComment = (String) dorisColumn.get("columnComment");
                if (!Objects.equals(dorisComment, localField.getFieldComment())) {
                    fieldDiff.addChange("comment", localField.getFieldComment(), dorisComment);
                    hasChanges = true;
                }

                Integer dorisIsNullable = (Integer) dorisColumn.get("isNullable");
                if (!Objects.equals(dorisIsNullable, localField.getIsNullable())) {
                    fieldDiff.addChange("nullable", localField.getIsNullable(), dorisIsNullable);
                    hasChanges = true;
                }

                if (hasChanges) {
                    tableDiff.addFieldDifference(fieldDiff);
                }
            }
        }

        // 检查冗余字段
        for (DataField localField : localFields) {
            if (!dorisFieldNames.contains(localField.getFieldName())) {
                FieldDifference fieldDiff = new FieldDifference(localField.getFieldName(), DifferenceType.REMOVED);
                fieldDiff.addChange("status", "存在", "已删除");
                tableDiff.addFieldDifference(fieldDiff);
            }
        }
    }

    /**
     * 同步指定集群的所有元数据
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncAllMetadata(Long clusterId) {
        DorisCluster cluster = resolveCluster(clusterId);
        boolean isDoris = isDorisCluster(cluster);
        SyncResult result = new SyncResult();
        log.info("Starting metadata sync for cluster: {}", clusterId);

        try {
            // 获取所有数据库
            List<String> databases = dorisConnectionService.getAllDatabases(clusterId).stream()
                    .filter(db -> !IGNORED_DATABASES.contains(db))
                    .collect(Collectors.toList());
            log.info("Found {} databases to sync", databases.size());

            for (String database : databases) {
                try {
                    syncDatabaseInternal(clusterId, database, result, isDoris);
                } catch (Exception e) {
                    log.error("Failed to sync database: {}", database, e);
                    result.addError("同步数据库 " + database + " 失败: " + e.getMessage());
                }
            }

            // 将当前账号不可见数据库中的 active 表降级为 inactive，避免巡检误报历史冗余元数据
            inactivateTablesInHiddenDatabases(clusterId, databases, result);

            log.info("Metadata sync completed: {}", result);
        } catch (Exception e) {
            log.error("Failed to sync metadata", e);
            result.addError("同步元数据失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 同步指定集群的表统计信息，不同步结构元数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncAllStatistics(Long clusterId) {
        resolveCluster(clusterId);
        SyncResult result = new SyncResult();
        log.info("Starting metadata statistics sync for cluster: {}", clusterId);

        try {
            List<String> databases = dorisConnectionService.getAllDatabases(clusterId).stream()
                    .filter(db -> !IGNORED_DATABASES.contains(db))
                    .collect(Collectors.toList());

            for (String database : databases) {
                try {
                    syncDatabaseStatisticsInternal(clusterId, database, result);
                } catch (Exception e) {
                    log.error("Failed to sync statistics for database: {}", database, e);
                    result.addError("同步数据库统计 " + database + " 失败: " + e.getMessage());
                }
            }

            log.info("Metadata statistics sync completed: {}", result);
        } catch (Exception e) {
            log.error("Failed to sync metadata statistics", e);
            result.addError("同步元数据统计失败: " + e.getMessage());
        }

        return result;
    }

    /**
     * 同步指定数据库的表统计信息，不同步结构元数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncDatabaseStatistics(Long clusterId, String database) {
        resolveCluster(clusterId);
        return syncDatabaseStatisticsInternal(clusterId, database, new SyncResult());
    }

    private SyncResult syncDatabaseStatisticsInternal(Long clusterId, String database, SyncResult result) {
        if (result == null) {
            result = new SyncResult();
        }

        log.info("Syncing metadata statistics for database: {}", database);
        List<Map<String, Object>> dorisTables = dorisConnectionService.getTablesInDatabase(clusterId, database);
        List<DataTable> localTables = dataTableMapper.selectList(
                new LambdaQueryWrapper<DataTable>()
                        .eq(DataTable::getDbName, database)
                        .eq(DataTable::getClusterId, clusterId));

        Map<String, DataTable> localTableMap = localTables.stream()
                .collect(Collectors.toMap(DataTable::getTableName, t -> t));

        for (Map<String, Object> dorisTable : dorisTables) {
            String tableName = (String) dorisTable.get("tableName");
            DataTable localTable = localTableMap.get(tableName);
            if (localTable == null) {
                continue;
            }

            try {
                applyTableStatistics(clusterId, database, tableName, dorisTable, localTable, result);
            } catch (Exception e) {
                log.error("Failed to sync statistics for table {}.{}", database, tableName, e);
                result.addError("同步表统计 " + database + "." + tableName + " 失败: " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * 同步指定表的统计信息，不同步结构元数据。
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncTableStatisticsOnly(Long clusterId, String database, String tableName) {
        resolveCluster(clusterId);
        SyncResult result = new SyncResult();
        log.info("Syncing metadata statistics for table: {}.{}", database, tableName);

        try {
            List<Map<String, Object>> tables = dorisConnectionService.getTablesInDatabase(clusterId, database);
            Map<String, Object> dorisTable = tables.stream()
                    .filter(t -> tableName.equals(t.get("tableName")))
                    .findFirst()
                    .orElse(null);

            if (dorisTable == null) {
                result.addError("表 " + database + "." + tableName + " 在 Doris 中不存在");
                return result;
            }

            DataTable localTable = dataTableMapper.selectOne(
                    new LambdaQueryWrapper<DataTable>()
                            .eq(DataTable::getDbName, database)
                            .eq(DataTable::getTableName, tableName)
                            .eq(DataTable::getClusterId, clusterId));

            if (localTable == null) {
                result.addError("表 " + database + "." + tableName + " 在平台中不存在");
                return result;
            }

            applyTableStatistics(clusterId, database, tableName, dorisTable, localTable, result);
            log.info("Table statistics sync completed: {}", result);
        } catch (Exception e) {
            log.error("Failed to sync table statistics {}.{}", database, tableName, e);
            result.addError("同步表统计失败: " + e.getMessage());
        }

        return result;
    }

    private void appendInaccessibleDatabaseWarnings(Long clusterId, List<String> visibleDatabases, AuditResult result) {
        Set<String> visible = visibleDatabases == null ? Collections.emptySet() : new HashSet<>(visibleDatabases);
        List<DataTable> localTables = dataTableMapper.selectList(
                new LambdaQueryWrapper<DataTable>()
                        .eq(DataTable::getClusterId, clusterId)
                        .eq(DataTable::getStatus, "active"));

        Set<String> hiddenDatabases = localTables.stream()
                .map(DataTable::getDbName)
                .filter(StringUtils::hasText)
                .filter(db -> !visible.contains(db))
                .collect(Collectors.toCollection(TreeSet::new));

        if (!hiddenDatabases.isEmpty()) {
            result.addError("检测到本地存在当前 Doris 账号不可见的数据库: " + String.join(", ", hiddenDatabases)
                    + "。可能是权限收缩导致的历史元数据残留。");
        }
    }

    private void inactivateTablesInHiddenDatabases(Long clusterId, List<String> visibleDatabases, SyncResult result) {
        Set<String> visible = visibleDatabases == null ? Collections.emptySet() : new HashSet<>(visibleDatabases);
        List<DataTable> localTables = dataTableMapper.selectList(
                new LambdaQueryWrapper<DataTable>()
                        .eq(DataTable::getClusterId, clusterId)
                        .eq(DataTable::getStatus, "active"));

        for (DataTable table : localTables) {
            String dbName = table.getDbName();
            if (StringUtils.hasText(dbName) && visible.contains(dbName)) {
                continue;
            }
            DataTable update = new DataTable();
            update.setId(table.getId());
            update.setStatus("inactive");
            update.setIsSynced(0);
            update.setSyncTime(LocalDateTime.now());
            dataTableMapper.updateById(update);
            result.addInactivatedTable();
            result.addUpdatedTableDetail(
                    table.getDbName(),
                    table.getTableName(),
                    "表在当前账号下不可见，已标记为 inactive",
                    Collections.singletonMap("status", buildValueChange("active", "inactive")));
            log.info("Inactivated hidden table metadata: {}.{} (cluster={})",
                    table.getDbName(), table.getTableName(), clusterId);
        }
    }

    /**
     * 同步指定数据库的元数据
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncDatabase(Long clusterId, String database, SyncResult result) {
        DorisCluster cluster = resolveCluster(clusterId);
        boolean isDoris = isDorisCluster(cluster);
        return syncDatabaseInternal(clusterId, database, result, isDoris);
    }

    private SyncResult syncDatabaseInternal(Long clusterId, String database, SyncResult result, boolean isDoris) {
        if (result == null) {
            result = new SyncResult();
        }

        log.info("Syncing database: {}", database);

        // 获取 Doris 中的所有表
        List<Map<String, Object>> dorisTables = dorisConnectionService.getTablesInDatabase(clusterId, database);
        log.info("Found {} tables in database {}", dorisTables.size(), database);

        // 获取本地已存在的表
        List<DataTable> localTables = dataTableMapper.selectList(
                new LambdaQueryWrapper<DataTable>()
                        .eq(DataTable::getDbName, database)
                        .eq(DataTable::getClusterId, clusterId));

        Map<String, DataTable> localTableMap = localTables.stream()
                .collect(Collectors.toMap(DataTable::getTableName, t -> t));

        // 遍历 Doris 中的表
        for (Map<String, Object> dorisTable : dorisTables) {
            String tableName = (String) dorisTable.get("tableName");

            try {
                DataTable localTable = localTableMap.get(tableName);

                if (localTable == null) {
                    // 新表：插入元数据
                    syncNewTable(clusterId, database, tableName, dorisTable, result, isDoris);
                } else {
                    // 已存在表：更新元数据
                    syncExistingTable(clusterId, database, tableName, dorisTable, localTable, result, isDoris);
                }
            } catch (Exception e) {
                log.error("Failed to sync table {}.{}", database, tableName, e);
                result.addError("同步表 " + database + "." + tableName + " 失败: " + e.getMessage());
            }
        }

        // 处理已删除的表（平台有但 Doris 没有）
        Set<String> dorisTableNames = dorisTables.stream()
                .map(t -> (String) t.get("tableName"))
                .collect(Collectors.toSet());

        for (DataTable localTable : localTables) {
            if (!dorisTableNames.contains(localTable.getTableName())) {
                try {
                    TableReferenceStats referenceStats = countTableReferences(localTable.getId());
                    if (referenceStats.hasReferences()) {
                        result.addBlockedDeletedTable();
                        String reason = String.format(
                                "表 %s.%s 在 Doris 中不存在，但仍被引用（任务关联=%d，血缘上游=%d，血缘下游=%d），已阻断删除",
                                database,
                                localTable.getTableName(),
                                referenceStats.getTaskRelationCount(),
                                referenceStats.getUpstreamLineageCount(),
                                referenceStats.getDownstreamLineageCount());
                        result.addError(reason);
                        log.warn("Blocked metadata deletion for referenced table: {}.{} (cluster={}, tableId={})",
                                database, localTable.getTableName(), clusterId, localTable.getId());
                        continue;
                    }

                    log.info("Deleting table not in Doris: {}.{}", database, localTable.getTableName());
                    dataTableMapper.deleteById(localTable.getId());
                    result.addDeletedTable();
                    result.addDeletedTableDetail(database, localTable.getTableName(), "Doris 中不存在，已删除平台元数据");
                } catch (Exception e) {
                    log.error("Failed to delete table {}.{}", database, localTable.getTableName(), e);
                    result.addError("删除表 " + database + "." + localTable.getTableName() + " 失败: " + e.getMessage());
                }
            }
        }

        return result;
    }

    private TableReferenceStats countTableReferences(Long tableId) {
        Long taskRelationCount = tableTaskRelationMapper.selectCount(
                new LambdaQueryWrapper<TableTaskRelation>()
                        .eq(TableTaskRelation::getTableId, tableId));
        Long upstreamLineageCount = dataLineageMapper.selectCount(
                new LambdaQueryWrapper<DataLineage>()
                        .eq(DataLineage::getUpstreamTableId, tableId));
        Long downstreamLineageCount = dataLineageMapper.selectCount(
                new LambdaQueryWrapper<DataLineage>()
                        .eq(DataLineage::getDownstreamTableId, tableId));

        return new TableReferenceStats(
                taskRelationCount == null ? 0L : taskRelationCount,
                upstreamLineageCount == null ? 0L : upstreamLineageCount,
                downstreamLineageCount == null ? 0L : downstreamLineageCount);
    }

    private static class TableReferenceStats {
        private final long taskRelationCount;
        private final long upstreamLineageCount;
        private final long downstreamLineageCount;

        private TableReferenceStats(long taskRelationCount, long upstreamLineageCount, long downstreamLineageCount) {
            this.taskRelationCount = taskRelationCount;
            this.upstreamLineageCount = upstreamLineageCount;
            this.downstreamLineageCount = downstreamLineageCount;
        }

        private boolean hasReferences() {
            return taskRelationCount > 0 || upstreamLineageCount > 0 || downstreamLineageCount > 0;
        }

        private long getTaskRelationCount() {
            return taskRelationCount;
        }

        private long getUpstreamLineageCount() {
            return upstreamLineageCount;
        }

        private long getDownstreamLineageCount() {
            return downstreamLineageCount;
        }
    }

    /**
     * 同步新表
     */
    private void syncNewTable(Long clusterId, String database, String tableName,
            Map<String, Object> dorisTable, SyncResult result, boolean isDoris) {
        log.info("Syncing new table: {}.{}", database, tableName);

        // 获取表的详细信息
        Map<String, Object> tableCreateInfo = dorisConnectionService.getTableCreateInfo(clusterId, database, tableName);
        List<Map<String, Object>> columns = dorisConnectionService.getColumnsInTable(clusterId, database, tableName);
        String tableType = normalizeTableType((String) dorisTable.get("tableType"));
        boolean viewType = isViewType(tableType);

        // 创建表记录
        DataTable newTable = new DataTable();
        newTable.setClusterId(clusterId);
        newTable.setTableName(tableName);
        newTable.setTableType(tableType);
        newTable.setDbName(database);
        newTable.setLayer(resolveLayerForNewTable(tableName));

        String tableComment = (String) dorisTable.get("tableComment");
        newTable.setTableComment(tableComment != null ? tableComment : "");

        newTable.setStatus("active");
        newTable.setIsSynced(isDoris ? 1 : 0);
        newTable.setSyncTime(LocalDateTime.now());

        if (isDoris && !viewType) {
            // 从建表语句中解析的信息（仅 Doris）
            if (tableCreateInfo.containsKey("bucketNum")) {
                newTable.setBucketNum((Integer) tableCreateInfo.get("bucketNum"));
            }
            if (tableCreateInfo.containsKey("replicationNum")) {
                newTable.setReplicaNum((Integer) tableCreateInfo.get("replicationNum"));
            }
            if (tableCreateInfo.containsKey("partitionColumn")) {
                newTable.setPartitionColumn((String) tableCreateInfo.get("partitionColumn"));
            }
            if (tableCreateInfo.containsKey("distributionColumn")) {
                newTable.setDistributionColumn((String) tableCreateInfo.get("distributionColumn"));
            }
            if (tableCreateInfo.containsKey("keyColumns")) {
                newTable.setKeyColumns((String) tableCreateInfo.get("keyColumns"));
            }
            if (tableCreateInfo.containsKey("tableModel")) {
                newTable.setTableModel((String) tableCreateInfo.get("tableModel"));
            }
        } else {
            newTable.setBucketNum(null);
            newTable.setReplicaNum(null);
            newTable.setPartitionColumn(null);
            newTable.setDistributionColumn(null);
            newTable.setKeyColumns(null);
            newTable.setTableModel(null);
        }
        if (tableCreateInfo.containsKey("createTableSql")) {
            newTable.setDorisDdl((String) tableCreateInfo.get("createTableSql"));
        }

        // 从统计信息中获取的信息
        Long tableRows = (Long) dorisTable.get("tableRows");
        if (tableRows != null) {
            newTable.setRowCount(tableRows);
        }

        Long dataLength = (Long) dorisTable.get("dataLength");
        if (dataLength != null) {
            newTable.setStorageSize(dataLength);
        }

        Timestamp updateTime = (Timestamp) dorisTable.get("updateTime");
        if (updateTime != null) {
            newTable.setDorisUpdateTime(updateTime.toLocalDateTime());
        }

        // 同步Doris创建时间
        Timestamp createTime = (Timestamp) dorisTable.get("createTime");
        if (createTime != null) {
            newTable.setDorisCreateTime(createTime.toLocalDateTime());
        }

        dataTableMapper.insert(newTable);
        result.addNewTable();
        result.addAddedTableDetail(database, tableName, "新增表并同步平台元数据");
        recordStatisticsSnapshot(clusterId, database, tableName, newTable);

        // 同步字段
        syncTableFields(newTable.getId(), database, tableName, columns, result);

        tableMetadataVersionService.captureVersion(newTable.getId(),
                TableMetadataVersionService.TRIGGER_METADATA_SYNC, "system");
    }

    /**
     * 同步已存在的表
     */
    private void syncExistingTable(Long clusterId, String database, String tableName,
            Map<String, Object> dorisTable, DataTable localTable, SyncResult result, boolean isDoris) {
        log.debug("Syncing existing table: {}.{}", database, tableName);

        // 获取表的详细信息
        Map<String, Object> tableCreateInfo = dorisConnectionService.getTableCreateInfo(clusterId, database, tableName);
        List<Map<String, Object>> columns = dorisConnectionService.getColumnsInTable(clusterId, database, tableName);
        String tableType = normalizeTableType((String) dorisTable.get("tableType"));
        boolean viewType = isViewType(tableType);

        boolean tableMetadataUpdated = false;
        Map<String, Object> tableChanges = new LinkedHashMap<>();

        // 同步数据源
        if (!Objects.equals(localTable.getClusterId(), clusterId)) {
            addChangedValue(tableChanges, "clusterId", localTable.getClusterId(), clusterId);
            localTable.setClusterId(clusterId);
            tableMetadataUpdated = true;
        }
        if (!Objects.equals(tableType, localTable.getTableType())) {
            addChangedValue(tableChanges, "tableType", localTable.getTableType(), tableType);
            localTable.setTableType(tableType);
            tableMetadataUpdated = true;
        }

        // 之前因账号不可见被降级为 inactive 的表，在再次可见时自动恢复
        if ("inactive".equals(localTable.getStatus())
                && Objects.equals(localTable.getIsSynced(), 0)) {
            addChangedValue(tableChanges, "status", localTable.getStatus(), "active");
            localTable.setStatus("active");
            tableMetadataUpdated = true;
        }

        // 按表名前缀自动修正数据分层（ods_/dwd_/dim_/dws_/ads_）
        String inferredLayer = TableNameUtils.inferLayerFromTableName(tableName);
        if (StringUtils.hasText(inferredLayer) && !Objects.equals(inferredLayer, localTable.getLayer())) {
            addChangedValue(tableChanges, "layer", localTable.getLayer(), inferredLayer);
            localTable.setLayer(inferredLayer);
            tableMetadataUpdated = true;
        }

        // 更新表注释
        String tableComment = (String) dorisTable.get("tableComment");
        if (tableComment != null && !tableComment.equals(localTable.getTableComment())) {
            addChangedValue(tableChanges, "tableComment", localTable.getTableComment(), tableComment);
            localTable.setTableComment(tableComment);
            tableMetadataUpdated = true;
        }

        if (isDoris && !viewType) {
            // 更新分桶数
            if (tableCreateInfo.containsKey("bucketNum")) {
                Integer bucketNum = (Integer) tableCreateInfo.get("bucketNum");
                if (!Objects.equals(bucketNum, localTable.getBucketNum())) {
                    addChangedValue(tableChanges, "bucketNum", localTable.getBucketNum(), bucketNum);
                    localTable.setBucketNum(bucketNum);
                    tableMetadataUpdated = true;
                }
            }

            // 更新副本数
            if (tableCreateInfo.containsKey("replicationNum")) {
                Integer replicationNum = (Integer) tableCreateInfo.get("replicationNum");
                if (!Objects.equals(replicationNum, localTable.getReplicaNum())) {
                    addChangedValue(tableChanges, "replicationNum", localTable.getReplicaNum(), replicationNum);
                    localTable.setReplicaNum(replicationNum);
                    tableMetadataUpdated = true;
                }
            }

            // 更新分区字段
            if (tableCreateInfo.containsKey("partitionColumn")) {
                String partitionColumn = (String) tableCreateInfo.get("partitionColumn");
                if (!Objects.equals(partitionColumn, localTable.getPartitionColumn())) {
                    addChangedValue(tableChanges, "partitionColumn", localTable.getPartitionColumn(), partitionColumn);
                    localTable.setPartitionColumn(partitionColumn);
                    tableMetadataUpdated = true;
                }
            }

            // 更新分桶字段
            if (tableCreateInfo.containsKey("distributionColumn")) {
                String distributionColumn = (String) tableCreateInfo.get("distributionColumn");
                if (!Objects.equals(distributionColumn, localTable.getDistributionColumn())) {
                    addChangedValue(tableChanges, "distributionColumn", localTable.getDistributionColumn(), distributionColumn);
                    localTable.setDistributionColumn(distributionColumn);
                    tableMetadataUpdated = true;
                }
            }

            // 更新 Key 列
            if (tableCreateInfo.containsKey("keyColumns")) {
                String keyColumns = (String) tableCreateInfo.get("keyColumns");
                if (!Objects.equals(keyColumns, localTable.getKeyColumns())) {
                    addChangedValue(tableChanges, "keyColumns", localTable.getKeyColumns(), keyColumns);
                    localTable.setKeyColumns(keyColumns);
                    tableMetadataUpdated = true;
                }
            }

            // 更新表模型
            if (tableCreateInfo.containsKey("tableModel")) {
                String tableModel = (String) tableCreateInfo.get("tableModel");
                if (!Objects.equals(tableModel, localTable.getTableModel())) {
                    addChangedValue(tableChanges, "tableModel", localTable.getTableModel(), tableModel);
                    localTable.setTableModel(tableModel);
                    tableMetadataUpdated = true;
                }
            }

            // 更新 DDL
            if (tableCreateInfo.containsKey("createTableSql")) {
                String createTableSql = (String) tableCreateInfo.get("createTableSql");
                if (!Objects.equals(createTableSql, localTable.getDorisDdl())) {
                    addChangedValue(tableChanges, "dorisDdl", localTable.getDorisDdl(), createTableSql);
                    localTable.setDorisDdl(createTableSql);
                    tableMetadataUpdated = true;
                }
            }
        } else {
            if (localTable.getBucketNum() != null) {
                addChangedValue(tableChanges, "bucketNum", localTable.getBucketNum(), null);
                localTable.setBucketNum(null);
                tableMetadataUpdated = true;
            }
            if (localTable.getReplicaNum() != null) {
                addChangedValue(tableChanges, "replicationNum", localTable.getReplicaNum(), null);
                localTable.setReplicaNum(null);
                tableMetadataUpdated = true;
            }
            if (StringUtils.hasText(localTable.getPartitionColumn())) {
                addChangedValue(tableChanges, "partitionColumn", localTable.getPartitionColumn(), null);
                localTable.setPartitionColumn(null);
                tableMetadataUpdated = true;
            }
            if (StringUtils.hasText(localTable.getDistributionColumn())) {
                addChangedValue(tableChanges, "distributionColumn", localTable.getDistributionColumn(), null);
                localTable.setDistributionColumn(null);
                tableMetadataUpdated = true;
            }
            if (StringUtils.hasText(localTable.getKeyColumns())) {
                addChangedValue(tableChanges, "keyColumns", localTable.getKeyColumns(), null);
                localTable.setKeyColumns(null);
                tableMetadataUpdated = true;
            }
            if (StringUtils.hasText(localTable.getTableModel())) {
                addChangedValue(tableChanges, "tableModel", localTable.getTableModel(), null);
                localTable.setTableModel(null);
                tableMetadataUpdated = true;
            }
            String createTableSql = (String) tableCreateInfo.get("createTableSql");
            if (StringUtils.hasText(createTableSql)) {
                if (!Objects.equals(createTableSql, localTable.getDorisDdl())) {
                    addChangedValue(tableChanges, "dorisDdl", localTable.getDorisDdl(), createTableSql);
                    localTable.setDorisDdl(createTableSql);
                    tableMetadataUpdated = true;
                }
            } else if (StringUtils.hasText(localTable.getDorisDdl())) {
                addChangedValue(tableChanges, "dorisDdl", localTable.getDorisDdl(), null);
                localTable.setDorisDdl(null);
                tableMetadataUpdated = true;
            }
        }

        // 更新同步状态
        Integer synced = isDoris ? 1 : 0;
        if (!Objects.equals(synced, localTable.getIsSynced())) {
            addChangedValue(tableChanges, "isSynced", localTable.getIsSynced(), synced);
            localTable.setIsSynced(synced);
            tableMetadataUpdated = true;
        }

        // 同步Doris创建时间（如果本地还没有记录）
        if (localTable.getDorisCreateTime() == null) {
            Timestamp createTime = (Timestamp) dorisTable.get("createTime");
            if (createTime != null) {
                addChangedValue(tableChanges, "dorisCreateTime", localTable.getDorisCreateTime(), createTime.toLocalDateTime());
                localTable.setDorisCreateTime(createTime.toLocalDateTime());
                tableMetadataUpdated = true;
            }
        }

        // 同步字段（增量更新）
        FieldSyncChanges fieldChanges = syncTableFieldsIncremental(localTable.getId(), database, tableName, columns, result);

        if (tableMetadataUpdated) {
            localTable.setSyncTime(LocalDateTime.now());
            dataTableMapper.updateById(localTable);
            if (!tableChanges.isEmpty()) {
                result.addUpdatedTable();
                result.addUpdatedTableDetail(database, tableName, "表元数据更新", tableChanges);
            }
        } else if (fieldChanges.hasChanges()) {
            DataTable syncTimeUpdate = new DataTable();
            syncTimeUpdate.setId(localTable.getId());
            syncTimeUpdate.setSyncTime(LocalDateTime.now());
            dataTableMapper.updateById(syncTimeUpdate);
        }

        // 仅对同步真正触碰的表尝试捕获版本；volatile-only 变更由快照白名单兜底（哈希不变则不记版本）
        if (tableMetadataUpdated || fieldChanges.hasChanges()) {
            tableMetadataVersionService.captureVersion(localTable.getId(),
                    TableMetadataVersionService.TRIGGER_METADATA_SYNC, "system");
        }
    }

    /**
     * 记录统计快照，供趋势分析使用。
     */
    private void recordStatisticsSnapshot(Long clusterId, String database, String tableName, DataTable table) {
        if (table == null || table.getId() == null) {
            return;
        }
        if (table.getRowCount() == null && table.getStorageSize() == null && table.getDorisUpdateTime() == null) {
            return;
        }

        try {
            TableStatisticsHistory history = new TableStatisticsHistory();
            history.setTableId(table.getId());
            history.setClusterId(clusterId);
            history.setDatabaseName(database);
            history.setTableName(tableName);
            history.setRowCount(table.getRowCount());
            history.setDataSize(table.getStorageSize());
            history.setReplicationNum(table.getReplicaNum());
            history.setBucketNum(table.getBucketNum());
            history.setTableLastUpdateTime(table.getDorisUpdateTime());
            history.setStatisticsTime(LocalDateTime.now());
            tableStatisticsHistoryMapper.insert(history);
        } catch (Exception e) {
            log.warn("Failed to record statistics snapshot for table {}.{}", database, tableName, e);
        }
    }

    /**
     * 同步表字段（全量插入，用于新表）
     */
    private void syncTableFields(Long tableId, String database, String tableName, List<Map<String, Object>> columns, SyncResult result) {
        for (Map<String, Object> column : columns) {
            DataField field = new DataField();
            field.setTableId(tableId);
            field.setFieldName((String) column.get("columnName"));
            field.setFieldType((String) column.get("dataType"));
            field.setFieldComment(truncateComment((String) column.get("columnComment")));
            field.setIsNullable((Integer) column.get("isNullable"));
            field.setIsPrimary((Integer) column.get("isPrimary"));
            field.setDefaultValue((String) column.get("defaultValue"));
            field.setFieldOrder((Integer) column.get("ordinalPosition"));

            dataFieldMapper.insert(field);
            result.addNewField();
            Map<String, Object> fieldChanges = new LinkedHashMap<>();
            addChangedValue(fieldChanges, "type", null, field.getFieldType());
            addChangedValue(fieldChanges, "comment", null, field.getFieldComment());
            addChangedValue(fieldChanges, "nullable", null, field.getIsNullable());
            addChangedValue(fieldChanges, "primary", null, field.getIsPrimary());
            addChangedValue(fieldChanges, "default", null, field.getDefaultValue());
            addChangedValue(fieldChanges, "order", null, field.getFieldOrder());
            result.addAddedFieldDetail(database, tableName, field.getFieldName(), fieldChanges);
        }
    }

    /**
     * 同步表字段（增量更新，用于已存在的表）
     */
    private FieldSyncChanges syncTableFieldsIncremental(Long tableId,
            String database,
            String tableName,
            List<Map<String, Object>> dorisColumns,
            SyncResult result) {
        FieldSyncChanges changes = new FieldSyncChanges();

        // 获取本地已存在的字段
        List<DataField> localFields = dataFieldMapper.selectList(
                new LambdaQueryWrapper<DataField>()
                        .eq(DataField::getTableId, tableId));

        Map<String, DataField> localFieldMap = localFields.stream()
                .collect(Collectors.toMap(DataField::getFieldName, f -> f));

        Set<String> dorisFieldNames = new HashSet<>();

        // 遍历 Doris 中的字段
        for (Map<String, Object> dorisColumn : dorisColumns) {
            String fieldName = (String) dorisColumn.get("columnName");
            dorisFieldNames.add(fieldName);

            DataField localField = localFieldMap.get(fieldName);

            if (localField == null) {
                // 新字段：插入
                DataField newField = new DataField();
                newField.setTableId(tableId);
                newField.setFieldName(fieldName);
                newField.setFieldType((String) dorisColumn.get("dataType"));
                newField.setFieldComment(truncateComment((String) dorisColumn.get("columnComment")));
                newField.setIsNullable((Integer) dorisColumn.get("isNullable"));
                newField.setIsPrimary((Integer) dorisColumn.get("isPrimary"));
                newField.setDefaultValue((String) dorisColumn.get("defaultValue"));
                newField.setFieldOrder((Integer) dorisColumn.get("ordinalPosition"));

                dataFieldMapper.insert(newField);
                changes.markCreated();
                result.addNewField();
                Map<String, Object> fieldChanges = new LinkedHashMap<>();
                addChangedValue(fieldChanges, "type", null, newField.getFieldType());
                addChangedValue(fieldChanges, "comment", null, newField.getFieldComment());
                addChangedValue(fieldChanges, "nullable", null, newField.getIsNullable());
                addChangedValue(fieldChanges, "primary", null, newField.getIsPrimary());
                addChangedValue(fieldChanges, "default", null, newField.getDefaultValue());
                addChangedValue(fieldChanges, "order", null, newField.getFieldOrder());
                result.addAddedFieldDetail(database, tableName, fieldName, fieldChanges);
            } else {
                // 已存在字段：更新
                boolean updated = false;
                Map<String, Object> fieldChanges = new LinkedHashMap<>();

                String dataType = (String) dorisColumn.get("dataType");
                if (!Objects.equals(dataType, localField.getFieldType())) {
                    addChangedValue(fieldChanges, "type", localField.getFieldType(), dataType);
                    localField.setFieldType(dataType);
                    updated = true;
                }

                String columnComment = truncateComment((String) dorisColumn.get("columnComment"));
                if (!Objects.equals(columnComment, localField.getFieldComment())) {
                    addChangedValue(fieldChanges, "comment", localField.getFieldComment(), columnComment);
                    localField.setFieldComment(columnComment);
                    updated = true;
                }

                Integer isNullable = (Integer) dorisColumn.get("isNullable");
                if (!Objects.equals(isNullable, localField.getIsNullable())) {
                    addChangedValue(fieldChanges, "nullable", localField.getIsNullable(), isNullable);
                    localField.setIsNullable(isNullable);
                    updated = true;
                }

                Integer isPrimary = (Integer) dorisColumn.get("isPrimary");
                if (!Objects.equals(isPrimary, localField.getIsPrimary())) {
                    addChangedValue(fieldChanges, "primary", localField.getIsPrimary(), isPrimary);
                    localField.setIsPrimary(isPrimary);
                    updated = true;
                }

                String defaultValue = (String) dorisColumn.get("defaultValue");
                if (!Objects.equals(defaultValue, localField.getDefaultValue())) {
                    addChangedValue(fieldChanges, "default", localField.getDefaultValue(), defaultValue);
                    localField.setDefaultValue(defaultValue);
                    updated = true;
                }

                Integer ordinalPosition = (Integer) dorisColumn.get("ordinalPosition");
                if (!Objects.equals(ordinalPosition, localField.getFieldOrder())) {
                    addChangedValue(fieldChanges, "order", localField.getFieldOrder(), ordinalPosition);
                    localField.setFieldOrder(ordinalPosition);
                    updated = true;
                }

                if (updated) {
                    dataFieldMapper.updateById(localField);
                    changes.markUpdated();
                    result.addUpdatedField();
                    result.addUpdatedFieldDetail(database, tableName, fieldName, fieldChanges);
                }
            }
        }

        // 处理冗余字段（在本地存在但在 Doris 中不存在的字段）
        for (DataField localField : localFields) {
            if (!dorisFieldNames.contains(localField.getFieldName())) {
                // 逻辑删除
                dataFieldMapper.deleteById(localField.getId());
                changes.markDeleted();
                result.addDeletedField();
                result.addDeletedFieldDetail(database, tableName, localField.getFieldName());
                log.info("Logically deleted field: {} from table {}", localField.getFieldName(), tableId);
            }
        }

        return changes;
    }

    private static class FieldSyncChanges {
        private boolean created;
        private boolean updated;
        private boolean deleted;

        private void markCreated() {
            created = true;
        }

        private void markUpdated() {
            updated = true;
        }

        private void markDeleted() {
            deleted = true;
        }

        private boolean hasChanges() {
            return created || updated || deleted;
        }
    }

    /**
     * 同步指定表的元数据
     */
    @Transactional(rollbackFor = Exception.class)
    public SyncResult syncTable(Long clusterId, String database, String tableName) {
        DorisCluster cluster = resolveCluster(clusterId);
        boolean isDoris = isDorisCluster(cluster);
        return syncTableInternal(clusterId, database, tableName, isDoris);
    }

    private SyncResult syncTableInternal(Long clusterId, String database, String tableName, boolean isDoris) {
        SyncResult result = new SyncResult();
        log.info("Syncing table: {}.{}", database, tableName);

        try {
            // 获取 Doris 中的表信息
            List<Map<String, Object>> tables = dorisConnectionService.getTablesInDatabase(clusterId, database);
            Map<String, Object> dorisTable = tables.stream()
                    .filter(t -> tableName.equals(t.get("tableName")))
                    .findFirst()
                    .orElse(null);

            if (dorisTable == null) {
                result.addError("表 " + database + "." + tableName + " 在 Doris 中不存在");
                return result;
            }

            // 获取本地表
            DataTable localTable = dataTableMapper.selectOne(
                    new LambdaQueryWrapper<DataTable>()
                            .eq(DataTable::getDbName, database)
                            .eq(DataTable::getTableName, tableName)
                            .eq(DataTable::getClusterId, clusterId));

            if (localTable == null) {
                syncNewTable(clusterId, database, tableName, dorisTable, result, isDoris);
            } else {
                syncExistingTable(clusterId, database, tableName, dorisTable, localTable, result, isDoris);
            }

            log.info("Table sync completed: {}", result);
        } catch (Exception e) {
            log.error("Failed to sync table {}.{}", database, tableName, e);
            result.addError("同步表失败: " + e.getMessage());
        }

        return result;
    }

    private void addChangedValue(Map<String, Object> changes, String key, Object oldValue, Object newValue) {
        if (changes == null || !StringUtils.hasText(key) || Objects.equals(oldValue, newValue)) {
            return;
        }
        changes.put(key, buildValueChange(oldValue, newValue));
    }

    private static Map<String, Object> buildValueChange(Object oldValue, Object newValue) {
        Map<String, Object> diff = new LinkedHashMap<>(2);
        diff.put("old", oldValue);
        diff.put("new", newValue);
        return diff;
    }

    private DorisCluster resolveCluster(Long clusterId) {
        if (clusterId == null) {
            throw new RuntimeException("请指定数据源");
        }
        DorisCluster cluster = dorisClusterMapper.selectById(clusterId);
        if (cluster == null) {
            throw new RuntimeException("未找到指定的数据源: " + clusterId);
        }
        return cluster;
    }

    private boolean isDorisCluster(DorisCluster cluster) {
        if (cluster == null || !StringUtils.hasText(cluster.getSourceType())) {
            return true;
        }
        return "DORIS".equalsIgnoreCase(cluster.getSourceType());
    }

    private String normalizeTableType(String tableType) {
        if (!StringUtils.hasText(tableType)) {
            return "BASE TABLE";
        }
        return tableType.trim().toUpperCase(Locale.ROOT);
    }

    private boolean isViewType(String tableType) {
        return normalizeTableType(tableType).contains("VIEW");
    }

    private String resolveLayerForNewTable(String tableName) {
        String inferredLayer = TableNameUtils.inferLayerFromTableName(tableName);
        if (StringUtils.hasText(inferredLayer)) {
            return inferredLayer;
        }
        // 保持历史行为：无法识别前缀时默认 ODS，避免写入空 layer。
        return "ODS";
    }

    private String truncateComment(String comment) {
        if (comment == null) {
            return null;
        }
        if (comment.length() > MAX_COMMENT_LENGTH) {
            return comment.substring(0, MAX_COMMENT_LENGTH);
        }
        return comment;
    }
}
