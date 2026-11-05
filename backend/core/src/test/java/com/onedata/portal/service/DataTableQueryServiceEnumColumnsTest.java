package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.onedata.portal.dto.ColumnValueProfile;
import com.onedata.portal.dto.TableLocation;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * {@link DataTableQueryService#profileEnumColumns} 的候选列筛选行为。
 *
 * <p>智能元数据的枚举取值必须来自真实数据，这里锁定「哪些列会被拿去查取值」： 类型不可分组的列与标识类命名的列不参与，宽表也不会打出无上限的分组查询。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataTableQueryServiceEnumColumnsTest {

    @Mock private DataTableService dataTableService;

    @Mock private DorisConnectionService dorisConnectionService;

    @Mock private TableStatisticsCacheService cacheService;

    @Mock private TableStatisticsHistoryService historyService;

    @Mock private DorisTableAccessService dorisTableAccessService;

    private DataTableQueryService service() {
        return new DataTableQueryService(
                dataTableService,
                dorisConnectionService,
                cacheService,
                historyService,
                dorisTableAccessService,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private DataField field(String name, String type) {
        DataField field = new DataField();
        field.setFieldName(name);
        field.setFieldType(type);
        return field;
    }

    private void givenTableWithFields(List<DataField> fields) {
        DataTable table = new DataTable();
        table.setId(1L);
        table.setDbName("dw");
        table.setTableName("dwd_orders");
        when(dataTableService.getById(1L)).thenReturn(table);
        when(dataTableService.listFields(1L)).thenReturn(fields);
        when(dataTableService.requireTableLocation(table))
                .thenReturn(new TableLocation("dw", "dwd_orders"));
        when(dorisConnectionService.profileColumnValues(
                        any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedCandidates() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(dorisConnectionService)
                .profileColumnValues(any(), eq("dw"), eq("dwd_orders"), captor.capture(), anyInt());
        return captor.getValue();
    }

    @Test
    void skipsIdentifierColumnsAndNonGroupableTypes() {
        givenTableWithFields(
                Arrays.asList(
                        field("id", "BIGINT"),
                        field("order_id", "BIGINT"),
                        field("user_no", "VARCHAR(64)"),
                        field("biz_key", "VARCHAR(64)"),
                        field("created_at", "DATETIME"),
                        field("amount", "DECIMAL(12,2)"),
                        field("ext", "JSON"),
                        field("order_status", "INT"),
                        field("pay_channel", "VARCHAR(32)"),
                        field("is_deleted", "TINYINT")));

        service().profileEnumColumns(1L, 8L);

        assertEquals(
                Arrays.asList("order_status", "pay_channel", "is_deleted"), capturedCandidates());
    }

    @Test
    void capsCandidateColumnsOnWideTable() {
        List<DataField> fields = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            fields.add(field("flag_" + i, "INT"));
        }
        givenTableWithFields(fields);

        service().profileEnumColumns(1L, 8L);

        assertEquals(20, capturedCandidates().size());
    }

    @Test
    void skipsQueryEntirelyWhenNoCandidateColumn() {
        givenTableWithFields(Arrays.asList(field("id", "BIGINT"), field("created_at", "DATETIME")));

        assertTrue(service().profileEnumColumns(1L, 8L).isEmpty());

        verify(dorisConnectionService, never())
                .profileColumnValues(any(), anyString(), anyString(), any(), anyInt());
    }

    @Test
    void fillsFieldTypeBackOntoReturnedProfiles() {
        givenTableWithFields(Collections.singletonList(field("order_status", "INT")));
        ColumnValueProfile profile = new ColumnValueProfile();
        profile.setFieldName("order_status");
        profile.setDistinctCount(2);
        profile.setValues(
                Arrays.asList(
                        new ColumnValueProfile.ColumnValueCount("0", 12L),
                        new ColumnValueProfile.ColumnValueCount("1", 8L)));
        when(dorisConnectionService.profileColumnValues(
                        any(), anyString(), anyString(), any(), anyInt()))
                .thenReturn(new ArrayList<>(Collections.singletonList(profile)));

        List<ColumnValueProfile> profiles = service().profileEnumColumns(1L, 8L);

        assertEquals(1, profiles.size());
        assertEquals("INT", profiles.get(0).getFieldType());
        assertEquals("0", profiles.get(0).getValues().get(0).getValue());
    }
}
