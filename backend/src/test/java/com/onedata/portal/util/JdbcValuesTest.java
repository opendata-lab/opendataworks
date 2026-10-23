package com.onedata.portal.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JdbcValuesTest {

    @Test
    void leavesMysqlAndDorisValuesUntouched() throws Exception {
        byte[] blob = new byte[] {1, 2};
        Timestamp ts = Timestamp.valueOf("2026-10-23 10:00:00");
        LocalDateTime ldt = LocalDateTime.of(2026, 10, 23, 10, 0);
        BigDecimal amount = new BigDecimal("12.30");
        assertSame(blob, JdbcValues.normalize(blob));
        assertSame(ts, JdbcValues.normalize(ts));
        assertSame(ldt, JdbcValues.normalize(ldt));
        assertSame(amount, JdbcValues.normalize(amount));
        assertEquals("text", JdbcValues.normalize("text"));
        assertEquals(null, JdbcValues.normalize(null));
    }

    @Test
    void materializesNestedArraysAndFreesTheDriverObject() throws Exception {
        Array array = mock(Array.class);
        when(array.getArray()).thenReturn(new Integer[][] {{1, 2}, {3}});
        assertEquals(Arrays.asList(Arrays.asList(1, 2), Arrays.asList(3)), JdbcValues.normalize(array));
        verify(array).free();
    }

    @Test
    void rendersPostgresqlDriverObjectsAsText() throws Exception {
        org.postgresql.util.PGobject jsonb = new org.postgresql.util.PGobject();
        jsonb.setType("jsonb");
        jsonb.setValue("{\"a\": 1}");
        assertEquals("{\"a\": 1}", JdbcValues.normalize(jsonb));
    }
}
