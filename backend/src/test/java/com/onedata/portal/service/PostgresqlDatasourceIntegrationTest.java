package com.onedata.portal.service;

import com.onedata.auth.context.UserContext;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.service.AgentJdbcExecutor;
import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.dto.DorisCredential;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Run against a dedicated PostgreSQL database; bootstrap containers outside this test. */
@EnabledIfEnvironmentVariable(named = "ODW_POSTGRES_TEST_PORT", matches = "[0-9]+")
class PostgresqlDatasourceIntegrationTest {
    private DorisConnectionService service;
    private UserMappingService userMapping;
    private String schema;
    private DorisCluster cluster;

    @BeforeEach
    void setUp() throws Exception {
        schema = "odw_" + Long.toHexString(System.nanoTime());
        cluster = new DorisCluster();
        cluster.setId(1L);
        cluster.setSourceType("POSTGRESQL");
        cluster.setDatabaseName("odw_smoke");
        cluster.setFeHost("127.0.0.1");
        cluster.setFePort(Integer.parseInt(System.getenv("ODW_POSTGRES_TEST_PORT")));
        cluster.setUsername("postgres");
        cluster.setPassword("odw_smoke_password");
        DorisClusterMapper mapper = mock(DorisClusterMapper.class);
        when(mapper.selectById(1L)).thenReturn(cluster);
        userMapping = mock(UserMappingService.class);
        // Leave MySQL URL unconfigured and charset enabled: neither may be used for PostgreSQL.
        DorisJdbcProperties properties = new DorisJdbcProperties();
        properties.setSessionCharsetEnabled(true);
        properties.setSessionCharset("utf8mb4");
        service = new DorisConnectionService(mapper, properties, userMapping, DatasourceAdapterFixtures.registry(properties));
        try (Connection c = service.getConnection(1L); Statement s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
            s.execute("CREATE TABLE " + schema + ".orders (id bigint PRIMARY KEY, amount numeric(18,2) DEFAULT 0, "
                    + "note varchar(40), labels text[], \"odd\"\"column\" text)");
            s.execute("COMMENT ON TABLE " + schema + ".orders IS '订单_100%'");
            s.execute("COMMENT ON COLUMN " + schema + ".orders.amount IS '金额'");
            s.execute("INSERT INTO " + schema + ".orders(id, amount, note, labels, \"odd\"\"column\") "
                    + "VALUES (1, 12.30, 'ok', ARRAY['a','b'], 'quoted')");
            s.execute("ANALYZE " + schema + ".orders");
            s.execute("CREATE VIEW " + schema + ".order_view AS SELECT * FROM " + schema + ".orders");
            s.execute("CREATE TABLE " + schema + ".unknown_rows(id integer)");
            s.execute("CREATE TABLE " + schema + ".events (id integer, d date) PARTITION BY RANGE (d)");
            s.execute("CREATE TABLE " + schema + ".events_2026 PARTITION OF " + schema
                    + ".events FOR VALUES FROM ('2026-01-01') TO ('2027-01-01')");
            s.execute("INSERT INTO " + schema + ".events VALUES (1, '2026-05-01')");
            s.execute("ANALYZE " + schema + ".events_2026");
            s.execute("CREATE TABLE " + schema + ".covered (id integer, note text, PRIMARY KEY (id) INCLUDE (note))");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        UserContextHolder.clear();
        if (service != null && schema != null) {
            try (Connection c = service.getConnection(1L); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    @Test
    void connectionUsesConfiguredDatabaseAndSchema() throws Exception {
        assertTrue(service.testConnection(1L));
        try (Connection c = service.getConnection(1L, schema);
                Statement s = c.createStatement()) {
            assertEquals("odw_smoke", c.getCatalog());
            assertEquals(schema, c.getSchema());
            try (java.sql.ResultSet rs = s.executeQuery("SELECT count(*) FROM orders")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }

    @Test
    void userContextUsesMappedSchemaCredential() throws Exception {
        String role = schema + "_mapped";
        try (Connection c = service.getConnection(1L); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'odw_smoke_password'");
            s.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            s.execute("GRANT SELECT ON " + schema + ".orders TO " + role);
        }
        try {
            when(userMapping.getDorisCredential("user", 1L, schema))
                    .thenReturn(new DorisCredential(role, "odw_smoke_password"));
            UserContextHolder.setContext(new UserContext("user", "user", "admin", "local"));
            try (Connection c = service.getConnection(1L, schema);
                    Statement s = c.createStatement();
                    java.sql.ResultSet rs = s.executeQuery("SELECT current_user")) {
                assertTrue(rs.next());
                assertEquals(role, rs.getString(1));
            }
        } finally {
            UserContextHolder.clear();
            try (Connection c = service.getConnection(1L); Statement s = c.createStatement()) {
                s.execute("DROP OWNED BY " + role);
                s.execute("DROP ROLE " + role);
            }
        }
    }

    @Test
    void agentExecutorRunsReadOnlyQueriesAndBuildsColumnDdl() {
        AgentDatasourceResolution datasource = new AgentDatasourceResolution();
        datasource.setEngine("postgresql");
        datasource.setDatabase(schema);
        datasource.setConnectionDatabase("odw_smoke");
        datasource.setHost("127.0.0.1");
        datasource.setPort(cluster.getFePort());
        datasource.setUser("postgres");
        datasource.setPassword("odw_smoke_password");
        AgentJdbcExecutor executor = new AgentJdbcExecutor(new DorisJdbcProperties());

        AgentJdbcExecutor.QueryExecutionResult result =
                executor.executeReadOnlyQuery(datasource, "SELECT note, labels FROM orders", 10, 10);
        assertEquals("ok", result.getRows().get(0).get("note"));
        assertEquals(java.util.Arrays.asList("a", "b"), result.getRows().get(0).get("labels"));
        AgentJdbcExecutor.QueryExecutionResult limited =
                executor.executeReadOnlyQuery(datasource, "SELECT g FROM generate_series(1, 50) g", 3, 10);
        assertEquals(3, limited.getRowCount());
        assertTrue(limited.isHasMore());
        RuntimeException writeError = assertThrows(RuntimeException.class,
                () -> executor.executeReadOnlyQuery(datasource, "SELECT 1; DELETE FROM orders", 10, 10));
        assertTrue(writeError.getMessage().contains("read-only"));

        String ddl = executor.fetchTableDdl(datasource, schema, "orders", 10);
        assertTrue(ddl.contains("\"amount\" numeric(18,2)"));
        assertTrue(ddl.contains("PRIMARY KEY (\"id\")"));
        assertTrue(ddl.contains("IS '金额'"));
    }

    @Test
    void metadataIncludesCommentsTypesPrimaryKeysAndUnknownStatistics() {
        assertTrue(service.getAllDatabases(1L).contains(schema));
        assertFalse(service.getAllDatabases(1L).contains("pg_catalog"));
        Map<String, Map<String, Object>> tables = service.getTablesInDatabase(1L, schema).stream()
                .collect(Collectors.toMap(t -> (String) t.get("tableName"), t -> t));
        assertEquals("订单_100%", tables.get("orders").get("tableComment"));
        assertEquals("VIEW", tables.get("order_view").get("tableType"));
        assertEquals(1L, tables.get("orders").get("tableRows"));
        assertNull(tables.get("unknown_rows").get("tableRows"));
        assertNull(tables.get("orders").get("updateTime"));
        assertNull(tables.get("orders").get("createTime"));
        List<Map<String, Object>> columns = service.getColumnsInTable(1L, schema, "orders");
        assertEquals(1, columns.get(0).get("isPrimary"));
        assertEquals(0, columns.get(0).get("isNullable"));
        assertEquals("numeric(18,2)", columns.get(1).get("dataType"));
        assertEquals("金额", columns.get(1).get("columnComment"));
        assertNotNull(columns.get(1).get("defaultValue"));
        assertEquals("character varying(40)", columns.get(2).get("dataType"));
        assertEquals("text[]", columns.get(3).get("dataType"));
        assertTrue(service.getSchemaObjects(1L, "订单_100%").stream()
                .anyMatch(t -> schema.equals(t.get("schemaName")) && "orders".equals(t.get("tableName"))));
        assertTrue(service.getSchemaObjects(1L, "missing_'%_").isEmpty());
        assertEquals(1L, service.getTableStatistics(1L, schema, "orders").getRowCount());
        assertTrue(service.getTableStatistics(1L, schema, "orders").getDataSize() > 0);
        assertEquals(5, service.getAllTableStatistics(1L, schema).size());
        assertTrue(tables.containsKey("events"));
        assertFalse(tables.containsKey("events_2026"));
        // A partitioned parent reports its leaf partitions, not its own empty relation.
        assertEquals(1L, tables.get("events").get("tableRows"));
        assertTrue((Long) tables.get("events").get("dataLength") > 0);
        // INCLUDE columns live in the primary key index but are not key columns.
        List<Map<String, Object>> covered = service.getColumnsInTable(1L, schema, "covered");
        assertEquals(1, covered.get(0).get("isPrimary"));
        assertEquals(0, covered.get(1).get("isPrimary"));
        assertTrue(service.getTableCreateInfo(1L, schema, "orders").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> service.getTableDdl(1L, schema, "orders"));
    }

    @Test
    void previewAndColumnProfileQuotePostgresqlIdentifiers() {
        Map<String, Object> row = service.previewTableData(1L, schema, "orders", 10).get(0);
        assertEquals("quoted", row.get("odd\"column"));
        // Arrays come back as plain lists that survive the closed connection, not a live PgArray.
        assertEquals(java.util.Arrays.asList("a", "b"), row.get("labels"));
        assertEquals("quoted", service.profileColumnValues(1L, schema, "orders",
                java.util.Collections.singletonList("odd\"column"), 5).get(0).getValues().get(0).getValue());
    }

    @Test
    void restrictedAccountOnlySeesGrantedTablesAndColumns() throws Exception {
        String role = schema + "_reader";
        try (Connection c = service.getConnection(1L); Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + role + " LOGIN PASSWORD 'odw_smoke_password'");
            s.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
            s.execute("GRANT SELECT(id) ON " + schema + ".orders TO " + role);
        }
        try {
            cluster.setUsername(role);
            List<Map<String, Object>> tables = service.getTablesInDatabase(1L, schema);
            assertEquals(1, tables.size());
            assertEquals("orders", tables.get(0).get("tableName"));
            List<Map<String, Object>> columns = service.getColumnsInTable(1L, schema, "orders");
            assertEquals(1, columns.size());
            assertEquals("id", columns.get(0).get("columnName"));
        } finally {
            cluster.setUsername("postgres");
            try (Connection c = service.getConnection(1L); Statement s = c.createStatement()) {
                s.execute("DROP OWNED BY " + role);
                s.execute("DROP ROLE " + role);
            }
        }
    }

    @Test
    void sameNamedTablesAndQuotedSchemasStayIsolated() throws Exception {
        String other = schema + "_other";
        try (Connection c = service.getConnection(1L); Statement statement = c.createStatement()) {
            statement.execute("CREATE SCHEMA " + other);
            try {
                statement.execute("CREATE TABLE " + other + ".orders (note text)");
                statement.execute("INSERT INTO " + other + ".orders VALUES ('other schema')");
                assertEquals("other schema", service.previewTableData(1L, other, "orders", 1).get(0).get("note"));
                assertEquals("ok", service.previewTableData(1L, schema, "orders", 1).get(0).get("note"));
                assertEquals(1, service.getColumnsInTable(1L, other, "orders").size());
                assertEquals(5, service.getColumnsInTable(1L, schema, "orders").size());
            } finally { statement.execute("DROP SCHEMA " + other + " CASCADE"); }
        }
    }

    @Test
    void failedConnectionReturnsFalse() {
        cluster.setDatabaseName("odw_missing_database");
        try {
            assertFalse(service.testConnection(1L));
        } finally {
            cluster.setDatabaseName("odw_smoke");
        }
    }
}
