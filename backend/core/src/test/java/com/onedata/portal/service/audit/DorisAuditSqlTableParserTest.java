package com.onedata.portal.service.audit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DorisAuditSqlTableParserTest {

    private final DorisAuditSqlTableParser parser = new DorisAuditSqlTableParser();

    @Test
    void classifiesInsertSelectSourcesAndTargetWithoutDoubleCounting() {
        List<AuditTableReference> references = parser.parse(
                "INSERT INTO ads.order_summary "
                        + "SELECT a.id FROM dwd.orders a JOIN dim.users u ON a.user_id = u.id "
                        + "JOIN dwd.orders b ON b.id = a.id",
                "default_db");

        Map<String, AuditTableReference> index = references.stream()
                .collect(Collectors.toMap(
                        item -> item.getDatabaseName() + "." + item.getTableName(),
                        item -> item));

        assertEquals(3, index.size());
        assertTrue(index.get("ads.order_summary").isWrite());
        assertFalse(index.get("ads.order_summary").isRead());
        assertTrue(index.get("dwd.orders").isRead());
        assertFalse(index.get("dwd.orders").isWrite());
        assertTrue(index.get("dim.users").isRead());
    }

    @Test
    void dropsAuditSourceSoSyncDoesNotCountItself() {
        List<AuditTableReference> internalSchema = parser.parse(
                "SELECT `time`, stmt FROM `__internal_schema`.`audit_log` WHERE `time` > '2026-07-30'",
                "dwd");
        List<AuditTableReference> legacyAuditDb = parser.parse(
                "SELECT stmt FROM doris_audit_db__.doris_audit_tbl__ LIMIT 1", "dwd");

        assertTrue(internalSchema.isEmpty());
        assertTrue(legacyAuditDb.isEmpty());
    }

    @Test
    void dropsSystemSchemasButKeepsBusinessTablesInTheSameStatement() {
        List<AuditTableReference> references = parser.parse(
                "SELECT t.name FROM information_schema.tables t "
                        + "JOIN mysql.user u ON u.name = t.name "
                        + "JOIN dwd.orders o ON o.id = t.id",
                "dwd");

        assertEquals(1, references.size());
        assertEquals("dwd", references.get(0).getDatabaseName());
        assertEquals("orders", references.get(0).getTableName());
    }

    @Test
    void ignoresCteNamesButKeepsTheirSourceTables() {
        List<AuditTableReference> references = parser.parse(
                "WITH recent AS (SELECT * FROM dwd.orders), "
                        + "ranked AS (SELECT * FROM recent) "
                        + "SELECT * FROM ranked JOIN recent ON 1 = 1",
                "dwd");

        assertEquals(1, references.size());
        assertEquals("dwd", references.get(0).getDatabaseName());
        assertEquals("orders", references.get(0).getTableName());
    }

    @Test
    void keepsQualifiedTableThatSharesNameWithCte() {
        List<AuditTableReference> references = parser.parse(
                "WITH orders AS (SELECT 1) SELECT * FROM dwd.orders", "ads");

        assertEquals(1, references.size());
        assertEquals("dwd", references.get(0).getDatabaseName());
        assertEquals("orders", references.get(0).getTableName());
    }

    @Test
    void doubledQuoteInsideLiteralDoesNotSwallowTheRealTable() {
        // 只跳过转义引号的前一个字符会让字面量提前结束，残留引号再开启一段新“字面量”，
        // 把后面真正的表引用整段吞掉。
        List<AuditTableReference> references = parser.parse(
                "SELECT 'it''s here' AS note FROM dwd.orders", "dwd");

        assertEquals(1, references.size());
        assertEquals("dwd", references.get(0).getDatabaseName());
        assertEquals("orders", references.get(0).getTableName());
    }

    @Test
    void ignoresTableTokensInsideStringLiteralsAndComments() {
        List<AuditTableReference> references = parser.parse(
                "SELECT 'copied from dwd.ghost_one' AS note, \"join dwd.ghost_two\" AS other "
                        + "-- from dwd.ghost_three\n"
                        + "/* join dwd.ghost_four */ "
                        + "FROM dwd.orders",
                "dwd");

        assertEquals(1, references.size());
        assertEquals("orders", references.get(0).getTableName());
    }

    @Test
    void usesDefaultDatabaseAndClassifiesUpdateAndDeleteAsWrites() {
        List<AuditTableReference> references = parser.parse(
                "UPDATE orders SET status = 1; DELETE FROM order_items WHERE id = 2",
                "DWD");

        assertEquals(2, references.size());
        assertTrue(references.stream().allMatch(AuditTableReference::isWrite));
        assertTrue(references.stream().allMatch(item -> "dwd".equals(item.getDatabaseName())));
    }
}
