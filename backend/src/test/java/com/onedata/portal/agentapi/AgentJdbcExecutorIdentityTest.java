package com.onedata.portal.agentapi;

import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.identity.AgentIdentityContext;
import com.onedata.portal.agentapi.service.AgentJdbcExecutor;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentJdbcExecutorIdentityTest {
    private final DatasourceAdapterRegistry adapters = mock(DatasourceAdapterRegistry.class);
    private final DatasourceConnectionFactory connections = mock(DatasourceConnectionFactory.class);
    private final Connection person = mock(Connection.class);
    private final Connection agent = mock(Connection.class);
    private final Statement check = mock(Statement.class);
    private final Statement execute = mock(Statement.class);
    private final AgentDatasourceResolution datasource = new AgentDatasourceResolution();
    private final AgentJdbcExecutor executor = new AgentJdbcExecutor(adapters, connections);

    @BeforeEach
    void setup() throws Exception {
        AgentIdentityContext.set("local:alice", "agent_sales");
        datasource.setSourceType("MYSQL");
        datasource.setHost("localhost");
        datasource.setPort(3306);
        datasource.setDatabase("demo");
        datasource.setUser("agent");
        datasource.setPassword("agent-secret");
        datasource.setCheckUser("person");
        datasource.setCheckPassword("person-secret");
        when(adapters.require("MYSQL")).thenReturn(mock(DatasourceAdapter.class));
        when(connections.open(eq("MYSQL"), any(ConnectionConfig.class), eq("demo"),
                eq("person"), eq("person-secret"), eq(true))).thenReturn(person);
        when(connections.open(eq("MYSQL"), any(ConnectionConfig.class), eq("demo"),
                eq("agent"), eq("agent-secret"), eq(true))).thenReturn(agent);
        when(person.createStatement()).thenReturn(check);
        when(agent.createStatement()).thenReturn(execute);
        when(check.executeQuery(any())).thenReturn(mock(ResultSet.class));
        ResultSet rows = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(rows.getMetaData()).thenReturn(metadata);
        when(execute.execute(any())).thenReturn(true);
        when(execute.getResultSet()).thenReturn(rows);
    }

    @AfterEach
    void clearIdentity() {
        AgentIdentityContext.clear();
    }

    @Test
    void explainsOriginalSqlBeforeExecutingWithOtherReadOnlyConnection() throws Exception {
        String sql = "SELECT * FROM demo.x";
        assertEquals(0, executor.executeReadOnlyQuery(datasource, sql, 10, 7).getRowCount());
        InOrder order = inOrder(check, person, execute);
        order.verify(check).executeQuery("EXPLAIN " + sql);
        order.verify(person).close();
        order.verify(execute).setQueryTimeout(7);
        order.verify(execute).setMaxRows(11);
        order.verify(execute).execute(sql);
        verify(check).close();
        verify(agent).close();
    }

    @Test
    void humanDenialNeverOpensAgentConnectionAndDoesNotExposePlan() throws Exception {
        when(check.executeQuery(any())).thenThrow(new SQLException("sensitive object detail"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> executor.executeReadOnlyQuery(datasource, "SELECT * FROM demo.secret", 10, 7));
        assertEquals("你的账号无权访问所请求对象或权限检查失败", failure.getMessage());
        verify(connections, never()).open(eq("MYSQL"), any(ConnectionConfig.class), eq("demo"),
                eq("agent"), eq("agent-secret"), eq(true));
        verify(person).close();
    }

    @Test
    void onBehalfQueryWithoutHumanAccountIsRejectedInsteadOfSkippingTheCheck() throws Exception {
        datasource.setCheckUser(null);
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> executor.executeReadOnlyQuery(datasource, "SELECT * FROM demo.x", 10, 7));
        assertEquals("缺少提问人的数据库账号映射", failure.getMessage());
        verify(connections, never()).open(eq("MYSQL"), any(ConnectionConfig.class), eq("demo"),
                eq("agent"), eq("agent-secret"), eq(true));
    }
}
