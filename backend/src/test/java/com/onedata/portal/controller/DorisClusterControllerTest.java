package com.onedata.portal.controller;

import com.onedata.portal.service.DataTableService;
import com.onedata.portal.service.DorisClusterService;
import com.onedata.portal.service.DorisConnectionService;
import com.onedata.portal.service.MetadataSyncHistoryService;
import com.onedata.portal.service.SchemaBackupService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DorisClusterControllerTest {

    @Mock
    private DorisClusterService dorisClusterService;

    @Mock
    private DorisConnectionService dorisConnectionService;

    @Mock
    private DataTableService dataTableService;

    @Mock
    private MetadataSyncHistoryService metadataSyncHistoryService;

    @Mock
    private SchemaBackupService schemaBackupService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        DorisClusterController controller = new DorisClusterController(
                dorisClusterService,
                dorisConnectionService,
                dataTableService,
                metadataSyncHistoryService,
                schemaBackupService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listSchemaObjectsReturnsLimitedObjectsAndFiltersSoftDeletedTables() throws Exception {
        Map<String, Object> active = schemaObject("dw", "fact_orders", "BASE TABLE", "订单明细");
        Map<String, Object> deleted = schemaObject("dw", "old_orders", "BASE TABLE", "废弃订单");
        Map<String, Object> view = schemaObject("mart", "v_order_summary", "VIEW", "订单汇总");

        when(dorisConnectionService.getSchemaObjects(1L, "ord"))
                .thenReturn(Arrays.asList(active, deleted, view));
        when(dataTableService.listSoftDeletedTableKeys(1L))
                .thenReturn(Collections.singleton(DataTableService.buildDbTableKey("dw", "old_orders")));

        mockMvc.perform(get("/v1/doris-clusters/1/schema-objects")
                        .param("keyword", "ord")
                        .param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].schemaName").value("dw"))
                .andExpect(jsonPath("$.data[0].tableName").value("fact_orders"))
                .andExpect(jsonPath("$.data[0].tableComment").value("订单明细"))
                .andExpect(jsonPath("$.data[1].schemaName").value("mart"))
                .andExpect(jsonPath("$.data[1].tableName").value("v_order_summary"));

        verify(dorisConnectionService).getSchemaObjects(1L, "ord");
        verify(dataTableService).listSoftDeletedTableKeys(1L);
    }

    @Test
    void listTableColumnsDelegatesToDorisMetadata() throws Exception {
        List<Map<String, Object>> columns = Arrays.asList(
                column("order_id", "BIGINT", "订单 ID"),
                column("pay_amount", "DECIMAL(18,2)", "支付金额"));
        when(dorisConnectionService.getColumnsInTable(1L, "dw", "fact_orders")).thenReturn(columns);

        mockMvc.perform(get("/v1/doris-clusters/1/databases/dw/tables/fact_orders/columns"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].columnName").value("order_id"))
                .andExpect(jsonPath("$.data[0].dataType").value("BIGINT"))
                .andExpect(jsonPath("$.data[1].columnName").value("pay_amount"));

        verify(dorisConnectionService).getColumnsInTable(1L, "dw", "fact_orders");
    }

    private Map<String, Object> schemaObject(String schemaName, String tableName, String tableType, String comment) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("schemaName", schemaName);
        object.put("tableName", tableName);
        object.put("tableType", tableType);
        object.put("tableComment", comment);
        return object;
    }

    private Map<String, Object> column(String name, String type, String comment) {
        Map<String, Object> column = new HashMap<>();
        column.put("columnName", name);
        column.put("dataType", type);
        column.put("columnComment", comment);
        return column;
    }
}
