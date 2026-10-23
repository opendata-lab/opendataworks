package com.onedata.portal.agentapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.dto.AgentReadQueryRequest;
import com.onedata.portal.agentapi.dto.AgentReadQueryResponse;
import com.onedata.portal.agentapi.scope.AgentDataScopeContext;
import com.onedata.portal.agentapi.service.AgentJdbcExecutor;
import com.onedata.portal.agentapi.service.AgentMetadataService;
import com.onedata.portal.agentapi.service.BackendAgentQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackendAgentQueryServiceTest {

    @Mock
    private AgentMetadataService agentMetadataService;

    @Mock
    private AgentJdbcExecutor agentJdbcExecutor;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper();

    @InjectMocks
    private BackendAgentQueryService backendAgentQueryService;

    @Test
    void readQueryDelegatesToDatasourceResolverAndJdbcExecutor() {
        AgentReadQueryRequest request = new AgentReadQueryRequest();
        request.setDatabase("opendataworks");
        request.setSql("SELECT 1");
        request.setPreferredEngine("mysql");
        request.setLimit(50);
        request.setTimeoutSeconds(20);

        AgentDatasourceResolution datasource = new AgentDatasourceResolution();
        datasource.setDatabase("opendataworks");
        datasource.setEngine("mysql");
        when(agentMetadataService.resolveDatasource("opendataworks", "mysql")).thenReturn(datasource);

        AgentJdbcExecutor.QueryExecutionResult execution = new AgentJdbcExecutor.QueryExecutionResult();
        execution.setRows(Collections.singletonList(Collections.singletonMap("value", 1)));
        execution.setRowCount(1);
        execution.setHasMore(false);
        execution.setDurationMs(12);
        when(agentJdbcExecutor.executeReadOnlyQuery(datasource, "SELECT 1", 50, 20)).thenReturn(execution);

        AgentReadQueryResponse response = backendAgentQueryService.readQuery(request);

        assertEquals("query_result", response.getKind());
        assertEquals("opendataworks", response.getDatabase());
        assertEquals("mysql", response.getEngine());
        assertEquals(Integer.valueOf(1), response.getRowCount());
        assertEquals(Integer.valueOf(12), response.getDurationMs());
        verify(agentMetadataService).resolveDatasource("opendataworks", "mysql");
        verify(agentJdbcExecutor).executeReadOnlyQuery(datasource, "SELECT 1", 50, 20);
        assertNull(response.getTruncatedBySize());
        assertNull(response.getNotice());
    }

    @Test
    void readQueryTruncatesResultWhenByteBudgetExceeded() {
        ReflectionTestUtils.setField(backendAgentQueryService, "maxResultBytes", 64);

        AgentReadQueryRequest request = new AgentReadQueryRequest();
        request.setDatabase("opendataworks");
        request.setSql("SELECT * FROM big_table");

        AgentDatasourceResolution datasource = new AgentDatasourceResolution();
        datasource.setDatabase("opendataworks");
        datasource.setEngine("mysql");
        when(agentMetadataService.resolveDatasource(eq("opendataworks"), any())).thenReturn(datasource);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("payload", "value-with-some-length-" + i);
            rows.add(row);
        }
        AgentJdbcExecutor.QueryExecutionResult execution = new AgentJdbcExecutor.QueryExecutionResult();
        execution.setRows(rows);
        execution.setRowCount(rows.size());
        execution.setHasMore(false);
        execution.setDurationMs(5);
        when(agentJdbcExecutor.executeReadOnlyQuery(any(), any(), anyInt(), anyInt())).thenReturn(execution);

        AgentReadQueryResponse response = backendAgentQueryService.readQuery(request);

        assertEquals(Boolean.TRUE, response.getTruncatedBySize());
        assertEquals(Boolean.TRUE, response.getHasMore());
        assertTrue(response.getRows().size() >= 1);
        assertTrue(response.getRows().size() < rows.size());
        assertEquals(Integer.valueOf(response.getRows().size()), response.getRowCount());
        assertNotNull(response.getNotice());
    }

    @Test
    void readQueryDoesNotTruncateWhenForExport() {
        ReflectionTestUtils.setField(backendAgentQueryService, "maxResultBytes", 64);

        AgentReadQueryRequest request = new AgentReadQueryRequest();
        request.setDatabase("opendataworks");
        request.setSql("SELECT * FROM big_table");
        request.setForExport(true);

        AgentDatasourceResolution datasource = new AgentDatasourceResolution();
        datasource.setDatabase("opendataworks");
        datasource.setEngine("mysql");
        when(agentMetadataService.resolveDatasource(eq("opendataworks"), any())).thenReturn(datasource);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", i);
            row.put("payload", "value-with-some-length-" + i);
            rows.add(row);
        }
        AgentJdbcExecutor.QueryExecutionResult execution = new AgentJdbcExecutor.QueryExecutionResult();
        execution.setRows(rows);
        execution.setRowCount(rows.size());
        execution.setHasMore(false);
        execution.setDurationMs(5);
        when(agentJdbcExecutor.executeReadOnlyQuery(any(), any(), anyInt(), anyInt())).thenReturn(execution);

        AgentReadQueryResponse response = backendAgentQueryService.readQuery(request);

        assertEquals(50, response.getRows().size());
        assertNull(response.getTruncatedBySize());
        assertNull(response.getNotice());
    }

    @Test
    void readQueryKeepsAtLeastOneRowWhenSingleRowExceedsBudget() {
        ReflectionTestUtils.setField(backendAgentQueryService, "maxResultBytes", 1);

        AgentReadQueryRequest request = new AgentReadQueryRequest();
        request.setDatabase("opendataworks");
        request.setSql("SELECT * FROM wide_table");

        AgentDatasourceResolution datasource = new AgentDatasourceResolution();
        datasource.setDatabase("opendataworks");
        datasource.setEngine("mysql");
        when(agentMetadataService.resolveDatasource(eq("opendataworks"), any())).thenReturn(datasource);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            rows.add(Collections.singletonMap("payload", "row-" + i));
        }
        AgentJdbcExecutor.QueryExecutionResult execution = new AgentJdbcExecutor.QueryExecutionResult();
        execution.setRows(rows);
        execution.setRowCount(rows.size());
        execution.setHasMore(false);
        execution.setDurationMs(5);
        when(agentJdbcExecutor.executeReadOnlyQuery(any(), any(), anyInt(), anyInt())).thenReturn(execution);

        AgentReadQueryResponse response = backendAgentQueryService.readQuery(request);

        assertEquals(1, response.getRows().size());
        assertEquals(Boolean.TRUE, response.getTruncatedBySize());
    }

    @Test
    void readQueryAcceptsSelectWithLeadingComment() {
        String sql = "/* publish trend */\n"
                + "SELECT\n"
                + "  DATE(created_at) AS publish_date,\n"
                + "  SUM(publish_count) AS total_publish_count\n"
                + "FROM opendataworks.workflow_publish_record\n"
                + "GROUP BY DATE(created_at)";

        assertReadQueryAccepted(sql);
    }

    @Test
    void readQueryAcceptsSelectWhenCommentContainsMutatingKeywords() {
        String sql = "/* example only: update demo set name = 'x'; "
                + "delete from demo; drop table demo; alter table demo add column name varchar(64); */\n"
                + "SELECT * FROM demo LIMIT 10";

        assertReadQueryAccepted(sql);
    }

    @Test
    void readQueryAcceptsMultilineAggregateSelect() {
        String sql = "SELECT\n"
                + "  DATE(created_at) AS publish_date,\n"
                + "  SUM(publish_count) AS total_publish_count\n"
                + "FROM opendataworks.workflow_publish_record\n"
                + "GROUP BY DATE(created_at)\n"
                + "ORDER BY publish_date";

        assertReadQueryAccepted(sql);
    }

    @Test
    void readQueryAcceptsDorisSelectWithNestedNotInAndChineseAliases() {
        String sql = "SELECT\n"
                + "  t.workflow_code AS 工作流编码,\n"
                + "  t.workflow_name AS 工作流名称,\n"
                + "  t.publish_channel AS 发布渠道,\n"
                + "  t.publish_status AS 发布状态,\n"
                + "  t.workflow_type AS 工作流类型\n"
                + "FROM opendataworks.workflow_publish_record t\n"
                + "WHERE t.ds = (\n"
                + "  SELECT MAX(ds)\n"
                + "  FROM opendataworks.workflow_publish_record\n"
                + "  WHERE ds <= '2026-05-08'\n"
                + ")\n"
                + "AND t.workflow_type != 'TEMP'\n"
                + "AND t.publish_status = 'SUCCESS'\n"
                + "AND t.workflow_code NOT IN (\n"
                + "  SELECT DISTINCT workflow_code\n"
                + "  FROM opendataworks.workflow_publish_record\n"
                + "  WHERE ds = (\n"
                + "    SELECT MAX(ds)\n"
                + "    FROM opendataworks.workflow_publish_record\n"
                + "    WHERE ds <= '2026-04-08'\n"
                + "  )\n"
                + "  AND workflow_type != 'TEMP'\n"
                + "  AND publish_status = 'SUCCESS'\n"
                + ")\n"
                + "ORDER BY t.workflow_code\n"
                + "LIMIT 500;";

        assertReadQueryAccepted(sql);
    }

    @Test
    void readQueryAcceptsDorisSelectWithChineseAliasAndMutatingWordsInMaskedText() {
        String sql = "/* example only: update demo set name = 'x'; delete from demo; */\n"
                + "SELECT t.workflow_code AS 工作流编码\n"
                + "FROM opendataworks.workflow_publish_record t\n"
                + "WHERE t.remark = 'drop table demo; update demo set name = ''x'''\n"
                + "LIMIT 10;";

        assertReadQueryAccepted(sql);
    }

    @Test
    void readQueryRejectsMultipleStatementsWhenParserFallbackHandlesDorisAlias() {
        assertReadQueryRejected(
                "SELECT t.workflow_code AS 工作流编码 FROM opendataworks.workflow_publish_record t; SELECT 2",
                "仅支持单条只读 SQL"
        );
    }

    @Test
    void readQueryRejectsMutatingSqlWhenParserFallbackHandlesDorisAlias() {
        assertReadQueryRejected(
                "SELECT t.workflow_code AS 工作流编码 FROM opendataworks.workflow_publish_record t DELETE FROM demo",
                "仅支持只读 SQL"
        );
    }

    @Test
    void readQueryAcceptsShowSql() {
        assertReadQueryAccepted("SHOW TABLES");
    }

    @Test
    void readQueryAcceptsDescribeAndExplainSql() {
        assertReadQueryAccepted("DESCRIBE demo");
        assertReadQueryAccepted("EXPLAIN SELECT * FROM demo");
    }

    @Test
    void readQueryAcceptsCrudSelectAndRejectsMutatingCrudSql() {
        assertReadQueryAccepted("SELECT * FROM demo LIMIT 10");
        assertReadQueryRejected("INSERT INTO demo VALUES (1)", "仅支持只读 SQL");
        assertReadQueryRejected("UPDATE demo SET name = 'updated' WHERE id = 1", "仅支持只读 SQL");
        assertReadQueryRejected("DELETE FROM demo WHERE id = 1", "仅支持只读 SQL");
    }

    @Test
    void readQueryRejectsMutatingSqlWithLeadingComment() {
        assertReadQueryRejected("/* cleanup */\nDELETE FROM demo WHERE id = 1", "仅支持只读 SQL");
    }

    @Test
    void readQueryRejectsTruncateSql() {
        assertReadQueryRejected("TRUNCATE TABLE demo", "仅支持只读 SQL");
    }

    @Test
    void readQueryRejectsAlterSql() {
        assertReadQueryRejected("ALTER TABLE demo ADD COLUMN name VARCHAR(64)", "仅支持只读 SQL");
    }

    @Test
    void readQueryRejectsMultipleStatements() {
        assertReadQueryRejected("SELECT 1; SELECT 2", "仅支持单条只读 SQL");
    }

    @Test
    void readQueryRejectsDatabaseOutsideAgentDataScope() {
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19");
        try {
            assertReadQueryRejected("SELECT 1", "数据范围限制: 未授权访问 database `opendataworks`");
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void readQueryAllowsDatabaseWhenDataScopeIsUnconfiguredOrEmpty() {
        // 当未配置数据范围（null、空字符串或 allowed_scopes 为空列表）时，不限制数据范围
        AgentDataScopeContext.setEncodedScope(null);
        org.junit.jupiter.api.Assertions.assertFalse(AgentDataScopeContext.isActive());

        AgentDataScopeContext.setEncodedScope("");
        org.junit.jupiter.api.Assertions.assertFalse(AgentDataScopeContext.isActive());

        // base64url('{"allowed_scopes":[]}')
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W119");
        org.junit.jupiter.api.Assertions.assertFalse(AgentDataScopeContext.isActive());
        AgentDataScopeContext.clear();
    }

    @Test
    void readQueryRejectsCrossSchemaReferencesOutsideAgentDataScope() {
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19");
        try {
            AgentReadQueryRequest request = new AgentReadQueryRequest();
            request.setDatabase("ads_user");
            request.setSql("SELECT * FROM ads_user.profile p JOIN ods_user.orders o ON p.id = o.user_id");

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> backendAgentQueryService.readQuery(request)
            );

            assertEquals("数据范围限制: SQL 引用了未授权 schema `ods_user`", exception.getMessage());
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void readQueryReadsDoubleQuotedSchemasAgainstAgentDataScope() {
        // PostgreSQL 写法："schema"."table"。授权的 schema 不能因为带引号被误拒，未授权的也不能因此漏检
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19");
        try {
            AgentReadQueryRequest denied = new AgentReadQueryRequest();
            denied.setDatabase("ads_user");
            denied.setSql("SELECT * FROM \"ads_user\".\"profile\" p JOIN \"ods_user\".\"orders\" o ON p.id = o.user_id");
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> backendAgentQueryService.readQuery(denied)
            );
            assertEquals("数据范围限制: SQL 引用了未授权 schema `ods_user`", exception.getMessage());

            AgentReadQueryRequest allowed = new AgentReadQueryRequest();
            allowed.setDatabase("ads_user");
            allowed.setSql("SELECT * FROM \"ads_user\".\"profile\"");
            stubPostgresqlDatasource("ads_user");
            backendAgentQueryService.readQuery(allowed);
            verify(agentJdbcExecutor).executeReadOnlyQuery(any(), eq(allowed.getSql()), anyInt(), anyInt());
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void readQueryFollowsPostgresqlIdentifierFolding() {
        // PostgreSQL 把未加引号的 Sales 折叠成 sales；只有带引号的 "Sales" 才是授权的 schema
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJTYWxlcyIsInNvdXJjZV90eXBlIjoiUE9TVEdSRVNRTCJ9XX0");
        try {
            stubPostgresqlDatasource("Sales");

            AgentReadQueryRequest folded = new AgentReadQueryRequest();
            folded.setDatabase("Sales");
            folded.setSql("SELECT * FROM Sales.orders");
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> backendAgentQueryService.readQuery(folded));
            assertEquals("数据范围限制: SQL 引用了未授权 schema `sales`", exception.getMessage());

            AgentReadQueryRequest quoted = new AgentReadQueryRequest();
            quoted.setDatabase("Sales");
            quoted.setSql("SELECT * FROM \"Sales\".orders");
            backendAgentQueryService.readQuery(quoted);
            verify(agentJdbcExecutor).executeReadOnlyQuery(any(), eq(quoted.getSql()), anyInt(), anyInt());
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void readQueryKeepsWhitespaceInsideQuotedSchemaNames() {
        // 引号里的空白属于名称本身：" ads_user " 不是 ads_user
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19");
        try {
            stubPostgresqlDatasource("ads_user");
            AgentReadQueryRequest request = new AgentReadQueryRequest();
            request.setDatabase("ads_user");
            request.setSql("SELECT * FROM \" ads_user \".orders");
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> backendAgentQueryService.readQuery(request));
            assertEquals("数据范围限制: SQL 引用了未授权 schema ` ads_user `", exception.getMessage());
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void readQueryFoldsOnlyAsciiLettersForPostgresql() {
        // PostgreSQL 只折叠 ASCII 大写：未加引号的 Ä 仍是 Ä，不是授权的 ä
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiLDpCIsInNvdXJjZV90eXBlIjoiUE9TVEdSRVNRTCJ9XX0");
        try {
            stubPostgresqlDatasource("ä");
            AgentReadQueryRequest request = new AgentReadQueryRequest();
            request.setDatabase("ä");
            request.setSql("SELECT * FROM Ä.orders");
            IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                    () -> backendAgentQueryService.readQuery(request));
            assertEquals("数据范围限制: SQL 引用了未授权 schema `Ä`", exception.getMessage());
            org.mockito.Mockito.verifyNoInteractions(agentJdbcExecutor);
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void readQueryRejectsUnparseableSqlUnderScopeInsteadOfLexicalGuessing() {
        // 词法检查认不出逗号连接的第二张表和含 $ 的名称；数据范围生效时这类无法解析的 SQL 一律拒绝
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19");
        try {
            for (String engine : new String[] {"postgresql", "mysql"}) {
                AgentDatasourceResolution datasource = new AgentDatasourceResolution();
                datasource.setDatabase("ads_user");
                datasource.setEngine(engine);
                when(agentMetadataService.resolveDatasource(eq("ads_user"), any())).thenReturn(datasource);
                for (String sql : new String[] {
                        "SELECT * FROM private$area.orders TABLESAMPLE SYSTEM (10)",
                        "SELECT * FROM ads_user.profile, private.orders TABLESAMPLE SYSTEM (10)"}) {
                    AgentReadQueryRequest request = new AgentReadQueryRequest();
                    request.setDatabase("ads_user");
                    request.setSql(sql);
                    IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                            () -> backendAgentQueryService.readQuery(request), engine + ": " + sql);
                    assertTrue(exception.getMessage().startsWith("数据范围限制"), exception.getMessage());
                }
            }
            org.mockito.Mockito.verifyNoInteractions(agentJdbcExecutor);
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void readQueryOnlyAcceptsFixedMetadataFormsWhenSqlCannotBeParsed() {
        // 第四轮评审给出的绕过样例：DESC 后接查询、SHOW 的 FROM/IN 库名子句、反引号、可执行注释
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19");
        try {
            AgentDatasourceResolution datasource = new AgentDatasourceResolution();
            datasource.setDatabase("ads_user");
            datasource.setEngine("mysql");
            when(agentMetadataService.resolveDatasource(eq("ads_user"), any())).thenReturn(datasource);
            for (String sql : new String[] {
                    "DESC SELECT * FROM ads_user.profile, private.orders",
                    "DESC FORMAT=JSON SELECT * FROM ads_user.profile, private.orders",
                    "DESC ANALYZE SELECT * FROM ads_user.profile, private.orders",
                    "SHOW TABLES FROM private",
                    "SHOW TABLES IN private",
                    "SHOW FULL COLUMNS FROM orders FROM private",
                    "SHOW COLUMNS IN private.orders",
                    "DESC `private`.orders",
                    "DESC `private$area`.orders",
                    "SHOW TABLES /*! FROM private */"}) {
                AgentReadQueryRequest request = new AgentReadQueryRequest();
                request.setDatabase("ads_user");
                request.setSql(sql);
                IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                        () -> backendAgentQueryService.readQuery(request), sql);
                assertTrue(exception.getMessage().startsWith("数据范围限制"), sql + " -> " + exception.getMessage());
            }
            org.mockito.Mockito.verifyNoInteractions(agentJdbcExecutor);

            AgentJdbcExecutor.QueryExecutionResult execution = new AgentJdbcExecutor.QueryExecutionResult();
            execution.setRows(Collections.emptyList());
            when(agentJdbcExecutor.executeReadOnlyQuery(any(), any(), anyInt(), anyInt())).thenReturn(execution);
            for (String sql : new String[] {"SHOW TABLES", "SHOW FULL COLUMNS FROM orders", "DESC ads_user.orders;"}) {
                AgentReadQueryRequest request = new AgentReadQueryRequest();
                request.setDatabase("ads_user");
                request.setSql(sql);
                backendAgentQueryService.readQuery(request);
            }
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    private void stubPostgresqlDatasource(String database) {
        AgentDatasourceResolution datasource = new AgentDatasourceResolution();
        datasource.setDatabase(database);
        datasource.setEngine("postgresql");
        when(agentMetadataService.resolveDatasource(eq(database), any())).thenReturn(datasource);
        AgentJdbcExecutor.QueryExecutionResult execution = new AgentJdbcExecutor.QueryExecutionResult();
        execution.setRows(Collections.emptyList());
        org.mockito.Mockito.lenient().when(agentJdbcExecutor.executeReadOnlyQuery(any(), any(), anyInt(), anyInt()))
                .thenReturn(execution);
    }

    @Test
    void readQueryFailsClosedOnUnparseableSqlWithDoubleQuotedIdentifiers() {
        // JSqlParser 解析不了 TABLESAMPLE；词法回退会把双引号当字符串遮掉，必须拒绝而不是放行
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19");
        try {
            AgentReadQueryRequest request = new AgentReadQueryRequest();
            request.setDatabase("ads_user");
            request.setSql("SELECT * FROM \"private\".\"orders\" TABLESAMPLE SYSTEM (10)");
            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> backendAgentQueryService.readQuery(request)
            );
            org.junit.jupiter.api.Assertions.assertTrue(exception.getMessage().startsWith("数据范围限制"),
                    exception.getMessage());
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void readQueryAcceptsShowTablesWithinActiveDataScope() {
        // 数据范围生效时，SHOW 语句无法被 TablesNamesFinder 解析出表名（会抛
        // UnsupportedOperationException）。它没有 schema 引用，应回退到词法范围校验后放行，
        // 而不是因内部异常直接失败。
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19");
        try {
            AgentReadQueryRequest request = new AgentReadQueryRequest();
            request.setDatabase("ads_user");
            request.setSql("SHOW TABLES");

            AgentDatasourceResolution datasource = new AgentDatasourceResolution();
            datasource.setDatabase("ads_user");
            datasource.setEngine("doris");
            when(agentMetadataService.resolveDatasource("ads_user", null)).thenReturn(datasource);
            when(agentJdbcExecutor.executeReadOnlyQuery(any(), eq("SHOW TABLES"), eq(1000), eq(30)))
                    .thenReturn(new AgentJdbcExecutor.QueryExecutionResult());

            backendAgentQueryService.readQuery(request);

            verify(agentJdbcExecutor).executeReadOnlyQuery(datasource, "SHOW TABLES", 1000, 30);
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void readQueryRejectsShowReferencingUnauthorizedSchemaWithinActiveDataScope() {
        // 回退到词法范围校验后，仍必须拦住引用了未授权 schema 的 SHOW 语句。
        AgentDataScopeContext.setEncodedScope("eyJhbGxvd2VkX3Njb3BlcyI6W3siY2x1c3Rlcl9pZCI6MywiZGF0YWJhc2UiOiJhZHNfdXNlciIsInNvdXJjZV90eXBlIjoiRE9SSVMifV19");
        try {
            AgentReadQueryRequest request = new AgentReadQueryRequest();
            request.setDatabase("ads_user");
            request.setSql("SHOW COLUMNS FROM ods_user.orders");

            IllegalArgumentException exception = assertThrows(
                    IllegalArgumentException.class,
                    () -> backendAgentQueryService.readQuery(request)
            );

            assertEquals("数据范围限制: SQL 引用了未授权 schema `ods_user`", exception.getMessage());
        } finally {
            AgentDataScopeContext.clear();
        }
    }

    @Test
    void readQueryClampsLimitAndTimeout() {
        AgentReadQueryRequest request = new AgentReadQueryRequest();
        request.setDatabase("opendataworks");
        request.setSql("SELECT 1");
        request.setLimit(20000);
        request.setTimeoutSeconds(500);

        AgentDatasourceResolution datasource = new AgentDatasourceResolution();
        datasource.setDatabase("opendataworks");
        datasource.setEngine("mysql");
        when(agentMetadataService.resolveDatasource("opendataworks", null)).thenReturn(datasource);
        when(agentJdbcExecutor.executeReadOnlyQuery(any(), eq("SELECT 1"), eq(10000), eq(120)))
                .thenReturn(new AgentJdbcExecutor.QueryExecutionResult());

        backendAgentQueryService.readQuery(request);

        verify(agentJdbcExecutor).executeReadOnlyQuery(datasource, "SELECT 1", 10000, 120);
    }

    private void assertReadQueryAccepted(String sql) {
        AgentReadQueryRequest request = new AgentReadQueryRequest();
        request.setDatabase("opendataworks");
        request.setSql(sql);

        AgentDatasourceResolution datasource = new AgentDatasourceResolution();
        datasource.setDatabase("opendataworks");
        datasource.setEngine("mysql");
        when(agentMetadataService.resolveDatasource("opendataworks", null)).thenReturn(datasource);
        when(agentJdbcExecutor.executeReadOnlyQuery(any(), eq(sql), eq(1000), eq(30)))
                .thenReturn(new AgentJdbcExecutor.QueryExecutionResult());

        backendAgentQueryService.readQuery(request);

        verify(agentJdbcExecutor).executeReadOnlyQuery(datasource, sql, 1000, 30);
    }

    private void assertReadQueryRejected(String sql, String expectedMessage) {
        AgentReadQueryRequest request = new AgentReadQueryRequest();
        request.setDatabase("opendataworks");
        request.setSql(sql);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> backendAgentQueryService.readQuery(request)
        );

        assertEquals(expectedMessage, exception.getMessage());
    }
}
