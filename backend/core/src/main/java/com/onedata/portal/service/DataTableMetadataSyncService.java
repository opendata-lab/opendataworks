package com.onedata.portal.service;

import com.onedata.portal.dto.TableLocation;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.MetadataSyncHistory;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;



/**
 * 数据表元数据同步 / 稽核编排服务。
 *
 * <p>承接控制器下沉的稽核与同步编排：数据源前置校验、同步历史记录、响应组装与附加字段。
 * 保留原有「同步调用异常被捕获并记录为 FAIL 结果后仍返回」的语义；仅 clusterId / 数据源
 * 前置校验以异常表达，由控制器薄 {@code try/catch} 映射为 {@code Result}。返回响应 Map，
 * 控制器据 {@code status}（或稽核差异）选择端点专属文案。
 */
@Service
@RequiredArgsConstructor
public class DataTableMetadataSyncService {

    private final DorisMetadataSyncService dorisMetadataSyncService;
    private final MetadataSyncHistoryService metadataSyncHistoryService;
    private final DorisClusterService dorisClusterService;
    private final DataTableService dataTableService;

    /**
     * 稽核/比对 Doris 元数据（只检查差异，不同步）。
     */
    public Map<String, Object> auditAllMetadata(Long clusterId) {
        DorisMetadataSyncService.AuditResult result = dorisMetadataSyncService.auditAllMetadata(clusterId);

        Map<String, Object> response = new HashMap<>();
        response.put("hasDifferences", result.hasDifferences());
        response.put("totalDifferences", result.getTotalDifferences());
        response.put("statisticsSynced", result.getStatisticsSynced());
        response.put("differences", result.getTableDifferences());
        response.put("errors", result.getErrors());
        response.put("auditTime", LocalDateTime.now());
        return response;
    }

    /**
     * 全量同步元数据。
     */
    public Map<String, Object> syncAll(Long clusterId) {
        DorisCluster cluster = requireCluster(clusterId);

        LocalDateTime startedAt = LocalDateTime.now();
        DorisMetadataSyncService.SyncResult result;
        try {
            result = dorisMetadataSyncService.syncAllMetadata(clusterId);
        } catch (Exception e) {
            result = new DorisMetadataSyncService.SyncResult();
            result.addError("元数据同步失败: " + e.getMessage());
        }

        MetadataSyncHistory history = metadataSyncHistoryService.record(cluster, "manual", "all", null, startedAt, result);
        return buildSyncResponse(result, history);
    }

    /**
     * 同步指定数据库的元数据。
     */
    public Map<String, Object> syncDatabase(Long clusterId, String database) {
        DorisCluster cluster = requireCluster(clusterId);

        LocalDateTime startedAt = LocalDateTime.now();
        DorisMetadataSyncService.SyncResult result;
        try {
            result = dorisMetadataSyncService.syncDatabase(clusterId, database, null);
        } catch (Exception e) {
            result = new DorisMetadataSyncService.SyncResult();
            result.addError("数据库元数据同步失败: " + e.getMessage());
        }

        MetadataSyncHistory history = metadataSyncHistoryService.record(cluster, "manual", "database", database, startedAt,
                result);
        Map<String, Object> response = buildSyncResponse(result, history);
        response.put("database", database);
        return response;
    }

    /**
     * 按库表名同步指定表的元数据，用于平台尚未有 tableId 的 Doris 表。
     */
    public Map<String, Object> syncTableByName(Long clusterId, String database, String tableName) {
        DorisCluster cluster = requireCluster(clusterId);

        LocalDateTime startedAt = LocalDateTime.now();
        DorisMetadataSyncService.SyncResult result;
        try {
            result = dorisMetadataSyncService.syncTable(clusterId, database, tableName);
        } catch (Exception e) {
            result = new DorisMetadataSyncService.SyncResult();
            result.addError("表元数据同步失败: " + e.getMessage());
        }

        String scopeTarget = database + "." + tableName;
        MetadataSyncHistory history = metadataSyncHistoryService.record(cluster, "manual", "table", scopeTarget, startedAt,
                result);
        Map<String, Object> response = buildSyncResponse(result, history);
        DataTable syncedTable = dataTableService.getByDbAndTableName(clusterId, database, tableName);
        response.put("database", database);
        response.put("tableName", tableName);
        response.put("tableId", syncedTable != null ? syncedTable.getId() : null);
        return response;
    }

    /**
     * 同步指定表（按表 ID）的元数据。
     */
    public Map<String, Object> syncTable(Long id, Long clusterId) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        Long actualClusterId = clusterId != null ? clusterId : table.getClusterId();
        DorisCluster cluster = requireCluster(actualClusterId);

        TableLocation location = dataTableService.requireTableLocation(table);
        String database = location.getDatabase();
        String actualTableName = location.getTableName();

        LocalDateTime startedAt = LocalDateTime.now();
        DorisMetadataSyncService.SyncResult result;
        try {
            result = dorisMetadataSyncService.syncTable(actualClusterId, database, actualTableName);
        } catch (Exception e) {
            result = new DorisMetadataSyncService.SyncResult();
            result.addError("表元数据同步失败: " + e.getMessage());
        }

        String scopeTarget = database + "." + actualTableName;
        MetadataSyncHistory history = metadataSyncHistoryService.record(cluster, "manual", "table", scopeTarget, startedAt,
                result);
        Map<String, Object> response = buildSyncResponse(result, history);
        response.put("database", database);
        response.put("tableName", actualTableName);
        return response;
    }

    private DorisCluster requireCluster(Long clusterId) {
        if (clusterId == null) {
            throw new RuntimeException("请指定数据源");
        }
        DorisCluster cluster = dorisClusterService.getById(clusterId);
        if (cluster == null) {
            throw new RuntimeException("未找到指定数据源: " + clusterId);
        }
        if (!"active".equals(cluster.getStatus())) {
            throw new IllegalArgumentException("数据源已停用");
        }
        return cluster;
    }

    private Map<String, Object> buildSyncResponse(DorisMetadataSyncService.SyncResult result, MetadataSyncHistory history) {
        Map<String, Object> response = new HashMap<>();
        response.put("success", "SUCCESS".equals(result.getStatus()));
        response.put("status", result.getStatus());
        response.put("syncRunId", history != null ? history.getId() : null);
        response.put("newTables", result.getNewTables());
        response.put("updatedTables", result.getUpdatedTables());
        response.put("deletedTables", result.getDeletedTables());
        response.put("blockedDeletedTables", result.getBlockedDeletedTables());
        response.put("newFields", result.getNewFields());
        response.put("updatedFields", result.getUpdatedFields());
        response.put("deletedFields", result.getDeletedFields());
        response.put("inactivatedTables", result.getInactivatedTables());
        response.put("errors", result.getErrors());
        response.put("syncTime", LocalDateTime.now());
        return response;
    }
}
