package com.onedata.portal.service.freshness;

import com.onedata.portal.dto.TableFreshnessRequest;
import com.onedata.portal.dto.TableFreshnessResponse;
import com.onedata.portal.dto.WorkflowFreshnessResponse;
import com.onedata.portal.dto.WorkflowTableRelationItem;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.TableFreshnessConfig;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.TableFreshnessConfigMapper;
import com.onedata.portal.mapper.TableFreshnessResultMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 契约保存校验：列白名单、互斥、custom_sql 形状、filter 拒绝、partition 前置条件。
 */
class TableFreshnessServiceTest {

    private final DataTableMapper dataTableMapper = mock(DataTableMapper.class);
    private final DataFieldMapper dataFieldMapper = mock(DataFieldMapper.class);
    private final TableFreshnessConfigMapper configMapper = mock(TableFreshnessConfigMapper.class);
    private final TableFreshnessResultMapper resultMapper = mock(TableFreshnessResultMapper.class);
    private final com.onedata.portal.mapper.TableTaskRelationMapper relationMapper =
        mock(com.onedata.portal.mapper.TableTaskRelationMapper.class);
    private final FreshnessContractResolver resolver = new FreshnessContractResolver();
    private final FreshnessCheckService checkService = mock(FreshnessCheckService.class);

    private final TableFreshnessService service = new TableFreshnessService(
        dataTableMapper, dataFieldMapper, configMapper, resultMapper, relationMapper, resolver, checkService);

    private void tableExists() {
        DataTable t = new DataTable();
        t.setId(1L);
        t.setClusterId(10L);
        t.setDbName("dwd");
        t.setTableName("dwd_order_di");
        when(dataTableMapper.selectById(1L)).thenReturn(t);
    }

    private void fields(DataField... fs) {
        when(dataFieldMapper.selectList(any())).thenReturn(Arrays.asList(fs));
    }

    private DataField field(String name, int isPartition) {
        DataField f = new DataField();
        f.setFieldName(name);
        f.setIsPartition(isPartition);
        return f;
    }

    private TableFreshnessRequest req(String mode) {
        TableFreshnessRequest r = new TableFreshnessRequest();
        r.setMode(mode);
        r.setWarnAfterCount(2);
        r.setWarnAfterPeriod("hour");
        r.setErrorAfterCount(4);
        r.setErrorAfterPeriod("hour");
        return r;
    }

    @Test
    void column_realColumn_saves() {
        tableExists();
        fields(field("etl_time", 0));
        when(configMapper.selectOne(any())).thenReturn(null);
        TableFreshnessRequest r = req("column");
        r.setLoadedAtField("etl_time");

        service.saveFreshness(1L, r, "alice");
        verify(configMapper).insert(any(TableFreshnessConfig.class));
    }

    @Test
    void column_unknownColumn_rejected() {
        tableExists();
        fields(field("etl_time", 0));
        TableFreshnessRequest r = req("column");
        r.setLoadedAtField("not_a_column");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.saveFreshness(1L, r, "alice"));
        assertTrue(ex.getMessage().contains("不存在"));
        verify(configMapper, never()).insert(any());
    }

    @Test
    void mutualExclusion_rejected() {
        tableExists();
        fields(field("etl_time", 0));
        TableFreshnessRequest r = req("column");
        r.setLoadedAtField("etl_time");
        r.setLoadedAtQuery("select max(etl_time) from t");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.saveFreshness(1L, r, "alice"));
        assertTrue(ex.getMessage().contains("互斥"));
    }

    @Test
    void customSql_mustStartWithSelect() {
        tableExists();
        fields();
        TableFreshnessRequest r = req("custom_sql");
        r.setLoadedAtQuery("delete from t");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.saveFreshness(1L, r, "alice"));
        assertTrue(ex.getMessage().contains("SELECT"));
    }

    @Test
    void customSql_rejectsSemicolon() {
        tableExists();
        fields();
        TableFreshnessRequest r = req("custom_sql");
        r.setLoadedAtQuery("select max(dt) from t; drop table t");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.saveFreshness(1L, r, "alice"));
        assertTrue(ex.getMessage().contains("分号"));
    }

    @Test
    void filter_rejectsComment() {
        tableExists();
        fields(field("etl_time", 0));
        TableFreshnessRequest r = req("column");
        r.setLoadedAtField("etl_time");
        r.setFilterExpr("1=1 -- bypass");
        assertThrows(IllegalArgumentException.class, () -> service.saveFreshness(1L, r, "alice"));
    }

    @Test
    void partition_requiresPartitionColumn() {
        tableExists();
        fields(field("etl_time", 0)); // 无分区列
        TableFreshnessRequest r = req("partition");
        r.setPartitionFormat("yyyyMMdd");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> service.saveFreshness(1L, r, "alice"));
        assertTrue(ex.getMessage().contains("分区"));
    }

    @Test
    void partition_withPartitionColumn_saves() {
        tableExists();
        fields(field("ds", 1));
        when(configMapper.selectOne(any())).thenReturn(null);
        TableFreshnessRequest r = req("partition");
        r.setPartitionFormat("yyyyMMdd");
        service.saveFreshness(1L, r, "alice");
        verify(configMapper).insert(any(TableFreshnessConfig.class));
    }

    @Test
    void getFreshness_returnsEffectiveWithSources() {
        tableExists();
        TableFreshnessConfig cfg = new TableFreshnessConfig();
        cfg.setTableId(1L);
        cfg.setMode("column");
        cfg.setLoadedAtField("etl_time");
        cfg.setWarnAfterCount(2);
        cfg.setWarnAfterPeriod("hour");
        cfg.setErrorAfterCount(4);
        cfg.setErrorAfterPeriod("hour");
        cfg.setEnabled(true);
        when(configMapper.selectOne(any())).thenReturn(cfg);
        when(resultMapper.selectList(any())).thenReturn(Collections.emptyList());

        TableFreshnessResponse response = service.getFreshness(1L);
        assertTrue(response.isConfigured());
        assertEquals("column", response.getEffective().getMode());
        assertEquals("table", response.getEffective().getFieldSources().get("mode"));
        assertEquals(2, response.getEffective().getWarnAfter().getCount());
    }

    @Test
    void workflowFreshness_includesReadAndWriteTables() {
        WorkflowTableRelationItem r1 = new WorkflowTableRelationItem(100L, "write");
        WorkflowTableRelationItem r2 = new WorkflowTableRelationItem(200L, "read");
        WorkflowTableRelationItem r3 = new WorkflowTableRelationItem(300L, "read");
        WorkflowTableRelationItem r4 = new WorkflowTableRelationItem(300L, "write"); // 300 is both
        when(relationMapper.selectWorkflowTableRelations(10L)).thenReturn(Arrays.asList(r1, r2, r3, r4));

        DataTable t1 = new DataTable();
        t1.setId(100L);
        t1.setDbName("dwd");
        t1.setTableName("dwd_orders");
        t1.setDeleted(0);

        DataTable t2 = new DataTable();
        t2.setId(200L);
        t2.setDbName("ods");
        t2.setTableName("ods_users");
        t2.setDeleted(0);

        DataTable t3 = new DataTable();
        t3.setId(300L);
        t3.setDbName("dws");
        t3.setTableName("dws_summary");
        t3.setDeleted(0);

        when(dataTableMapper.selectBatchIds(any())).thenReturn(Arrays.asList(t1, t2, t3));
        when(resultMapper.selectList(any())).thenReturn(Collections.emptyList());

        WorkflowFreshnessResponse res = service.workflowFreshness(10L);

        assertEquals(3, res.getSummary().getTotal());
        assertEquals(2, res.getSummary().getWriteCount()); // 100 and 300
        assertEquals(2, res.getSummary().getReadCount());  // 200 and 300
        assertEquals(3, res.getTables().size());

        WorkflowFreshnessResponse.TableStatus ts1 = res.getTables().stream()
            .filter(t -> t.getTableId().equals(100L)).findFirst().orElse(null);
        assertNotNull(ts1);
        assertEquals("write", ts1.getRelationType());

        WorkflowFreshnessResponse.TableStatus ts2 = res.getTables().stream()
            .filter(t -> t.getTableId().equals(200L)).findFirst().orElse(null);
        assertNotNull(ts2);
        assertEquals("read", ts2.getRelationType());

        WorkflowFreshnessResponse.TableStatus ts3 = res.getTables().stream()
            .filter(t -> t.getTableId().equals(300L)).findFirst().orElse(null);
        assertNotNull(ts3);
        assertEquals("both", ts3.getRelationType());
    }
}
