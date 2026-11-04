package com.onedata.portal.service;

import com.onedata.portal.dto.SqlTableAnalyzeResponse;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SqlTableMatcherServiceTest {

    @Mock
    private DataTableMapper dataTableMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private DorisClusterMapper dorisClusterMapper;

    @InjectMocks
    private SqlTableMatcherService service;

    @Test
    void analyzeBasicInsertSelectShouldResolveInputAndOutput() {
        DataTable input = table(1L, 101L, "ods", "t0", "input");
        DataTable output = table(2L, 101L, "dws", "t1", "output");

        when(dataTableMapper.selectActiveByDbAndTable("ods", "t0")).thenReturn(Collections.singletonList(input));
        when(dataTableMapper.selectActiveByDbAndTable("dws", "t1")).thenReturn(Collections.singletonList(output));
        when(dorisClusterMapper.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(cluster(101L, "prod", "DORIS")));

        SqlTableAnalyzeResponse response = service.analyze("INSERT INTO dws.t1 SELECT * FROM ods.t0", "SQL");

        assertEquals(1, response.getInputRefs().size());
        assertEquals(1, response.getOutputRefs().size());
        assertEquals("matched", response.getInputRefs().get(0).getMatchStatus());
        assertEquals("matched", response.getOutputRefs().get(0).getMatchStatus());
        assertFalse(response.getInputRefs().get(0).getSpans().isEmpty());
        assertFalse(response.getOutputRefs().get(0).getSpans().isEmpty());
    }

    @Test
    void analyzeWithCteShouldExcludeCteAliasAndKeepRealInputs() {
        DataTable ods = table(11L, 101L, "ods", "src_order", "ods");
        DataTable dim = table(12L, 101L, "dim", "dim_user", "dim");
        DataTable dws = table(13L, 101L, "dws", "dws_order_user", "dws");

        when(dataTableMapper.selectActiveByDbAndTable("ods", "src_order")).thenReturn(Collections.singletonList(ods));
        when(dataTableMapper.selectActiveByDbAndTable("dim", "dim_user")).thenReturn(Collections.singletonList(dim));
        when(dataTableMapper.selectActiveByDbAndTable("dws", "dws_order_user")).thenReturn(Collections.singletonList(dws));
        when(dorisClusterMapper.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(cluster(101L, "prod", "DORIS")));

        String sql = "WITH cte AS (SELECT * FROM ods.src_order) " +
                "INSERT INTO dws.dws_order_user " +
                "SELECT c.id, d.user_id FROM cte c JOIN dim.dim_user d ON c.user_id=d.user_id";

        SqlTableAnalyzeResponse response = service.analyze(sql, "SQL");

        List<String> inputRaw = Arrays.asList(
                response.getInputRefs().get(0).getRawName(),
                response.getInputRefs().get(1).getRawName()
        );
        assertTrue(inputRaw.contains("ods.src_order"));
        assertTrue(inputRaw.contains("dim.dim_user"));
        assertEquals(2, response.getInputRefs().size());
        assertEquals("dws.dws_order_user", response.getOutputRefs().get(0).getRawName());
    }

    @Test
    void analyzeInsertOverwriteAndMergeShouldExtractOutputs() {
        DataTable out1 = table(21L, 201L, "dws", "agg_day", "agg day");
        DataTable out2 = table(22L, 201L, "dws", "merge_target", "merge target");
        DataTable in1 = table(23L, 201L, "ods", "event_log", "event");
        DataTable in2 = table(24L, 201L, "ods", "merge_src", "merge src");

        when(dataTableMapper.selectActiveByDbAndTable("dws", "agg_day")).thenReturn(Collections.singletonList(out1));
        when(dataTableMapper.selectActiveByDbAndTable("dws", "merge_target")).thenReturn(Collections.singletonList(out2));
        when(dataTableMapper.selectActiveByDbAndTable("ods", "event_log")).thenReturn(Collections.singletonList(in1));
        when(dataTableMapper.selectActiveByDbAndTable("ods", "merge_src")).thenReturn(Collections.singletonList(in2));
        when(dorisClusterMapper.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(cluster(201L, "prod", "DORIS")));

        String sql = "INSERT OVERWRITE TABLE dws.agg_day SELECT * FROM ods.event_log; " +
                "MERGE INTO dws.merge_target t USING ods.merge_src s ON t.id=s.id WHEN MATCHED THEN UPDATE SET t.v=s.v";

        SqlTableAnalyzeResponse response = service.analyze(sql, "SQL");

        assertEquals(2, response.getOutputRefs().size());
        assertEquals(2, response.getInputRefs().size());
        assertTrue(response.getOutputRefs().stream().allMatch(r -> "matched".equals(r.getMatchStatus())));
    }

    @Test
    void analyzeShouldIgnoreFakeTableNamesInCommentsAndLiterals() {
        DataTable real = table(31L, 301L, "ods", "real_tbl", "real");
        when(dataTableMapper.selectActiveByDbAndTable("ods", "real_tbl")).thenReturn(Collections.singletonList(real));
        when(dorisClusterMapper.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(cluster(301L, "prod", "DORIS")));

        String sql = "SELECT '-- FROM fake.tbl' AS x FROM ods.real_tbl -- JOIN fake.tbl\n";
        SqlTableAnalyzeResponse response = service.analyze(sql, "SQL");

        assertEquals(1, response.getInputRefs().size());
        assertEquals("ods.real_tbl", response.getInputRefs().get(0).getRawName());
    }

    @Test
    void analyzeShouldReturnAmbiguousForCrossClusterSameDbTable() {
        DataTable c1 = table(41L, 401L, "dws", "same_tbl", "c1");
        DataTable c2 = table(42L, 402L, "dws", "same_tbl", "c2");

        when(dataTableMapper.selectActiveByDbAndTable("dws", "same_tbl")).thenReturn(Arrays.asList(c1, c2));
        when(dorisClusterMapper.selectBatchIds(anyCollection())).thenReturn(Arrays.asList(
                cluster(401L, "cluster-a", "DORIS"),
                cluster(402L, "cluster-b", "MYSQL")
        ));

        SqlTableAnalyzeResponse response = service.analyze("INSERT INTO dws.same_tbl SELECT 1", "SQL");

        SqlTableAnalyzeResponse.TableRefMatch output = response.getOutputRefs().get(0);
        assertEquals("ambiguous", output.getMatchStatus());
        assertEquals(2, output.getCandidates().size());
        assertNotNull(response.getAmbiguous());
        assertTrue(response.getAmbiguous().contains("dws.same_tbl"));
    }

    @Test
    void analyzeShouldReturnUnmatchedWhenMetadataMissing() {
        when(dataTableMapper.selectActiveByDbAndTable(eq("ods"), eq("missing_src"))).thenReturn(Collections.emptyList());
        when(dataTableMapper.selectActiveByDbAndTable(eq("dws"), eq("missing_tgt"))).thenReturn(Collections.emptyList());

        SqlTableAnalyzeResponse response = service.analyze("INSERT INTO dws.missing_tgt SELECT * FROM ods.missing_src", "SQL");

        assertTrue(response.getInputRefs().stream().allMatch(r -> "unmatched".equals(r.getMatchStatus())));
        assertTrue(response.getOutputRefs().stream().allMatch(r -> "unmatched".equals(r.getMatchStatus())));
        assertTrue(response.getUnmatched().contains("ods.missing_src"));
        assertTrue(response.getUnmatched().contains("dws.missing_tgt"));
    }

    @Test
    void analyzeDeleteFromShouldTreatTargetAsOutput() {
        DataTable target = table(51L, 501L, "dwd", "user_snapshot", "snapshot");
        DataTable source = table(52L, 501L, "ods", "user_delta", "delta");

        when(dataTableMapper.selectActiveByDbAndTable("dwd", "user_snapshot")).thenReturn(Collections.singletonList(target));
        when(dataTableMapper.selectActiveByDbAndTable("ods", "user_delta")).thenReturn(Collections.singletonList(source));
        when(dorisClusterMapper.selectBatchIds(anyCollection())).thenReturn(Collections.singletonList(cluster(501L, "prod", "DORIS")));

        String sql = "DELETE FROM dwd.user_snapshot WHERE id IN (SELECT id FROM ods.user_delta)";
        SqlTableAnalyzeResponse response = service.analyze(sql, "SQL");

        assertEquals(1, response.getOutputRefs().size());
        assertEquals("dwd.user_snapshot", response.getOutputRefs().get(0).getRawName());
        assertEquals("matched", response.getOutputRefs().get(0).getMatchStatus());

        assertEquals(1, response.getInputRefs().size());
        assertEquals("ods.user_delta", response.getInputRefs().get(0).getRawName());
        assertEquals("matched", response.getInputRefs().get(0).getMatchStatus());
    }

    @Test
    void analyzeUpdateSetShouldTreatTargetAsOutputOnly() {
        DataTable target = table(61L, 601L, "dwd", "user_snapshot", "snapshot");

        when(dataTableMapper.selectActiveByDbAndTable("dwd", "user_snapshot"))
                .thenReturn(Collections.singletonList(target));
        when(dorisClusterMapper.selectBatchIds(anyCollection()))
                .thenReturn(Collections.singletonList(cluster(601L, "prod", "DORIS")));

        String sql = "UPDATE dwd.user_snapshot SET status = 'inactive' WHERE dt = '2026-02-24'";
        SqlTableAnalyzeResponse response = service.analyze(sql, "SQL");

        assertTrue(response.getInputRefs().isEmpty(), "纯 UPDATE SET 不应推断输入表");
        assertEquals(1, response.getOutputRefs().size());
        assertEquals("dwd.user_snapshot", response.getOutputRefs().get(0).getRawName());
        assertEquals("matched", response.getOutputRefs().get(0).getMatchStatus());
    }

    private DataTable table(Long id, Long clusterId, String db, String name, String comment) {
        DataTable t = new DataTable();
        t.setId(id);
        t.setClusterId(clusterId);
        t.setDbName(db);
        t.setTableName(name);
        t.setTableComment(comment);
        t.setStatus("active");
        return t;
    }

    private DorisCluster cluster(Long id, String name, String sourceType) {
        DorisCluster cluster = new DorisCluster();
        cluster.setId(id);
        cluster.setClusterName(name);
        cluster.setSourceType(sourceType);
        return cluster;
    }
}
