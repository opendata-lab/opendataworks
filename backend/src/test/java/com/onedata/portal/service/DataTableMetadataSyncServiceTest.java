package com.onedata.portal.service;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.MetadataSyncHistory;
import com.onedata.portal.service.DatasourceAdapterFixtures;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataTableMetadataSyncServiceTest {

    @Mock
    private DorisMetadataSyncService dorisMetadataSyncService;

    @Mock
    private MetadataSyncHistoryService metadataSyncHistoryService;

    @Mock
    private DorisClusterService dorisClusterService;

    @Mock
    private DataTableService dataTableService;

    @Spy
    private DatasourceAdapterRegistry adapterRegistry =
            DatasourceAdapterFixtures.registry(new DorisJdbcProperties());

    @InjectMocks
    private DataTableMetadataSyncService service;

    @Test
    void syncTableByNameRecordsHistoryAndEnrichesSyncedTableId() {
        DorisCluster cluster = new DorisCluster();
        cluster.setId(1L);
        cluster.setClusterName("local-doris");
        cluster.setSourceType("DORIS");

        DorisMetadataSyncService.SyncResult syncResult = new DorisMetadataSyncService.SyncResult();
        syncResult.addNewTable();

        MetadataSyncHistory history = new MetadataSyncHistory();
        history.setId(77L);

        DataTable syncedTable = new DataTable();
        syncedTable.setId(42L);
        syncedTable.setClusterId(1L);
        syncedTable.setDbName("dw");
        syncedTable.setTableName("fact_orders");

        when(dorisClusterService.getById(1L)).thenReturn(cluster);
        when(dorisMetadataSyncService.syncTable(1L, "dw", "fact_orders")).thenReturn(syncResult);
        when(metadataSyncHistoryService.record(eq(cluster), eq("manual"), eq("table"), eq("dw.fact_orders"),
                any(LocalDateTime.class), eq(syncResult))).thenReturn(history);
        when(dataTableService.getByDbAndTableName(1L, "dw", "fact_orders")).thenReturn(syncedTable);

        Map<String, Object> response = service.syncTableByName(1L, "dw", "fact_orders");

        assertEquals(true, response.get("success"));
        assertEquals("SUCCESS", response.get("status"));
        assertEquals(77L, response.get("syncRunId"));
        assertEquals("dw", response.get("database"));
        assertEquals("fact_orders", response.get("tableName"));
        assertEquals(42L, response.get("tableId"));

        verify(dorisMetadataSyncService).syncTable(1L, "dw", "fact_orders");
        verify(metadataSyncHistoryService).record(eq(cluster), eq("manual"), eq("table"), eq("dw.fact_orders"),
                any(LocalDateTime.class), eq(syncResult));
    }

    @Test
    void syncTableByNameRequiresClusterId() {
        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.syncTableByName(null, "dw", "fact_orders"));

        assertEquals("请指定数据源", ex.getMessage());
        verifyNoInteractions(dorisMetadataSyncService);
        verifyNoInteractions(metadataSyncHistoryService);
    }
}
