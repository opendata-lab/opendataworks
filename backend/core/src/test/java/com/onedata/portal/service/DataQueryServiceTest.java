package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.context.PrincipalContextHolder;
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

class DataQueryServiceTest {

    private DataQueryService service;

    @BeforeEach
    void setUp() {
        service =
                new DataQueryService(
                        mock(DorisConnectionService.class),
                        mock(DorisClusterService.class),
                        mock(DataQueryHistoryMapper.class),
                        new ObjectMapper(),
                        mock(DatasourceSubjectAccessService.class));
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
        RuntimeException ex =
                assertThrows(RuntimeException.class, () -> service.validateSql("DROP TABLE users"));
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
        DataQueryService queries =
                new DataQueryService(
                        connections,
                        mock(DorisClusterService.class),
                        histories,
                        new ObjectMapper(),
                        mock(DatasourceSubjectAccessService.class));
        SqlQueryRequest request = new SqlQueryRequest();
        request.setClusterId(1L);
        request.setDatabase("demo");
        request.setSql("SELECT 1 WHERE 1 = 0");
        PrincipalContextHolder.setContext(new CurrentPrincipal("2", "alice", "user", "local"));
        try {
            queries.executeQuery(request);
            ArgumentCaptor<DataQueryHistory> history =
                    ArgumentCaptor.forClass(DataQueryHistory.class);
            verify(histories).insert(history.capture());
            assertEquals("mapped-person", history.getValue().getExecutionAccount());
            assertEquals("2", history.getValue().getExecutedBy());
        } finally {
            PrincipalContextHolder.clear();
        }
    }

    @Test
    void serviceTokenReadRunsAsServiceAccountAndAuditsReportedCaller()
            throws Exception {
        var access = mock(DatasourceSubjectAccessService.class);
        var histories = mock(DataQueryHistoryMapper.class);
        var ds = new com.onedata.portal.dto.DatasourceResolutionDto();
        ds.setClusterId(1L);
        ds.setDatabase("demo");
        ds.setEngine("mysql");
        ds.setSourceType("MYSQL");
        when(access.resolveDatasource("demo", null)).thenReturn(ds);
        var serviceAccount = new com.onedata.portal.entity.DatasourceSubjectAccount();
        serviceAccount.setUsername("service_native");
        when(access.require(1L, "SERVICE", "19")).thenReturn(serviceAccount);
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        ResultSet rows = mock(ResultSet.class);
        when(access.openPrincipalConnection(1L, "demo", true)).thenReturn(connection);
        when(connection.createStatement(ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY))
                .thenReturn(statement);
        when(statement.executeQuery("SELECT 1")).thenReturn(rows);
        when(rows.getMetaData()).thenReturn(mock(ResultSetMetaData.class));
        var queries =
                new DataQueryService(
                        mock(DorisConnectionService.class),
                        mock(DorisClusterService.class),
                        histories,
                        new ObjectMapper(),
                        access);
        var request = new com.onedata.portal.dto.ReadOnlyQueryRequest();
        request.setDatabase("demo");
        request.setSql("SELECT 1");
        PrincipalContextHolder.setContext(
                CurrentPrincipal.service(19L, "agent_sales", "local:alice"));
        try {
            queries.readQuery(request);
            var captured = ArgumentCaptor.forClass(DataQueryHistory.class);
            verify(histories).insert(captured.capture());
            assertNull(captured.getValue().getExecutedBy());
            assertNull(captured.getValue().getCheckAccount());
            assertEquals("19", captured.getValue().getAgentId());
            assertEquals("service_native", captured.getValue().getExecutionAccount());
            assertEquals("agent_sales", captured.getValue().getReportedAgentId());
            assertEquals("local:alice", captured.getValue().getReportedUserId());
            // Only the service account's own connection is opened: no per-person check.
            verify(access).openPrincipalConnection(1L, "demo", true);
            verify(access, org.mockito.Mockito.never())
                    .open(any(), eq("USER"), any(), any(), anyBoolean());
        } finally {
            PrincipalContextHolder.clear();
        }
    }

    @Test
    void permissionDenialsSayNoPermissionAndNameOnlyTheTable() {
        var mysql =
                new java.sql.SQLException(
                        "SELECT command denied to user 'svc_native'@'10.0.0.5' for table 'sys_user'",
                        "42000",
                        1142);
        assertTrue(DataQueryService.isPermissionDenied(mysql));
        String message = DataQueryService.permissionMessage(mysql);
        assertEquals("没有读取权限：表 sys_user 未授权给当前身份，请联系数据源管理员授权", message);
        assertFalse(message.contains("svc_native") || message.contains("10.0.0.5"));

        var postgres =
                new java.sql.SQLException("ERROR: permission denied for table orders", "42501", 0);
        assertTrue(DataQueryService.isPermissionDenied(postgres));
        assertTrue(DataQueryService.permissionMessage(postgres).contains("表 orders "));

        // Exact Doris 2.0.15 message (2026-11-06, local docker).
        var doris =
                new java.sql.SQLException(
                        "errCode = 2, detailMessage = SELECT command denied to user"
                                + " 'default_cluster:odw_m_9_x'@'172.30.80.1' for table"
                                + " 'default_cluster:odw_chk.secret'",
                        "42000",
                        1142);
        assertEquals(
                "没有读取权限：表 odw_chk.secret 未授权给当前身份，请联系数据源管理员授权",
                DataQueryService.permissionMessage(doris));

        var syntax = new java.sql.SQLException("You have an error in your SQL syntax", "42000", 1064);
        assertFalse(DataQueryService.isPermissionDenied(syntax));
    }
}
