package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.context.UserContext;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.portal.dto.SqlAnalyzeRequest;
import com.onedata.portal.dto.SqlAnalyzeResponse;
import com.onedata.portal.dto.SqlQueryRequest;
import com.onedata.portal.entity.DataQueryHistory;
import com.onedata.portal.mapper.DataQueryHistoryMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataQueryServiceTest {

    private DataQueryService service;

    @BeforeEach
    void setUp() {
        service = new DataQueryService(
            mock(DorisConnectionService.class),
            mock(DorisClusterService.class),
            mock(DataQueryHistoryMapper.class),
            new ObjectMapper()
        );
    }

    @Test
    void validateSqlAllowsSemicolonsInsideStringLiterals() {
        assertDoesNotThrow(() -> service.validateSql("SELECT ';drop table' AS col"));
    }

    @Test
    void validateSqlAllowsDangerousWordsInsideComments() {
        String sql = "SELECT * FROM users -- drop table";
        assertDoesNotThrow(() -> service.validateSql(sql));
    }

    @Test
    void validateSqlAllowsMultipleSafeStatements() {
        assertDoesNotThrow(() -> service.validateSql("SELECT 1; SELECT 2"));
    }

    @Test
    void validateSqlRequiresConfirmForDangerousStatement() {
        RuntimeException ex = assertThrows(RuntimeException.class,
            () -> service.validateSql("DROP TABLE users"));
        assertEquals("高风险 SQL 需强确认后执行", ex.getMessage());
    }

    @Test
    void analyzeQueryReturnsConfirmChallengeForDelete() {
        SqlAnalyzeRequest request = new SqlAnalyzeRequest();
        request.setClusterId(1L);
        request.setDatabase("db1");
        request.setSql("DELETE FROM db1.t_user WHERE id = 1");

        SqlAnalyzeResponse response = service.analyzeQuery(request);
        assertFalse(response.isBlocked());
        assertNotNull(response.getConfirmChallenges());
        assertTrue(response.getConfirmChallenges().size() == 1);
        assertNotNull(response.getConfirmChallenges().get(0).getConfirmToken());
    }

    @Test
    void analyzeQueryBlocksWhenTargetCannotBeResolved() {
        SqlAnalyzeRequest request = new SqlAnalyzeRequest();
        request.setClusterId(1L);
        request.setDatabase("db1");
        request.setSql("DELETE");

        SqlAnalyzeResponse response = service.analyzeQuery(request);
        assertTrue(response.isBlocked());
        assertNotNull(response.getBlockedReason());
    }
    @Test
    void studioHistoryRecordsMappedConnectionAccountAndPlatformOwner() throws Exception {
        DorisConnectionService connections = mock(DorisConnectionService.class);
        DataQueryHistoryMapper histories = mock(DataQueryHistoryMapper.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(connections.getConnection(1L, "demo")).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getUserName()).thenReturn("mapped-person");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("SELECT 1 WHERE 1 = 0")).thenReturn(true);
        when(statement.getResultSet()).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(mock(ResultSetMetaData.class));
        DataQueryService queries = new DataQueryService(connections, mock(DorisClusterService.class), histories,
                new ObjectMapper());
        SqlQueryRequest request = new SqlQueryRequest();
        request.setClusterId(1L);
        request.setDatabase("demo");
        request.setSql("SELECT 1 WHERE 1 = 0");
        UserContextHolder.setContext(
                new UserContext("2", "alice", "user", "local"));
        try {
            queries.executeQuery(request);
            ArgumentCaptor<DataQueryHistory> history =
                    ArgumentCaptor.forClass(DataQueryHistory.class);
            verify(histories).insert(history.capture());
            assertEquals("mapped-person", history.getValue().getExecutionAccount());
            assertEquals("2", history.getValue().getExecutedBy());
        } finally {
            UserContextHolder.clear();
        }
    }

}
