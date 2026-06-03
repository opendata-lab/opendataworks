package com.onedata.portal.agentapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.dto.AgentFieldMetadata;
import com.onedata.portal.agentapi.dto.AgentInspectResponse;
import com.onedata.portal.agentapi.dto.AgentLineageRecord;
import com.onedata.portal.agentapi.dto.AgentLineageResponse;
import com.onedata.portal.agentapi.dto.AgentTableMetadata;
import com.onedata.portal.agentapi.dto.AgentTableDdlResponse;
import com.onedata.portal.agentapi.scope.AgentDataScopeContext;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.DorisDbUser;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.DorisDbUserMapper;
import com.onedata.portal.service.LineageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class BackendAgentMetadataService implements AgentMetadataService {

    /** 元数据导出行数安全阀，防止无界导出撑爆 agent 工具结果缓冲。 */
    private static final int EXPORT_MAX_ROWS = 5000;
    private static final int INSPECT_FETCH_LIMIT = 800;
    private static final int INSPECT_FIELD_FETCH_LIMIT = 2400;
    private static final int INSPECT_LINEAGE_LIMIT = 120;
    private static final int LINEAGE_FETCH_LIMIT = 400;
    private static final int DDL_TIMEOUT_SECONDS = 20;

    private final DataTableMapper dataTableMapper;
    private final DataFieldMapper dataFieldMapper;
    private final DataLineageMapper dataLineageMapper;
    private final DorisClusterMapper dorisClusterMapper;
    private final DorisDbUserMapper dorisDbUserMapper;
    private final LineageService lineageService;
    private final DataSourceProperties dataSourceProperties;
    private final AgentJdbcExecutor agentJdbcExecutor;

    @Override
    public AgentInspectResponse inspect(String database, String table, String keyword, int tableLimit) {
        String normalizedDatabase = trimToNull(database);
        String normalizedTable = trimToNull(table);
        String normalizedKeyword = trimToNull(keyword);
        int safeTableLimit = Math.max(1, Math.min(tableLimit, 100));

        List<DataTable> matchedTables = findTables(normalizedDatabase, normalizedTable, normalizedKeyword, INSPECT_FETCH_LIMIT);
        List<DataTable> selectedTables = matchedTables.subList(0, Math.min(safeTableLimit, matchedTables.size()));
        List<Long> selectedIds = selectedTables.stream()
                .map(DataTable::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        Map<Long, List<DataField>> fieldMap = loadFields(selectedIds);

        AgentInspectResponse response = new AgentInspectResponse();
        response.setDatabase(normalizedDatabase);
        response.setTable(normalizedTable);
        response.setKeyword(normalizedKeyword);
        response.setTableCount(selectedTables.size());
        response.setTables(selectedTables.stream()
                .map(tableItem -> toAgentTableMetadata(tableItem, fieldMap.getOrDefault(tableItem.getId(), Collections.emptyList())))
                .collect(Collectors.toList()));
        response.setLineage(loadLineageRecordsForTableIds(selectedIds, INSPECT_LINEAGE_LIMIT));
        response.setError(null);
        return response;
    }

    @Override
    public AgentLineageResponse lineage(String table, String dbName, Long tableId, Integer depth) {
        String normalizedTable = trimToNull(table);
        String normalizedDbName = trimToNull(dbName);
        if (tableId == null && !StringUtils.hasText(normalizedTable)) {
            throw new IllegalArgumentException("table 或 tableId 至少提供一个");
        }

        List<DataTable> targets = filterTablesByScope(resolveLineageTargets(normalizedTable, normalizedDbName, tableId));
        if (tableId != null && targets.isEmpty()) {
            throw new IllegalArgumentException("数据范围限制: 未授权访问 tableId `" + tableId + "`");
        }
        AgentLineageResponse response = new AgentLineageResponse();
        response.setDbName(normalizedDbName);
        response.setTable(normalizedTable);
        response.setTableId(tableId);
        response.setDepth(depth);
        response.setError(null);
        if (targets.isEmpty()) {
            response.setLineage(Collections.emptyList());
            return response;
        }

        boolean graphScoped = targets.size() == 1;
        Set<Long> targetIds = targets.stream()
                .map(DataTable::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> visibleIds = new LinkedHashSet<>(targetIds);

        if (graphScoped) {
            DataTable center = targets.get(0);
            response.setDbName(StringUtils.hasText(normalizedDbName) ? normalizedDbName : center.getDbName());
            response.setTable(StringUtils.hasText(normalizedTable) ? normalizedTable : center.getTableName());
            response.setTableId(center.getId());
            LineageService.LineageGraph graph = lineageService.getLineageGraph(
                    null,
                    null,
                    null,
                    null,
                    center.getClusterId(),
                    center.getDbName(),
                    center.getId(),
                    depth
            );
            if (graph != null && graph.getNodes() != null) {
                graph.getNodes().stream()
                        .map(LineageService.LineageNode::getTableId)
                        .filter(Objects::nonNull)
                        .forEach(visibleIds::add);
            }
        }

        response.setLineage(loadLineageRecords(visibleIds, targetIds, LINEAGE_FETCH_LIMIT, graphScoped));
        return response;
    }

    @Override
    public AgentDatasourceResolution resolveDatasource(String database, String preferredEngine) {
        String targetDatabase = trimToNull(database);
        if (!StringUtils.hasText(targetDatabase)) {
            throw new IllegalArgumentException("database 不能为空");
        }

        String normalizedPreferredEngine = trimToNull(preferredEngine);
        if (StringUtils.hasText(normalizedPreferredEngine)) {
            normalizedPreferredEngine = normalizedPreferredEngine.toLowerCase(Locale.ROOT);
        }

        JdbcMysqlTarget platformMysql = parsePlatformMysql(dataSourceProperties.getUrl());
        if (platformMysql != null && targetDatabase.equals(platformMysql.getDatabase())) {
            AgentDataScopeContext.requireAllowed(null, targetDatabase);
            ensurePreferredEngine(targetDatabase, normalizedPreferredEngine, "mysql");
            AgentDatasourceResolution response = new AgentDatasourceResolution();
            response.setEngine("mysql");
            response.setDatabase(targetDatabase);
            response.setHost(platformMysql.getHost());
            response.setPort(platformMysql.getPort());
            response.setUser(trimToEmpty(dataSourceProperties.getUsername()));
            response.setPassword(trimToEmpty(dataSourceProperties.getPassword()));
            response.setSourceType("MYSQL");
            response.setClusterId(null);
            response.setClusterName("platform-mysql");
            response.setResolvedBy("platform_runtime");
            return response;
        }

        Long clusterId = resolveClusterIdFromTables(targetDatabase);
        String resolvedBy = "data_table";
        if (clusterId == null) {
            clusterId = resolveClusterIdFromDbUsers(targetDatabase);
            resolvedBy = "doris_database_users";
        }
        if (clusterId == null) {
            throw new IllegalArgumentException("未在 opendataworks 中找到 database `" + targetDatabase + "` 的数据源");
        }

        DorisCluster cluster = dorisClusterMapper.selectById(clusterId);
        if (cluster == null) {
            throw new IllegalArgumentException("cluster_id `" + clusterId + "` 不存在");
        }
        AgentDataScopeContext.requireAllowed(clusterId, targetDatabase);

        String sourceType = normalizeSourceType(cluster.getSourceType());
        String engine = "MYSQL".equals(sourceType) ? "mysql" : "doris";
        ensurePreferredEngine(targetDatabase, normalizedPreferredEngine, engine);

        String user = trimToEmpty(cluster.getUsername());
        String password = trimToEmpty(cluster.getPassword());
        if ("doris".equals(engine)) {
            List<DorisDbUser> matchedUsers = dorisDbUserMapper.selectList(
                    new LambdaQueryWrapper<DorisDbUser>()
                            .eq(DorisDbUser::getClusterId, clusterId)
                            .eq(DorisDbUser::getDatabaseName, targetDatabase)
                            .last("LIMIT 2")
            );
            if (matchedUsers.size() > 1) {
                throw new IllegalArgumentException("database `" + targetDatabase + "` 命中了多个只读账号");
            }
            if (!matchedUsers.isEmpty() && StringUtils.hasText(matchedUsers.get(0).getReadonlyUsername())) {
                user = matchedUsers.get(0).getReadonlyUsername().trim();
                password = trimToEmpty(matchedUsers.get(0).getReadonlyPassword());
                resolvedBy = "readonly_user";
            }
        }

        AgentDatasourceResolution response = new AgentDatasourceResolution();
        response.setEngine(engine);
        response.setDatabase(targetDatabase);
        response.setHost(trimToEmpty(cluster.getFeHost()));
        response.setPort(cluster.getFePort() != null ? cluster.getFePort() : ("mysql".equals(engine) ? 3306 : 9030));
        response.setUser(user);
        response.setPassword(password);
        response.setSourceType(sourceType);
        response.setClusterId(cluster.getId());
        response.setClusterName(trimToEmpty(cluster.getClusterName()));
        response.setResolvedBy(resolvedBy);
        return response;
    }

    @Override
    public AgentTableDdlResponse ddl(String database, String table, Long tableId) {
        String normalizedDatabase = trimToNull(database);
        String normalizedTable = trimToNull(table);
        if (tableId == null && (!StringUtils.hasText(normalizedDatabase) || !StringUtils.hasText(normalizedTable))) {
            throw new IllegalArgumentException("tableId 或 database + table 至少提供一组");
        }

        DataTable matchedTable = resolveDdlTable(normalizedDatabase, normalizedTable, tableId);
        String targetDatabase = normalizedDatabase;
        String rawTableName = normalizedTable;
        if (matchedTable != null) {
            AgentDataScopeContext.requireAllowed(matchedTable.getClusterId(), matchedTable.getDbName());
            if (StringUtils.hasText(matchedTable.getDbName())) {
                targetDatabase = matchedTable.getDbName();
            }
            rawTableName = matchedTable.getTableName();
        }
        String actualTableName = extractActualTableName(targetDatabase, rawTableName);
        if (!StringUtils.hasText(targetDatabase) || !StringUtils.hasText(actualTableName)) {
            throw new IllegalArgumentException("database 或 table 无法确定");
        }

        AgentDatasourceResolution datasource = resolveDatasource(targetDatabase, null);
        String ddl = agentJdbcExecutor.fetchTableDdl(datasource, targetDatabase, actualTableName, DDL_TIMEOUT_SECONDS);

        AgentTableDdlResponse response = new AgentTableDdlResponse();
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
            response.setFields(loadFields(Collections.singletonList(matchedTable.getId()))
                    .getOrDefault(matchedTable.getId(), Collections.emptyList())
                    .stream()
                    .map(field -> {
                        AgentFieldMetadata item = new AgentFieldMetadata();
                        item.setFieldName(field.getFieldName());
                        item.setFieldType(field.getFieldType());
                        item.setFieldComment(field.getFieldComment());
                        return item;
                    })
                    .collect(Collectors.toList()));
        }
        return response;
    }

    @Override
    public List<Map<String, Object>> exportTables(String database) {
        List<DataTable> tables = listActiveTables(trimToNull(database));
        Map<Long, List<DataField>> fieldMap = loadFields(
                tables.stream().map(DataTable::getId).filter(Objects::nonNull).collect(Collectors.toList())
        );

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

    @Override
    public List<Map<String, Object>> exportLineage(String database) {
        String normalizedDatabase = trimToNull(database);
        List<DataLineage> lineages = dataLineageMapper.selectList(
                new LambdaQueryWrapper<DataLineage>().orderByAsc(DataLineage::getId)
        );
        Map<Long, DataTable> tableMap = loadTableMapFromLineages(lineages);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DataLineage lineage : lineages) {
            DataTable upstream = tableMap.get(lineage.getUpstreamTableId());
            DataTable downstream = tableMap.get(lineage.getDownstreamTableId());
            if (!isTableAllowed(upstream) && !isTableAllowed(downstream)) {
                continue;
            }
            if (StringUtils.hasText(normalizedDatabase)) {
                String upstreamDb = upstream == null ? null : upstream.getDbName();
                String downstreamDb = downstream == null ? null : downstream.getDbName();
                if (!normalizedDatabase.equals(upstreamDb) && !normalizedDatabase.equals(downstreamDb)) {
                    continue;
                }
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", lineage.getId());
            row.put("lineage_type", lineage.getLineageType());
            row.put("upstream_db", upstream == null ? null : upstream.getDbName());
            row.put("upstream_table", upstream == null ? null : upstream.getTableName());
            row.put("downstream_db", downstream == null ? null : downstream.getDbName());
            row.put("downstream_table", downstream == null ? null : downstream.getTableName());
            rows.add(row);
        }
        return capExportRows(rows, "lineage");
    }

    @Override
    public List<Map<String, Object>> exportDatasource(String database) {
        List<DataTable> tables = listActiveTables(trimToNull(database));
        Set<Long> clusterIds = tables.stream()
                .map(DataTable::getClusterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, DorisCluster> clusterMap = clusterIds.isEmpty()
                ? Collections.emptyMap()
                : dorisClusterMapper.selectBatchIds(clusterIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DorisCluster::getId, item -> item));
        Map<String, DorisDbUser> dbUserMap = loadReadonlyUserMap(tables);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DataTable table : tables) {
            DorisCluster cluster = clusterMap.get(table.getClusterId());
            DorisDbUser dbUser = dbUserMap.get(datasourceKey(table.getClusterId(), table.getDbName()));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("db_name", table.getDbName());
            row.put("cluster_id", table.getClusterId());
            row.put("engine", cluster == null ? inferEngineFromDatabase(table.getDbName()) : engineForSourceType(cluster.getSourceType()));
            row.put("source_type", cluster == null ? null : normalizeSourceType(cluster.getSourceType()));
            row.put("cluster_name", cluster == null ? "platform-mysql" : trimToEmpty(cluster.getClusterName()));
            row.put("resolved_by", cluster == null ? "platform_runtime" : (dbUser == null ? "data_table" : "readonly_user"));
            rows.add(row);
        }
        return capExportRows(rows, "datasource");
    }

    private List<Map<String, Object>> capExportRows(List<Map<String, Object>> rows, String exportKind) {
        if (rows == null || rows.size() <= EXPORT_MAX_ROWS) {
            return rows;
        }
        log.warn("agent metadata export '{}' truncated from {} to {} rows", exportKind, rows.size(), EXPORT_MAX_ROWS);
        return new ArrayList<>(rows.subList(0, EXPORT_MAX_ROWS));
    }

    private List<DataTable> findTables(String database, String table, String keyword, int fetchLimit) {
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
                .sorted(Comparator
                        .comparingLong(SearchCandidate::getScore).reversed()
                        .thenComparing(Comparator.comparingInt(SearchCandidate::getMatchSignals).reversed())
                        .thenComparing(candidate -> trimToEmpty(candidate.getTable().getDbName()))
                        .thenComparing(candidate -> trimToEmpty(candidate.getTable().getTableName()))
                        .thenComparing(candidate -> candidate.getTable().getId(), Comparator.nullsLast(Long::compareTo)))
                .limit(Math.max(1, fetchLimit))
                .map(SearchCandidate::getTable)
                .collect(Collectors.toList());
    }

    private void indexTableMatches(
            Map<Long, SearchCandidate> candidates,
            String database,
            String term,
            boolean tableQuery,
            int fetchLimit
    ) {
        if (!StringUtils.hasText(term)) {
            return;
        }
        List<DataTable> tables = queryTableMatches(database, term, fetchLimit);
        for (DataTable item : tables) {
            if (item == null || item.getId() == null) {
                continue;
            }
            long score = scoreTextMatch(item.getTableName(), term, 1600, 900)
                    + scoreTextMatch(item.getTableComment(), term, 1000, 520);
            if (score <= 0) {
                continue;
            }
            SearchCandidate candidate = candidates.computeIfAbsent(item.getId(), ignored -> new SearchCandidate(item));
            candidate.addScore(score);
            candidate.markMatched(tableQuery);
        }
    }

    private void indexFieldMatches(
            Map<Long, SearchCandidate> candidates,
            String database,
            String term,
            boolean tableQuery,
            int fetchLimit
    ) {
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
            SearchCandidate candidate = candidates.computeIfAbsent(table.getId(), ignored -> new SearchCandidate(table));
            long score = scoreTextMatch(field.getFieldName(), term, 700, 320)
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
        wrapper.and(nested -> nested.like(DataTable::getTableName, term)
                        .or().like(DataTable::getTableComment, term))
                .ne(DataTable::getStatus, "deprecated")
                .orderByAsc(DataTable::getDbName)
                .orderByAsc(DataTable::getTableName)
                .orderByAsc(DataTable::getId)
                .last("LIMIT " + Math.max(1, fetchLimit));
        return dataTableMapper.selectList(wrapper);
    }

    private List<DataField> queryFieldMatches(String database, String term, int fetchLimit) {
        List<DataField> fields = dataFieldMapper.selectList(
                new LambdaQueryWrapper<DataField>()
                        .and(wrapper -> wrapper.like(DataField::getFieldName, term)
                                .or().like(DataField::getFieldComment, term))
                        .orderByAsc(DataField::getTableId)
                        .orderByAsc(DataField::getFieldOrder)
                        .orderByAsc(DataField::getId)
                        .last("LIMIT " + Math.max(1, Math.min(fetchLimit * 3, INSPECT_FIELD_FETCH_LIMIT)))
        );
        if (fields.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, DataTable> tableMap = loadTableMap(
                fields.stream().map(DataField::getTableId).filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new))
        );
        return fields.stream()
                .filter(field -> {
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
                fields.stream().map(DataField::getTableId).filter(Objects::nonNull).collect(Collectors.toCollection(LinkedHashSet::new))
        );
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
        return (!StringUtils.hasText(database) || database.equals(table.getDbName())) && isTableAllowed(table);
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
        List<DataField> fields = dataFieldMapper.selectList(
                new LambdaQueryWrapper<DataField>()
                        .in(DataField::getTableId, tableIds)
                        .orderByAsc(DataField::getFieldOrder)
                        .orderByAsc(DataField::getId)
        );
        return fields.stream().collect(Collectors.groupingBy(
                DataField::getTableId,
                LinkedHashMap::new,
                Collectors.toList()
        ));
    }

    private AgentTableMetadata toAgentTableMetadata(DataTable table, List<DataField> fields) {
        AgentTableMetadata metadata = new AgentTableMetadata();
        metadata.setTableId(table.getId());
        metadata.setClusterId(table.getClusterId());
        metadata.setDbName(table.getDbName());
        metadata.setTableName(table.getTableName());
        metadata.setTableComment(table.getTableComment());
        metadata.setFields(fields.stream().map(field -> {
            AgentFieldMetadata item = new AgentFieldMetadata();
            item.setFieldName(field.getFieldName());
            item.setFieldType(field.getFieldType());
            item.setFieldComment(field.getFieldComment());
            return item;
        }).collect(Collectors.toList()));
        return metadata;
    }

    private List<AgentLineageRecord> loadLineageRecordsForTableIds(List<Long> tableIds, int limit) {
        if (tableIds == null || tableIds.isEmpty()) {
            return Collections.emptyList();
        }
        return loadLineageRecords(new LinkedHashSet<>(tableIds), new LinkedHashSet<>(tableIds), limit, true);
    }

    private List<AgentLineageRecord> loadLineageRecords(
            Set<Long> visibleIds,
            Set<Long> targetIds,
            int limit,
            boolean graphScoped
    ) {
        if (visibleIds == null || visibleIds.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<DataLineage> wrapper = new LambdaQueryWrapper<DataLineage>()
                .and(nested -> nested.in(DataLineage::getUpstreamTableId, visibleIds)
                        .or().in(DataLineage::getDownstreamTableId, visibleIds))
                .orderByAsc(DataLineage::getId)
                .last("LIMIT " + Math.max(1, limit));

        if (!graphScoped && targetIds != null && !targetIds.isEmpty()) {
            wrapper.and(nested -> nested.in(DataLineage::getUpstreamTableId, targetIds)
                    .or().in(DataLineage::getDownstreamTableId, targetIds));
        }

        List<DataLineage> lineages = dataLineageMapper.selectList(wrapper);
        if (graphScoped) {
            lineages = lineages.stream()
                    .filter(item -> visibleIds.contains(item.getUpstreamTableId()) || visibleIds.contains(item.getDownstreamTableId()))
                    .collect(Collectors.toList());
        }
        return toLineageRecords(lineages);
    }

    private List<AgentLineageRecord> toLineageRecords(List<DataLineage> lineages) {
        if (lineages == null || lineages.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, DataTable> tableMap = loadTableMapFromLineages(lineages);
        List<AgentLineageRecord> records = new ArrayList<>();
        for (DataLineage lineage : lineages) {
            DataTable upstream = tableMap.get(lineage.getUpstreamTableId());
            DataTable downstream = tableMap.get(lineage.getDownstreamTableId());
            AgentLineageRecord record = new AgentLineageRecord();
            record.setId(lineage.getId());
            record.setLineageType(lineage.getLineageType());
            if (!isTableAllowed(upstream) && !isTableAllowed(downstream)) {
                continue;
            }
            record.setUpstreamDb(upstream == null ? null : upstream.getDbName());
            record.setUpstreamTable(upstream == null ? null : upstream.getTableName());
            record.setDownstreamDb(downstream == null ? null : downstream.getDbName());
            record.setDownstreamTable(downstream == null ? null : downstream.getTableName());
            records.add(record);
        }
        return records;
    }

    private Map<Long, DataTable> loadTableMapFromLineages(List<DataLineage> lineages) {
        Set<Long> tableIds = new LinkedHashSet<>();
        for (DataLineage lineage : lineages) {
            if (lineage.getUpstreamTableId() != null) {
                tableIds.add(lineage.getUpstreamTableId());
            }
            if (lineage.getDownstreamTableId() != null) {
                tableIds.add(lineage.getDownstreamTableId());
            }
        }
        if (tableIds.isEmpty()) {
            return Collections.emptyMap();
        }
        return dataTableMapper.selectBatchIds(tableIds).stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(DataTable::getId, item -> item));
    }

    private List<DataTable> resolveLineageTargets(String table, String dbName, Long tableId) {
        if (tableId != null) {
            DataTable target = dataTableMapper.selectById(tableId);
            if (target == null) {
                return Collections.emptyList();
            }
            if (StringUtils.hasText(dbName) && !dbName.equals(target.getDbName())) {
                return Collections.emptyList();
            }
            return Collections.singletonList(target);
        }

        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<DataTable>()
                .eq(DataTable::getTableName, table)
                .ne(DataTable::getStatus, "deprecated")
                .orderByAsc(DataTable::getId)
                .last("LIMIT 10");
        if (StringUtils.hasText(dbName)) {
            wrapper.eq(DataTable::getDbName, dbName);
        }
        return filterTablesByScope(dataTableMapper.selectList(wrapper));
    }

    private Long resolveClusterIdFromTables(String database) {
        List<DataTable> tables = dataTableMapper.selectList(
                new LambdaQueryWrapper<DataTable>()
                        .eq(DataTable::getDbName, database)
                        .ne(DataTable::getStatus, "deprecated")
                        .last("LIMIT 8")
        );
        Set<Long> clusterIds = tables.stream()
                .map(DataTable::getClusterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (clusterIds.size() > 1) {
            throw new IllegalArgumentException("database `" + database + "` 命中了多个 cluster_id");
        }
        return clusterIds.stream().findFirst().orElse(null);
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
                String actualTableName = extractActualTableName(item.getDbName(), item.getTableName());
                String requestedTableName = extractActualTableName(database, table);
                if (!table.equals(item.getTableName()) && !requestedTableName.equals(actualTableName)) {
                    throw new IllegalArgumentException("tableId 与 table 不匹配");
                }
            }
            return item;
        }

        String actualTableName = extractActualTableName(database, table);
        List<DataTable> matched = dataTableMapper.selectList(
                new LambdaQueryWrapper<DataTable>()
                        .eq(DataTable::getDbName, database)
                        .and(wrapper -> wrapper.eq(DataTable::getTableName, table)
                                .or().eq(DataTable::getTableName, actualTableName)
                                .or().eq(DataTable::getTableName, database + "." + actualTableName))
                        .ne(DataTable::getStatus, "deprecated")
                        .orderByAsc(DataTable::getId)
                        .last("LIMIT 4")
        );
        matched = filterTablesByScope(matched);
        if (matched.size() > 1) {
            throw new IllegalArgumentException("database `" + database + "` 与 table `" + table + "` 命中了多张表，请改用 tableId");
        }
        return matched.isEmpty() ? null : matched.get(0);
    }

    private Long resolveClusterIdFromDbUsers(String database) {
        List<DorisDbUser> dbUsers = dorisDbUserMapper.selectList(
                new LambdaQueryWrapper<DorisDbUser>()
                        .eq(DorisDbUser::getDatabaseName, database)
                        .last("LIMIT 8")
        );
        Set<Long> clusterIds = dbUsers.stream()
                .map(DorisDbUser::getClusterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (clusterIds.size() > 1) {
            throw new IllegalArgumentException("database `" + database + "` 在 doris_database_users 中命中了多个 cluster_id");
        }
        return clusterIds.stream().findFirst().orElse(null);
    }

    private List<DataTable> listActiveTables(String database) {
        LambdaQueryWrapper<DataTable> wrapper = new LambdaQueryWrapper<DataTable>()
                .ne(DataTable::getStatus, "deprecated")
                .orderByAsc(DataTable::getDbName)
                .orderByAsc(DataTable::getTableName)
                .orderByAsc(DataTable::getId);
        if (StringUtils.hasText(database)) {
            wrapper.eq(DataTable::getDbName, database);
        }
        return filterTablesByScope(dataTableMapper.selectList(wrapper));
    }

    private List<DataTable> filterTablesByScope(List<DataTable> tables) {
        if (!AgentDataScopeContext.isActive()) {
            return tables;
        }
        if (tables == null || tables.isEmpty()) {
            return Collections.emptyList();
        }
        return tables.stream().filter(this::isTableAllowed).collect(Collectors.toList());
    }

    private boolean isTableAllowed(DataTable table) {
        if (!AgentDataScopeContext.isActive()) {
            return true;
        }
        return table != null && AgentDataScopeContext.isAllowed(table.getClusterId(), table.getDbName());
    }

    private Map<String, DorisDbUser> loadReadonlyUserMap(List<DataTable> tables) {
        Set<Long> clusterIds = tables.stream()
                .map(DataTable::getClusterId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> databases = tables.stream()
                .map(DataTable::getDbName)
                .filter(StringUtils::hasText)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (clusterIds.isEmpty() || databases.isEmpty()) {
            return Collections.emptyMap();
        }
        List<DorisDbUser> users = dorisDbUserMapper.selectList(
                new LambdaQueryWrapper<DorisDbUser>()
                        .in(DorisDbUser::getClusterId, clusterIds)
                        .in(DorisDbUser::getDatabaseName, databases)
        );
        Map<String, DorisDbUser> results = new LinkedHashMap<>();
        for (DorisDbUser user : users) {
            results.putIfAbsent(datasourceKey(user.getClusterId(), user.getDatabaseName()), user);
        }
        return results;
    }

    private Map<String, Object> tableExportRow(DataTable table, DataField field) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", table.getId());
        row.put("cluster_id", table.getClusterId());
        row.put("db_name", table.getDbName());
        row.put("table_name", table.getTableName());
        row.put("table_comment", table.getTableComment());
        row.put("field_name", field == null ? null : field.getFieldName());
        row.put("field_type", field == null ? null : field.getFieldType());
        row.put("field_comment", field == null ? null : field.getFieldComment());
        return row;
    }

    private void ensurePreferredEngine(String database, String preferredEngine, String actualEngine) {
        if (StringUtils.hasText(preferredEngine) && !preferredEngine.equals(actualEngine)) {
            throw new IllegalArgumentException("database `" + database + "` 与 " + preferredEngine + " 引擎不匹配");
        }
    }

    private String datasourceKey(Long clusterId, String database) {
        return String.valueOf(clusterId) + "::" + trimToEmpty(database);
    }

    private String inferEngineFromDatabase(String database) {
        return "opendataworks".equals(trimToEmpty(database)) ? "mysql" : "doris";
    }

    private String engineForSourceType(String sourceType) {
        return "MYSQL".equals(normalizeSourceType(sourceType)) ? "mysql" : "doris";
    }

    private String extractActualTableName(String database, String tableName) {
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

    private String normalizeSourceType(String sourceType) {
        String normalized = trimToEmpty(sourceType).toUpperCase(Locale.ROOT);
        return StringUtils.hasText(normalized) ? normalized : "DORIS";
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

    private JdbcMysqlTarget parsePlatformMysql(String jdbcUrl) {
        if (!StringUtils.hasText(jdbcUrl) || !jdbcUrl.startsWith("jdbc:")) {
            return null;
        }
        try {
            String uriText = jdbcUrl.substring("jdbc:".length());
            URI uri = URI.create(uriText);
            String path = uri.getPath();
            String database = path == null ? "" : path.replaceFirst("^/", "");
            if (!StringUtils.hasText(database)) {
                return null;
            }
            JdbcMysqlTarget target = new JdbcMysqlTarget();
            target.setHost(StringUtils.hasText(uri.getHost()) ? uri.getHost() : "localhost");
            target.setPort(uri.getPort() > 0 ? uri.getPort() : 3306);
            target.setDatabase(database);
            return target;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static class JdbcMysqlTarget {
        private String host;
        private Integer port;
        private String database;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }
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
