package com.onedata.portal.util;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.util.ArrayList;
import java.util.List;


/**
 * Reads a column into a value that stays valid after the connection closes and
 * serializes as plain JSON.
 *
 * {@link ResultSet#getObject(int)} can hand back driver objects: a PostgreSQL
 * array is a live {@code PgArray} whose getters query the closed connection when
 * Jackson walks them, and json/jsonb/custom types come back as {@code PGobject}.
 *
 * <p>Dates and times are read as {@code java.time} values. {@code java.sql.Timestamp}/{@code
 * Date} are built in the JVM time zone and then rendered by Jackson in its configured zone, which
 * shifted PostgreSQL {@code timestamp} values when the two differ and dropped fractional seconds.
 * {@code timestamptz}/{@code timetz} keep their offset.
 */
public final class JdbcValues {
    private JdbcValues() {
    }

    public static Object read(ResultSet resultSet, int column) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        String typeName = String.valueOf(meta.getColumnTypeName(column));
        switch (meta.getColumnType(column)) {
            case Types.DATE:
                if ("YEAR".equalsIgnoreCase(typeName)) {
                    // MySQL Connector/J maps YEAR to DATE (yearIsDateType); the value is the year.
                    LocalDate year = resultSet.getObject(column, LocalDate.class);
                    return year == null ? null : year.getYear();
                }
                return resultSet.getObject(column, LocalDate.class);
            case Types.TIME:
                return "timetz".equalsIgnoreCase(typeName)
                        ? resultSet.getObject(column, OffsetTime.class)
                        : resultSet.getObject(column, LocalTime.class);
            case Types.TIME_WITH_TIMEZONE:
                return resultSet.getObject(column, OffsetTime.class);
            case Types.TIMESTAMP:
                // PostgreSQL reports timestamptz as TIMESTAMP; only its type name tells them apart.
                return "timestamptz".equalsIgnoreCase(typeName)
                        ? resultSet.getObject(column, OffsetDateTime.class)
                        : resultSet.getObject(column, LocalDateTime.class);
            case Types.TIMESTAMP_WITH_TIMEZONE:
                return resultSet.getObject(column, OffsetDateTime.class);
            default:
                return normalize(resultSet.getObject(column));
        }
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
