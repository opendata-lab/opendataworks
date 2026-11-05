package com.onedata.portal.service.datasource.jdbc;

import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.*;
import java.util.*;

/** Standard Spring JDBC operations on a caller-owned datasource connection. */
public final class DatasourceJdbc {
    private DatasourceJdbc() {}

    private static JdbcTemplate jdbc(Connection c) {
        JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(c, true));
        jdbc.setQueryTimeout(20);
        return jdbc;
    }

    public static List<Map<String, Object>> rows(Connection c, String sql, Object... args)
            throws SQLException {
        try {
            return jdbc(c).query(
                            sql,
                            new ColumnMapRowMapper() {
                                @Override
                                protected String getColumnKey(String name) {
                                    return name.toLowerCase(Locale.ROOT);
                                }
                            },
                            args);
        } catch (org.springframework.dao.DataAccessException e) {
            throw sqlException(e);
        }
    }

    public static void execute(Connection c, String sql) throws SQLException {
        try {
            jdbc(c).execute(sql);
        } catch (org.springframework.dao.DataAccessException e) {
            throw sqlException(e);
        }
    }

    public static String string(Map<String, Object> row, String key) {
        return row.get(key) == null ? "" : String.valueOf(row.get(key));
    }

    private static SQLException sqlException(org.springframework.dao.DataAccessException e) {
        if (e.getMostSpecificCause() instanceof SQLException sql) return sql;
        return new SQLException(e.getMessage(), e);
    }
}
