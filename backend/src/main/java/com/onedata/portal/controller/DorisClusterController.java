package com.onedata.portal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.auth.annotation.RequireAuth;
import com.onedata.portal.dto.backup.SchemaBackupConfigRequest;
import com.onedata.portal.dto.backup.SchemaBackupItem;
import com.onedata.portal.dto.backup.SchemaBackupRestoreRequest;
import com.onedata.portal.dto.backup.SchemaBackupRestoreResponse;
import com.onedata.portal.dto.backup.SchemaBackupSnapshot;
import com.onedata.portal.dto.backup.SchemaBackupTriggerResponse;
import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.SchemaObjectCount;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.MetadataSyncHistory;
import com.onedata.portal.service.DataTableService;
import com.onedata.portal.service.DorisClusterService;
import com.onedata.portal.service.DorisConnectionService;
import com.onedata.portal.service.MetadataSyncHistoryService;
import com.onedata.portal.service.SchemaBackupService;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Doris 集群管理 Controller
 */
@RestController
@RequestMapping("/v1/doris-clusters")
@RequiredArgsConstructor
public class DorisClusterController {

    private final DorisClusterService dorisClusterService;
    private final DorisConnectionService dorisConnectionService;
    private final DataTableService dataTableService;
    private final MetadataSyncHistoryService metadataSyncHistoryService;
    private final SchemaBackupService schemaBackupService;

    @GetMapping
    public Result<List<DorisCluster>> list() {
        return Result.success(dorisClusterService.listAll());
    }

    @GetMapping("/{id}")
    public Result<DorisCluster> getById(@PathVariable Long id) {
        return Result.success(dorisClusterService.getById(id));
    }

    @PostMapping
    public Result<DorisCluster> create(@RequestBody DorisCluster cluster) {
        return Result.success(dorisClusterService.create(cluster));
    }

    @PutMapping("/{id}")
    public Result<DorisCluster> update(@PathVariable Long id, @RequestBody DorisCluster cluster) {
        return Result.success(dorisClusterService.update(id, cluster));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        dorisClusterService.delete(id);
        return Result.success();
    }

    @PostMapping("/{id}/default")
    public Result<Void> setDefault(@PathVariable Long id) {
        dorisClusterService.setDefault(id);
        return Result.success();
    }

    @PostMapping("/{id}/test")
    public Result<Boolean> testConnection(@PathVariable Long id) {
        return Result.success(dorisConnectionService.testConnection(id));
    }

    @RequireAuth
    @GetMapping("/{id}/databases")
    public Result<List<String>> listDatabases(@PathVariable Long id) {
        return Result.success(dorisConnectionService.getAllDatabases(id));
    }

    @RequireAuth
    @GetMapping("/{id}/databases/{database}/tables")
    public Result<List<Map<String, Object>>> listTables(
            @PathVariable Long id,
            @PathVariable String database,
            @RequestParam(required = false, defaultValue = "false") boolean includeSoftDeleted) {
        List<Map<String, Object>> tables = dorisConnectionService.getTablesInDatabase(id, database);
        if (includeSoftDeleted) {
            return Result.success(tables);
        }
        Set<String> softDeletedKeys = dataTableService.listSoftDeletedTableKeys(id, database);
        if (softDeletedKeys.isEmpty()) {
            return Result.success(tables);
        }
        List<Map<String, Object>> filtered = tables.stream()
                .filter(table -> {
                    String tableName = toText(table.get("tableName"));
                    if (!StringUtils.hasText(tableName)) {
                        return true;
                    }
                    return !softDeletedKeys.contains(buildDbTableKey(database, tableName));
                })
                .collect(Collectors.toList());
        return Result.success(filtered);
    }

    @RequireAuth
    @GetMapping("/{id}/schema-objects")
    public Result<List<Map<String, Object>>> listSchemaObjects(
            @PathVariable Long id,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false, defaultValue = "false") boolean includeSoftDeleted) {
        List<Map<String, Object>> objects = dorisConnectionService.getSchemaObjects(id, keyword);
        Set<String> softDeletedKeys = includeSoftDeleted
                ? Collections.emptySet()
                : dataTableService.listSoftDeletedTableKeys(id);
        int max = normalizeObjectLimit(limit);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map<String, Object> object : objects) {
            String schemaName = toText(object.get("schemaName"));
            String tableName = toText(object.get("tableName"));
            if (!StringUtils.hasText(schemaName) || !StringUtils.hasText(tableName)) {
                continue;
            }
            if (!includeSoftDeleted && softDeletedKeys.contains(buildDbTableKey(schemaName, tableName))) {
                continue;
            }
            Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("schemaName", schemaName);
            item.put("tableName", tableName);
            item.put("tableType", toText(object.get("tableType")));
            item.put("tableComment", toText(object.get("tableComment")));
            result.add(item);
            if (result.size() >= max) {
                break;
            }
        }
        return Result.success(result);
    }

    @RequireAuth
    @GetMapping("/{id}/databases/{database}/tables/{table}/columns")
    public Result<List<Map<String, Object>>> listTableColumns(
            @PathVariable Long id,
            @PathVariable String database,
            @PathVariable("table") String tableName) {
        return Result.success(dorisConnectionService.getColumnsInTable(id, database, tableName));
    }

    @RequireAuth
    @GetMapping("/{id}/schema-object-counts")
    public Result<List<SchemaObjectCount>> listSchemaObjectCounts(
            @PathVariable Long id,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false, defaultValue = "false") boolean includeSoftDeleted) {
        List<Map<String, Object>> objects = dorisConnectionService.getSchemaObjects(id, keyword);
        Set<String> softDeletedKeys = includeSoftDeleted
                ? Collections.emptySet()
                : dataTableService.listSoftDeletedTableKeys(id);
        Map<String, SchemaObjectCount> countMap = new java.util.HashMap<>();
        for (Map<String, Object> object : objects) {
            String schemaName = toText(object.get("schemaName"));
            String tableName = toText(object.get("tableName"));
            if (!StringUtils.hasText(schemaName) || !StringUtils.hasText(tableName)) {
                continue;
            }
            if (!includeSoftDeleted && softDeletedKeys.contains(buildDbTableKey(schemaName, tableName))) {
                continue;
            }
            SchemaObjectCount count = countMap.computeIfAbsent(schemaName, key -> {
                SchemaObjectCount item = new SchemaObjectCount();
                item.setSchemaName(key);
                return item;
            });
            if (isViewType(toText(object.get("tableType")))) {
                count.setViewCount(count.getViewCount() + 1);
            } else {
                count.setTableCount(count.getTableCount() + 1);
            }
            count.setTotalCount(count.getTableCount() + count.getViewCount());
        }

        List<SchemaObjectCount> result = new ArrayList<>(countMap.values());
        result.sort(Comparator.comparing(
                item -> StringUtils.hasText(item.getSchemaName()) ? item.getSchemaName() : "",
                String.CASE_INSENSITIVE_ORDER));
        return Result.success(result);
    }

    @GetMapping("/{id}/sync-history")
    public Result<PageResult<MetadataSyncHistory>> listSyncHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String triggerType) {
        DorisCluster cluster = dorisClusterService.getById(id);
        if (cluster == null) {
            return Result.fail("数据源不存在");
        }
        Page<MetadataSyncHistory> page = metadataSyncHistoryService.listByCluster(id, pageNum, pageSize, status, triggerType);
        return Result.success(PageResult.of(page.getTotal(), page.getRecords()));
    }

    @GetMapping("/{id}/sync-history/{runId}")
    public Result<MetadataSyncHistory> getSyncHistoryDetail(
            @PathVariable Long id,
            @PathVariable Long runId) {
        MetadataSyncHistory history = metadataSyncHistoryService.getById(runId);
        if (history == null || !Objects.equals(history.getClusterId(), id)) {
            return Result.fail("同步记录不存在");
        }
        return Result.success(history);
    }

    @RequireAuth
    @GetMapping("/{id}/schema-backups")
    public Result<List<SchemaBackupItem>> listSchemaBackups(@PathVariable Long id) {
        return Result.success(schemaBackupService.listSchemaBackupItems(id));
    }

    @RequireAuth
    @GetMapping("/{id}/schema-backups/{schema}")
    public Result<SchemaBackupItem> getSchemaBackup(
            @PathVariable Long id,
            @PathVariable String schema) {
        return Result.success(schemaBackupService.getSchemaBackupItem(id, schema));
    }

    @RequireAuth
    @PutMapping("/{id}/schema-backups/{schema}")
    public Result<SchemaBackupItem> saveSchemaBackup(
            @PathVariable Long id,
            @PathVariable String schema,
            @RequestBody SchemaBackupConfigRequest request) {
        return Result.success(schemaBackupService.upsertConfig(id, schema, request));
    }

    @RequireAuth
    @PostMapping("/{id}/schema-backups/{schema}/backup")
    public Result<SchemaBackupTriggerResponse> triggerSchemaBackup(
            @PathVariable Long id,
            @PathVariable String schema) {
        return Result.success(schemaBackupService.triggerBackup(id, schema, "manual"));
    }

    @RequireAuth
    @GetMapping("/{id}/schema-backups/{schema}/snapshots")
    public Result<List<SchemaBackupSnapshot>> listSnapshots(
            @PathVariable Long id,
            @PathVariable String schema) {
        return Result.success(schemaBackupService.listSnapshots(id, schema));
    }

    @RequireAuth
    @PostMapping("/{id}/schema-backups/{schema}/restore")
    public Result<SchemaBackupRestoreResponse> restoreSnapshot(
            @PathVariable Long id,
            @PathVariable String schema,
            @RequestBody SchemaBackupRestoreRequest request) {
        return Result.success(schemaBackupService.restoreSnapshot(id, schema, request));
    }

    private int normalizeObjectLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return 50;
        }
        return Math.min(limit, 200);
    }

    private String toText(Object value) {
        if (value == null) {
            return "";
        }
        return String.valueOf(value);
    }

    private String buildDbTableKey(String dbName, String tableName) {
        return DataTableService.buildDbTableKey(dbName, tableName);
    }

    private boolean isViewType(String tableType) {
        return StringUtils.hasText(tableType) && tableType.trim().toUpperCase().contains("VIEW");
    }
}
