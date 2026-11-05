package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.dto.LineageInspectionResponse;
import com.onedata.portal.dto.LineageRecordDto;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;

import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.stream.Collectors;

/** 血缘关系服务 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LineageService {
    private final DatasourceSubjectAccessService datasourceAccess;

    private final DataLineageMapper dataLineageMapper;
    private final DataTableMapper dataTableMapper;

    /** 获取血缘图数据 */
    public LineageGraph getLineageGraph() {
        return getLineageGraph(null, null, null, null, null, null, null, null);
    }

    /** 获取血缘图数据（支持筛选） */
    public LineageGraph getLineageGraph(
            String layer, String businessDomain, String dataDomain, String keyword) {
        return getLineageGraph(layer, businessDomain, dataDomain, keyword, null, null, null, null);
    }

    /** 获取血缘图数据（支持数据源/schema/中心表/层级筛选） */
    public LineageGraph getLineageGraph(
            String layer,
            String businessDomain,
            String dataDomain,
            String keyword,
            Long clusterId,
            String dbName,
            Long centerTableId,
            Integer depth) {
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(layer)) {
            tableWrapper.eq(DataTable::getLayer, layer.toUpperCase());
        }
        if (StringUtils.hasText(businessDomain)) {
            tableWrapper.eq(DataTable::getBusinessDomain, businessDomain);
        }
        if (StringUtils.hasText(dataDomain)) {
            tableWrapper.eq(DataTable::getDataDomain, dataDomain);
        }
        if (clusterId != null) {
            tableWrapper.eq(DataTable::getClusterId, clusterId);
        }
        if (StringUtils.hasText(dbName)) {
            tableWrapper.eq(DataTable::getDbName, dbName.trim());
        }
        if (StringUtils.hasText(keyword)) {
            String trimmedKeyword = keyword.trim();
            tableWrapper.and(
                    w ->
                            w.like(DataTable::getTableName, trimmedKeyword)
                                    .or()
                                    .like(DataTable::getTableComment, trimmedKeyword));
        }
        tableWrapper.ne(DataTable::getStatus, "deprecated");

        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);
        if (tables.isEmpty()) {
            return emptyGraph();
        }

        Map<Long, DataTable> tableMap =
                tables.stream().collect(Collectors.toMap(DataTable::getId, t -> t));

        if (centerTableId != null && !tableMap.containsKey(centerTableId)) {
            return emptyGraph();
        }

        Set<Long> tableIds = new HashSet<>(tableMap.keySet());

        List<DataLineage> lineages =
                dataLineageMapper.selectList(
                        new LambdaQueryWrapper<DataLineage>()
                                .and(
                                        wrapper ->
                                                wrapper.in(
                                                                DataLineage::getUpstreamTableId,
                                                                tableIds)
                                                        .or()
                                                        .in(
                                                                DataLineage::getDownstreamTableId,
                                                                tableIds)));

        List<EdgeRecord> allEdges = buildEdges(lineages, tableIds);
        Set<Long> visibleTableIds =
                centerTableId == null
                        ? new LinkedHashSet<>(tableIds)
                        : collectReachableTableIds(centerTableId, allEdges, depth);

        List<LineageNode> nodes =
                visibleTableIds.stream()
                        .map(tableMap::get)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(table -> String.valueOf(table.getTableName())))
                        .map(this::buildLineageNode)
                        .collect(Collectors.toList());

        List<LineageEdge> edges =
                allEdges.stream()
                        .filter(
                                edge ->
                                        visibleTableIds.contains(edge.sourceTableId)
                                                && visibleTableIds.contains(edge.targetTableId))
                        .map(
                                edge -> {
                                    LineageEdge lineageEdge = new LineageEdge();
                                    lineageEdge.setSource(String.valueOf(edge.sourceTableId));
                                    lineageEdge.setTarget(String.valueOf(edge.targetTableId));
                                    lineageEdge.setTaskId(edge.taskId);
                                    return lineageEdge;
                                })
                        .collect(Collectors.toList());

        if (centerTableId != null
                && nodes.stream()
                        .noneMatch(node -> String.valueOf(centerTableId).equals(node.getId()))) {
            DataTable centerTable = tableMap.get(centerTableId);
            if (centerTable != null) {
                nodes.add(buildLineageNode(centerTable));
            }
        }

        LineageGraph graph = new LineageGraph();
        graph.setNodes(nodes);
        graph.setEdges(edges);
        return graph;
    }

    private List<EdgeRecord> buildEdges(List<DataLineage> lineages, Set<Long> validTableIds) {
        if (lineages == null
                || lineages.isEmpty()
                || validTableIds == null
                || validTableIds.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, List<DataLineage>> lineagesByTask =
                lineages.stream()
                        .filter(lineage -> lineage.getTaskId() != null)
                        .collect(Collectors.groupingBy(DataLineage::getTaskId));

        List<EdgeRecord> edges = new ArrayList<>();
        for (Map.Entry<Long, List<DataLineage>> entry : lineagesByTask.entrySet()) {
            Long taskId = entry.getKey();
            List<DataLineage> taskLineages = entry.getValue();
            Set<String> uniquePairs = new HashSet<>();

            // 兼容直接表级血缘（lineage_type=table）或已有上下游都写入同一行的数据
            for (DataLineage lineage : taskLineages) {
                Long upstreamId = lineage.getUpstreamTableId();
                Long downstreamId = lineage.getDownstreamTableId();
                if (upstreamId == null || downstreamId == null) {
                    continue;
                }
                if (!validTableIds.contains(upstreamId) || !validTableIds.contains(downstreamId)) {
                    continue;
                }
                String key = upstreamId + "->" + downstreamId;
                if (!uniquePairs.add(key)) {
                    continue;
                }
                edges.add(new EdgeRecord(upstreamId, downstreamId, taskId));
            }

            Set<Long> inputTableIds =
                    taskLineages.stream()
                            .filter(
                                    l ->
                                            "input".equals(l.getLineageType())
                                                    && l.getUpstreamTableId() != null)
                            .map(DataLineage::getUpstreamTableId)
                            .filter(validTableIds::contains)
                            .collect(Collectors.toSet());

            Set<Long> outputTableIds =
                    taskLineages.stream()
                            .filter(
                                    l ->
                                            "output".equals(l.getLineageType())
                                                    && l.getDownstreamTableId() != null)
                            .map(DataLineage::getDownstreamTableId)
                            .filter(validTableIds::contains)
                            .collect(Collectors.toSet());

            for (Long upstreamId : inputTableIds) {
                for (Long downstreamId : outputTableIds) {
                    String key = upstreamId + "->" + downstreamId;
                    if (!uniquePairs.add(key)) {
                        continue;
                    }
                    edges.add(new EdgeRecord(upstreamId, downstreamId, taskId));
                }
            }
        }
        return edges;
    }

    private Set<Long> collectReachableTableIds(
            Long centerTableId, List<EdgeRecord> edges, Integer depth) {
        if (centerTableId == null) {
            return Collections.emptySet();
        }
        int maxDepth = normalizeDepth(depth);

        Map<Long, Set<Long>> outgoing = new HashMap<>();
        Map<Long, Set<Long>> incoming = new HashMap<>();
        for (EdgeRecord edge : edges) {
            outgoing.computeIfAbsent(edge.sourceTableId, key -> new LinkedHashSet<>())
                    .add(edge.targetTableId);
            incoming.computeIfAbsent(edge.targetTableId, key -> new LinkedHashSet<>())
                    .add(edge.sourceTableId);
        }

        Set<Long> upstreamReachable = traverseDirectional(centerTableId, incoming, maxDepth);
        Set<Long> downstreamReachable = traverseDirectional(centerTableId, outgoing, maxDepth);

        Set<Long> result = new LinkedHashSet<>();
        result.addAll(upstreamReachable);
        result.addAll(downstreamReachable);
        return result;
    }

    private Set<Long> traverseDirectional(
            Long centerTableId, Map<Long, Set<Long>> adjacency, int maxDepth) {
        Set<Long> visited = new LinkedHashSet<>();
        visited.add(centerTableId);
        Set<Long> frontier = new LinkedHashSet<>();
        frontier.add(centerTableId);

        int currentDepth = 0;
        while (!frontier.isEmpty() && (maxDepth < 0 || currentDepth < maxDepth)) {
            Set<Long> nextFrontier = new LinkedHashSet<>();
            for (Long current : frontier) {
                nextFrontier.addAll(adjacency.getOrDefault(current, Collections.emptySet()));
            }
            nextFrontier.removeAll(visited);
            if (nextFrontier.isEmpty()) {
                break;
            }
            visited.addAll(nextFrontier);
            frontier = nextFrontier;
            currentDepth++;
        }
        return visited;
    }

    private int normalizeDepth(Integer depth) {
        if (depth == null) {
            return 1;
        }
        if (depth <= 0) {
            return -1;
        }
        return depth;
    }

    private LineageGraph emptyGraph() {
        LineageGraph graph = new LineageGraph();
        graph.setNodes(Collections.emptyList());
        graph.setEdges(Collections.emptyList());
        return graph;
    }

    private LineageNode buildLineageNode(DataTable table) {
        LineageNode node = new LineageNode();
        node.setId(String.valueOf(table.getId()));
        node.setTableId(table.getId());
        node.setName(table.getTableName());
        node.setLayer(table.getLayer());
        node.setComment(table.getTableComment());
        node.setBusinessDomain(table.getBusinessDomain());
        node.setDataDomain(table.getDataDomain());
        node.setClusterId(table.getClusterId());
        node.setDbName(table.getDbName());
        return node;
    }

    private static class EdgeRecord {
        private final Long sourceTableId;
        private final Long targetTableId;
        private final Long taskId;

        private EdgeRecord(Long sourceTableId, Long targetTableId, Long taskId) {
            this.sourceTableId = sourceTableId;
            this.targetTableId = targetTableId;
            this.taskId = taskId;
        }
    }

    @Data
    public static class LineageGraph {
        private List<LineageNode> nodes;
        private List<LineageEdge> edges;
    }

    @Data
    public static class LineageNode {
        private Long tableId;
        private String id;
        private String name;
        private String layer;
        private String comment;
        private String businessDomain;
        private String dataDomain;
        private Long clusterId;
        private String dbName;
    }

    @Data
    public static class LineageEdge {
        private String source;
        private String target;
        private Long taskId;
    }

    public LineageInspectionResponse inspectLineage(
            String table, String dbName, Long tableId, Integer depth) {
        String normalizedTable = trimToNull(table);
        String normalizedDbName = trimToNull(dbName);
        if (tableId == null && !StringUtils.hasText(normalizedTable)) {
            throw new IllegalArgumentException("table 或 tableId 至少提供一个");
        }

        List<DataTable> targets =
                filterVisibleTables(
                        resolveLineageTargets(normalizedTable, normalizedDbName, tableId));
        if (tableId != null && targets.isEmpty()) {
            throw new IllegalArgumentException("账号无权访问 tableId `" + tableId + "`");
        }
        LineageInspectionResponse response = new LineageInspectionResponse();
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
        Set<Long> targetIds =
                targets.stream()
                        .map(DataTable::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<Long> visibleIds = new LinkedHashSet<>(targetIds);

        if (graphScoped) {
            DataTable center = targets.get(0);
            response.setDbName(
                    StringUtils.hasText(normalizedDbName) ? normalizedDbName : center.getDbName());
            response.setTable(
                    StringUtils.hasText(normalizedTable) ? normalizedTable : center.getTableName());
            response.setTableId(center.getId());
            LineageService.LineageGraph graph =
                    getLineageGraph(
                            null,
                            null,
                            null,
                            null,
                            center.getClusterId(),
                            center.getDbName(),
                            center.getId(),
                            depth);
            if (graph != null && graph.getNodes() != null) {
                graph.getNodes().stream()
                        .map(LineageService.LineageNode::getTableId)
                        .filter(Objects::nonNull)
                        .forEach(visibleIds::add);
            }
        }

        response.setLineage(loadLineageRecords(visibleIds, targetIds, 400, graphScoped));
        return response;
    }

    public List<Map<String, Object>> exportLineage(String database) {
        String normalizedDatabase = trimToNull(database);
        List<DataLineage> lineages =
                dataLineageMapper.selectList(
                        new LambdaQueryWrapper<DataLineage>().orderByAsc(DataLineage::getId));
        Map<Long, DataTable> tableMap = loadTableMapFromLineages(lineages);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (DataLineage lineage : lineages) {
            DataTable upstream = tableMap.get(lineage.getUpstreamTableId());
            DataTable downstream = tableMap.get(lineage.getDownstreamTableId());
            if (!isLineageAllowed(lineage, upstream, downstream)) {
                continue;
            }
            if (StringUtils.hasText(normalizedDatabase)) {
                String upstreamDb = upstream == null ? null : upstream.getDbName();
                String downstreamDb = downstream == null ? null : downstream.getDbName();
                if (!normalizedDatabase.equals(upstreamDb)
                        && !normalizedDatabase.equals(downstreamDb)) {
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

    public List<LineageRecordDto> inspectRecordsForTables(List<Long> tableIds, int limit) {
        if (tableIds == null || tableIds.isEmpty()) {
            return Collections.emptyList();
        }
        return loadLineageRecords(
                new LinkedHashSet<>(tableIds), new LinkedHashSet<>(tableIds), limit, true);
    }

    private List<LineageRecordDto> loadLineageRecords(
            Set<Long> visibleIds, Set<Long> targetIds, int limit, boolean graphScoped) {
        if (visibleIds == null || visibleIds.isEmpty()) {
            return Collections.emptyList();
        }

        LambdaQueryWrapper<DataLineage> wrapper =
                new LambdaQueryWrapper<DataLineage>()
                        .and(
                                nested ->
                                        nested.in(DataLineage::getUpstreamTableId, visibleIds)
                                                .or()
                                                .in(DataLineage::getDownstreamTableId, visibleIds))
                        .orderByAsc(DataLineage::getId)
                        .last("LIMIT " + Math.max(1, limit));

        if (!graphScoped && targetIds != null && !targetIds.isEmpty()) {
            wrapper.and(
                    nested ->
                            nested.in(DataLineage::getUpstreamTableId, targetIds)
                                    .or()
                                    .in(DataLineage::getDownstreamTableId, targetIds));
        }

        List<DataLineage> lineages = dataLineageMapper.selectList(wrapper);
        if (graphScoped) {
            lineages =
                    lineages.stream()
                            .filter(
                                    item ->
                                            visibleIds.contains(item.getUpstreamTableId())
                                                    || visibleIds.contains(
                                                            item.getDownstreamTableId()))
                            .collect(Collectors.toList());
        }
        return toLineageRecords(lineages);
    }

    private List<LineageRecordDto> toLineageRecords(List<DataLineage> lineages) {
        if (lineages == null || lineages.isEmpty()) {
            return Collections.emptyList();
        }
        Map<Long, DataTable> tableMap = loadTableMapFromLineages(lineages);
        List<LineageRecordDto> records = new ArrayList<>();
        for (DataLineage lineage : lineages) {
            DataTable upstream = tableMap.get(lineage.getUpstreamTableId());
            DataTable downstream = tableMap.get(lineage.getDownstreamTableId());
            LineageRecordDto record = new LineageRecordDto();
            record.setId(lineage.getId());
            record.setLineageType(lineage.getLineageType());
            if (!isLineageAllowed(lineage, upstream, downstream)) {
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

        LambdaQueryWrapper<DataTable> wrapper =
                new LambdaQueryWrapper<DataTable>()
                        .eq(DataTable::getTableName, table)
                        .ne(DataTable::getStatus, "deprecated")
                        .orderByAsc(DataTable::getId)
                        .last("LIMIT 10");
        if (StringUtils.hasText(dbName)) {
            wrapper.eq(DataTable::getDbName, dbName);
        }
        return filterVisibleTables(dataTableMapper.selectList(wrapper));
    }

    private List<DataTable> listActiveTables(String database) {
        LambdaQueryWrapper<DataTable> query =
                new LambdaQueryWrapper<DataTable>().ne(DataTable::getStatus, "deprecated");
        if (StringUtils.hasText(database)) query.eq(DataTable::getDbName, database);
        return filterVisibleTables(dataTableMapper.selectList(query.orderByAsc(DataTable::getId)));
    }

    private List<DataTable> filterVisibleTables(List<DataTable> tables) {
        return tables.stream().filter(this::isTableAllowed).collect(Collectors.toList());
    }

    private boolean isLineageAllowed(
            DataLineage lineage, DataTable upstream, DataTable downstream) {
        return (lineage.getUpstreamTableId() != null || lineage.getDownstreamTableId() != null)
                && (lineage.getUpstreamTableId() == null || isTableAllowed(upstream))
                && (lineage.getDownstreamTableId() == null || isTableAllowed(downstream));
    }

    private boolean isTableAllowed(DataTable table) {
        if (table == null || table.getClusterId() == null) return false;
        String name = table.getTableName();
        String prefix = table.getDbName() + ".";
        if (name != null && name.startsWith(prefix)) name = name.substring(prefix.length());
        return datasourceAccess.isVisible(table.getClusterId(), table.getDbName(), name);
    }

    private String trimToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    private List<Map<String, Object>> capExportRows(List<Map<String, Object>> rows, String kind) {
        if (rows.size() > 5000) throw new IllegalArgumentException("导出结果超过5000行，请缩小范围");
        return rows;
    }
}
