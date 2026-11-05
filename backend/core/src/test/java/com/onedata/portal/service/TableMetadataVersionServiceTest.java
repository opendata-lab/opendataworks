package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.table.TableVersionCompareRequest;
import com.onedata.portal.dto.table.TableVersionCompareResponse;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTableVersion;
import com.onedata.portal.mapper.DataFieldMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTableVersionMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TableMetadataVersionServiceTest {

    static {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, DataTable.class);
        TableInfoHelper.initTableInfo(assistant, DataField.class);
        TableInfoHelper.initTableInfo(assistant, DataTableVersion.class);
    }

    private static final Long TABLE_ID = 100L;

    @Mock
    private DataTableMapper dataTableMapper;

    @Mock
    private DataFieldMapper dataFieldMapper;

    @Mock
    private DataTableVersionMapper dataTableVersionMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TableMetadataVersionService service;

    @BeforeEach
    void setUp() {
        service = new TableMetadataVersionService(
                dataTableMapper, dataFieldMapper, dataTableVersionMapper, objectMapper);
        lenient().when(dataTableVersionMapper.insert(any(DataTableVersion.class))).thenReturn(1);
    }

    @Test
    void firstCaptureCreatesVersionOne() {
        when(dataTableMapper.selectById(TABLE_ID)).thenReturn(sampleTable());
        when(dataFieldMapper.selectList(any())).thenReturn(sampleFields());
        when(dataTableVersionMapper.selectOne(any())).thenReturn(null);

        DataTableVersion version = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_TABLE_CREATE, "alice");

        assertNotNull(version);
        assertEquals(1, version.getVersionNo());
        assertEquals(TABLE_ID, version.getTableId());
        assertEquals("初始版本快照", version.getChangeSummary());
        assertEquals("alice", version.getCreatedBy());
        assertNotNull(version.getSnapshotHash());
        assertEquals(64, version.getSnapshotHash().length());
        assertTrue(version.getMetadataSnapshot().contains("\"tableName\""));
        verify(dataTableVersionMapper).insert(any(DataTableVersion.class));
    }

    @Test
    void identicalMetadataDoesNotCreateNewVersion() {
        when(dataTableMapper.selectById(TABLE_ID)).thenReturn(sampleTable());
        when(dataFieldMapper.selectList(any())).thenReturn(sampleFields());
        when(dataTableVersionMapper.selectOne(any())).thenReturn(null);

        DataTableVersion first = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);
        assertNotNull(first);

        when(dataTableVersionMapper.selectOne(any())).thenReturn(first);
        DataTableVersion second = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);

        assertNull(second);
        verify(dataTableVersionMapper, times(1)).insert(any(DataTableVersion.class));
    }

    @Test
    void volatileStatisticsChangeDoesNotCreateNewVersion() {
        DataTable table = sampleTable();
        when(dataTableMapper.selectById(TABLE_ID)).thenReturn(table);
        when(dataFieldMapper.selectList(any())).thenReturn(sampleFields());
        when(dataTableVersionMapper.selectOne(any())).thenReturn(null);

        DataTableVersion first = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_METADATA_SYNC, "system");
        assertNotNull(first);

        // 统计/同步类字段波动：不在快照白名单内，不得产生新版本
        table.setRowCount(999999L);
        table.setStorageSize(123456789L);
        table.setDorisDdl("CREATE TABLE ... PARTITION p20260611 ...");
        table.setSyncTime(LocalDateTime.now());
        table.setIsSynced(0);
        table.setDorisUpdateTime(LocalDateTime.now());

        when(dataTableVersionMapper.selectOne(any())).thenReturn(first);
        DataTableVersion second = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_METADATA_SYNC, "system");

        assertNull(second);
        verify(dataTableVersionMapper, times(1)).insert(any(DataTableVersion.class));
    }

    @Test
    void fieldCommentChangeCreatesNextVersionWithSummary() {
        DataTable table = sampleTable();
        List<DataField> fields = sampleFields();
        when(dataTableMapper.selectById(TABLE_ID)).thenReturn(table);
        when(dataFieldMapper.selectList(any())).thenReturn(fields);
        when(dataTableVersionMapper.selectOne(any())).thenReturn(null);

        DataTableVersion first = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);
        assertNotNull(first);

        fields.get(0).setFieldComment("主键ID（更新后）");
        when(dataTableVersionMapper.selectOne(any())).thenReturn(first);

        DataTableVersion second = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);

        assertNotNull(second);
        assertEquals(2, second.getVersionNo());
        assertTrue(second.getChangeSummary().contains("~id"),
                "summary should mark modified field: " + second.getChangeSummary());
    }

    @Test
    void tableAttributeChangeCreatesNextVersionWithSummary() {
        DataTable table = sampleTable();
        when(dataTableMapper.selectById(TABLE_ID)).thenReturn(table);
        when(dataFieldMapper.selectList(any())).thenReturn(sampleFields());
        when(dataTableVersionMapper.selectOne(any())).thenReturn(null);

        DataTableVersion first = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);

        table.setTableComment("订单明细表（新注释）");
        table.setBucketNum(20);
        when(dataTableVersionMapper.selectOne(any())).thenReturn(first);

        DataTableVersion second = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);

        assertNotNull(second);
        assertEquals(2, second.getVersionNo());
        assertTrue(second.getChangeSummary().contains("tableComment"));
        assertTrue(second.getChangeSummary().contains("bucketNum"));
    }

    @Test
    void fieldLoadOrderDoesNotAffectHash() {
        DataTable table = sampleTable();
        when(dataTableMapper.selectById(TABLE_ID)).thenReturn(table);
        when(dataFieldMapper.selectList(any())).thenReturn(sampleFields());
        when(dataTableVersionMapper.selectOne(any())).thenReturn(null);

        DataTableVersion first = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);
        assertNotNull(first);

        List<DataField> reversed = new ArrayList<>(sampleFields());
        Collections.reverse(reversed);
        when(dataFieldMapper.selectList(any())).thenReturn(reversed);
        when(dataTableVersionMapper.selectOne(any())).thenReturn(first);

        DataTableVersion second = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);

        assertNull(second);
    }

    @Test
    void deletedOrMissingTableIsNoOp() {
        when(dataTableMapper.selectById(TABLE_ID)).thenReturn(null);

        DataTableVersion version = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);

        assertNull(version);
        verify(dataTableVersionMapper, never()).insert(any(DataTableVersion.class));
    }

    @Test
    void captureNeverThrows() {
        when(dataTableMapper.selectById(TABLE_ID)).thenThrow(new RuntimeException("db down"));

        DataTableVersion version = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_METADATA_SYNC, "system");

        assertNull(version);
    }

    @Test
    void compareReturnsStructuredDiff() {
        DataTable table = sampleTable();
        List<DataField> fields = sampleFields();
        when(dataTableMapper.selectById(TABLE_ID)).thenReturn(table);
        when(dataFieldMapper.selectList(any())).thenReturn(fields);
        when(dataTableVersionMapper.selectOne(any())).thenReturn(null);

        ArgumentCaptor<DataTableVersion> captor = ArgumentCaptor.forClass(DataTableVersion.class);
        DataTableVersion v1 = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_TABLE_CREATE, null);
        v1.setId(1L);

        table.setTableComment("新注释");
        DataField added = field("new_col", "VARCHAR(64)", "新增列", 3);
        fields.add(added);
        when(dataTableVersionMapper.selectOne(any())).thenReturn(v1);
        DataTableVersion v2 = service.captureVersion(TABLE_ID,
                TableMetadataVersionService.TRIGGER_MANUAL_EDIT, null);
        v2.setId(2L);
        verify(dataTableVersionMapper, times(2)).insert(captor.capture());

        when(dataTableVersionMapper.selectById(1L)).thenReturn(v1);
        when(dataTableVersionMapper.selectById(2L)).thenReturn(v2);

        TableVersionCompareRequest request = new TableVersionCompareRequest();
        // 故意把新版本放在 left，验证服务端会按版本号交换方向
        request.setLeftVersionId(2L);
        request.setRightVersionId(1L);
        TableVersionCompareResponse response = service.compare(TABLE_ID, request);

        assertEquals(1, response.getLeftVersionNo());
        assertEquals(2, response.getRightVersionNo());
        assertTrue(response.getChanged());
        assertEquals(1, response.getTableAttributeChanges().size());
        assertEquals("tableComment", response.getTableAttributeChanges().get(0).getName());
        assertEquals(Collections.singletonList("new_col"), response.getColumnsAdded());
        assertTrue(response.getColumnsRemoved().isEmpty());
        assertTrue(response.getColumnsModified().isEmpty());
        assertEquals(1, response.getSummary().getColumnsAddedCount());
        assertNotNull(response.getRawDiff());
        assertTrue(response.getRawDiff().contains("+++ v2"));
    }

    private DataTable sampleTable() {
        DataTable table = new DataTable();
        table.setId(TABLE_ID);
        table.setClusterId(1L);
        table.setTableName("dwd_order_detail");
        table.setDbName("dwd");
        table.setTableComment("订单明细表");
        table.setTableType("BASE TABLE");
        table.setLayer("DWD");
        table.setBusinessDomain("trade");
        table.setDataDomain("order");
        table.setOwner("alice");
        table.setStatus("active");
        table.setTableModel("UNIQUE");
        table.setPartitionColumn("dt");
        table.setDistributionColumn("id");
        table.setKeyColumns("id");
        table.setBucketNum(10);
        table.setReplicaNum(3);
        table.setRowCount(1000L);
        table.setStorageSize(2048L);
        table.setDorisDdl("CREATE TABLE dwd_order_detail (...)");
        table.setIsSynced(1);
        return table;
    }

    private List<DataField> sampleFields() {
        return new ArrayList<>(Arrays.asList(
                field("id", "BIGINT", "主键ID", 1),
                field("order_no", "VARCHAR(64)", "订单号", 2)));
    }

    private DataField field(String name, String type, String comment, int order) {
        DataField field = new DataField();
        field.setTableId(TABLE_ID);
        field.setFieldName(name);
        field.setFieldType(type);
        field.setFieldComment(comment);
        field.setIsNullable(0);
        field.setIsPrimary("id".equals(name) ? 1 : 0);
        field.setIsPartition(0);
        field.setFieldOrder(order);
        return field;
    }
}
