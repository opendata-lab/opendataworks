package com.onedata.portal.util;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a column into a value that stays valid after the connection closes and
 * serializes as plain JSON.
 *
 * {@link ResultSet#getObject(int)} can hand back driver objects: a PostgreSQL
 * array is a live {@code PgArray} whose getters query the closed connection when
 * Jackson walks them, and json/jsonb/custom types come back as {@code PGobject}.
 */
public final class JdbcValues {
    private JdbcValues() {
    }

    public static Object read(ResultSet resultSet, int column) throws SQLException {
        return normalize(resultSet.getObject(column));
    }

    static Object normalize(Object value) throws SQLException {
        if (value instanceof Array) {
            Array array = (Array) value;
            try {
                return toList(array.getArray());
            } finally {
                array.free();
            }
        }
        if (value != null && value.getClass().getName().startsWith("org.postgresql.")) {
            // PGobject and friends render their textual value via toString().
            return value.toString();
        }
        return value;
    }

    private static Object toList(Object array) throws SQLException {
        if (array == null || !array.getClass().isArray()) {
            return normalize(array);
        }
        int length = java.lang.reflect.Array.getLength(array);
        List<Object> items = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            items.add(toList(java.lang.reflect.Array.get(array, i)));
        }
        return items;
    }
}
