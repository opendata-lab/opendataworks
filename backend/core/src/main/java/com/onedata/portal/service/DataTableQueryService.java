package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.ColumnValueProfile;
import com.onedata.portal.dto.DatasourceResolutionDto;
import com.onedata.portal.dto.FieldMetadataDto;
import com.onedata.portal.dto.TableAccessStats;
import com.onedata.portal.dto.TableDdlResponse;
import com.onedata.portal.dto.TableInspectionResponse;
import com.onedata.portal.dto.TableLocation;
import com.onedata.portal.dto.TableMetadataDto;
import com.onedata.portal.dto.TablePartitionInfo;
import com.onedata.portal.dto.TableStatistics;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.TableStatisticsHistory;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 数据表只读查询编排服务。
 *
 * <p>承接控制器下沉的 Doris 只读查询编排：统计信息（含缓存与历史落库）、访问统计、 DDL、数据预览与导出、统计历史。库表标识统一经 {@link
 * DataTableService#requireTableLocation} 解析。失败以「最终态消息」异常表达，由控制器薄 {@code try/catch} 映射为 {@code
 * Result}。
 */
@lombok.extern.slf4j.Slf4j
@Service
@RequiredArgsConstructor
public class DataTableQueryService {
    /** 单表最多统计多少个候选列，避免宽表打出几十条分组查询 */
    private static final int MAX_ENUM_CANDIDATE_COLUMNS = 20;

    /** 实测取值超过这个数就不当枚举 */
    private static final int MAX_ENUM_DISTINCT = 30;

    /** 可分组、且值域可能有限的类型；日期、浮点、复杂类型不参与 */
    private static final Set<String> ENUM_CANDIDATE_TYPES =
            new HashSet<>(
                    Arrays.asList(
                            "BOOLEAN",
                            "BOOL",
                            "TINYINT",
                            "SMALLINT",
                            "INT",
                            "INTEGER",
                            "BIGINT",
                            "CHAR",
                            "VARCHAR",
                            "STRING",
                            "TEXT"));

    /** 标识类命名：主键、编号、流水号等，取值必然发散，不必扫 */
    private static final Pattern IDENTIFIER_NAME_PATTERN =
            Pattern.compile("(^|.*_)(id|uid|uuid|guid|sn|seq|key|no)$");

    private final DataTableService dataTableService;
    private final DorisConnectionService dorisConnectionService;
    private final TableStatisticsCacheService cacheService;
    private final TableStatisticsHistoryService historyService;
    private final DorisTableAccessService dorisTableAccessService;

    /** 获取表在 Doris 中的统计信息（支持缓存，forceRefresh 强制刷新）。 */
    public TableStatistics getStatistics(Long id, Long clusterId, boolean forceRefresh) {
        // 如果不强制刷新，先尝试从缓存获取
        if (!forceRefresh) {
            TableStatistics cached = cacheService.get(id, clusterId);
            if (cached != null) {
                return cached;
            }
        }
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        TableLocation location = dataTableService.requireTableLocation(table);
        try {
            TableStatistics statistics =
                    dorisConnectionService.getTableStatistics(
                            clusterId, location.getDatabase(), location.getTableName());
            // 放入缓存
            cacheService.put(id, clusterId, statistics);
            // 保存到历史记录
            historyService.saveHistory(id, clusterId, statistics);
            return statistics;
        } catch (Exception e) {
            throw new RuntimeException("获取表统计信息失败: " + e.getMessage());
        }
    }

    /** 获取表访问统计（Doris 层面）。 */
    public TableAccessStats getAccessStats(
            Long id, Long clusterId, Integer recentDays, Integer trendDays, Integer topUsers) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        try {
            return dorisTableAccessService.getTableAccessStats(
                    table,
                    clusterId,
                    recentDays == null ? 30 : recentDays,
                    trendDays == null ? 14 : trendDays,
                    topUsers == null ? 5 : topUsers);
        } catch (Exception e) {
            throw new RuntimeException("获取表访问统计失败: " + e.getMessage());
        }
    }

    /** 获取数据库中所有表的统计信息。 */

    /** 获取表的统计历史记录（最近 N 条）。 */
    public List<TableStatisticsHistory> getStatisticsHistory(Long id, int limit) {
        try {
            return historyService.getRecentHistory(id, limit);
        } catch (Exception e) {
            throw new RuntimeException("获取统计历史记录失败: " + e.getMessage());
        }
    }

    /** 获取表最近 7 天的统计历史。 */

    /** 获取表最近 30 天的统计历史。 */

    /** 获取表的建表语句（DDL）。 */
    public String getTableDdl(Long id, Long clusterId) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        TableLocation location = dataTableService.requireTableLocation(table);
        try {
            return dorisConnectionService.getTableDdl(
                    clusterId, location.getDatabase(), location.getTableName());
        } catch (Exception e) {
            throw new RuntimeException("获取建表语句失败: " + e.getMessage());
        }
    }

    /** 根据数据库与表名获取表的建表语句（DDL）。 */
    public String getTableDdlByName(Long clusterId, String database, String tableName) {
        if (clusterId == null) {
            throw new RuntimeException("请指定数据源");
        }
        if (!StringUtils.hasText(database) || !StringUtils.hasText(tableName)) {
            throw new RuntimeException("数据库和表名不能为空");
        }
        String actualTableName = dataTableService.extractActualTableName(database, tableName);
        if (!StringUtils.hasText(actualTableName)) {
            throw new RuntimeException("表名无效");
        }
        try {
            return dorisConnectionService.getTableDdl(clusterId, database, actualTableName);
        } catch (Exception e) {
            throw new RuntimeException("获取建表语句失败: " + e.getMessage());
        }
    }

    /** 列出表的分区（分区名、范围、分桶、副本、大小、行数）。 */
    public List<TablePartitionInfo> listPartitions(Long id, Long clusterId) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        TableLocation location = dataTableService.requireTableLocation(table);
        try {
            return dorisConnectionService.listPartitions(
                    clusterId, location.getDatabase(), location.getTableName());
        } catch (Exception e) {
            throw new RuntimeException("获取分区列表失败: " + e.getMessage());
        }
    }

    /**
     * 统计疑似枚举列的实测取值分布。
     *
     * <p>供智能元数据把枚举含义建立在真实数据上：只有出现在返回结果里的取值才允许写进字段描述。 候选列由字段类型与命名先筛一遍（见 {@link
     * #isEnumCandidate}），再由取值数上限决定是否算枚举 ——超过 {@link #MAX_ENUM_DISTINCT} 个取值的列不会返回。
     */
    public List<ColumnValueProfile> profileEnumColumns(Long id, Long clusterId) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        List<DataField> fields = dataTableService.listFields(id);
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyList();
        }
        Map<String, String> typeByName = new LinkedHashMap<>();
        List<String> candidates = new ArrayList<>();
        for (DataField field : fields) {
            if (candidates.size() >= MAX_ENUM_CANDIDATE_COLUMNS) {
                break;
            }
            if (!isEnumCandidate(field)) {
                continue;
            }
            candidates.add(field.getFieldName());
            typeByName.put(field.getFieldName(), field.getFieldType());
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        TableLocation location = dataTableService.requireTableLocation(table);
        try {
            List<ColumnValueProfile> profiles =
                    dorisConnectionService.profileColumnValues(
                            clusterId,
                            location.getDatabase(),
                            location.getTableName(),
                            candidates,
                            MAX_ENUM_DISTINCT);
            profiles.forEach(
                    profile -> profile.setFieldType(typeByName.get(profile.getFieldName())));
            return profiles;
        } catch (Exception e) {
            throw new RuntimeException("统计字段取值失败: " + e.getMessage());
        }
    }

    /** 疑似枚举列判定：类型可分组、命名不像标识列。真正是不是枚举由实测取值数决定。 */
    private boolean isEnumCandidate(DataField field) {
        if (field == null
                || !StringUtils.hasText(field.getFieldName())
                || !StringUtils.hasText(field.getFieldType())) {
            return false;
        }
        String type = field.getFieldType().trim().toUpperCase();
        int paren = type.indexOf('(');
        String baseType = paren > 0 ? type.substring(0, paren) : type;
        if (!ENUM_CANDIDATE_TYPES.contains(baseType)) {
            return false;
        }
        String name = field.getFieldName().trim().toLowerCase();
        return !IDENTIFIER_NAME_PATTERN.matcher(name).matches();
    }

    /** 预览表数据。 */
    public List<Map<String, Object>> previewTableData(Long id, Long clusterId, int limit) {
        DataTable table = dataTableService.getById(id);
        if (table == null) {
            throw new RuntimeException("表不存在");
        }
        TableLocation location = dataTableService.requireTableLocation(table);
        try {
            return dorisConnectionService.previewTableData(
                    clusterId, location.getDatabase(), location.getTableName(), limit);
        } catch (Exception e) {
            throw new RuntimeException("预览表数据失败: " + e.getMessage());
        }
    }

    /** 产出表数据导出字节与 Content-Type；文件名与响应头由控制器组装。 */

    /** 元数据导出行数安全阀，防止无界导出撑爆 agent 工具结果缓冲。 */
    private static final int EXPORT_MAX_ROWS = 5000;

    private static final int INSPECT_FETCH_LIMIT = 800;
    private static final int INSPECT_FIELD_FETCH_LIMIT = 2400;
    private static final int INSPECT_LINEAGE_LIMIT = 120;
    private static final int LINEAGE_FETCH_LIMIT = 400;
    private static final int DDL_TIMEOUT_SECONDS = 20;

    private final DatasourceAdapterRegistry adapterRegistry;
    private final DataTableMapper dataTableMapper;
    private final DataFieldMapper dataFieldMapper;
    private final DorisClusterMapper dorisClusterMapper;
    private final LineageService lineageService;
    private final com.onedata.portal.service.DatasourceSubjectAccessService datasourceAccess;

    public TableInspectionResponse inspect(
            String database, String table, String keyword, int tableLimit) {
        String normalizedDatabase = trimToNull(database);
        String normalizedTable = trimToNull(table);
        String normalizedKeyword = trimToNull(keyword);
        int safeTableLimit = Math.max(1, Math.min(tableLimit, 100));

        List<DataTable> matchedTables =
                findTables(
                        normalizedDatabase,
                        normalizedTable,
                        normalizedKeyword,
                        INSPECT_FETCH_LIMIT);
        List<DataTable> selectedTables =
                matchedTables.subList(0, Math.min(safeTableLimit, matchedTables.size()));
        List<Long> selectedIds =
                selectedTables.stream()
                        .map(DataTable::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

        Map<Long, List<DataField>> fieldMap = loadFields(selectedIds);

        TableInspectionResponse response = new TableInspectionResponse();
        response.setDatabase(normalizedDatabase);
        response.setTable(normalizedTable);
        response.setKeyword(normalizedKeyword);
        response.setTableCount(selectedTables.size());
        response.setTables(
                selectedTables.stream()
                        .map(
                                tableItem ->
                                        toAgentTableMetadata(
                                                tableItem,
                                                fieldMap.getOrDefault(
                                                        tableItem.getId(),
                                                        Collections.emptyList())))
                        .collect(Collectors.toList()));
        response.setLineage(
                lineageService.inspectRecordsForTables(selectedIds, INSPECT_LINEAGE_LIMIT));
        response.setError(null);
        return response;
    }

    public TableDdlResponse ddl(String database, String table, Long tableId) {
        String normalizedDatabase = trimToNull(database);
        String normalizedTable = trimToNull(table);
        if (tableId == null
                && (!StringUtils.hasText(normalizedDatabase)
                        || !StringUtils.hasText(normalizedTable))) {
            throw new IllegalArgumentException("tableId 或 database + table 至少提供一组");
        }

        DataTable matchedTable = resolveDdlTable(normalizedDatabase, normalizedTable, tableId);
        String targetDatabase = normalizedDatabase;
        String rawTableName = normalizedTable;
        if (matchedTable != null) {
            if (StringUtils.hasText(matchedTable.getDbName())) {
                targetDatabase = matchedTable.getDbName();
            }
            rawTableName = matchedTable.getTableName();
        }
        String actualTableName = restrictedExtractActualTableName(targetDatabase, rawTableName);
        if (!StringUtils.hasText(targetDatabase) || !StringUtils.hasText(actualTableName)) {
            throw new IllegalArgumentException("database 或 table 无法确定");
        }

        // 已定位到具体表时沿用它的数据源，不再按 database 名全局解析（同名 schema 会冲突）
        DatasourceResolutionDto datasource =
                matchedTable != null && matchedTable.getClusterId() != null
                        ? datasourceAccess.resolveClusterDatasource(
                                matchedTable.getClusterId(), targetDatabase, null, "data_table")
                        : datasourceAccess.resolveDatasource(targetDatabase, null);
        String ddl =
                datasourceAccess.fetchTableDdl(
                        datasource, targetDatabase, actualTableName, DDL_TIMEOUT_SECONDS);

        TableDdlResponse response = new TableDdlResponse();
        response.setDatabase(targetDatabase);
        response.setTableName(actualTableName);
        response.setEngine(datasource.getEngine());
        response.setClusterId(datasource.getClusterId());
        response.setClusterName(datasource.getClusterName());
        response.setSourceType(datasource.getSourceType());
        response.setResolvedBy(datasource.getResolvedBy());
        response.setDdl(ddl);

        if (matchedTable != null) {
            response.setTableId(matchedTable.getId());
            response.setTableComment(matchedTable.getTableComment());
            response.setFields(
                    loadFields(Collections.singletonList(matchedTable.getId()))
                            .getOrDefault(matchedTable.getId(), Collections.emptyList())
                            .stream()
                            .map(
                                    field -> {
                                        FieldMetadataDto item = new FieldMetadataDto();
                                        item.setFieldName(field.getFieldName());
                                        item.setFieldType(field.getFieldType());
                                        item.setFieldComment(field.getFieldComment());
                                        return item;
                                    })
                            .collect(Collectors.toList()));
        }
        return response;
    }

    public List<Map<String, Object>> exportTables(String database) {
        List<DataTable> tables = listActiveTables(trimToNull(database));
        Map<Long, List<DataField>> fieldMap =
                loadFields(
                        tables.stream()
                                .map(DataTable::getId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toList()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DataTable table : tables) {
            List<DataField> fields = fieldMap.getOrDefault(table.getId(), Collections.emptyList());
            if (fields.isEmpty()) {
                rows.add(tableExportRow(table, null));
                continue;
            }
            for (DataField field : fields) {
                rows.add(tableExportRow(table, field));
            }
        }
        return capExportRows(rows, "tables");
    }

    public List<Map<String, Object>> exportDatasource(String database) {
        List<DataTable> tables = listActiveTables(trimToNull(database));
        Set<Long> clusterIds =
                tables.stream()
                        .map(DataTable::getClusterId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, DorisCluster> clusterMap =
                clusterIds.isEmpty()
                        ? Collections.emptyMap()
                        : dorisClusterMapper.selectBatchIds(clusterIds).stream()
                                .filter(Objects::nonNull)
                                .collect(Collectors.toMap(DorisCluster::getId, item -> item));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DataTable table : tables) {
            DorisCluster cluster = clusterMap.get(table.getClusterId());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("db_name", table.getDbName());
            row.put("cluster_id", table.getClusterId());
            row.put(
                    "engine",
                    cluster == null
                            ? inferEngineFromDatabase(table.getDbName())
                            : datasourceAccess.engineForSourceType(cluster.getSourceType()));
            row.put(
                    "source_type",
                    cluster == null
                            ? null
                            : datasourceAccess.normalizeSourceType(cluster.getSourceType()));
            row.put("cluster_name", cluster == null ? null : trimToEmpty(cluster.getClusterName()));
            row.put("resolved_by", "subject_mapping");
            rows.add(row);
        }
        return capExportRows(rows, "datasource");
    }

    private List<Map<String, Object>> capExportRows(
            List<Map<String, Object>> rows, String exportKind) {
        if (rows == null || rows.size() <= EXPORT_MAX_ROWS) {
            return rows;
        }
        throw new IllegalArgumentException("导出结果超过5000行，请缩小范围");
    }

    private List<DataTable> findTables(
            String database, String table, String keyword, int fetchLimit) {
        if (!StringUtils.hasText(table) && !StringUtils.hasText(keyword)) {
            return listActiveTables(database).stream()
                    .limit(Math.max(1, fetchLimit))
                    .collect(Collectors.toList());
        }

        Map<Long, SearchCandidate> candidates = new LinkedHashMap<>();
        if (StringUtils.hasText(table)) {
            indexTableMatches(candidates, database, table, true, fetchLimit);
            indexFieldMatches(candidates, database, table, true, fetchLimit);
        }
        if (StringUtils.hasText(keyword)) {
            indexTableMatches(candidates, database, keyword, false, fetchLimit);
            indexFieldMatches(candidates, database, keyword, false, fetchLimit);
        }

        return candidates.values().stream()
                .filter(candidate -> isTableAllowed(candidate.getTable()))
                .filter(candidate -> candidate.matches(table, keyword))
                .sorted(
                        Comparator.comparingLong(SearchCandidate::getScore)
                                .reversed()
                                .thenComparing(
                                        Comparator.comparingInt(SearchCandidate::getMatchSignals)
                                                .reversed())
                                .thenComparing(
                                        candidate -> trimToEmpty(candidate.getTable().getDbName()))
                                .thenComparing(
                                        candidate ->
                                                trimToEmpty(candidate.getTable().getTableName()))
                                .thenComparing(
                                        candidate -> candidate.getTable().getId(),
                                        Comparator.nullsLast(Long::compareTo)))
                .limit(Math.max(1, fetchLimit))
                .map(SearchCandidate::getTable)
                .collect(Collectors.toList());
    }

    private void indexTableMatches(
            Map<Long, SearchCandidate> candidates,
            String database,
            String term,
            boolean tableQuery,
            int fetchLimit) {
        if (!StringUtils.hasText(term)) {
            return;
        }
        List<DataTable> tables = queryTableMatches(database, term, fetchLimit);
        for (DataTable item : tables) {
            if (item == null || item.getId() == null) {
                continue;
            }
            long score =
                    scoreTextMatch(item.getTableName(), term, 1600, 900)
                            + scoreTextMatch(item.getTableComment(), term, 1000, 520);
            if (score <= 0) {
                continue;
            }
            SearchCandidate candidate =
                    candidates.computeIfAbsent(item.getId(), ignored -> new SearchCandidate(item));
            candidate.addScore(score);
            candidate.markMatched(tableQuery);
        }
    }

    private void indexFieldMatches(
            Map<Long, SearchCandidate> candidates,
            String database,
            String term,
            boolean tableQuery,
            int fetchLimit) {
        if (!StringUtils.hasText(term)) {
            return;
        }
        List<DataField> fields = queryFieldMatches(database, term, fetchLimit);
        Map<Long, DataTable> tableMap = loadTableMapForFields(fields);
        for (DataField field : fields) {
            if (field == null || field.getTableId() == null) {
                continue;
            }
            DataTable table = tableMap.get(field.getTableId());
            if (table == null || table.getId() == null) {
                continue;
            }
            SearchCandidate candidate =
                    candidates.computeIfAbsent(
                            table.getId(), ignored -> new SearchCandidate(table));
            long score =
                    scoreTextMatch(field.getFieldName(), term, 700, 320)
                            + scoreTextMatch(field.getFieldComment(), term, 480, 220);
            if (score <= 0) {
                continue;
            }
            candidate.addScore(score);
            candidate.markMatched(tableQuery);
        }
    }

    private List<DataTable> queryTableMatches(String database, String term, int fetchLimit) {
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(database)) {
            wrapper.eq(DataTable::getDbName, database);
        }
        wrapper.and(
                        nested ->
                                nested.like(DataTable::getTableName, term)
                                        .or()
                                        .like(DataTable::getTableComment, term))
                .ne(DataTable::getStatus, "deprecated")
                .orderByAsc(DataTable::getDbName)
                .orderByAsc(DataTable::getTableName)
                .orderByAsc(DataTable::getId)
                .last("LIMIT " + Math.max(1, fetchLimit));
        return dataTableMapper.selectList(wrapper);
    }

    private List<DataField> queryFieldMatches(String database, String term, int fetchLimit) {
        List<DataField> fields =
                dataFieldMapper.selectList(
                        new LambdaQueryWrapper<DataField>()
                                .and(
                                        wrapper ->
                                                wrapper.like(DataField::getFieldName, term)
                                                        .or()
                                                        .like(DataField::getFieldComment, term))
                                .orderByAsc(DataField::getTableId)
                                .orderByAsc(DataField::getFieldOrder)
                                .orderByAsc(DataField::getId)
                                .last(
                                        "LIMIT "
                                                + Math.max(
                                                        1,
                                                        Math.min(
                                                                fetchLimit * 3,
                                                                INSPECT_FIELD_FETCH_LIMIT))));
        if (fields.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, DataTable> tableMap =
                loadTableMap(
                        fields.stream()
                                .map(DataField::getTableId)
                                .filter(Objects::nonNull)
                                .collect(Collectors.toCollection(LinkedHashSet::new)));
        return fields.stream()
                .filter(
                        field -> {
                            DataTable table = tableMap.get(field.getTableId());
                            return isSearchableTable(table, database) && isTableAllowed(table);
                        })
                .collect(Collectors.toList());
    }

    private Map<Long, DataTable> loadTableMapForFields(List<DataField> fields) {
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyMap();
        }
        return loadTableMap(
                fields.stream()
                        .map(DataField::getTableId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new)));
    }

    private Map<Long, DataTable> loadTableMap(Collection<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return dataTableMapper.selectBatchIds(tableIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DataTable::getId, item -> item));
    }

    private boolean isSearchableTable(DataTable table, String database) {
        if (table == null || table.getId() == null) {
            return false;
        }
        if ("deprecated".equalsIgnoreCase(trimToEmpty(table.getStatus()))) {
            return false;
        }
        return (!StringUtils.hasText(database) || database.equals(table.getDbName()))
                && isTableAllowed(table);
    }

    private long scoreTextMatch(String source, String term, long exactScore, long containsScore) {
        String normalizedSource = normalizeSearchToken(source);
        String normalizedTerm = normalizeSearchToken(term);
        if (!StringUtils.hasText(normalizedSource) || !StringUtils.hasText(normalizedTerm)) {
            return 0;
        }
        if (normalizedSource.equals(normalizedTerm)) {
            return exactScore;
        }
        if (normalizedSource.contains(normalizedTerm)) {
            return containsScore;
        }
        return 0;
    }

    private String normalizeSearchToken(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<Long, List<DataField>> loadFields(List<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<DataField> fields =
                dataFieldMapper.selectList(
                        new LambdaQueryWrapper<DataField>()
                                .in(DataField::getTableId, tableIds)
                                .orderByAsc(DataField::getFieldOrder)
                                .orderByAsc(DataField::getId));
        Map<Long, DataTable> tables = loadTableMap(tableIds);
        return fields.stream()
                .filter(
                        field -> {
                            DataTable table = tables.get(field.getTableId());
                            return table != null
                                    && datasourceAccess.isColumnVisible(
                                            table.getClusterId(),
                                            table.getDbName(),
                                            restrictedExtractActualTableName(
                                                    table.getDbName(), table.getTableName()),
                                            field.getFieldName());
                        })
                .collect(
                        Collectors.groupingBy(
                                DataField::getTableId, LinkedHashMap::new, Collectors.toList()));
    }

    private TableMetadataDto toAgentTableMetadata(DataTable table, List<DataField> fields) {
        TableMetadataDto metadata = new TableMetadataDto();
        metadata.setTableId(table.getId());
        metadata.setClusterId(table.getClusterId());
        metadata.setDbName(table.getDbName());
        metadata.setTableName(table.getTableName());
        metadata.setTableComment(table.getTableComment());
        metadata.setLayer(table.getLayer());
        metadata.setBusinessDomain(table.getBusinessDomain());
        metadata.setDataDomain(table.getDataDomain());
        metadata.setStatus(table.getStatus());
        metadata.setFields(
                fields.stream()
                        .map(
                                field -> {
                                    FieldMetadataDto item = new FieldMetadataDto();
                                    item.setFieldName(field.getFieldName());
                                    item.setFieldType(field.getFieldType());
                                    item.setFieldComment(field.getFieldComment());
                                    return item;
                                })
                        .collect(Collectors.toList()));
        return metadata;
    }

    private DataTable resolveDdlTable(String database, String table, Long tableId) {
        if (tableId != null) {
            DataTable item = dataTableMapper.selectById(tableId);
            if (item == null) {
                throw new IllegalArgumentException("tableId `" + tableId + "` 不存在");
            }
            if (StringUtils.hasText(database) && !database.equals(item.getDbName())) {
                throw new IllegalArgumentException("tableId 与 database 不匹配");
            }
            if (StringUtils.hasText(table)) {
                String actualTableName =
                        restrictedExtractActualTableName(item.getDbName(), item.getTableName());
                String requestedTableName = restrictedExtractActualTableName(database, table);
                if (!table.equals(item.getTableName())
                        && !requestedTableName.equals(actualTableName)) {
                    throw new IllegalArgumentException("tableId 与 table 不匹配");
                }
            }
            return item;
        }

        String actualTableName = restrictedExtractActualTableName(database, table);
        List<DataTable> matched =
                dataTableMapper.selectList(
                        new LambdaQueryWrapper<DataTable>()
                                .eq(DataTable::getDbName, database)
                                .and(
                                        wrapper ->
                                                wrapper.eq(DataTable::getTableName, table)
                                                        .or()
                                                        .eq(
                                                                DataTable::getTableName,
                                                                actualTableName)
                                                        .or()
                                                        .eq(
                                                                DataTable::getTableName,
                                                                database + "." + actualTableName))
                                .ne(DataTable::getStatus, "deprecated")
                                .orderByAsc(DataTable::getId)
                                .last("LIMIT 4"));
        matched = filterVisibleTables(matched);
        if (matched.size() > 1) {
            throw new IllegalArgumentException(
                    "database `" + database + "` 与 table `" + table + "` 命中了多张表，请改用 tableId");
        }
        return matched.isEmpty() ? null : matched.get(0);
    }

    private List<DataTable> listActiveTables(String database) {
        LambdaQueryWrapper<DataTable> wrapper =
                new LambdaQueryWrapper<DataTable>()
                        .ne(DataTable::getStatus, "deprecated")
                        .orderByAsc(DataTable::getDbName)
                        .orderByAsc(DataTable::getTableName)
                        .orderByAsc(DataTable::getId);
        if (StringUtils.hasText(database)) {
            wrapper.eq(DataTable::getDbName, database);
        }
        return filterVisibleTables(dataTableMapper.selectList(wrapper));
    }

    private List<DataTable> filterVisibleTables(List<DataTable> tables) {
        if (tables == null) {
            return Collections.emptyList();
        }
        return tables.stream().filter(this::isTableAllowed).collect(Collectors.toList());
    }

    private boolean isTableAllowed(DataTable table) {
        return table != null
                && table.getClusterId() != null
                && datasourceAccess.isVisible(
                        table.getClusterId(),
                        table.getDbName(),
                        restrictedExtractActualTableName(table.getDbName(), table.getTableName()));
    }

    private Map<String, Object> tableExportRow(DataTable table, DataField field) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", table.getId());
        row.put("cluster_id", table.getClusterId());
        row.put("db_name", table.getDbName());
        row.put("table_name", table.getTableName());
        row.put("table_comment", table.getTableComment());
        row.put("layer", table.getLayer());
        row.put("business_domain", table.getBusinessDomain());
        row.put("data_domain", table.getDataDomain());
        row.put("status", table.getStatus());
        row.put("field_name", field == null ? null : field.getFieldName());
        row.put("field_type", field == null ? null : field.getFieldType());
        row.put("field_comment", field == null ? null : field.getFieldComment());
        return row;
    }

    private String datasourceKey(Long clusterId, String database) {
        return String.valueOf(clusterId) + "::" + trimToEmpty(database);
    }

    private String inferEngineFromDatabase(String database) {
        return "opendataworks".equals(trimToEmpty(database)) ? "mysql" : "doris";
    }

    private String restrictedExtractActualTableName(String database, String tableName) {
        if (!StringUtils.hasText(tableName)) {
            return null;
        }
        String normalized = tableName.trim();
        if (normalized.contains(".")) {
            String[] parts = normalized.split("\\.", 2);
            if (parts.length == 2 && StringUtils.hasText(parts[1])) {
                return parts[1].trim();
            }
        }
        return normalized;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class SearchCandidate {
        private final DataTable table;
        private long score;
        private int matchSignals;
        private boolean tableMatched;
        private boolean keywordMatched;

        private SearchCandidate(DataTable table) {
            this.table = table;
        }

        public DataTable getTable() {
            return table;
        }

        public long getScore() {
            return score;
        }

        public int getMatchSignals() {
            return matchSignals;
        }

        private void addScore(long delta) {
            if (delta <= 0) {
                return;
            }
            score += delta;
            matchSignals += 1;
        }

        private void markMatched(boolean tableQuery) {
            if (tableQuery) {
                tableMatched = true;
                return;
            }
            keywordMatched = true;
        }

        private boolean matches(String table, String keyword) {
            if (StringUtils.hasText(table) && !tableMatched) {
                return false;
            }
            if (StringUtils.hasText(keyword) && !keywordMatched) {
                return false;
            }
            return true;
        }
    }
}
