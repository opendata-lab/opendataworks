package com.onedata.portal.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JdbcUrlPartsTest {

    @Test
    void acceptsPlainHostsAndAddresses() {
        assertEquals("db.example.com", JdbcUrlParts.requireHost(" db.example.com "));
        assertEquals("10.0.0.5", JdbcUrlParts.requireHost("10.0.0.5"));
        assertEquals("data-portal_mysql", JdbcUrlParts.requireHost("data-portal_mysql"));
        assertEquals("::1", JdbcUrlParts.requireHost("::1"));
        assertEquals("[fe80::1]", JdbcUrlParts.requireHost("[fe80::1]"));
        assertEquals("postgres", JdbcUrlParts.requireHost("postgres"));
        assertEquals("db.example.com.", JdbcUrlParts.requireHost("db.example.com."));
        assertEquals("fe80::1%en0", JdbcUrlParts.requireHost("fe80::1%en0"));
    }

    @Test
    void zoneIdReachesThePostgresqlDriverUnescaped() throws Exception {
        // pgJDBC 不解码 host：未加括号的 %zone 原样到达驱动
        for (String zone : new String[] {"1", "251", "2525"}) {
            String url = PostgresqlMetadataSql.jdbcUrl("fe80::1%" + zone, 5432, "db");
            java.util.Properties props = org.postgresql.Driver.parseURL(url, new java.util.Properties());
            assertEquals("[fe80::1%" + zone + "]", props.getProperty("PGHOST"), url);
        }
    }

    @Test
    void validationIsIdempotent() {
        // 保存和连接都会校验；校验只判断不改写，同一个值反复校验结果不变
        for (String host : new String[] {"fe80::1%251", "fe80::1%2525", "db.example.com.", "[::1]"}) {
            assertEquals(host, JdbcUrlParts.requireHost(JdbcUrlParts.requireHost(host)));
        }
    }

    @Test
    void rejectsPercentEncodedZoneInsideBrackets() {
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParts.requireHost("[fe80::1%25en0]"));
    }

    @Test
    void rejectsHostsThatWouldInjectUrlParts() {
        // pgJDBC would read this as database "other" with user/sslmode overridden.
        assertThrows(IllegalArgumentException.class,
                () -> JdbcUrlParts.requireHost("127.0.0.1/other?sslmode=disable&user=injected&x="));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParts.requireHost("db:5432/other"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParts.requireHost("user@db"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParts.requireHost("db host"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParts.requireHost(""));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParts.requireHost("[::1]/other"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParts.requireHost("fe80::1%en0/x?y=z"));
    }

    @Test
    void rejectsDatabasesThatWouldInjectUrlParameters() {
        assertEquals("ods_user", JdbcUrlParts.requireDatabase("ods_user"));
        assertThrows(IllegalArgumentException.class,
                () -> JdbcUrlParts.requireDatabase("ods?allowLoadLocalInfile=true"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParts.requireDatabase("a&b=c"));
        assertThrows(IllegalArgumentException.class, () -> JdbcUrlParts.requireDatabase("../x"));
    }
}
