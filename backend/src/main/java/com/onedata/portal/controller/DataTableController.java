package com.onedata.portal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.auth.annotation.RequireAuth;
import com.onedata.auth.annotation.RequireRole;
import com.onedata.portal.dto.ColumnValueProfile;
import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.TableAccessStats;
import com.onedata.portal.dto.TableExport;
import com.onedata.portal.dto.TableFreshnessRequest;
import com.onedata.portal.dto.TableFreshnessResponse;
import com.onedata.portal.dto.TableLocation;
import com.onedata.portal.dto.TableOption;
import com.onedata.portal.dto.TablePartitionInfo;
import com.onedata.portal.dto.TableRelatedLineageResponse;
import com.onedata.portal.dto.TableRelatedTasksResponse;
import com.onedata.portal.dto.TableStatistics;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessResult;
import com.onedata.portal.entity.TableStatisticsHistory;
import com.onedata.portal.service.DataTableMetadataSyncService;
import com.onedata.portal.service.DataTableQueryService;
import com.onedata.portal.service.DataTableService;
import com.onedata.portal.service.freshness.FreshnessCheckResult;
import com.onedata.portal.service.freshness.TableFreshnessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 数据表管理 Controller。
 *
 * <p>仅负责请求绑定、鉴权注解、HTTP 响应组装与 {@code try/catch → Result} 映射；
 * 表生命周期、Doris 只读查询与元数据同步/稽核等业务编排已下沉到
 * {@link DataTableService}、{@link DataTableQueryService}、{@link DataTableMetadataSyncService}。
 */
@RestController
@RequestMapping("/v1/tables")
@RequiredArgsConstructor
public class DataTableController {

    private final DataTableService dataTableService;
    private final DataTableQueryService dataTableQueryService;
    private final DataTableMetadataSyncService dataTableMetadataSyncService;
    private final TableFreshnessService tableFreshnessService;

    /**
     * 分页查询表列表
     */
    @GetMapping
    public Result<PageResult<DataTable>> list(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) Long clusterId) {
        Page<DataTable> page = dataTableService.list(pageNum, pageSize, layer, keyword, sortField, sortOrder, clusterId);
        return Result.success(PageResult.of(page.getTotal(), page.getRecords()));
    }

    /**
     * 获取所有数据库列表（用于左侧导航）
     */
    @GetMapping("/databases")
    public Result<List<String>> listDatabases(@RequestParam(required = false) Long clusterId) {
        List<String> databases = dataTableService.listDatabases(clusterId);
        return Result.success(databases);
    }

    /**
     * 根据数据库获取表列表（包含统计信息）
     */
    @GetMapping("/by-database")
    public Result<List<DataTable>> listByDatabase(
            @RequestParam String database,
            @RequestParam(required = false) String sortField,
            @RequestParam(required = false) String sortOrder,
            @RequestParam(required = false) Long clusterId) {
        List<DataTable> tables = dataTableService.listByDatabase(database, sortField, sortOrder, clusterId);
        return Result.success(tables);
    }

    /**
     * 获取所有表（用于下拉选择）
     */
    @GetMapping("/all")
    public Result<List<DataTable>> listAll() {
        return Result.success(dataTableService.listAll());
    }

    /**
     * 搜索用于下拉的表选项
     */
    @GetMapping("/options")
    public Result<List<TableOption>> searchTableOptions(
            @RequestParam String keyword,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String layer,
            @RequestParam(required = false) String dbName,
            @RequestParam(required = false) Long clusterId) {
        return Result.success(dataTableService.searchTableOptions(keyword, limit, layer, dbName, clusterId));
    }

    /**
     * 根据ID获取表详情
     */
    @GetMapping("/{id}")
    public Result<DataTable> getById(@PathVariable Long id) {
        return Result.success(dataTableService.getById(id));
    }

    /**
     * 获取表字段
     */
    @GetMapping("/{id}/fields")
    public Result<List<DataField>> getFields(@PathVariable Long id) {
        return Result.success(dataTableService.listFields(id));
    }

    /**
     * 新增字段
     */
    @PostMapping("/{id}/fields")
    public Result<DataField> createField(@PathVariable Long id, @RequestBody DataField field,
            @RequestParam(required = false) Long clusterId) {
        try {
            return Result.success(dataTableService.createField(id, field, clusterId));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 更新字段
     */
    @PutMapping("/{id}/fields/{fieldId}")
    public Result<DataField> updateField(@PathVariable Long id, @PathVariable Long fieldId,
            @RequestBody DataField field,
            @RequestParam(required = false) Long clusterId) {
        try {
            return Result.success(dataTableService.updateField(id, fieldId, field, clusterId));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 删除字段
     */
    @DeleteMapping("/{id}/fields/{fieldId}")
    public Result<Void> deleteField(@PathVariable Long id, @PathVariable Long fieldId,
            @RequestParam(required = false) Long clusterId) {
        try {
            dataTableService.deleteField(id, fieldId, clusterId);
            return Result.success();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取表关联任务
     */
    @GetMapping("/{id}/tasks")
    public Result<TableRelatedTasksResponse> getRelatedTasks(@PathVariable Long id) {
        return Result.success(dataTableService.getRelatedTasks(id));
    }

    /**
     * 获取表上下游
     */
    @GetMapping("/{id}/lineage")
    public Result<TableRelatedLineageResponse> getRelatedLineage(@PathVariable Long id) {
        return Result.success(dataTableService.getRelatedLineage(id));
    }

    /**
     * 创建表
     */
    @PostMapping
    public Result<DataTable> create(@RequestBody DataTable dataTable) {
        try {
            dataTable.setLayer(dataTableService.normalizeLayer(dataTable.getLayer(), true));
            return Result.success(dataTableService.create(dataTable));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 更新表
     */
    @PutMapping("/{id}")
    public Result<DataTable> update(@PathVariable Long id, @RequestBody DataTable dataTable,
            @RequestParam(required = false) Long clusterId) {
        try {
            return Result.success(dataTableService.updateTable(id, dataTable, clusterId));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 删除表
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestParam String confirmTableName) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            return Result.fail("表不存在");
        }
        if (!DataTableService.isConfirmTableNameMatched(confirmTableName, table.getTableName())) {
            return Result.fail("确认失败：请输入正确的表名 " + table.getTableName());
        }
        dataTableService.delete(id);
        return Result.success();
    }

    /**
     * 修改表注释（同时更新Doris和本地）
     */
    @RequireAuth
    @PutMapping("/{id}/comment")
    public Result<Void> updateTableComment(
            @PathVariable Long id,
            @RequestBody Map<String, String> body,
            @RequestParam(required = false) Long clusterId) {
        try {
            dataTableService.updateTableComment(id, body.get("comment"), clusterId);
            return Result.success();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 软删除表（重命名为 tableName_deprecated_时间戳）
     */
    @RequireAuth
    @PostMapping("/{id}/soft-delete")
    public Result<Void> softDeleteTable(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId,
            @RequestParam String confirmTableName) {
        try {
            dataTableService.softDeleteTable(id, clusterId, confirmTableName);
            return Result.success();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 待删除表列表
     */
    @RequireAuth
    @GetMapping("/pending-deletion")
    public Result<List<Map<String, Object>>> listPendingDeletion(
            @RequestParam(required = false) Long clusterId) {
        return Result.success(dataTableService.listPendingDeletionView(clusterId));
    }

    /**
     * 恢复待删除表
     */
    @RequireAuth
    @PostMapping("/{id}/restore")
    public Result<DataTable> restoreTable(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId) {
        try {
            return Result.success(dataTableService.restoreTable(id, clusterId));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 立即物理删除表
     */
    @RequireAuth
    @PostMapping("/{id}/purge-now")
    public Result<Void> purgeTableNow(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId,
            @RequestParam String confirmTableName) {
        try {
            dataTableService.purgeTableNow(id, clusterId, confirmTableName);
            return Result.success();
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取表在 Doris 中的统计信息
     * 支持缓存机制，默认缓存5分钟
     * 使用 forceRefresh=true 强制刷新
     */
    @RequireAuth
    @GetMapping("/{id}/statistics")
    public Result<TableStatistics> getStatistics(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId,
            @RequestParam(required = false, defaultValue = "false") boolean forceRefresh) {
        try {
            return Result.success(dataTableQueryService.getStatistics(id, clusterId, forceRefresh));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取表访问统计（Doris 层面）
     */
    @RequireAuth
    @GetMapping("/{id}/access-stats")
    public Result<TableAccessStats> getAccessStats(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId,
            @RequestParam(required = false, defaultValue = "30") Integer recentDays,
            @RequestParam(required = false, defaultValue = "14") Integer trendDays,
            @RequestParam(required = false, defaultValue = "5") Integer topUsers) {
        try {
            return Result.success(dataTableQueryService.getAccessStats(id, clusterId, recentDays, trendDays, topUsers));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取数据库中所有表的统计信息
     */
    @GetMapping("/statistics/database/{database}")
    public Result<List<TableStatistics>> getDatabaseStatistics(
            @PathVariable String database,
            @RequestParam(required = false) Long clusterId) {
        try {
            return Result.success(dataTableQueryService.getDatabaseStatistics(database, clusterId));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取表的统计历史记录（最近N条）
     */
    @GetMapping("/{id}/statistics/history")
    public Result<List<TableStatisticsHistory>> getStatisticsHistory(
            @PathVariable Long id,
            @RequestParam(required = false, defaultValue = "30") int limit) {
        try {
            return Result.success(dataTableQueryService.getStatisticsHistory(id, limit));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取表最近7天的统计历史
     */
    @GetMapping("/{id}/statistics/history/last7days")
    public Result<List<TableStatisticsHistory>> getLast7DaysHistory(@PathVariable Long id) {
        try {
            return Result.success(dataTableQueryService.getLast7DaysHistory(id));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取表最近30天的统计历史
     */
    @GetMapping("/{id}/statistics/history/last30days")
    public Result<List<TableStatisticsHistory>> getLast30DaysHistory(@PathVariable Long id) {
        try {
            return Result.success(dataTableQueryService.getLast30DaysHistory(id));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取表的建表语句（DDL）
     */
    @RequireAuth
    @GetMapping("/{id}/ddl")
    public Result<String> getTableDdl(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId) {
        try {
            return Result.success(dataTableQueryService.getTableDdl(id, clusterId));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 根据数据库与表名获取表的建表语句（DDL）
     */
    @RequireAuth
    @GetMapping("/ddl/by-name")
    public Result<String> getTableDdlByName(
            @RequestParam Long clusterId,
            @RequestParam String database,
            @RequestParam String tableName) {
        try {
            return Result.success(dataTableQueryService.getTableDdlByName(clusterId, database, tableName));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 获取表的分区列表（分区名、范围、分桶、副本、大小、行数）
     */
    @RequireAuth
    @GetMapping("/{id}/partitions")
    public Result<List<TablePartitionInfo>> listPartitions(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId) {
        try {
            return Result.success(dataTableQueryService.listPartitions(id, clusterId));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 统计疑似枚举列的实测取值分布（智能元数据用于校验枚举，避免模型凭空编造取值）
     */
    @RequireAuth
    @GetMapping("/{id}/column-values")
    public Result<List<ColumnValueProfile>> profileEnumColumns(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId) {
        try {
            return Result.success(dataTableQueryService.profileEnumColumns(id, clusterId));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 预览表数据
     */
    @RequireAuth
    @GetMapping("/{id}/preview")
    public Result<List<Map<String, Object>>> previewTableData(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId,
            @RequestParam(defaultValue = "100") int limit) {
        try {
            return Result.success(dataTableQueryService.previewTableData(id, clusterId, limit));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 导出表数据
     */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> exportTableData(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam(defaultValue = "1000") int limit) {

        DataTable table = dataTableService.getById(id);
        if (table == null) {
            return ResponseEntity.notFound().build();
        }

        TableLocation location;
        try {
            location = dataTableService.requireTableLocation(table);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }

        try {
            TableExport export = dataTableQueryService.export(location, clusterId, format, limit);

            // 生成文件名
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String filename = String.format("%s_%s.%s", export.getTableName(), timestamp, export.getFileExtension());

            // URL编码文件名以支持中文
            String encodedFilename;
            try {
                encodedFilename = URLEncoder.encode(filename, "UTF-8").replaceAll("\\+", "%20");
            } catch (UnsupportedEncodingException e) {
                encodedFilename = filename;
            }

            return ResponseEntity.ok()
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encodedFilename)
                    .contentType(MediaType.parseMediaType(export.getContentType()))
                    .body(export.getData());
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * 稽核/比对 Doris 元数据（只检查差异，不同步）
     */
    @RequireRole("admin")
    @PostMapping("/audit-metadata")
    public Result<Map<String, Object>> auditAllMetadata(
            @RequestParam(required = false) Long clusterId) {
        try {
            Map<String, Object> response = dataTableMetadataSyncService.auditAllMetadata(clusterId);
            boolean hasDifferences = Boolean.TRUE.equals(response.get("hasDifferences"));
            if (hasDifferences) {
                return Result.success(response, String.format("发现 %d 处差异，请确认后同步", response.get("totalDifferences")));
            }
            return Result.success(response, "元数据一致，无需同步");
        } catch (Exception e) {
            return Result.fail("元数据稽核失败: " + e.getMessage());
        }
    }

    /**
     * 手动触发 Doris 元数据同步（全量同步）
     * 建议先调用 audit-metadata 接口确认差异后再调用此接口
     */
    @RequireRole("admin")
    @PostMapping("/sync-metadata")
    public Result<Map<String, Object>> syncAllMetadata(
            @RequestParam(required = false) Long clusterId) {
        try {
            Map<String, Object> response = dataTableMetadataSyncService.syncAll(clusterId);
            return respondSync(response, "元数据同步成功", "元数据同步完成，但存在部分错误", "元数据同步失败");
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 手动触发指定数据库的元数据同步
     */
    @RequireRole("admin")
    @PostMapping("/sync-metadata/database/{database}")
    public Result<Map<String, Object>> syncDatabaseMetadata(
            @PathVariable String database,
            @RequestParam(required = false) Long clusterId) {
        try {
            Map<String, Object> response = dataTableMetadataSyncService.syncDatabase(clusterId, database);
            return respondSync(response, "数据库元数据同步成功", "数据库元数据同步完成，但存在部分错误", "数据库元数据同步失败");
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 手动按库表名同步指定表的元数据，用于平台尚未有 tableId 的 Doris 表。
     */
    @RequireRole("admin")
    @PostMapping("/sync-metadata/database/{database}/table/{tableName}")
    public Result<Map<String, Object>> syncTableMetadataByName(
            @PathVariable String database,
            @PathVariable String tableName,
            @RequestParam(required = false) Long clusterId) {
        try {
            Map<String, Object> response = dataTableMetadataSyncService.syncTableByName(clusterId, database, tableName);
            return respondSync(response, "表元数据同步成功", "表元数据同步完成，但存在部分错误", "表元数据同步失败");
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 手动触发指定表的元数据同步
     */
    @RequireRole("admin")
    @PostMapping("/{id}/sync-metadata")
    public Result<Map<String, Object>> syncTableMetadata(
            @PathVariable Long id,
            @RequestParam(required = false) Long clusterId) {
        try {
            Map<String, Object> response = dataTableMetadataSyncService.syncTable(id, clusterId);
            return respondSync(response, "表元数据同步成功", "表元数据同步完成，但存在部分错误", "表元数据同步失败");
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 按同步结果状态选择端点专属文案。失败结果同样以 {@code Result.success} 返回（保持现状）。
     */
    private Result<Map<String, Object>> respondSync(Map<String, Object> response,
            String successMessage, String partialMessage, String failMessage) {
        String status = (String) response.get("status");
        if ("SUCCESS".equals(status)) {
            return Result.success(response, successMessage);
        }
        if ("PARTIAL".equals(status)) {
            return Result.success(response, partialMessage);
        }
        return Result.success(response, failMessage);
    }

    // ---- 数据新鲜度 ----

    /**
     * 查询表的生效新鲜度契约与最近一次结果
     */
    @RequireAuth
    @GetMapping("/{id}/freshness")
    public Result<TableFreshnessResponse> getFreshness(@PathVariable Long id) {
        try {
            return Result.success(tableFreshnessService.getFreshness(id));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 保存（upsert）表级新鲜度契约
     */
    @RequireAuth
    @PutMapping("/{id}/freshness")
    public Result<Map<String, Object>> saveFreshness(
            @PathVariable Long id,
            @RequestBody TableFreshnessRequest request) {
        try {
            tableFreshnessService.saveFreshness(id, request, TableFreshnessService.currentOperator());
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("success", true);
            return Result.success(result, "新鲜度契约保存成功");
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 删除表级新鲜度契约
     */
    @RequireAuth
    @DeleteMapping("/{id}/freshness")
    public Result<Map<String, Object>> deleteFreshness(@PathVariable Long id) {
        try {
            tableFreshnessService.deleteFreshness(id);
            Map<String, Object> result = new java.util.HashMap<>();
            result.put("success", true);
            return Result.success(result, "新鲜度契约已删除");
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 立即检查该表新鲜度
     */
    @RequireAuth
    @PostMapping("/{id}/freshness/check")
    public Result<FreshnessCheckResult> checkFreshness(@PathVariable Long id) {
        try {
            return Result.success(tableFreshnessService.checkNow(id, TableFreshnessService.currentOperator()));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 新鲜度检查结果历史
     */
    @RequireAuth
    @GetMapping("/{id}/freshness/history")
    public Result<List<TableFreshnessResult>> freshnessHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "20") int limit) {
        try {
            return Result.success(tableFreshnessService.history(id, limit));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }
}
