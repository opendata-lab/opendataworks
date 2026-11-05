package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.sql.*;

class BoundedJdbcRowsTest {
    private ResultSet result(int type, Object... values) throws Exception {
        ResultSet rs = mock(ResultSet.class);
        ResultSetMetaData md = mock(ResultSetMetaData.class);
        when(rs.getMetaData()).thenReturn(md);
        when(md.getColumnCount()).thenReturn(1);
        when(md.getColumnType(1)).thenReturn(type);
        when(md.getColumnLabel(1)).thenReturn("value");
        java.util.concurrent.atomic.AtomicInteger index =
                new java.util.concurrent.atomic.AtomicInteger(-1);
        when(rs.next()).thenAnswer(i -> index.incrementAndGet() < values.length);
        when(rs.getObject(1)).thenAnswer(i -> values[index.get()]);
        return rs;
    }

    @Test
    void escapingIsMeasuredAndReturnedCellsStayUnchanged() throws Exception {
        String value = "{\"中文\":\"quoted\\value\"}";
        var result =
                BoundedJdbcRows.read(
                        result(Types.VARCHAR, value, "\"".repeat(250000)),
                        10,
                        131072,
                        new ObjectMapper());
        assertEquals(1, result.rows().size());
        assertEquals(value, result.rows().get(0).get("value"));
        assertTrue(result.hasMore());
        assertTrue(result.truncated());
    }

    @Test
    void oversizedFirstRowReturnsNoAlteredPreview() throws Exception {
        var result =
                BoundedJdbcRows.read(
                        result(Types.VARCHAR, "x".repeat(600000)), 10, 131072, new ObjectMapper());
        assertTrue(result.rows().isEmpty());
        assertTrue(result.truncated());
    }

    @Test
    void lobStreamStopsBeforeLoadingWholeValueAndCloses() throws Exception {
        ResultSet rs = result(Types.CLOB, "unused");
        Reader reader = spy(new StringReader("x".repeat(600000)));
        when(rs.getCharacterStream(1)).thenReturn(reader);
        var result = BoundedJdbcRows.read(rs, 10, 131072, new ObjectMapper());
        assertTrue(result.rows().isEmpty());
        assertTrue(result.truncated());
        verify(reader).close();
        verify(rs, never()).getObject(1);
    }

    @Test
    void rowLimitAndNullValuesKeepTheirMeaning() throws Exception {
        var limited =
                BoundedJdbcRows.read(
                        result(Types.VARCHAR, null, "next"), 1, 131072, new ObjectMapper());
        assertEquals(1, limited.rows().size());
        assertNull(limited.rows().get(0).get("value"));
        assertTrue(limited.hasMore());
        assertFalse(limited.truncated());
    }
}
