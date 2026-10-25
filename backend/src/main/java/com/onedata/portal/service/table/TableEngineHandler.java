package com.onedata.portal.service.table;

import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;

/**
 * Engine-specific table metadata normalization and physical DDL operations.
 */
public interface TableEngineHandler {

    String sourceType();

    default void prepareCreateMetadata(DataTable table, TableCreateRequest request, String ddl) {
        clearDorisPhysicalMetadata(table);
    }

    default String buildCreateDdl(String tableName, TableCreateRequest request) {
        throw unsupported();
    }

    default void executeCreateTable(Long datasourceId, String database, String ddl) {
        throw unsupported();
    }

    default void alterTableComment(Long datasourceId, String database, String tableName, String comment) {
        throw unsupported();
    }

    default void renameTable(Long datasourceId, String database, String oldTableName, String newTableName) {
        throw unsupported();
    }

    default void dropTable(Long datasourceId, String database, String tableName) {
        throw unsupported();
    }

    default void addColumn(Long datasourceId, DataTable table, String database, String tableName, DataField field) {
        throw unsupported();
    }

    default void updateColumn(Long datasourceId, DataTable table, String database, String tableName,
            DataField oldField, DataField newField) {
        throw unsupported();
    }

    default void dropColumn(Long datasourceId, String database, String tableName, String columnName) {
        throw unsupported();
    }

    default void modifyDistribution(Long datasourceId, DataTable table, String database, String tableName,
            Integer bucketNum) {
        throw unsupported();
    }

    default void setReplicationNum(Long datasourceId, String database, String tableName, Integer replicaNum) {
        throw unsupported();
    }

    default RuntimeException unsupported() {
        return new RuntimeException("暂不支持同步 " + sourceType() + " 数据源的表变更");
    }

    static void clearDorisPhysicalMetadata(DataTable table) {
        if (table == null) {
            return;
        }
        table.setTableModel(null);
        table.setBucketNum(null);
        table.setReplicaNum(null);
        table.setPartitionColumn(null);
        table.setDistributionColumn(null);
        table.setKeyColumns(null);
        table.setDorisDdl(null);
    }
}
