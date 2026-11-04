package com.onedata.portal.service;

import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LineageServiceTest {

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private DataTableMapper dataTableMapper;

    @InjectMocks
    private LineageService lineageService;

    @Test
    void getLineageGraphCenterDepthOneReturnsDirectNeighbors() {
        when(dataTableMapper.selectList(any())).thenReturn(buildTables());
        when(dataLineageMapper.selectList(any())).thenReturn(buildChainLineages());

        LineageService.LineageGraph graph = lineageService.getLineageGraph(
                null, null, null, null, null, null, 2L, 1);

        Set<String> nodeIds = graph.getNodes().stream()
                .map(LineageService.LineageNode::getId)
                .collect(Collectors.toSet());
        Set<String> edgePairs = graph.getEdges().stream()
                .map(edge -> edge.getSource() + "->" + edge.getTarget())
                .collect(Collectors.toSet());

        assertEquals(newHashSet("1", "2", "3"), nodeIds);
        assertEquals(newHashSet("1->2", "2->3"), edgePairs);
    }

    @Test
    void getLineageGraphCenterUnlimitedDepthReturnsAllReachableNodes() {
        when(dataTableMapper.selectList(any())).thenReturn(buildTables());
        when(dataLineageMapper.selectList(any())).thenReturn(buildChainLineages());

        LineageService.LineageGraph graph = lineageService.getLineageGraph(
                null, null, null, null, null, null, 2L, -1);

        Set<String> nodeIds = graph.getNodes().stream()
                .map(LineageService.LineageNode::getId)
                .collect(Collectors.toSet());
        Set<String> edgePairs = graph.getEdges().stream()
                .map(edge -> edge.getSource() + "->" + edge.getTarget())
                .collect(Collectors.toSet());

        assertEquals(newHashSet("0", "1", "2", "3", "4"), nodeIds);
        assertEquals(newHashSet("0->1", "1->2", "2->3", "3->4"), edgePairs);
        assertTrue(!nodeIds.contains("9"), "Disconnected table should not be included in centered query");
    }

    @Test
    void getLineageGraphWithoutCenterReturnsAllFilteredTables() {
        when(dataTableMapper.selectList(any())).thenReturn(buildTables());
        when(dataLineageMapper.selectList(any())).thenReturn(buildChainLineages());

        LineageService.LineageGraph graph = lineageService.getLineageGraph(
                null, null, null, null, null, null, null, null);

        Set<String> nodeIds = graph.getNodes().stream()
                .map(LineageService.LineageNode::getId)
                .collect(Collectors.toSet());

        assertEquals(newHashSet("0", "1", "2", "3", "4", "9"), nodeIds);
    }

    @Test
    void getLineageGraphCenterDepthTwoDoesNotMixDirections() {
        when(dataTableMapper.selectList(any())).thenReturn(buildDirectionalTables());
        when(dataLineageMapper.selectList(any())).thenReturn(buildDirectionalLineages());

        LineageService.LineageGraph graph = lineageService.getLineageGraph(
                null, null, null, null, null, null, 10L, 2);

        Set<String> nodeIds = graph.getNodes().stream()
                .map(LineageService.LineageNode::getId)
                .collect(Collectors.toSet());

        assertEquals(newHashSet("8", "9", "10", "11", "12"), nodeIds);
        assertTrue(!nodeIds.contains("7"), "Should not include upstream of downstream branch");
        assertTrue(!nodeIds.contains("13"), "Should not include downstream of upstream branch");
    }

    @Test
    void getLineageGraphSupportsDirectTableLineageRows() {
        when(dataTableMapper.selectList(any())).thenReturn(buildDirectTables());
        when(dataLineageMapper.selectList(any())).thenReturn(buildDirectTableLineagesOnly());

        LineageService.LineageGraph graph = lineageService.getLineageGraph(
                null, null, null, null, null, null, 21L, 1);

        Set<String> nodeIds = graph.getNodes().stream()
                .map(LineageService.LineageNode::getId)
                .collect(Collectors.toSet());
        Set<String> edgePairs = graph.getEdges().stream()
                .map(edge -> edge.getSource() + "->" + edge.getTarget())
                .collect(Collectors.toSet());

        assertEquals(newHashSet("20", "21"), nodeIds);
        assertEquals(newHashSet("20->21"), edgePairs);
    }

    @Test
    void getLineageGraphShouldWorkWithoutWorkflowBindingData() {
        when(dataTableMapper.selectList(any())).thenReturn(buildTables());
        when(dataLineageMapper.selectList(any())).thenReturn(buildChainLineages());

        LineageService.LineageGraph graph = lineageService.getLineageGraph(
                null, null, null, null, null, null, 2L, 2);

        Set<String> nodeIds = graph.getNodes().stream()
                .map(LineageService.LineageNode::getId)
                .collect(Collectors.toSet());
        Set<String> edgePairs = graph.getEdges().stream()
                .map(edge -> edge.getSource() + "->" + edge.getTarget())
                .collect(Collectors.toSet());

        assertEquals(newHashSet("0", "1", "2", "3", "4"), nodeIds);
        assertEquals(newHashSet("0->1", "1->2", "2->3", "3->4"), edgePairs);
    }

    private List<DataTable> buildTables() {
        List<DataTable> tables = new ArrayList<>();
        tables.add(table(0L, "ods_user"));
        tables.add(table(1L, "dwd_user"));
        tables.add(table(2L, "dws_user"));
        tables.add(table(3L, "ads_user"));
        tables.add(table(4L, "ads_user_detail"));
        tables.add(table(9L, "isolated_table"));
        return tables;
    }

    private List<DataLineage> buildChainLineages() {
        List<DataLineage> lineages = new ArrayList<>();
        addTaskLineage(lineages, 100L, 0L, 1L);
        addTaskLineage(lineages, 101L, 1L, 2L);
        addTaskLineage(lineages, 102L, 2L, 3L);
        addTaskLineage(lineages, 103L, 3L, 4L);
        return lineages;
    }

    private void addTaskLineage(List<DataLineage> lineages, Long taskId, Long upstreamId, Long downstreamId) {
        DataLineage input = new DataLineage();
        input.setTaskId(taskId);
        input.setLineageType("input");
        input.setUpstreamTableId(upstreamId);
        lineages.add(input);

        DataLineage output = new DataLineage();
        output.setTaskId(taskId);
        output.setLineageType("output");
        output.setDownstreamTableId(downstreamId);
        lineages.add(output);
    }

    private DataTable table(Long id, String tableName) {
        DataTable table = new DataTable();
        table.setId(id);
        table.setTableName(tableName);
        table.setStatus("active");
        return table;
    }

    private List<DataTable> buildDirectionalTables() {
        List<DataTable> tables = new ArrayList<>();
        tables.add(table(7L, "branch_in_of_downstream"));
        tables.add(table(8L, "upstream_lv2"));
        tables.add(table(9L, "upstream_lv1"));
        tables.add(table(10L, "center"));
        tables.add(table(11L, "downstream_lv1"));
        tables.add(table(12L, "downstream_lv2"));
        tables.add(table(13L, "branch_out_of_upstream"));
        return tables;
    }

    private List<DataLineage> buildDirectionalLineages() {
        List<DataLineage> lineages = new ArrayList<>();
        addTaskLineage(lineages, 201L, 8L, 9L);
        addTaskLineage(lineages, 202L, 9L, 10L);
        addTaskLineage(lineages, 203L, 10L, 11L);
        addTaskLineage(lineages, 204L, 11L, 12L);

        // Direction-mixing branches that should be excluded for depth=2 from center(10)
        addTaskLineage(lineages, 205L, 7L, 11L);
        addTaskLineage(lineages, 206L, 9L, 13L);
        return lineages;
    }

    private List<DataTable> buildDirectTables() {
        List<DataTable> tables = new ArrayList<>();
        tables.add(table(20L, "direct_upstream"));
        tables.add(table(21L, "direct_center"));
        return tables;
    }

    private List<DataLineage> buildDirectTableLineagesOnly() {
        List<DataLineage> lineages = new ArrayList<>();
        DataLineage direct = new DataLineage();
        direct.setTaskId(300L);
        direct.setLineageType("table");
        direct.setUpstreamTableId(20L);
        direct.setDownstreamTableId(21L);
        lineages.add(direct);
        return lineages;
    }

    private Set<String> newHashSet(String... values) {
        Set<String> result = new HashSet<>();
        if (values == null) {
            return result;
        }
        for (String value : values) {
            result.add(value);
        }
        return result;
    }
}
