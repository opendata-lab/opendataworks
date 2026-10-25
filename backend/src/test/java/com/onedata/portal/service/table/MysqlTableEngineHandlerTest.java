package com.onedata.portal.service.table;

import com.onedata.portal.entity.DataField;
import com.onedata.portal.entity.DataTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MysqlTableEngineHandlerTest {

    @Mock
    private TableEngineJdbcExecutor jdbcExecutor;

    @Test
    void alterTableCommentExecutesMysqlCommentDdl() {
        MysqlTableEngineHandler handler = new MysqlTableEngineHandler(jdbcExecutor);

        handler.alterTableComment(8L, "dw", "fact_orders", "order's table");

        verify(jdbcExecutor).execute(8L, "dw",
                "ALTER TABLE `dw`.`fact_orders` COMMENT = 'order''s table'",
                "MYSQL");
    }

    @Test
    void addColumnExecutesMysqlAddColumnDdl() {
        MysqlTableEngineHandler handler = new MysqlTableEngineHandler(jdbcExecutor);
        DataField field = new DataField();
        field.setFieldName("amount");
        field.setFieldType("BIGINT");
        field.setIsNullable(0);
        field.setDefaultValue("0");
        field.setFieldComment("order's amount");

        handler.addColumn(8L, new DataTable(), "dw", "fact_orders", field);

        verify(jdbcExecutor).execute(8L, "dw",
                "ALTER TABLE `dw`.`fact_orders` ADD COLUMN `amount` BIGINT NOT NULL DEFAULT 0 COMMENT 'order''s amount'",
                "MYSQL");
    }

    @Test
    void updateColumnExecutesRenameThenModifyWhenNameAndDefinitionChange() {
        MysqlTableEngineHandler handler = new MysqlTableEngineHandler(jdbcExecutor);
        DataField oldField = new DataField();
        oldField.setFieldName("amount");
        oldField.setFieldType("BIGINT");
        oldField.setIsNullable(0);

        DataField newField = new DataField();
        newField.setFieldName("total_amount");
        newField.setFieldType("DECIMAL(12,2)");
        newField.setIsNullable(1);
        newField.setFieldComment("total");

        handler.updateColumn(8L, new DataTable(), "dw", "fact_orders", oldField, newField);

        InOrder inOrder = inOrder(jdbcExecutor);
        inOrder.verify(jdbcExecutor).execute(8L, "dw",
                "ALTER TABLE `dw`.`fact_orders` RENAME COLUMN `amount` TO `total_amount`",
                "MYSQL");
        inOrder.verify(jdbcExecutor).execute(8L, "dw",
                "ALTER TABLE `dw`.`fact_orders` MODIFY COLUMN `total_amount` DECIMAL(12,2) NULL COMMENT 'total'",
                "MYSQL");
    }

    @Test
    void dropColumnExecutesMysqlDropColumnDdl() {
        MysqlTableEngineHandler handler = new MysqlTableEngineHandler(jdbcExecutor);

        handler.dropColumn(8L, "dw", "fact_orders", "amount");

        verify(jdbcExecutor).execute(8L, "dw",
                "ALTER TABLE `dw`.`fact_orders` DROP COLUMN `amount`",
                "MYSQL");
    }

    @Test
    void mysqlRejectsDorisOnlyReplicationSetting() {
        MysqlTableEngineHandler handler = new MysqlTableEngineHandler(jdbcExecutor);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> handler.setReplicationNum(8L, "dw", "fact_orders", 3));

        assertEquals("MySQL 数据源不支持副本数设置", exception.getMessage());
    }
}
