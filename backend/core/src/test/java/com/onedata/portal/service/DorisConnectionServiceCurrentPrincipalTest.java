package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.onedata.portal.config.DatasourceJdbcProperties;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

class DorisConnectionServiceCurrentPrincipalTest {
    private final DatasourceSubjectAccessService accounts =
            mock(DatasourceSubjectAccessService.class);
    private final DorisClusterMapper clusters = mock(DorisClusterMapper.class);
    private DorisConnectionService service;

    @BeforeEach
    void setUp() {
        DorisCluster cluster = new DorisCluster();
        cluster.setId(1L);
        cluster.setSourceType("MYSQL");
        when(clusters.selectById(1L)).thenReturn(cluster);
        DatasourceJdbcProperties properties = new DatasourceJdbcProperties();
        service =
                new DorisConnectionService(
                        clusters,
                        properties,
                        accounts,
                        DatasourceAdapterFixtures.registry(properties));
    }

    @Test
    void usesCurrentPlatformUserIdEvenWithoutNamespace() throws Exception {
        Connection mapped = mock(Connection.class);
        when(accounts.openPrincipalConnection(1L, null, false)).thenReturn(mapped);
        assertSame(mapped, service.getConnection(1L));
        verify(accounts).openPrincipalConnection(1L, null, false);
    }

    @Test
    void missingMappingNeverUsesSourceCredentials() throws Exception {
        when(accounts.openPrincipalConnection(1L, "sales", false))
                .thenThrow(new IllegalArgumentException("未配置用户的数据源账号映射"));
        assertThrows(IllegalArgumentException.class, () -> service.getConnection(1L, "sales"));
        verify(accounts).openPrincipalConnection(1L, "sales", false);
        verifyNoMoreInteractions(accounts);
    }

    @Test
    void anonymousContextCannotUseSourceCredentials() throws Exception {
        when(accounts.openPrincipalConnection(1L, null, false))
                .thenThrow(new IllegalArgumentException("请登录后访问数据源"));
        assertThrows(IllegalArgumentException.class, () -> service.getConnection(1L));
        verify(accounts).openPrincipalConnection(1L, null, false);
        verifyNoMoreInteractions(accounts);
    }
}
