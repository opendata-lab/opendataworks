package com.onedata.portal.service;

import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.service.table.TableEngineHandler;
import com.onedata.portal.service.table.TableEngineHandlerRegistry;
import com.onedata.portal.util.DatasourceType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DataTableServiceTest {

    @Mock
    private DataTableMapper dataTableMapper;

    @Mock
    private DataFieldMapper dataFieldMapper;

    @Mock
    private TableTaskRelationMapper tableTaskRelationMapper;

    @Mock
    private DataTaskMapper dataTaskMapper;

    @Mock
    private TaskExecutionLogMapper taskExecutionLogMapper;

    @Mock
    private DataLineageMapper dataLineageMapper;

    @Mock
    private DorisClusterMapper dorisClusterMapper;

    @Mock
    private TableEngineHandlerRegistry tableEngineHandlerRegistry;

    @Mock
    private TableEngineHandler dorisHandler;

    @Mock
    private TableEngineHandler mysqlHandler;

    @Mock
    private TableMetadataVersionService tableMetadataVersionService;

    private DataTableService service;

    @Mock
    private com.onedata.portal.service.lineage.TaskLineageWriteService taskLineageWriteService;

    @BeforeEach
    void setUp() {
        service = new DataTableService(
                dataTableMapper,
                dataFieldMapper,
                tableTaskRelationMapper,
                dataTaskMapper,
                taskExecutionLogMapper,
                dataLineageMapper,
                dorisClusterMapper,
                tableEngineHandlerRegistry,
                DatasourceAdapterFixtures.registry(new com.onedata.portal.config.DorisJdbcProperties()),
                tableMetadataVersionService,
                taskLineageWriteService);
    }

    @Test
    void createClearsDorisPhysicalFieldsWhenTableHasNoDatasource() {
        DataTable table = table(null, null, 0);
        table.setDbName("dw");
        table.setTableName("metadata_table");
        table.setTableModel("DUPLICATE");
        table.setBucketNum(10);
        table.setReplicaNum(1);
        table.setPartitionColumn("dt");
        table.setDistributionColumn("id");
        table.setKeyColumns("id");
        table.setDorisDdl("CREATE TABLE ...");
        when(dataTableMapper.selectOne(any())).thenReturn(null);

        DataTable result = service.create(table);

        assertSame(table, result);
        assertNull(table.getTableModel());
        assertNull(table.getBucketNum());
        assertNull(table.getReplicaNum());
        assertNull(table.getPartitionColumn());
        assertNull(table.getDistributionColumn());
        assertNull(table.getKeyColumns());
        assertNull(table.getDorisDdl());
        verify(dataTableMapper).insert(table);
        verifyNoInteractions(dorisClusterMapper);
        verifyNoInteractions(tableEngineHandlerRegistry);
    }

    @Test
    void createClearsDorisPhysicalFieldsForMysqlDatasource() {
        DataTable table = table(null, 8L, 0);
        table.setDbName("dw");
        table.setTableName("metadata_table");
        table.setTableModel("DUPLICATE");
        table.setBucketNum(10);
        table.setReplicaNum(1);
        table.setDistributionColumn("id");
        when(dorisClusterMapper.selectById(8L)).thenReturn(cluster(8L, "MYSQL"));
        when(tableEngineHandlerRegistry.find(DatasourceType.MYSQL)).thenReturn(mysqlHandler);
        doAnswer(invocation -> {
            TableEngineHandler.clearDorisPhysicalMetadata(invocation.getArgument(0));
            return null;
        }).when(mysqlHandler).prepareCreateMetadata(eq(table), isNull(), isNull());
        when(dataTableMapper.selectOne(any())).thenReturn(null);

        DataTable result = service.create(table);

        assertSame(table, result);
        assertNull(table.getTableModel());
        assertNull(table.getBucketNum());
        assertNull(table.getReplicaNum());
        assertNull(table.getDistributionColumn());
        verify(dataTableMapper).insert(table);
    }

    @Test
    void createFieldDoesNotTreatDefaultReplicaNumAsDorisWhenTableIsUnsynced() {
        DataTable table = table(10L, null, 0);
        table.setReplicaNum(1);
        when(dataTableMapper.selectById(10L)).thenReturn(table);
        when(dataFieldMapper.selectOne(any())).thenReturn(null);

        DataField field = field("amount", "BIGINT");

        DataField result = service.createField(10L, field, null);

        assertSame(field, result);
        verify(dataFieldMapper).insert(field);
        verify(tableMetadataVersionService).captureVersion(
                eq(10L), eq(TableMetadataVersionService.TRIGGER_MANUAL_EDIT), eq(null));
        verifyNoInteractions(dorisClusterMapper);
        verifyNoInteractions(tableEngineHandlerRegistry);
    }

    @Test
    void createFieldUsesTableDatasourceWhenSyncedDorisTableDoesNotPassClusterId() {
        DataTable table = table(10L, 7L, 1);
        table.setDbName("dw");
        table.setTableName("fact_orders");
        when(dataTableMapper.selectById(10L)).thenReturn(table);
        when(dataFieldMapper.selectOne(any())).thenReturn(null);
        when(dorisClusterMapper.selectById(7L)).thenReturn(cluster(7L, "DORIS"));
        when(tableEngineHandlerRegistry.require(DatasourceType.DORIS)).thenReturn(dorisHandler);

        DataField field = field("amount", "BIGINT");

        DataField result = service.createField(10L, field, null);

        assertSame(field, result);
        verify(dorisHandler).addColumn(7L, table, "dw", "fact_orders", field);
        verify(dataFieldMapper).insert(field);
    }

    @Test
    void createFieldDelegatesSyncedMysqlTableToMysqlHandler() {
        DataTable table = table(10L, 8L, 1);
        table.setDbName("dw");
        table.setTableName("fact_orders");
        when(dataTableMapper.selectById(10L)).thenReturn(table);
        when(dataFieldMapper.selectOne(any())).thenReturn(null);
        when(dorisClusterMapper.selectById(8L)).thenReturn(cluster(8L, "MYSQL"));
        when(tableEngineHandlerRegistry.require(DatasourceType.MYSQL)).thenReturn(mysqlHandler);

        DataField field = field("amount", "BIGINT");
        DataField result = service.createField(10L, field, null);

        assertSame(field, result);
        verify(mysqlHandler).addColumn(8L, table, "dw", "fact_orders", field);
        verify(dataFieldMapper).insert(field);
    }

    @Test
    void updateTableAllowsMetadataOnlyChangeForSyncedNonDorisTable() {
        DataTable existing = table(10L, 8L, 1);
        existing.setDbName("dw");
        existing.setTableName("fact_orders");
        when(dataTableMapper.selectById(10L)).thenReturn(existing);

        DataTable update = new DataTable();
        update.setLayer("ADS");
        update.setOwner("alice");

        DataTable result = service.updateTable(10L, update, null);

        assertSame(update, result);
        verify(dataTableMapper).updateById(update);
        verifyNoInteractions(dorisClusterMapper);
        verifyNoInteractions(tableEngineHandlerRegistry);
    }

    @Test
    void updateTableDelegatesPhysicalChangeForSyncedMysqlTable() {
        DataTable existing = table(10L, 8L, 1);
        existing.setDbName("dw");
        existing.setTableName("fact_orders");
        existing.setTableComment("old comment");
        when(dataTableMapper.selectById(10L)).thenReturn(existing);
        when(dorisClusterMapper.selectById(8L)).thenReturn(cluster(8L, "MYSQL"));

        DataTable update = new DataTable();
        update.setLayer("DWD");
        update.setTableComment("new comment");
        when(tableEngineHandlerRegistry.require(DatasourceType.MYSQL)).thenReturn(mysqlHandler);

        DataTable result = service.updateTable(10L, update, null);

        assertSame(update, result);
        verify(mysqlHandler).alterTableComment(8L, "dw", "fact_orders", "new comment");
        verify(dataTableMapper).updateById(update);
    }

    private DataTable table(Long id, Long clusterId, Integer isSynced) {
        DataTable table = new DataTable();
        table.setId(id);
        table.setClusterId(clusterId);
        table.setTableName("metadata_table");
        table.setLayer("DWD");
        table.setIsSynced(isSynced);
        return table;
    }

    private DataField field(String name, String type) {
        DataField field = new DataField();
        field.setFieldName(name);
        field.setFieldType(type);
        return field;
    }

    @Test
    void purgeCollectsAffectedWorkflowsBeforeDeleteAndRefreshesDefinitionOnce() {
        // 删表会让引用它的任务少掉一条边，definitionJson 必须同步重建；
        // 受影响工作流只能在删除关系之前收集，删完就查不到归属了。
        java.util.Set<Long> taskIds = new java.util.LinkedHashSet<>(java.util.Arrays.asList(1L, 2L));
        java.util.Set<Long> workflowIds = new java.util.LinkedHashSet<>(java.util.Arrays.asList(10L));
        when(taskLineageWriteService.findTaskIdsByTableId(5L)).thenReturn(taskIds);
        when(taskLineageWriteService.findWorkflowIdsByTaskIds(taskIds)).thenReturn(workflowIds);

        service.purgeTableMetadata(5L);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(
                taskLineageWriteService, dataTableMapper);
        inOrder.verify(taskLineageWriteService).findTaskIdsByTableId(5L);
        inOrder.verify(dataTableMapper).deleteById(5L);
        inOrder.verify(taskLineageWriteService).refreshWorkflowDefinitions(workflowIds, null);
    }

    private DorisCluster cluster(Long id, String sourceType) {
        DorisCluster cluster = new DorisCluster();
        cluster.setId(id);
        cluster.setClusterName("test-" + id);
        cluster.setSourceType(sourceType);
        return cluster;
    }
}
