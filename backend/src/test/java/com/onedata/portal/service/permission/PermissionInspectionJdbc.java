package com.onedata.portal.service.permission;

import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Fail on every unexpected query, especially privileged mysql.* metadata reads. */
final class PermissionInspectionJdbc {
    final Connection connection = mock(Connection.class);
    final List<String> queries = new ArrayList<>();

    PermissionInspectionJdbc(Function<String, ResultSet> results) throws SQLException {
        when(connection.prepareStatement(anyString())).thenAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            queries.add(sql);
            PreparedStatement statement = mock(PreparedStatement.class);
            when(statement.executeQuery()).thenAnswer(ignored -> results.apply(sql));
            return statement;
        });
    }

    static ResultSet result(String[] columns, Object[]... values) {
        try {
            ResultSet result = mock(ResultSet.class);
            ResultSetMetaData metadata = mock(ResultSetMetaData.class);
            when(result.getMetaData()).thenReturn(metadata);
            when(metadata.getColumnCount()).thenReturn(columns.length);
            for (int i = 0; i < columns.length; i++) when(metadata.getColumnLabel(i + 1)).thenReturn(columns[i]);
            AtomicInteger cursor = new AtomicInteger(-1);
            when(result.next()).thenAnswer(ignored -> cursor.incrementAndGet() < values.length);
            when(result.getObject(anyInt())).thenAnswer(invocation -> values[cursor.get()][(Integer) invocation.getArgument(0) - 1]);
            return result;
        } catch (SQLException error) {
            throw new AssertionError(error);
        }
    }
}
