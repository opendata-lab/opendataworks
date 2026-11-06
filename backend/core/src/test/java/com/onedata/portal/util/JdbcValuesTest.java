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

    @Test
    void readsDatesAndTimesAsJavaTimeKeepingWallClockOrOffset() throws Exception {
        java.sql.ResultSet rs = mock(java.sql.ResultSet.class);
        java.sql.ResultSetMetaData meta = mock(java.sql.ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(meta);
        Object[][] columns = {
            {java.sql.Types.TIMESTAMP, "timestamp", LocalDateTime.class},
            {java.sql.Types.TIMESTAMP, "timestamptz", java.time.OffsetDateTime.class},
            {java.sql.Types.TIMESTAMP_WITH_TIMEZONE, "TIMESTAMP WITH TIME ZONE", java.time.OffsetDateTime.class},
            {java.sql.Types.DATE, "date", java.time.LocalDate.class},
            {java.sql.Types.TIME, "time", java.time.LocalTime.class},
            {java.sql.Types.TIME, "timetz", java.time.OffsetTime.class},
        };
        for (int i = 0; i < columns.length; i++) {
            int column = i + 1;
            when(meta.getColumnType(column)).thenReturn((Integer) columns[i][0]);
            when(meta.getColumnTypeName(column)).thenReturn((String) columns[i][1]);
            Object marker = new Object();
            when(rs.getObject(column, (Class<?>) columns[i][2])).thenAnswer(x -> marker);
            assertSame(marker, JdbcValues.read(rs, column), String.valueOf(columns[i][1]));
        }
        when(meta.getColumnType(9)).thenReturn(java.sql.Types.DATE);
        when(meta.getColumnTypeName(9)).thenReturn("YEAR");
        when(rs.getObject(9, java.time.LocalDate.class)).thenReturn(java.time.LocalDate.of(2026, 1, 1));
        assertEquals(2026, JdbcValues.read(rs, 9));
    }
}
