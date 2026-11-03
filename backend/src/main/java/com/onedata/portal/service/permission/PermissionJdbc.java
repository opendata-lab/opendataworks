package com.onedata.portal.service.permission;

import java.sql.*;
import java.util.*;

final class PermissionJdbc {
    private PermissionJdbc() {}
    static List<Map<String, Object>> rows(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            statement.setQueryTimeout(20);
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            try (ResultSet result = statement.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                ResultSetMetaData meta = result.getMetaData();
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int i = 1; i <= meta.getColumnCount(); i++) row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), result.getObject(i));
                    rows.add(row);
                }
                return rows;
            }
        }
    }
    static void execute(Connection c, String sql) throws SQLException {
        try (Statement statement = c.createStatement()) { statement.setQueryTimeout(20); statement.execute(sql); }
    }
    static String string(Map<String, Object> row, String key) {
        return row.get(key) == null ? "" : String.valueOf(row.get(key));
    }
}
