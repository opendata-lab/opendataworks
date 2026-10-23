package com.onedata.portal.service.table;

import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.util.DatasourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TableEngineJdbcExecutorUrlTest {

    @Test
    void rejectsDatabaseThatWouldInjectConnectorParametersBeforeConnecting() {
        DorisCluster datasource = new DorisCluster();
        datasource.setId(1L);
        datasource.setClusterName("mysql-a");
        datasource.setSourceType("MYSQL");
        datasource.setFeHost("127.0.0.1");
        datasource.setFePort(3306);
        datasource.setUsername("root");
        DorisClusterMapper mapper = mock(DorisClusterMapper.class);
        when(mapper.selectById(1L)).thenReturn(datasource);
        TableEngineJdbcExecutor executor = new TableEngineJdbcExecutor(mapper);

        // Connector/J would read allowLoadLocalInfile=true out of this "database".
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> executor.execute(1L, "test?allowLoadLocalInfile=true&x=", "SELECT 1", DatasourceType.MYSQL));
        assertTrue(error.getMessage().contains("数据库名包含非法字符"));
    }
}
