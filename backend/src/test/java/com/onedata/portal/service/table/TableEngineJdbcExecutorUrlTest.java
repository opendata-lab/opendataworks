package com.onedata.portal.service.table;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.DatasourceAdapterFixtures;
import com.onedata.portal.service.DorisConnectionService;
import com.onedata.portal.service.DatasourceSubjectAccountService;
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
        DorisConnectionService connectionService = new DorisConnectionService(
                mapper, new DorisJdbcProperties(),
                mock(DatasourceSubjectAccountService.class),
                DatasourceAdapterFixtures.registry(new DorisJdbcProperties()));
        TableEngineJdbcExecutor executor = new TableEngineJdbcExecutor(mapper, connectionService);

        // Connector/J would read allowLoadLocalInfile=true out of this "database".
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> connectionService.withSystemAccount(() -> {
                    executor.execute(1L, "test?allowLoadLocalInfile=true&x=", "SELECT 1", "MYSQL");
                    return null;
                }));
        assertTrue(error.getMessage().contains("数据库名包含非法字符"));
    }
}
