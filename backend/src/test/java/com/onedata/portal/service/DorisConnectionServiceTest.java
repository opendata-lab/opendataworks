package com.onedata.portal.service;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DorisConnectionServiceTest {

    @Mock
    private DorisClusterMapper dorisClusterMapper;

    @Mock
    private UserMappingService userMappingService;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    private DorisConnectionService dorisConnectionService;
    private Driver testDriver;

    @BeforeEach
    void setUp() throws Exception {
        DorisJdbcProperties dorisJdbcProperties = new DorisJdbcProperties();
        dorisJdbcProperties.setUrlTemplate("jdbc:odwtest://%s:%d/%s");
        dorisJdbcProperties.setDefaultDatabase("information_schema");
        dorisJdbcProperties.setSessionCharsetEnabled(false);

        dorisConnectionService = new DorisConnectionService(dorisClusterMapper, dorisJdbcProperties, userMappingService, DatasourceAdapterFixtures.registry(dorisJdbcProperties));

        DorisCluster cluster = new DorisCluster();
        cluster.setId(1L);
        cluster.setClusterName("test-cluster");
        cluster.setFeHost("127.0.0.1");
        cluster.setFePort(9030);
        cluster.setUsername("root");
        cluster.setPassword("root");
        when(dorisClusterMapper.selectById(1L)).thenReturn(cluster);

        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        testDriver = new TestDriver(connection);
        DriverManager.registerDriver(testDriver);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (testDriver != null) {
            DriverManager.deregisterDriver(testDriver);
        }
    }

    @Test
    void getSchemaObjectsUsesLocatePredicateForKeywordSearch() throws Exception {
        List<?> objects = dorisConnectionService.getSchemaObjects(1L, "  Sales_2024  ");

        assertTrue(objects.isEmpty());

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();
        assertTrue(sql.contains("LOCATE(?, LOWER(TABLE_NAME)) > 0"));
        assertTrue(sql.contains("LOCATE(?, LOWER(IFNULL(TABLE_COMMENT, ''))) > 0"));
        assertFalse(sql.contains("ESCAPE"));

        verify(preparedStatement).setString(1, "sales_2024");
        verify(preparedStatement).setString(2, "sales_2024");
    }

    private static final class TestDriver implements Driver {

        private final Connection connection;

        private TestDriver(Connection connection) {
            this.connection = connection;
        }

        @Override
        public Connection connect(String url, Properties info) {
            if (!acceptsURL(url)) {
                return null;
            }
            return connection;
        }

        @Override
        public boolean acceptsURL(String url) {
            return url != null && url.startsWith("jdbc:odwtest://");
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
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
