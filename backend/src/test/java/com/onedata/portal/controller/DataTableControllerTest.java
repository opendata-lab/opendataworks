package com.onedata.portal.controller;

import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.MetadataSyncHistory;
import com.onedata.portal.service.DataExportService;
import com.onedata.portal.service.DataTableService;
import com.onedata.portal.service.DorisClusterService;
import com.onedata.portal.service.DorisConnectionService;
import com.onedata.portal.service.DorisMetadataSyncService;
import com.onedata.portal.service.DorisTableAccessService;
import com.onedata.portal.service.MetadataSyncHistoryService;
import com.onedata.portal.service.TableStatisticsCacheService;
import com.onedata.portal.service.TableStatisticsHistoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DataTableControllerTest {

    @Mock
    private DataTableService dataTableService;

    @Mock
    private DorisConnectionService dorisConnectionService;

    @Mock
    private TableStatisticsCacheService cacheService;

    @Mock
    private TableStatisticsHistoryService historyService;

    @Mock
    private DataExportService dataExportService;

    @Mock
    private DorisMetadataSyncService dorisMetadataSyncService;

    @Mock
    private DorisTableAccessService dorisTableAccessService;

    @Mock
    private DorisClusterService dorisClusterService;

    @Mock
    private MetadataSyncHistoryService metadataSyncHistoryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DataTableController controller = new DataTableController(
                dataTableService,
                dorisConnectionService,
                cacheService,
                historyService,
                dataExportService,
                dorisMetadataSyncService,
                dorisTableAccessService,
                dorisClusterService,
                metadataSyncHistoryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void syncTableMetadataByNameReturnsSyncedTableId() throws Exception {
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

        mockMvc.perform(post("/v1/tables/sync-metadata/database/dw/table/fact_orders")
                        .param("clusterId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("表元数据同步成功"))
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.syncRunId").value(77))
                .andExpect(jsonPath("$.data.database").value("dw"))
                .andExpect(jsonPath("$.data.tableName").value("fact_orders"))
                .andExpect(jsonPath("$.data.tableId").value(42));

        verify(dorisMetadataSyncService).syncTable(1L, "dw", "fact_orders");
        verify(metadataSyncHistoryService).record(eq(cluster), eq("manual"), eq("table"), eq("dw.fact_orders"),
                any(LocalDateTime.class), eq(syncResult));
    }

    @Test
    void syncTableMetadataByNameRequiresClusterId() throws Exception {
        mockMvc.perform(post("/v1/tables/sync-metadata/database/dw/table/fact_orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("请指定数据源"));

        verifyNoInteractions(dorisMetadataSyncService);
    }
}
