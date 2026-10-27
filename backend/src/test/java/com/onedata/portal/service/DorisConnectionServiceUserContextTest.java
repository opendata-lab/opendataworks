package com.onedata.portal.service;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class DorisConnectionServiceUserContextTest {
    private final DatasourceSubjectAccountService accounts = mock(DatasourceSubjectAccountService.class);
    private final DorisClusterMapper clusters = mock(DorisClusterMapper.class);
    private DorisConnectionService service;

    @BeforeEach
    void setUp() {
        DorisCluster cluster = new DorisCluster();
        cluster.setId(1L);
        cluster.setSourceType("MYSQL");
        when(clusters.selectById(1L)).thenReturn(cluster);
        DorisJdbcProperties properties = new DorisJdbcProperties();
        service = new DorisConnectionService(clusters, properties, accounts,
                DatasourceAdapterFixtures.registry(properties));
    }

    @Test
    void usesCurrentPlatformUserIdEvenWithoutNamespace() throws Exception {
        Connection mapped = mock(Connection.class);
        when(accounts.currentUserId()).thenReturn("17");
        when(accounts.open(1L, "USER", "17", null, false)).thenReturn(mapped);
        assertSame(mapped, service.getConnection(1L));
        verify(accounts).open(1L, "USER", "17", null, false);
    }

    @Test
    void missingMappingNeverUsesSourceCredentials() throws Exception {
        when(accounts.currentUserId()).thenReturn("17");
        when(accounts.open(1L, "USER", "17", "sales", false))
                .thenThrow(new IllegalArgumentException("未配置用户的数据源账号映射"));
        assertThrows(IllegalArgumentException.class, () -> service.getConnection(1L, "sales"));
        verify(accounts).currentUserId();
        verify(accounts).open(1L, "USER", "17", "sales", false);
        verifyNoMoreInteractions(accounts);
    }

    @Test
    void anonymousContextCannotUseSourceCredentials() {
        when(accounts.currentUserId()).thenThrow(new IllegalArgumentException("请登录后访问数据源"));
        assertThrows(IllegalArgumentException.class, () -> service.getConnection(1L));
        verify(accounts).currentUserId();
        verifyNoMoreInteractions(accounts);
    }
}
