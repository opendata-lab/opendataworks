package com.onedata.portal.agentapi;

import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.dto.AgentInspectResponse;
import com.onedata.portal.agentapi.dto.AgentMetadataCompleteRequest;
import com.onedata.portal.agentapi.dto.AgentMetadataCompleteResponse;
import com.onedata.portal.agentapi.dto.AgentTableDdlResponse;
import com.onedata.portal.agentapi.scope.AgentDataScopeContext;
import com.onedata.portal.agentapi.service.AgentJdbcExecutor;
import com.onedata.portal.agentapi.service.BackendAgentMetadataService;
import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.dto.TableFreshnessRequest;
import com.onedata.portal.entity.BusinessDomain;
import com.onedata.portal.entity.DataDomain;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.DorisDbUser;
import com.onedata.portal.mapper.BusinessDomainMapper;
import com.onedata.portal.mapper.DataDomainMapper;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.DorisDbUserMapper;
import com.onedata.portal.service.DataTableService;
import com.onedata.portal.service.DatasourceAdapterFixtures;
import com.onedata.portal.service.LineageService;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.freshness.TableFreshnessService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackendAgentMetadataServiceTest {

    @Mock
    private DataTableMapper dataTableMapper;

    @Mock
    private DataFieldMapper dataFieldMapper;

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private DorisClusterMapper dorisClusterMapper;

    @Mock
    private DorisDbUserMapper dorisDbUserMapper;

    @Mock
    private LineageService lineageService;

    @Mock
    private DataSourceProperties dataSourceProperties;

    @Mock
    private AgentJdbcExecutor agentJdbcExecutor;

    @Mock
    private DataTableService dataTableService;

    @Mock
    private TableFreshnessService tableFreshnessService;

    @Mock
    private BusinessDomainMapper businessDomainMapper;

    @Mock
    private DataDomainMapper dataDomainMapper;

    @Spy
    private DatasourceAdapterRegistry adapterRegistry =
            DatasourceAdapterFixtures.registry(new DorisJdbcProperties());

    @InjectMocks
    private BackendAgentMetadataService backendAgentMetadataService;

    @Test
    void resolveDatasourceReturnsPlatformMysqlForPlatformSchema() {
        when(dataSourceProperties.getUrl()).thenReturn("jdbc:mysql://mysql:3306/opendataworks?serverTimezone=Asia/Shanghai");
        when(dataSourceProperties.getUsername()).thenReturn("platform_user");
        when(dataSourceProperties.getPassword()).thenReturn("platform_pass");

        AgentDatasourceResolution result = backendAgentMetadataService.resolveDatasource("opendataworks", null);

        assertEquals("mysql", result.getEngine());
        assertEquals("mysql", result.getHost());
        assertEquals(Integer.valueOf(3306), result.getPort());
        assertEquals("platform_user", result.getUser());
        assertEquals("platform_pass", result.getPassword());
        assertEquals("platform_runtime", result.getResolvedBy());
    }

    @Test
    void resolveDatasourceUsesReadonlyUserForDorisDatabase() {
        DataTable table = new DataTable();
        table.setId(1L);
        table.setClusterId(12L);
        table.setDbName("doris_ods");
        table.setStatus("active");

        DorisCluster cluster = new DorisCluster();
        cluster.setId(12L);
        cluster.setClusterName("cluster-a");
        cluster.setSourceType("DORIS");
        cluster.setFeHost("doris-fe");
        cluster.setFePort(9030);
        cluster.setUsername("cluster_user");
        cluster.setPassword("cluster_pass");

        DorisDbUser readonlyUser = new DorisDbUser();
        readonlyUser.setClusterId(12L);
        readonlyUser.setDatabaseName("doris_ods");
        readonlyUser.setReadonlyUsername("readonly_user");
        readonlyUser.setReadonlyPassword("readonly_pass");

        when(dataSourceProperties.getUrl()).thenReturn("jdbc:mysql://mysql:3306/opendataworks");
        when(dataTableMapper.selectList(any())).thenReturn(Collections.singletonList(table));
        when(dorisClusterMapper.selectById(12L)).thenReturn(cluster);
        when(dorisDbUserMapper.selectList(any())).thenReturn(Collections.singletonList(readonlyUser));

        AgentDatasourceResolution result = backendAgentMetadataService.resolveDatasource("doris_ods", "doris");

        assertEquals("doris", result.getEngine());
        assertEquals("readonly_user", result.getUser());
        assertEquals("readonly_pass", result.getPassword());
        assertEquals("readonly_user", result.getResolvedBy());
    }

    @Test
    void resolveDatasourceMapsPostgresqlSchemaToPostgresqlEngine() {
        DataTable table = new DataTable();
        table.setId(2L);
        table.setClusterId(21L);
        table.setDbName("public");
        table.setStatus("active");

        DorisCluster cluster = new DorisCluster();
        cluster.setId(21L);
        cluster.setClusterName("pg-a");
        cluster.setSourceType("POSTGRESQL");
        cluster.setDatabaseName("sales");
        cluster.setFeHost("pg-host");
        cluster.setUsername("cluster_user");
        cluster.setPassword("cluster_pass");

        DorisDbUser readonlyUser = new DorisDbUser();
        readonlyUser.setClusterId(21L);
        readonlyUser.setDatabaseName("public");
        readonlyUser.setReadonlyUsername("pg_reader");
        readonlyUser.setReadonlyPassword("pg_reader_pass");

        when(dataSourceProperties.getUrl()).thenReturn("jdbc:mysql://mysql:3306/opendataworks");
        when(dataTableMapper.selectList(any())).thenReturn(Collections.singletonList(table));
        when(dorisClusterMapper.selectById(21L)).thenReturn(cluster);
        when(dorisDbUserMapper.selectList(any())).thenReturn(Collections.singletonList(readonlyUser));

        AgentDatasourceResolution result = backendAgentMetadataService.resolveDatasource("public", "postgresql");

        assertEquals("postgresql", result.getEngine());
        assertEquals("POSTGRESQL", result.getSourceType());
        assertEquals("sales", result.getConnectionDatabase());
        assertEquals(Integer.valueOf(5432), result.getPort());
        assertEquals("pg_reader", result.getUser());
        assertEquals("readonly_user", result.getResolvedBy());
        assertThrows(IllegalArgumentException.class,
                () -> backendAgentMetadataService.resolveDatasource("public", "doris"));
    }

    @Test
    void resolveDatasourceNarrowsSameNamedSchemasByAgentDataScope() {
        // 两个 PostgreSQL 数据源都有 public：数据范围只授权了 21，就应定位到 21 而不是报冲突
        DataTable first = new DataTable();
        first.setClusterId(21L);
        DataTable second = new DataTable();
        second.setClusterId(22L);

        DorisCluster cluster = new DorisCluster();
        cluster.setId(21L);
        cluster.setClusterName("pg-a");
        cluster.setSourceType("POSTGRESQL");
        cluster.setDatabaseName("sales");
        cluster.setFeHost("pg-host");
        cluster.setUsername("cluster_user");

        when(dataSourceProperties.getUrl()).thenReturn("jdbc:mysql://mysql:3306/opendataworks");
        when(dataTableMapper.selectList(any())).thenReturn(Arrays.asList(first, second));
        when(dorisClusterMapper.selectById(21L)).thenReturn(cluster);
        when(dorisDbUserMapper.selectList(any())).thenReturn(Collections.emptyList());

        AgentDataScopeContext.setEncodedScope(
                "eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MjEsImRhdGFiYXNlIjoicHVibGljIiwic291cmNlX3R5cGUiOiJQT1NUR1JFU1FMIn1dfQ");
        try {
            AgentDatasourceResolution result = backendAgentMetadataService.resolveDatasource("public", null);
            assertEquals(Long.valueOf(21L), result.getClusterId());
            assertEquals("postgresql", result.getEngine());
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void resolveDatasourceRejectsMultiClusterMatch() {
        DataTable first = new DataTable();
        first.setClusterId(1L);
        first.setDbName("ods");
        first.setStatus("active");
        DataTable second = new DataTable();
        second.setClusterId(2L);
        second.setDbName("ods");
        second.setStatus("active");

        when(dataSourceProperties.getUrl()).thenReturn("jdbc:mysql://mysql:3306/opendataworks");
        when(dataTableMapper.selectList(any())).thenReturn(Arrays.asList(first, second));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> backendAgentMetadataService.resolveDatasource("ods", null)
        );

        assertTrue(exception.getMessage().contains("多个 cluster_id"));
    }

    @Test
    void inspectPreservesMetadataSnapshotShape() {
        DataTable table = new DataTable();
        table.setId(8L);
        table.setClusterId(3L);
        table.setDbName("doris_ods");
        table.setTableName("ads_sales_di");
        table.setTableComment("销售日报");
        table.setStatus("active");

        DataField field = new DataField();
        field.setTableId(8L);
        field.setFieldName("stat_day");
        field.setFieldType("date");
        field.setFieldComment("统计日期");

        DataLineage lineage = new DataLineage();
        lineage.setId(15L);
        lineage.setLineageType("table");
        lineage.setUpstreamTableId(8L);
        lineage.setDownstreamTableId(9L);

        DataTable downstream = new DataTable();
        downstream.setId(9L);
        downstream.setDbName("doris_ads");
        downstream.setTableName("ads_sales_summary_di");

        when(dataTableMapper.selectList(any())).thenReturn(Collections.singletonList(table));
        when(dataFieldMapper.selectList(any())).thenReturn(Collections.singletonList(field));
        when(dataLineageMapper.selectList(any())).thenReturn(Collections.singletonList(lineage));
        when(dataTableMapper.selectBatchIds(any())).thenReturn(Arrays.asList(table, downstream));

        AgentInspectResponse response = backendAgentMetadataService.inspect("doris_ods", "ads_sales_di", null, 12);

        assertEquals("metadata_snapshot", response.getKind());
        assertEquals(1, response.getTableCount());
        assertEquals("doris_ods", response.getDatabase());
        assertEquals("ads_sales_di", response.getTable());
        assertEquals("stat_day", response.getTables().get(0).getFields().get(0).getFieldName());
        assertEquals("doris_ads", response.getLineage().get(0).getDownstreamDb());
    }

    @Test
    void inspectRanksGlobalMatchesBeforeAlphabeticalFallback() {
        DataTable commentMatched = new DataTable();
        commentMatched.setId(11L);
        commentMatched.setDbName("a_finance");
        commentMatched.setTableName("finance_summary");
        commentMatched.setTableComment("包含 order_id 的汇总");
        commentMatched.setStatus("active");

        DataTable fieldMatched = new DataTable();
        fieldMatched.setId(22L);
        fieldMatched.setDbName("z_sales");
        fieldMatched.setTableName("sales_detail");
        fieldMatched.setTableComment("销售明细");
        fieldMatched.setStatus("active");

        DataField field = new DataField();
        field.setTableId(22L);
        field.setFieldName("order_id");
        field.setFieldType("bigint");
        field.setFieldComment("订单ID");

        when(dataTableMapper.selectList(any())).thenReturn(Collections.singletonList(commentMatched));
        when(dataFieldMapper.selectList(any())).thenReturn(Collections.singletonList(field), Collections.singletonList(field));
        when(dataTableMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(fieldMatched));
        when(dataLineageMapper.selectList(any())).thenReturn(Collections.emptyList());

        AgentInspectResponse response = backendAgentMetadataService.inspect(null, null, "order_id", 1);

        assertEquals(1, response.getTableCount());
        assertEquals("z_sales", response.getTables().get(0).getDbName());
        assertEquals("sales_detail", response.getTables().get(0).getTableName());
        assertEquals("order_id", response.getTables().get(0).getFields().get(0).getFieldName());
    }

    @Test
    void exportDatasourceRedactsConnectionFields() {
        DataTable table = new DataTable();
        table.setId(1L);
        table.setClusterId(12L);
        table.setDbName("doris_ods");
        table.setStatus("active");

        DorisCluster cluster = new DorisCluster();
        cluster.setId(12L);
        cluster.setClusterName("cluster-a");
        cluster.setSourceType("DORIS");
        cluster.setFeHost("doris-fe");
        cluster.setFePort(9030);
        cluster.setUsername("cluster_user");
        cluster.setPassword("cluster_pass");

        DorisDbUser readonlyUser = new DorisDbUser();
        readonlyUser.setClusterId(12L);
        readonlyUser.setDatabaseName("doris_ods");
        readonlyUser.setReadonlyUsername("readonly_user");
        readonlyUser.setReadonlyPassword("readonly_pass");

        when(dataTableMapper.selectList(any())).thenReturn(Collections.singletonList(table));
        when(dorisClusterMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(cluster));
        when(dorisDbUserMapper.selectList(any())).thenReturn(Collections.singletonList(readonlyUser));

        List<Map<String, Object>> rows = backendAgentMetadataService.exportDatasource(null);

        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("doris", row.get("engine"));
        assertEquals("cluster-a", row.get("cluster_name"));
        assertEquals("readonly_user", row.get("resolved_by"));
        assertTrue(!row.containsKey("fe_host"));
        assertTrue(!row.containsKey("password"));
        assertTrue(!row.containsKey("readonly_password"));
    }

    @Test
    void ddlReturnsMetadataAndLiveDdlForMatchedTable() {
        DataTable table = new DataTable();
        table.setId(8L);
        table.setClusterId(12L);
        table.setDbName("doris_ods");
        table.setTableName("ads_sales_di");
        table.setTableComment("销售日报");
        table.setStatus("active");

        DataField field = new DataField();
        field.setTableId(8L);
        field.setFieldName("stat_day");
        field.setFieldType("date");
        field.setFieldComment("统计日期");

        DorisCluster cluster = new DorisCluster();
        cluster.setId(12L);
        cluster.setClusterName("cluster-a");
        cluster.setSourceType("DORIS");
        cluster.setFeHost("doris-fe");
        cluster.setFePort(9030);
        cluster.setUsername("cluster_user");
        cluster.setPassword("cluster_pass");

        DorisDbUser readonlyUser = new DorisDbUser();
        readonlyUser.setClusterId(12L);
        readonlyUser.setDatabaseName("doris_ods");
        readonlyUser.setReadonlyUsername("readonly_user");
        readonlyUser.setReadonlyPassword("readonly_pass");

        when(dataTableMapper.selectList(any())).thenReturn(Collections.singletonList(table));
        when(dataFieldMapper.selectList(any())).thenReturn(Collections.singletonList(field));
        when(dorisClusterMapper.selectById(12L)).thenReturn(cluster);
        when(dorisDbUserMapper.selectList(any())).thenReturn(Collections.singletonList(readonlyUser));
        when(agentJdbcExecutor.fetchTableDdl(any(), any(), any(), anyInt())).thenReturn("CREATE TABLE ads_sales_di (...)");

        AgentTableDdlResponse response = backendAgentMetadataService.ddl("doris_ods", "ads_sales_di", null);

        assertEquals("table_ddl", response.getKind());
        assertEquals("doris_ods", response.getDatabase());
        assertEquals("ads_sales_di", response.getTableName());
        assertEquals(Long.valueOf(8L), response.getTableId());
        assertEquals(Long.valueOf(12L), response.getClusterId());
        assertEquals("doris", response.getEngine());
        assertEquals("销售日报", response.getTableComment());
        assertEquals("CREATE TABLE ads_sales_di (...)", response.getDdl());
        assertEquals("stat_day", response.getFields().get(0).getFieldName());
    }

    @Test
    void completeAppliesAllSectionsForValidRequest() {
        DataTable table = new DataTable();
        table.setId(1L);
        table.setDbName("dwd");
        table.setTableName("dwd_order");
        table.setLayer(null);
        when(dataTableMapper.selectById(1L)).thenReturn(table);
        when(dataTableService.normalizeLayer("DWD", false)).thenReturn("DWD");
        BusinessDomain businessDomain = new BusinessDomain();
        businessDomain.setDomainCode("TRADE");
        when(businessDomainMapper.selectOne(any())).thenReturn(businessDomain);
        DataDomain dataDomain = new DataDomain();
        dataDomain.setDomainCode("ORDER");
        dataDomain.setBusinessDomain("TRADE");
        when(dataDomainMapper.selectOne(any())).thenReturn(dataDomain);
        DataField field = new DataField();
        field.setId(11L);
        field.setTableId(1L);
        field.setFieldName("etl_time");
        field.setFieldComment("");
        when(dataFieldMapper.selectList(any())).thenReturn(Collections.singletonList(field));

        AgentMetadataCompleteRequest request = new AgentMetadataCompleteRequest();
        request.setTableId(1L);
        request.setTableComment("订单明细表");
        AgentMetadataCompleteRequest.Attributes attributes = new AgentMetadataCompleteRequest.Attributes();
        attributes.setLayer("DWD");
        attributes.setBusinessDomain("TRADE");
        attributes.setDataDomain("ORDER");
        request.setAttributes(attributes);
        AgentMetadataCompleteRequest.FieldComment fieldComment = new AgentMetadataCompleteRequest.FieldComment();
        fieldComment.setFieldName("etl_time");
        fieldComment.setComment("ETL 加载时间");
        request.setFields(Collections.singletonList(fieldComment));
        AgentMetadataCompleteRequest.Freshness freshness = new AgentMetadataCompleteRequest.Freshness();
        freshness.setLoadedAtField("etl_time");
        freshness.setWarnAfterCount(1);
        freshness.setWarnAfterPeriod("day");
        freshness.setErrorAfterCount(1);
        freshness.setErrorAfterPeriod("day");
        request.setFreshness(freshness);

        AgentMetadataCompleteResponse response = backendAgentMetadataService.complete(request, "agent:topic-1");

        assertTrue(response.getApplied().contains("table_comment"));
        assertTrue(response.getApplied().contains("attributes"));
        assertTrue(response.getApplied().contains("field:etl_time"));
        assertTrue(response.getApplied().contains("freshness"));
        assertTrue(response.getSkipped().isEmpty());
        assertTrue(response.getFailed().isEmpty());
        verify(dataTableService).updateTableComment(1L, "订单明细表", null);
        verify(dataTableService).updateTable(eq(1L), any(DataTable.class), isNull());
        verify(dataTableService).updateField(eq(1L), eq(11L), any(DataField.class), isNull());
        verify(tableFreshnessService).saveFreshness(eq(1L), any(TableFreshnessRequest.class), eq("agent:topic-1"));
    }

    @Test
    void completeSkipsInvalidAttributesAndUnknownFields() {
        DataTable table = new DataTable();
        table.setId(2L);
        table.setDbName("dwd");
        table.setTableName("t2");
        table.setLayer(null);
        when(dataTableMapper.selectById(2L)).thenReturn(table);
        when(dataTableService.normalizeLayer("DWM", false)).thenThrow(new RuntimeException("数据分层非法"));
        when(businessDomainMapper.selectOne(any())).thenReturn(null); // FAKE 不在平台清单
        DataField field = new DataField();
        field.setId(21L);
        field.setTableId(2L);
        field.setFieldName("etl_time");
        when(dataFieldMapper.selectList(any())).thenReturn(Collections.singletonList(field));

        AgentMetadataCompleteRequest request = new AgentMetadataCompleteRequest();
        request.setTableId(2L);
        AgentMetadataCompleteRequest.Attributes attributes = new AgentMetadataCompleteRequest.Attributes();
        attributes.setLayer("DWM");
        attributes.setBusinessDomain("FAKE");
        request.setAttributes(attributes);
        AgentMetadataCompleteRequest.FieldComment fieldComment = new AgentMetadataCompleteRequest.FieldComment();
        fieldComment.setFieldName("ghost");
        fieldComment.setComment("x");
        request.setFields(Collections.singletonList(fieldComment));

        AgentMetadataCompleteResponse response = backendAgentMetadataService.complete(request, null);

        assertTrue(response.getSkipped().stream().anyMatch(item -> item.contains("attributes")));
        assertTrue(response.getSkipped().stream().anyMatch(item -> item.contains("field:ghost")));
        verify(dataTableService, never()).updateTable(any(), any(), any());
        verify(dataTableService, never()).updateField(any(), any(), any(), any());
    }

    @Test
    void completeSkipsAttributesWhenNoEffectiveLayer() {
        DataTable table = new DataTable();
        table.setId(3L);
        table.setDbName("dwd");
        table.setTableName("t3");
        table.setLayer(null);
        when(dataTableMapper.selectById(3L)).thenReturn(table);
        BusinessDomain businessDomain = new BusinessDomain();
        businessDomain.setDomainCode("TRADE");
        when(businessDomainMapper.selectOne(any())).thenReturn(businessDomain);

        AgentMetadataCompleteRequest request = new AgentMetadataCompleteRequest();
        request.setTableId(3L);
        AgentMetadataCompleteRequest.Attributes attributes = new AgentMetadataCompleteRequest.Attributes();
        attributes.setBusinessDomain("TRADE"); // 未给分层，表上也无分层
        request.setAttributes(attributes);

        AgentMetadataCompleteResponse response = backendAgentMetadataService.complete(request, null);

        assertTrue(response.getSkipped().stream().anyMatch(item -> item.contains("缺少有效分层")));
        verify(dataTableService, never()).updateTable(any(), any(), any());
    }

    @Test
    void completeRejectsRequestWithoutLocator() {
        AgentMetadataCompleteRequest request = new AgentMetadataCompleteRequest();
        assertThrows(
                IllegalArgumentException.class,
                () -> backendAgentMetadataService.complete(request, null)
        );
    }
}
