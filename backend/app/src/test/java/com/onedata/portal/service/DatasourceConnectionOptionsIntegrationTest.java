package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.service.datasource.adapters.Descriptors;
import com.onedata.portal.service.datasource.adapters.postgresql.PostgresqlAdapter;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.datasource.model.JdbcConnectionSpec;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** 使用真实配置 mapper，证明新增适配器无须改通用服务或数据库专用表单。 */
@SpringBootTest
@Import(DatasourceConnectionOptionsIntegrationTest.Configuration.class)
@Transactional
class DatasourceConnectionOptionsIntegrationTest {

    private static final Connection CONNECTION = mock(Connection.class);
    private static final ExtraDriver DRIVER = new ExtraDriver(CONNECTION);

    @Autowired
    private DorisClusterService clusters;
    @Autowired
    private DatasourceConnectionFactory connections;
    @Autowired
    private ObjectMapper json;

    @BeforeAll
    static void registerDriver() throws SQLException {
        DriverManager.registerDriver(DRIVER);
    }

    @AfterAll
    static void removeDriver() throws SQLException {
        DriverManager.deregisterDriver(DRIVER);
    }

    @Test
    void declaredOptionsSurviveStorageAndReachConnectionWithoutLeakingSecrets() throws Exception {
        DorisCluster input = new DorisCluster();
        input.setClusterName("test-extra-connection");
        input.setSourceType("test_db");
        input.setFeHost("test-options.local");
        input.setFePort(15400);
        input.setUsername("reader");
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("serviceName", "sales");
        options.put("accessToken", "extra-secret");
        input.setConnectionOptions(options);
        DorisCluster saved = clusters.create(input);
        DorisCluster restored = clusters.getById(saved.getId());
        assertEquals(options, restored.getConnectionOptions());
        assertEquals("TEST_DB", restored.getTypeDefinition().getDescriptor().getType());
        assertFalse(json.writeValueAsString(restored).contains("extra-secret"));

        // HTTP 更新请求是独立对象，不能修改 MyBatis 会话缓存中的查询结果。
        DorisCluster update = json.readValue(json.writeValueAsString(restored), DorisCluster.class);
        update.setConnectionOptions(Collections.singletonMap("serviceName", "sales"));
        clusters.update(saved.getId(), update);
        restored = clusters.getById(saved.getId());
        assertEquals("extra-secret", restored.getConnectionOptions().get("accessToken"));
        try (Connection ignored = connections.open("test_db", ConnectionConfig.from(restored),
                "sales", restored.getUsername(), "", true)) {
            verify(CONNECTION).setReadOnly(true);
            verify(CONNECTION).setSchema("sales");
        }
        Map<String, Object> invalid = new LinkedHashMap<>(options);
        invalid.put("serviceName", "sales?readOnlyMode=never");
        input.setConnectionOptions(invalid);
        assertThrows(IllegalArgumentException.class, () -> clusters.create(input));
        invalid.put("serviceName", 123);
        assertThrows(IllegalArgumentException.class, () -> clusters.create(input));
        invalid.remove("serviceName");
        assertThrows(IllegalArgumentException.class, () -> clusters.create(input));
        invalid.put("readOnlyMode", "never");
        assertThrows(IllegalArgumentException.class, () -> clusters.create(input));
    }

    @TestConfiguration
    static class Configuration {

        @Bean
        DatasourceAdapter extraAdapter() {
            return new PostgresqlAdapter() {
                private final DatasourceDescriptor descriptor = Descriptors.builder("TEST_DB", "Test DB")
                        .agentEngine("test_db").defaultPort(15400).namespaceLabel("服务")
                        .identifierQuote("\"").editorDialect("standard")
                        .driverClass(ExtraDriver.class.getName())
                        .connectionField(new DatasourceDescriptor.ConnectionField(
                                "serviceName", "服务名", "text", true, "", ""))
                        .connectionField(new DatasourceDescriptor.ConnectionField(
                                "accessToken", "令牌", "password", false, "", ""))
                        .build();

                @Override
                public DatasourceDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public void validate(ConnectionConfig config) {
                    assertEquals("sales", config.getOptions().get("serviceName"));
                }

                @Override
                public JdbcConnectionSpec connectionSpec(ConnectionConfig config, String namespace) {
                    assertEquals("extra-secret", config.getOptions().get("accessToken"));
                    return new JdbcConnectionSpec("jdbc:extras-test:" + config.getOptions().get("serviceName"),
                            ExtraDriver.class.getName());
                }
            };
        }
    }

    public static class ExtraDriver extends DatasourceAdapterContractTest.TestDriver {

        ExtraDriver(Connection connection) {
            super(connection);
        }

        @Override
        public boolean acceptsURL(String url) {
            return url.startsWith("jdbc:extras-test:");
        }
    }
}
