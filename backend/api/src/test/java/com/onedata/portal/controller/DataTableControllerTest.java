package com.onedata.portal.controller;

import com.onedata.portal.service.DataTableMetadataSyncService;
import com.onedata.portal.service.DataTableQueryService;
import com.onedata.portal.service.DataTableService;
import com.onedata.portal.service.freshness.TableFreshnessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DataTableControllerTest {

    @Mock
    private DataTableService dataTableService;

    @Mock
    private DataTableQueryService dataTableQueryService;

    @Mock
    private DataTableMetadataSyncService dataTableMetadataSyncService;

    @Mock
    private TableFreshnessService tableFreshnessService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DataTableController controller = new DataTableController(
                dataTableService,
                dataTableQueryService,
                dataTableMetadataSyncService,
                tableFreshnessService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void syncTableMetadataByNameDelegatesAndMapsSuccessMessage() throws Exception {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("status", "SUCCESS");
        response.put("syncRunId", 77L);
        response.put("database", "dw");
        response.put("tableName", "fact_orders");
        response.put("tableId", 42L);

        when(dataTableMetadataSyncService.syncTableByName(1L, "dw", "fact_orders")).thenReturn(response);

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

        verify(dataTableMetadataSyncService).syncTableByName(1L, "dw", "fact_orders");
    }

    @Test
    void syncTableMetadataByNameSurfacesMissingClusterIdMessage() throws Exception {
        when(dataTableMetadataSyncService.syncTableByName(isNull(), eq("dw"), eq("fact_orders")))
                .thenThrow(new RuntimeException("请指定数据源"));

        mockMvc.perform(post("/v1/tables/sync-metadata/database/dw/table/fact_orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("请指定数据源"));
    }
}
