package com.onedata.portal.service;

import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.controller.DatasourceTypeController;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.datasource.adapters.Descriptors;
import com.onedata.portal.service.datasource.adapters.doris.DorisAdapter;
import com.onedata.portal.service.datasource.adapters.mysql.MysqlAdapter;
import com.onedata.portal.service.datasource.adapters.postgresql.PostgresqlAdapter;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.driver.JdbcDriverRegistry;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.datasource.model.DatasourceTypeDefinition;
import com.onedata.portal.service.datasource.model.JdbcConnectionSpec;
import com.onedata.portal.service.datasource.model.Namespace;
import com.onedata.portal.service.datasource.model.TableMetadata;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import com.onedata.portal.service.datasource.spi.DatasourceMetadataReader;
import com.onedata.portal.service.table.TableEngineHandler;
import com.onedata.portal.service.table.TableEngineHandlerRegistry;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DatasourceAdapterContractTest {

    private DorisJdbcProperties properties() {
        DorisJdbcProperties properties = new DorisJdbcProperties();
        properties.setUrlTemplate("jdbc:mysql://%s:%d/%s");
        properties.setDefaultDatabase("information_schema");
        properties.setSessionCharsetEnabled(false);
        return properties;
    }

    private Descriptors.Builder testDescriptor(String label, String driverClass) {
        return Descriptors.builder("TEST_DB", label)
                .defaultPort(1234)
                .namespaceLabel("数据库")
                .identifierQuote("\"")
                .editorDialect("standard")
                .driverClass(driverClass)
                .connectionDatabaseRequired(false);
    }

    @Test
    void definitionsFollowAdapterOrderSoDorisStaysTheDefaultType() {
        DorisJdbcProperties properties = new DorisJdbcProperties();
        DatasourceAdapterRegistry registry = new DatasourceAdapterRegistry(
                Arrays.asList(new PostgresqlAdapter(), new MysqlAdapter(properties), new DorisAdapter(properties)),
                new JdbcDriverRegistry());

        List<String> types = registry.definitions().stream()
                .map(definition -> definition.getDescriptor().getType())
                .collect(Collectors.toList());

        assertEquals(Arrays.asList("DORIS", "MYSQL", "POSTGRESQL"), types);
    }

    @Test
    void readOnlyIsSetOnMysqlButLeftToSqlGuardOnDoris() throws Exception {
        DorisJdbcProperties properties = new DorisJdbcProperties();
        Connection mysqlConnection = mock(Connection.class);
        Connection dorisConnection = mock(Connection.class);

        new MysqlAdapter(properties).applyReadOnly(mysqlConnection);
        new DorisAdapter(properties).applyReadOnly(dorisConnection);

        verify(mysqlConnection).setReadOnly(true);
        verify(dorisConnection, never()).setReadOnly(anyBoolean());
    }

    @Test
    void builtinDescriptionsMatchThePublishedJsonContract() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        // 基线锁定公开字段、默认值、能力及提示文案；接口变化须显式更新。
        try (InputStream baseline = getClass().getResourceAsStream("/datasource-descriptors.json")) {
            assertNotNull(baseline);
            JsonNode expected = mapper.readTree(baseline);
            JsonNode actual = mapper.valueToTree(DatasourceAdapterFixtures.registry(properties()).definitions());
            assertEquals(expected, actual);
        }
    }

    @Test
    void sharedUnsupportedReasonDoesNotAssumePostgresql() {
        DatasourceDescriptor descriptor = testDescriptor("Test database", TestDriver.class.getName())
                .capability("ddlExport", false)
                .build();

        assertEquals("Test database 暂不支持此操作", descriptor.getCapabilities().get("ddlExport").getReason());
    }

    @Test
    void descriptorsUrlsDialectsAndCapabilitiesMatchAllBuiltins() throws Exception {
        DatasourceAdapterRegistry registry = DatasourceAdapterFixtures.registry(properties());
        assertEquals(3, registry.definitions().size());
        for (String type : Arrays.asList("DORIS", "MYSQL", "POSTGRESQL")) {
            DatasourceAdapter adapter = registry.require(type.toLowerCase(Locale.ROOT));
            ConnectionConfig config = new ConnectionConfig(
                    "127.0.0.1", adapter.descriptor().getDefaultPort(), "reader", "business");
            registry.validate(type, config);
            assertEquals("AVAILABLE", registry.definition(type).getDriverStatus());
            String url = adapter.connectionSpec(config, "sales").getUrl();
            assertTrue(url.endsWith(type.equals("POSTGRESQL")
                    ? "/business?connectTimeout=5&socketTimeout=300" : "/sales")
                    || url.startsWith("jdbc:postgresql://127.0.0.1:5432/business?"));
            String quote = adapter.descriptor().getIdentifierQuote();
            assertEquals(quote + "odd" + quote + quote + "name" + quote,
                    adapter.dialect().quoteIdentifier("odd" + quote + "name"));
            assertTrue(adapter.dialect().defaultQuery("sales", "orders", 200)
                    .contains(quote + "sales" + quote + "." + quote + "orders" + quote));
            assertEquals(type.equals("DORIS"), adapter.descriptor().supports("partitions"));
            assertEquals(!type.equals("POSTGRESQL"), adapter.descriptor().supports("freshnessMetadata"));
        }
        assertThrows(UnsupportedOperationException.class,
                () -> registry.requireCapability("POSTGRESQL", "ddlExport"));
        assertThrows(UnsupportedOperationException.class,
                () -> registry.requireCapability("POSTGRESQL", "structureEdit"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("unknown"));
        assertThrows(IllegalArgumentException.class,
                () -> registry.validate("POSTGRESQL", new ConnectionConfig("localhost", 5432, "u", null)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.validate("MYSQL", new ConnectionConfig("db/?inject", 3306, "u", null)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.validate("MYSQL", new ConnectionConfig("localhost", 0, "u", null)));
        String json = new ObjectMapper().writeValueAsString(registry.definitions());
        assertTrue(json.contains("\"schemaVersion\":1"));
        assertFalse(json.contains("connectionSpec"));
    }

    @Test
    void allReadersReturnNormalizedMetadataAndPreserveUnknownStatistics() throws Exception {
        DatasourceAdapterRegistry registry = DatasourceAdapterFixtures.registry(properties());
        for (String type : Arrays.asList("DORIS", "MYSQL", "POSTGRESQL")) {
            Connection connection = mock(Connection.class);
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet rs = mock(ResultSet.class);
            when(connection.prepareStatement(anyString())).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(rs);
            when(rs.next()).thenReturn(true, false);
            when(rs.getString("TABLE_NAME")).thenReturn("orders");
            when(rs.getString("TABLE_COMMENT")).thenReturn("订单_100%");
            when(rs.getString("TABLE_TYPE")).thenReturn("BASE TABLE");
            when(rs.wasNull()).thenReturn(true);
            List<TableMetadata> tables = registry.require(type).metadata().tables(connection, "sales");
            assertEquals("sales", tables.get(0).getSchemaName());
            assertEquals("订单_100%", tables.get(0).getTableComment());
            assertNull(tables.get(0).getTableRows());
            assertNull(tables.get(0).getUpdateTime());
            assertNull(tables.get(0).getDataLength());
            verify(statement).setString(1, "sales");
        }
    }

    @Test
    void duplicateTypesAndUnimplementedCapabilitiesAreRejected() {
        DatasourceAdapter pg = new PostgresqlAdapter();
        assertThrows(IllegalStateException.class,
                () -> new DatasourceAdapterRegistry(Arrays.asList(pg, pg), new JdbcDriverRegistry()));
        DatasourceAdapter lying = new PostgresqlAdapter() {
            @Override
            public DatasourceDescriptor descriptor() {
                return testDescriptor("Test", "org.postgresql.Driver")
                        .capability("ddlExport", true)
                        .build();
            }
        };
        assertThrows(IllegalStateException.class,
                () -> new DatasourceAdapterRegistry(Collections.singletonList(lying), new JdbcDriverRegistry()));
    }

    @Test
    void driverRegistryReportsMissingAndWrongClassWithoutFallback() {
        JdbcDriverRegistry drivers = new JdbcDriverRegistry();
        assertEquals("DRIVER_NOT_INSTALLED", drivers.status("no.such.Driver"));
        assertEquals("INVALID_DRIVER_CLASS", drivers.status("java.lang.String"));
        assertThrows(IllegalStateException.class, () -> drivers.requireAvailable("no.such.Driver"));
    }

    @Test
    void newAdapterIsDiscoveredSavedAndDispatchedWithoutChangingServices() throws Exception {
        DatasourceMetadataReader metadata = mock(DatasourceMetadataReader.class);
        Connection connection = mock(Connection.class);
        Driver driver = new TestDriver(connection);
        DriverManager.registerDriver(driver);
        try {
            DatasourceAdapter adapter = new PostgresqlAdapter() {
                @Override
                public DatasourceDescriptor descriptor() {
                    return testDescriptor("Test database", TestDriver.class.getName())
                            .connectionField(new DatasourceDescriptor.ConnectionField(
                                    "serviceName", "服务名", "text", true, "", ""))
                            .connectionField(new DatasourceDescriptor.ConnectionField(
                                    "accessToken", "访问令牌", "password", false, "", ""))
                            .capability("ddlExport", false)
                            .build();
                }

                @Override
                public JdbcConnectionSpec connectionSpec(ConnectionConfig config, String namespace) {
                    assertEquals("sales", config.getOptions().get("serviceName"));
                    assertEquals("secret-token", config.getOptions().get("accessToken"));
                    return new JdbcConnectionSpec("jdbc:adaptertest:db", TestDriver.class.getName());
                }

                @Override
                public void validate(ConnectionConfig config) {
                }

                @Override
                public void initializeSession(Connection connection, String namespace) {
                }

                @Override
                public DatasourceMetadataReader metadata() {
                    return metadata;
                }
            };
            DatasourceAdapterRegistry registry = new DatasourceAdapterRegistry(
                    Collections.singletonList(adapter), new JdbcDriverRegistry());
            MockMvc http = MockMvcBuilders.standaloneSetup(new DatasourceTypeController(registry)).build();
            http.perform(get("/v1/datasource-types"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data[0].type").value("TEST_DB"))
                    .andExpect(jsonPath("$.data[0].driverStatus").value("AVAILABLE"));
            DorisClusterMapper mapper = mock(DorisClusterMapper.class);
            DorisCluster cluster = new DorisCluster();
            cluster.setId(7L);
            cluster.setSourceType("test_db");
            cluster.setClusterName("test");
            cluster.setFeHost("localhost");
            cluster.setFePort(1234);
            cluster.setUsername("reader");
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("serviceName", "sales");
            options.put("accessToken", "secret-token");
            cluster.setConnectionOptions(options);
            new DorisClusterService(mapper, registry).create(cluster);
            verify(mapper).insert(cluster);
            String json = new ObjectMapper().writeValueAsString(cluster);
            assertTrue(json.contains("sales"));
            assertFalse(json.contains("secret-token"));
            DorisCluster roundTrip = new ObjectMapper().readValue(
                    "{\"connectionOptions\":{\"serviceName\":\"sales\",\"accessToken\":\"secret-token\"}}", DorisCluster.class);
            assertEquals(options, roundTrip.getConnectionOptions());
            // 存储层 JSON 编解码契约与数据库 TEXT 列使用相同类型处理器。
            JacksonTypeHandler handler =
                    new JacksonTypeHandler(Map.class);
            PreparedStatement stored = mock(PreparedStatement.class);
            handler.setNonNullParameter(stored, 1, options, org.apache.ibatis.type.JdbcType.VARCHAR);
            verify(stored).setString(1, new ObjectMapper().writeValueAsString(options));
            assertEquals("TEST_DB", cluster.getTypeDefinition().getDescriptor().getType());
            when(mapper.selectById(7L)).thenReturn(cluster);
            when(metadata.namespaces(connection))
                    .thenReturn(Collections.singletonList(new Namespace("db", "sales", "sales")));
            DorisConnectionService service = new DorisConnectionService(
                    mapper, properties(), mock(DatasourceSubjectAccountService.class), registry);
            assertEquals(Collections.singletonList("sales"), service.withSystemAccount(() -> service.getAllDatabases(7L)));
            verify(metadata).namespaces(connection);
            verify(connection).close();
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void failedSessionInitializationClosesConnection() throws Exception {
        Connection connection = mock(Connection.class);
        doThrow(new SQLException("schema denied")).when(connection).setSchema("restricted");
        Driver driver = new TestDriver(connection);
        DriverManager.registerDriver(driver);
        try {
            PostgresqlAdapter adapter = new PostgresqlAdapter() {
                @Override
                public JdbcConnectionSpec connectionSpec(ConnectionConfig config, String namespace) {
                    return new JdbcConnectionSpec("jdbc:adaptertest:db", "org.postgresql.Driver");
                }
            };
            DatasourceAdapterRegistry registry = new DatasourceAdapterRegistry(
                    Collections.singletonList(adapter), new JdbcDriverRegistry());
            DorisCluster cluster = new DorisCluster();
            cluster.setSourceType("POSTGRESQL");
            cluster.setFeHost("localhost");
            cluster.setFePort(5432);
            cluster.setUsername("reader");
            cluster.setDatabaseName("business");
            DorisClusterMapper mapper = mock(DorisClusterMapper.class);
            when(mapper.selectById(1L)).thenReturn(cluster);
            DorisConnectionService service = new DorisConnectionService(
                    mapper, properties(), mock(DatasourceSubjectAccountService.class), registry);
            assertThrows(SQLException.class, () -> new com.onedata.portal.service.datasource.core.DatasourceConnectionFactory(registry).open(
                        cluster.getSourceType(), com.onedata.portal.service.datasource.model.ConnectionConfig.from(cluster),
                        "restricted", cluster.getUsername(), cluster.getPassword(), false));
            verify(connection).close();
        } finally {
            DriverManager.deregisterDriver(driver);
        }
    }

    @Test
    void capabilityCatalogRejectsUnknownNamesAndDefaultsToDenied() {
        assertThrows(IllegalStateException.class,
                () -> testDescriptor("Test", TestDriver.class.getName()).capability("typo", true));
        DatasourceDescriptor descriptor = testDescriptor("Test", TestDriver.class.getName()).build();
        assertFalse(descriptor.supports("backup"));
        assertEquals("此数据源不支持备份", descriptor.getCapabilities().get("backup").getReason());
        for (DatasourceTypeDefinition definition
                : DatasourceAdapterFixtures.registry(properties()).definitions()) {
            DatasourceAdapter adapter = DatasourceAdapterFixtures.registry(properties()).require(
                    definition.getDescriptor().getType());
            Assertions.assertSame(adapter.descriptor(), adapter.descriptor());
            Assertions.assertSame(adapter.dialect(), adapter.dialect());
            Assertions.assertSame(adapter.metadata(), adapter.metadata());
        }
    }

    @Test
    void undeclaredAndUnsafeConnectionOptionsAreRejected() {
        DatasourceAdapterRegistry registry = DatasourceAdapterFixtures.registry(properties());
        assertThrows(IllegalArgumentException.class, () -> registry.validate("MYSQL", new ConnectionConfig(
                "localhost", 3306, "reader", "db", Collections.singletonMap("readOnlyMode", "never"))));
        for (String key : Arrays.asList("readOnlyMode", "sourceType", "constructor", "jdbcUrl")) {
            DatasourceAdapter unsafe = new PostgresqlAdapter() {
                @Override
                public DatasourceDescriptor descriptor() {
                    return testDescriptor("Test", "org.postgresql.Driver")
                            .connectionField(new DatasourceDescriptor.ConnectionField(
                                    key, "不安全字段", "text", false, "", "")).build();
                }
            };
            assertThrows(IllegalStateException.class, () -> new DatasourceAdapterRegistry(
                    Collections.singletonList(unsafe), new JdbcDriverRegistry()));
        }
    }

    @Test
    void newEditingAdapterAndHandlerUseTheSameNormalizedStringType() {
        DatasourceAdapter adapter = new PostgresqlAdapter() {
            private final DatasourceDescriptor descriptor = testDescriptor("Test", "org.postgresql.Driver")
                    .capability("structureEdit", true).build();

            @Override
            public DatasourceDescriptor descriptor() {
                return descriptor;
            }
        };
        TableEngineHandler handler =
                new TableEngineHandler() {
                    @Override
                    public String sourceType() {
                        return " test_db ";
                    }
                };
        DatasourceAdapterRegistry registry = new DatasourceAdapterRegistry(
                Collections.singletonList(adapter), new JdbcDriverRegistry());
        registry.validateEditHandlers(Collections.singletonList(handler));
        TableEngineHandlerRegistry handlers =
                new TableEngineHandlerRegistry(Collections.singletonList(handler));
        Assertions.assertSame(handler, handlers.require("TEST_DB"));
        assertThrows(IllegalStateException.class, () ->
                new TableEngineHandlerRegistry(Arrays.asList(handler, handler)));
        assertThrows(IllegalStateException.class, () -> registry.validateEditHandlers(Collections.emptyList()));
    }

    public static class TestDriver implements Driver {

        private final Connection connection;

        TestDriver(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection connect(String url, Properties properties) {
            return acceptsURL(url) ? connection : null;
        }

        @Override
        public boolean acceptsURL(String url) {
            return url.startsWith("jdbc:adaptertest:");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties properties) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
