package com.onedata.portal.service;

import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.TableStatisticsHistory;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.TableStatisticsHistoryMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DorisMetadataSyncServiceTest {

    @Mock
    private DorisConnectionService dorisConnectionService;

    @Mock
    private DorisClusterMapper dorisClusterMapper;

    @Mock
    private DataTableMapper dataTableMapper;

    @Mock
    private DataFieldMapper dataFieldMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private TableStatisticsHistoryMapper tableStatisticsHistoryMapper;

    @Mock
    private TableMetadataVersionService tableMetadataVersionService;

    @InjectMocks
    private DorisMetadataSyncService service;

    @BeforeEach
    void setUp() {
        DorisCluster cluster = new DorisCluster();
        cluster.setId(1L);
        cluster.setSourceType("DORIS");

        lenient().when(dorisClusterMapper.selectById(1L)).thenReturn(cluster);
        lenient().when(dorisConnectionService.getTableCreateInfo(anyLong(), anyString(), anyString()))
                .thenReturn(Collections.emptyMap());
        lenient().when(dorisConnectionService.getColumnsInTable(anyLong(), anyString(), anyString()))
                .thenReturn(Collections.emptyList());
    }

    @Test
    void syncDatabaseSetsLayerFromPrefixForNewTable() {
        Map<String, Object> dorisTable = new HashMap<>();
        dorisTable.put("tableName", "dwd_order_detail");
        dorisTable.put("tableComment", "order detail");

        when(dorisConnectionService.getTablesInDatabase(1L, "dw"))
                .thenReturn(Collections.singletonList(dorisTable));
        when(dataTableMapper.selectList(any()))
                .thenReturn(Collections.emptyList());

        service.syncDatabase(1L, "dw", null);

        ArgumentCaptor<DataTable> captor = ArgumentCaptor.forClass(DataTable.class);
        verify(dataTableMapper).insert(captor.capture());
        assertEquals("DWD", captor.getValue().getLayer());
    }

    @Test
    void syncDatabaseCorrectsLayerFromPrefixForExistingTable() {
        DataTable existing = new DataTable();
        existing.setId(10L);
        existing.setClusterId(1L);
        existing.setDbName("dw");
        existing.setTableName("ads_sales_summary");
        existing.setLayer("ODS");
        existing.setStatus("active");

        Map<String, Object> dorisTable = new HashMap<>();
        dorisTable.put("tableName", "ads_sales_summary");
        dorisTable.put("tableComment", "sales summary");

        when(dorisConnectionService.getTablesInDatabase(1L, "dw"))
                .thenReturn(Collections.singletonList(dorisTable));
        when(dataTableMapper.selectList(any()))
                .thenReturn(Collections.singletonList(existing));
        when(dataFieldMapper.selectList(any())).thenReturn(Collections.emptyList());

        service.syncDatabase(1L, "dw", null);

        ArgumentCaptor<DataTable> captor = ArgumentCaptor.forClass(DataTable.class);
        verify(dataTableMapper).updateById(captor.capture());
        assertEquals("ADS", captor.getValue().getLayer());
    }

    @Test
    void syncDatabaseLeavesUnchangedExistingTableUntouched() {
        DataTable existing = existingTable("dw", "fact_orders", "order facts");

        when(dorisConnectionService.getTablesInDatabase(1L, "dw"))
                .thenReturn(Collections.singletonList(dorisTable("fact_orders", "order facts")));
        when(dataTableMapper.selectList(any()))
                .thenReturn(Collections.singletonList(existing));
        when(dataFieldMapper.selectList(any())).thenReturn(Collections.emptyList());

        DorisMetadataSyncService.SyncResult result = service.syncDatabase(1L, "dw", null);

        verify(dataTableMapper, never()).updateById(any(DataTable.class));
        assertEquals(0, result.getUpdatedTables());
    }

    @Test
    void syncDatabaseUpdatesTableCommentAndCountsUpdatedTable() {
        DataTable existing = existingTable("dw", "fact_orders", "old comment");

        when(dorisConnectionService.getTablesInDatabase(1L, "dw"))
                .thenReturn(Collections.singletonList(dorisTable("fact_orders", "new comment")));
        when(dataTableMapper.selectList(any()))
                .thenReturn(Collections.singletonList(existing));
        when(dataFieldMapper.selectList(any())).thenReturn(Collections.emptyList());

        DorisMetadataSyncService.SyncResult result = service.syncDatabase(1L, "dw", null);

        ArgumentCaptor<DataTable> captor = ArgumentCaptor.forClass(DataTable.class);
        verify(dataTableMapper).updateById(captor.capture());
        assertEquals("new comment", captor.getValue().getTableComment());
        assertNotNull(captor.getValue().getSyncTime());
        assertEquals(1, result.getUpdatedTables());
    }

    @Test
    void syncDatabaseUpdatesChangedFieldsWithoutCountingTableUpdate() {
        DataTable existing = existingTable("dw", "fact_orders", "order facts");
        DataField localField = localField(100L, 10L, "amount", "DECIMAL(10,2)", "amount", 1);

        when(dorisConnectionService.getTablesInDatabase(1L, "dw"))
                .thenReturn(Collections.singletonList(dorisTable("fact_orders", "order facts")));
        when(dorisConnectionService.getColumnsInTable(1L, "dw", "fact_orders"))
                .thenReturn(Collections.singletonList(dorisColumn(
                        "amount", "DECIMAL(20,2)", "order amount", 2)));
        when(dataTableMapper.selectList(any()))
                .thenReturn(Collections.singletonList(existing));
        when(dataFieldMapper.selectList(any()))
                .thenReturn(Collections.singletonList(localField));

        DorisMetadataSyncService.SyncResult result = service.syncDatabase(1L, "dw", null);

        ArgumentCaptor<DataField> fieldCaptor = ArgumentCaptor.forClass(DataField.class);
        verify(dataFieldMapper).updateById(fieldCaptor.capture());
        assertEquals("DECIMAL(20,2)", fieldCaptor.getValue().getFieldType());
        assertEquals("order amount", fieldCaptor.getValue().getFieldComment());
        assertEquals(2, fieldCaptor.getValue().getFieldOrder());

        ArgumentCaptor<DataTable> tableCaptor = ArgumentCaptor.forClass(DataTable.class);
        verify(dataTableMapper).updateById(tableCaptor.capture());
        assertEquals(10L, tableCaptor.getValue().getId());
        assertNotNull(tableCaptor.getValue().getSyncTime());
        assertEquals(0, result.getUpdatedTables());
        assertEquals(1, result.getUpdatedFields());
    }

    @Test
    void auditDatabaseDoesNotWriteTableStatistics() {
        DataTable existing = existingTable("dw", "fact_orders", "order facts");
        existing.setRowCount(1L);
        existing.setStorageSize(2L);
        existing.setDorisUpdateTime(LocalDateTime.of(2026, 5, 13, 10, 0));

        Map<String, Object> dorisTable = dorisTable("fact_orders", "order facts");
        dorisTable.put("tableRows", 9L);
        dorisTable.put("dataLength", 20L);
        dorisTable.put("updateTime", Timestamp.valueOf(LocalDateTime.of(2026, 5, 14, 10, 0)));

        when(dorisConnectionService.getTablesInDatabase(1L, "dw"))
                .thenReturn(Collections.singletonList(dorisTable));
        when(dataTableMapper.selectList(any()))
                .thenReturn(Collections.singletonList(existing));
        when(dataFieldMapper.selectList(any())).thenReturn(Collections.emptyList());
        lenient().when(dorisConnectionService.getTableRuntimeStats(eq(1L), eq("dw"), eq("fact_orders")))
                .thenReturn(Optional.empty());

        DorisMetadataSyncService.AuditResult result = service.auditDatabase(1L, "dw", null);

        verify(dataTableMapper, never()).updateById(any(DataTable.class));
        assertEquals(0, result.getStatisticsSynced());
    }

    @Test
    void syncTableStatisticsOnlyUpdatesChangedStatsAndRecordsHistory() {
        LocalDateTime updateTime = LocalDateTime.of(2026, 5, 14, 11, 0);
        DataTable existing = existingTable("dw", "fact_orders", "order facts");
        existing.setRowCount(1L);
        existing.setStorageSize(2L);
        existing.setDorisUpdateTime(LocalDateTime.of(2026, 5, 13, 11, 0));

        when(dorisConnectionService.getTablesInDatabase(1L, "dw"))
                .thenReturn(Collections.singletonList(dorisTable("fact_orders", "order facts")));
        when(dataTableMapper.selectOne(any())).thenReturn(existing);
        when(dorisConnectionService.getTableRuntimeStats(eq(1L), eq("dw"), eq("fact_orders")))
                .thenReturn(Optional.of(runtimeStats(9L, 20L, updateTime)));

        DorisMetadataSyncService.SyncResult result = service.syncTableStatisticsOnly(1L, "dw", "fact_orders");

        ArgumentCaptor<DataTable> tableCaptor = ArgumentCaptor.forClass(DataTable.class);
        verify(dataTableMapper).updateById(tableCaptor.capture());
        assertEquals(9L, tableCaptor.getValue().getRowCount());
        assertEquals(20L, tableCaptor.getValue().getStorageSize());
        assertEquals(updateTime, tableCaptor.getValue().getDorisUpdateTime());
        assertNotNull(tableCaptor.getValue().getSyncTime());

        ArgumentCaptor<TableStatisticsHistory> historyCaptor = ArgumentCaptor.forClass(TableStatisticsHistory.class);
        verify(tableStatisticsHistoryMapper).insert(historyCaptor.capture());
        assertEquals(10L, historyCaptor.getValue().getTableId());
        assertEquals(1L, historyCaptor.getValue().getClusterId());
        assertEquals("dw", historyCaptor.getValue().getDatabaseName());
        assertEquals("fact_orders", historyCaptor.getValue().getTableName());
        assertEquals(9L, historyCaptor.getValue().getRowCount());
        assertEquals(20L, historyCaptor.getValue().getDataSize());
        assertEquals(updateTime, historyCaptor.getValue().getTableLastUpdateTime());

        assertEquals(1, result.getUpdatedTables());
        verifyNoInteractions(dataFieldMapper);
        verify(dorisConnectionService, never()).getTableCreateInfo(anyLong(), anyString(), anyString());
        verify(dorisConnectionService, never()).getColumnsInTable(anyLong(), anyString(), anyString());
    }

    @Test
    void syncTableStatisticsOnlySkipsUnchangedStats() {
        LocalDateTime updateTime = LocalDateTime.of(2026, 5, 14, 11, 0);
        DataTable existing = existingTable("dw", "fact_orders", "order facts");
        existing.setRowCount(9L);
        existing.setStorageSize(20L);
        existing.setDorisUpdateTime(updateTime);

        when(dorisConnectionService.getTablesInDatabase(1L, "dw"))
                .thenReturn(Collections.singletonList(dorisTable("fact_orders", "order facts")));
        when(dataTableMapper.selectOne(any())).thenReturn(existing);
        when(dorisConnectionService.getTableRuntimeStats(eq(1L), eq("dw"), eq("fact_orders")))
                .thenReturn(Optional.of(runtimeStats(9L, 20L, updateTime)));

        DorisMetadataSyncService.SyncResult result = service.syncTableStatisticsOnly(1L, "dw", "fact_orders");

        verify(dataTableMapper, never()).updateById(any(DataTable.class));
        verify(tableStatisticsHistoryMapper, never()).insert(any(TableStatisticsHistory.class));
        assertEquals(0, result.getUpdatedTables());
        verifyNoInteractions(dataFieldMapper);
        verify(dorisConnectionService, never()).getTableCreateInfo(anyLong(), anyString(), anyString());
        verify(dorisConnectionService, never()).getColumnsInTable(anyLong(), anyString(), anyString());
    }

    private DataTable existingTable(String database, String tableName, String comment) {
        DataTable table = new DataTable();
        table.setId(10L);
        table.setClusterId(1L);
        table.setDbName(database);
        table.setTableName(tableName);
        table.setTableType("BASE TABLE");
        table.setTableComment(comment);
        table.setStatus("active");
        table.setIsSynced(1);
        table.setSyncTime(LocalDateTime.of(2026, 5, 13, 9, 0));
        return table;
    }

    private Map<String, Object> dorisTable(String tableName, String comment) {
        Map<String, Object> table = new HashMap<>();
        table.put("tableName", tableName);
        table.put("tableType", "BASE TABLE");
        table.put("tableComment", comment);
        return table;
    }

    private DataField localField(Long id,
            Long tableId,
            String fieldName,
            String fieldType,
            String fieldComment,
            Integer fieldOrder) {
        DataField field = new DataField();
        field.setId(id);
        field.setTableId(tableId);
        field.setFieldName(fieldName);
        field.setFieldType(fieldType);
        field.setFieldComment(fieldComment);
        field.setIsNullable(0);
        field.setIsPrimary(0);
        field.setFieldOrder(fieldOrder);
        return field;
    }

    private Map<String, Object> dorisColumn(String columnName, String dataType, String columnComment, Integer ordinalPosition) {
        Map<String, Object> column = new HashMap<>();
        column.put("columnName", columnName);
        column.put("dataType", dataType);
        column.put("columnComment", columnComment);
        column.put("isNullable", 0);
        column.put("isPrimary", 0);
        column.put("defaultValue", null);
        column.put("ordinalPosition", ordinalPosition);
        return column;
    }

    private DorisConnectionService.TableRuntimeStats runtimeStats(Long rowCount, Long dataSize, LocalDateTime lastUpdate) {
        DorisConnectionService.TableRuntimeStats stats = new DorisConnectionService.TableRuntimeStats();
        stats.setRowCount(rowCount);
        stats.setDataSize(dataSize);
        stats.setLastUpdate(Timestamp.valueOf(lastUpdate));
        return stats;
    }
}
