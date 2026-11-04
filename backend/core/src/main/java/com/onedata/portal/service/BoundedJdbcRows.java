package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.util.JdbcValues;

import java.io.*;
import java.sql.*;
import java.util.*;

/** Read whole rows within a serialized byte budget; never rewrite cell values. */
public final class BoundedJdbcRows {
    public record Result(List<Map<String, Object>> rows, boolean hasMore, boolean truncated) {}

    private BoundedJdbcRows() {}

    public static Result read(ResultSet rs, int limit, int budget, ObjectMapper mapper)
            throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        int used = 2;
        ResultSetMetaData md = rs.getMetaData();
        while (rs.next()) {
            if (rows.size() >= limit) return new Result(List.copyOf(rows), true, false);
            Map<String, Object> row = new LinkedHashMap<>();
            try {
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    Object value;
                    int type = md.getColumnType(i);
                    if (type == Types.CLOB
                            || type == Types.NCLOB
                            || type == Types.LONGVARCHAR
                            || type == Types.LONGNVARCHAR) {
                        try (Reader reader = rs.getCharacterStream(i)) {
                            value = readText(reader, budget - used);
                        }
                    } else if (type == Types.BLOB || type == Types.LONGVARBINARY) {
                        try (InputStream in = rs.getBinaryStream(i)) {
                            value = readBinary(in, budget - used);
                        }
                    } else value = JdbcValues.read(rs, i);
                    row.put(md.getColumnLabel(i), value);
                }
                int bytes = mapper.writeValueAsBytes(row).length + (rows.isEmpty() ? 0 : 1);
                if (used + bytes > budget) return new Result(List.copyOf(rows), true, true);
                used += bytes;
                rows.add(Collections.unmodifiableMap(row));
            } catch (BudgetExceeded e) {
                return new Result(List.copyOf(rows), true, true);
            } catch (IOException e) {
                throw new SQLException("查询结果读取失败", e);
            }
        }
        return new Result(List.copyOf(rows), false, false);
    }

    private static String readText(Reader reader, int remaining) throws IOException {
        if (reader == null) return null;
        StringBuilder text = new StringBuilder();
        char[] buf = new char[1024];
        int n;
        while ((n = reader.read(buf)) != -1) {
            if (text.length() + n > remaining) throw new BudgetExceeded();
            text.append(buf, 0, n);
        }
        return text.toString();
    }

    private static byte[] readBinary(InputStream in, int remaining) throws IOException {
        if (in == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) != -1) {
            if (out.size() + n > remaining * 3 / 4) throw new BudgetExceeded();
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static class BudgetExceeded extends IOException {}
}
