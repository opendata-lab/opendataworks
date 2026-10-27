package com.onedata.portal.service;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.dto.TablePartitionInfo;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * {@link DorisConnectionService#listPartitions} 的解析行为。
 *
 * <p>重点覆盖不同 Doris 版本返回列不一致时的容错：结果集缺失的列留空，而不是整体失败。
 */
@ExtendWith(MockitoExtension.class)
class DorisConnectionServicePartitionsTest {

    @Mock
    private DorisClusterMapper dorisClusterMapper;

    @Mock
    private DatasourceSubjectAccountService userMappingService;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Mock
    private ResultSet resultSet;

    @Mock
    private ResultSetMetaData metaData;

    private DorisConnectionService dorisConnectionService;
    private Driver testDriver;

    @BeforeEach
    void setUp() throws Exception {
        DorisJdbcProperties properties = new DorisJdbcProperties();
        properties.setUrlTemplate("jdbc:odwparttest://%s:%d/%s");
        properties.setDefaultDatabase("information_schema");
        properties.setSessionCharsetEnabled(false);

        dorisConnectionService = new DorisConnectionService(dorisClusterMapper, properties, userMappingService, DatasourceAdapterFixtures.registry(properties));
        org.mockito.Mockito.lenient().when(userMappingService.currentUserId()).thenReturn("1");
        org.mockito.Mockito.lenient().when(userMappingService.open(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.eq("USER"), org.mockito.ArgumentMatchers.eq("1"),
                org.mockito.ArgumentMatchers.nullable(String.class), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(connection);

        DorisCluster cluster = new DorisCluster();
        cluster.setId(1L);
        cluster.setClusterName("test-cluster");
        cluster.setFeHost("127.0.0.1");
        cluster.setFePort(9030);
        cluster.setUsername("root");
        cluster.setPassword("root");
        when(dorisClusterMapper.selectById(1L)).thenReturn(cluster);

        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(resultSet.getMetaData()).thenReturn(metaData);

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
    void listPartitionsParsesRowsAndToleratesMissingColumns() throws Exception {
        // 仅返回部分列：缺少 PartitionKey / DistributionKey / StorageMedium / VisibleVersionTime
        String[] columns = {"PartitionName", "Range", "Buckets", "ReplicationNum", "DataSize", "RowCount", "State"};
        when(metaData.getColumnCount()).thenReturn(columns.length);
        for (int i = 0; i < columns.length; i++) {
            when(metaData.getColumnLabel(i + 1)).thenReturn(columns[i]);
        }

        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("PartitionName")).thenReturn("p20260101");
        when(resultSet.getString("Range")).thenReturn("[types: [DATEV2]; keys: [2026-01-01]; )");
        when(resultSet.getString("Buckets")).thenReturn("10");
        when(resultSet.getString("ReplicationNum")).thenReturn("3");
        when(resultSet.getString("DataSize")).thenReturn("1.234 GB");
        when(resultSet.getString("RowCount")).thenReturn("1234567");
        when(resultSet.getString("State")).thenReturn("NORMAL");

        List<TablePartitionInfo> partitions = dorisConnectionService.listPartitions(1L, "ods", "orders");

        assertEquals(1, partitions.size());
        TablePartitionInfo partition = partitions.get(0);
        assertEquals("p20260101", partition.getPartitionName());
        assertEquals("[types: [DATEV2]; keys: [2026-01-01]; )", partition.getRange());
        assertEquals(Integer.valueOf(10), partition.getBuckets());
        assertEquals(Integer.valueOf(3), partition.getReplicationNum());
        assertEquals("1.234 GB", partition.getDataSize());
        assertEquals(Long.valueOf(1234567L), partition.getRowCount());
        assertEquals("NORMAL", partition.getState());
        // 结果集未返回的列保持为空
        assertNull(partition.getPartitionKey());
        assertNull(partition.getDistributionKey());
        assertNull(partition.getStorageMedium());
        assertNull(partition.getVisibleVersionTime());
    }

    @Test
    void listPartitionsIgnoresNonNumericCounters() throws Exception {
        String[] columns = {"PartitionName", "Buckets", "RowCount"};
        when(metaData.getColumnCount()).thenReturn(columns.length);
        for (int i = 0; i < columns.length; i++) {
            when(metaData.getColumnLabel(i + 1)).thenReturn(columns[i]);
        }

        when(resultSet.next()).thenReturn(true, false);
        when(resultSet.getString("PartitionName")).thenReturn("p_default");
        when(resultSet.getString("Buckets")).thenReturn("");
        when(resultSet.getString("RowCount")).thenReturn("N/A");

        List<TablePartitionInfo> partitions = dorisConnectionService.listPartitions(1L, "ods", "orders");

        assertEquals(1, partitions.size());
        assertEquals("p_default", partitions.get(0).getPartitionName());
        assertNull(partitions.get(0).getBuckets());
        assertNull(partitions.get(0).getRowCount());
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
            return url != null && url.startsWith("jdbc:odwparttest://");
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
