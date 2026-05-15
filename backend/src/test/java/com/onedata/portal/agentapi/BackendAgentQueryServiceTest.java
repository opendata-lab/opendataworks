package com.onedata.portal.agentapi;

import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.dto.AgentReadQueryRequest;
import com.onedata.portal.agentapi.dto.AgentReadQueryResponse;
import com.onedata.portal.agentapi.service.AgentJdbcExecutor;
import com.onedata.portal.agentapi.service.AgentMetadataService;
import com.onedata.portal.agentapi.service.BackendAgentQueryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BackendAgentQueryServiceTest {

    @Mock
    private AgentMetadataService agentMetadataService;

    @Mock
    private AgentJdbcExecutor agentJdbcExecutor;

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
