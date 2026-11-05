package com.onedata.portal.service.table;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.onedata.portal.dto.TableColumnRequest;
import com.onedata.portal.dto.TableCreateRequest;
import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.service.DatasourceSubjectAccessService;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

@ExtendWith(MockitoExtension.class)
class DorisTableEngineHandlerTest {

    @Mock private DatasourceSubjectAccessService dorisConnectionService;

    private TableCreateRequest sampleRequest() {
        TableColumnRequest col = new TableColumnRequest();
        col.setColumnName("order_id");
        col.setDataType("BIGINT");
        TableCreateRequest req = new TableCreateRequest();
        req.setDbName("dwd");
        req.setTableModel("DUPLICATE");
        req.setReplicaNum(3);
        req.setBucketNum(10);
        req.setKeyColumns(Arrays.asList("dt", "order_id"));
        req.setDistributionColumns(Collections.singletonList("order_id"));
        req.setColumns(Collections.singletonList(col));
        return req;
    }

    @Test
    void buildCreateDdlOmitsStorageFormatAndCompression() {
        String ddl =
                new DorisTableEngineHandler(dorisConnectionService)
                        .buildCreateDdl("dwd_user_order_di", sampleRequest());

        // storage_format / compression are Doris defaults and are no longer emitted.
        assertFalse(ddl.contains("storage_format"), ddl);
        assertFalse(ddl.contains("compression"), ddl);
        // PROPERTIES keeps only replication_num, and closes cleanly (no dangling comma).
        assertTrue(ddl.contains("\"replication_num\" = \"3\""), ddl);
        assertTrue(ddl.contains("\"replication_num\" = \"3\"\n)"), ddl);
        // Core structure is unchanged.
        assertTrue(ddl.contains("ENGINE=OLAP"), ddl);
        assertTrue(ddl.contains("DISTRIBUTED BY HASH(`order_id`) BUCKETS 10"), ddl);
    }

    private DataField field(String name, String type, String comment, String defaultValue) {
        DataField field = new DataField();
        field.setFieldName(name);
        field.setFieldType(type);
        field.setFieldComment(comment);
        field.setDefaultValue(defaultValue);
        field.setIsNullable(1);
        field.setIsPrimary(0);
        return field;
    }

    private DataTable table(String tableModel, String keyColumns) {
        DataTable table = new DataTable();
        table.setTableModel(tableModel);
        table.setKeyColumns(keyColumns);
        return table;
    }

    @Test
    void commentOnlyChangeUsesLightweightCommentDdl() {
        DorisTableEngineHandler handler = new DorisTableEngineHandler(dorisConnectionService);

        handler.updateColumn(
                8L,
                table("DUPLICATE", "order_status"),
                "dw",
                "dwd_orders",
                field("order_status", "INT", "", null),
                field("order_status", "INT", "订单状态", null));

        verify(dorisConnectionService)
                .executeAsPrincipal(
                        eq(8L),
                        eq("dw"),
                        org.mockito.ArgumentMatchers.contains(
                                "MODIFY COLUMN `order_status` COMMENT '订单状态'"));
    }

    @Test
    void commentOnlyChangeOnAggregateTableUsesLightweightCommentDdl() {
        DorisTableEngineHandler handler = new DorisTableEngineHandler(dorisConnectionService);

        handler.updateColumn(
                8L,
                table("AGGREGATE", "order_status"),
                "dw",
                "dws_orders",
                field("order_status", "INT", null, null),
                field("order_status", "INT", "订单状态", ""));

        verify(dorisConnectionService)
                .executeAsPrincipal(
                        eq(8L),
                        eq("dw"),
                        org.mockito.ArgumentMatchers.contains(
                                "MODIFY COLUMN `order_status` COMMENT '订单状态'"));
    }

    @Test
    void blankDefaultValueIsNotTreatedAsDefinitionChange() {
        DorisTableEngineHandler handler = new DorisTableEngineHandler(dorisConnectionService);

        // 前端表单把未设置的缺省值回填成空串，不应被当作定义变更
        handler.updateColumn(
                8L,
                table("DUPLICATE", "order_status"),
                "dw",
                "dwd_orders",
                field("order_status", "INT", "订单状态", null),
                field("order_status", "INT", "订单状态", ""));

        verify(dorisConnectionService, never())
                .executeAsPrincipal(anyLong(), anyString(), anyString());
    }

    @Test
    void definitionChangeOnKeyColumnKeepsKeyMarker() {
        DorisTableEngineHandler handler = new DorisTableEngineHandler(dorisConnectionService);

        handler.updateColumn(
                8L,
                table("DUPLICATE", "order_status,dt"),
                "dw",
                "dwd_orders",
                field("order_status", "INT", "订单状态", null),
                field("order_status", "BIGINT", "订单状态", null));

        // key 列必须带 KEY 标记，否则 Doris 报 Invalid column order. value should be after key
        verify(dorisConnectionService)
                .executeAsPrincipal(
                        eq(8L), eq("dw"), org.mockito.ArgumentMatchers.contains("BIGINT KEY"));
    }

    @Test
    void definitionChangeOnValueColumnDoesNotMarkKey() {
        DorisTableEngineHandler handler = new DorisTableEngineHandler(dorisConnectionService);

        handler.updateColumn(
                8L,
                table("DUPLICATE", "order_status"),
                "dw",
                "dwd_orders",
                field("amount", "INT", "金额", null),
                field("amount", "BIGINT", "金额", null));

        verify(dorisConnectionService)
                .executeAsPrincipal(
                        eq(8L),
                        eq("dw"),
                        org.mockito.ArgumentMatchers.argThat(
                                sql -> sql.contains("BIGINT") && !sql.contains("BIGINT KEY")));
    }

    @Test
    void unchangedColumnIssuesNoDdl() {
        DorisTableEngineHandler handler = new DorisTableEngineHandler(dorisConnectionService);

        handler.updateColumn(
                8L,
                table("DUPLICATE", "order_status"),
                "dw",
                "dwd_orders",
                field("amount", "BIGINT", "金额", null),
                field("amount", "BIGINT", "金额", null));

        verify(dorisConnectionService, never())
                .executeAsPrincipal(anyLong(), anyString(), anyString());
    }
}
