package com.onedata.portal.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DorisCreateTableUtilsTest {

    @Test
    void parseReplicationNum_prefersReplicationAllocationForNonDynamicPartition() {
        String ddl = "CREATE TABLE `db`.`t` (\n" +
                "  `id` INT NULL\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`id`)\n" +
                "DISTRIBUTED BY HASH(`id`) BUCKETS 10\n" +
                "PROPERTIES (\n" +
                "  \"replication_allocation\" = \"tag.location.default: 3\",\n" +
                "  \"storage_format\" = \"V2\"\n" +
                ");";

        assertEquals(3, DorisCreateTableUtils.parseReplicationNum(ddl));
    }

    @Test
    void parseReplicationNum_sumsReplicaCountAcrossTags() {
        String ddl = "PROPERTIES (\n" +
                "  \"replication_allocation\" = \"tag.location.default: 2, tag.location.other: 1\"\n" +
                ");";

        assertEquals(3, DorisCreateTableUtils.parseReplicationNum(ddl));
    }

    @Test
    void parseReplicationNum_prefersDynamicPartitionReplicationAllocationWhenEnabled() {
        String ddl = "PROPERTIES (\n" +
                "  \"dynamic_partition.enable\" = \"true\",\n" +
                "  \"dynamic_partition.replication_allocation\" = \"tag.location.default: 3\",\n" +
                "  \"replication_allocation\" = \"tag.location.default: 1\"\n" +
                ");";

        assertEquals(3, DorisCreateTableUtils.parseReplicationNum(ddl));
    }

    @Test
    void parseReplicationNum_fallsBackToReplicationNum() {
        String ddl = "PROPERTIES (\n" +
                "  \"replication_num\" = \"3\"\n" +
                ");";

        assertEquals(3, DorisCreateTableUtils.parseReplicationNum(ddl));
    }

    @Test
    void parseReplicationNum_fallsBackToDynamicPartitionReplicationNum() {
        String ddl = "PROPERTIES (\n" +
                "  \"dynamic_partition.enable\" = \"true\",\n" +
                "  \"dynamic_partition.replication_num\" = \"2\"\n" +
                ");";

        assertEquals(2, DorisCreateTableUtils.parseReplicationNum(ddl));
    }

    @Test
    void parseReplicationNum_returnsNullWhenMissing() {
        assertNull(DorisCreateTableUtils.parseReplicationNum(""));
        assertNull(DorisCreateTableUtils.parseReplicationNum(null));
        assertNull(DorisCreateTableUtils.parseReplicationNum("CREATE TABLE t (id INT)"));
    }

    @Test
    void parsePartitionColumn_parsesRangePartitionColumn() {
        String ddl = "CREATE TABLE `db`.`t` (\n" +
                "  `id` BIGINT,\n" +
                "  `dt` DATE\n" +
                ") ENGINE=OLAP\n" +
                "DUPLICATE KEY(`id`)\n" +
                "PARTITION BY RANGE(`dt`) (\n" +
                "  PARTITION p202601 VALUES LESS THAN ('2026-02-01')\n" +
                ")\n" +
                "DISTRIBUTED BY HASH(`id`) BUCKETS 10\n" +
                "PROPERTIES (\"replication_allocation\" = \"tag.location.default: 3\");";

        assertEquals("dt", DorisCreateTableUtils.parsePartitionColumn(ddl));
    }

    @Test
    void parsePartitionColumn_supportsLowercaseAndWhitespace() {
        String ddl = "create table t (\n" +
                "  id bigint,\n" +
                "  biz_date date\n" +
                ") engine=olap\n" +
                "partition   by   range (  `biz_date`  )\n" +
                "(partition p1 values less than ('2026-01-01'))";

        assertEquals("biz_date", DorisCreateTableUtils.parsePartitionColumn(ddl));
    }

    @Test
    void parsePartitionColumn_unescapesEscapedBackticks() {
        String ddl = "CREATE TABLE t (\n" +
                "  id BIGINT,\n" +
                "  dt DATE\n" +
                ") ENGINE=OLAP\n" +
                "PARTITION BY RANGE(\\`dt\\`) ()\n" +
                "DISTRIBUTED BY HASH(`id`) BUCKETS 8";

        assertEquals("dt", DorisCreateTableUtils.parsePartitionColumn(ddl));
    }

    @Test
    void parsePartitionColumn_supportsExpressionPartition() {
        String ddl = "CREATE TABLE t (\n" +
                "  id BIGINT,\n" +
                "  event_time DATETIME\n" +
                ") ENGINE=OLAP\n" +
                "PARTITION BY RANGE(date_trunc('day', `event_time`)) ()\n" +
                "DISTRIBUTED BY HASH(`id`) BUCKETS 8";

        assertEquals("date_trunc('day', `event_time`)", DorisCreateTableUtils.parsePartitionColumn(ddl));
    }

    @Test
    void parsePartitionColumn_returnsNullWhenNoPartitionBy() {
        assertNull(DorisCreateTableUtils.parsePartitionColumn(""));
        assertNull(DorisCreateTableUtils.parsePartitionColumn(null));
        assertNull(DorisCreateTableUtils.parsePartitionColumn("CREATE TABLE t (id INT) ENGINE=OLAP"));
    }
}
