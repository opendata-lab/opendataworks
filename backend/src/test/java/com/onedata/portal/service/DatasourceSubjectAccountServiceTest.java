package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.context.UserContext;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.portal.entity.DatasourceSubjectAccount;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DatasourceSubjectAccountMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.sql.Connection;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DatasourceSubjectAccountServiceTest {
    private final DatasourceSubjectAccountMapper accounts = mock(DatasourceSubjectAccountMapper.class);
    private final DorisClusterMapper clusters = mock(DorisClusterMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final DatasourceConnectionFactory factory = mock(DatasourceConnectionFactory.class);
    private final DatasourceSubjectAccountService service = new DatasourceSubjectAccountService(accounts, clusters, users, factory);

    @BeforeEach
    void metadata() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), DatasourceSubjectAccount.class);
    }

    @AfterEach
    void clear() {
        UserContextHolder.clear();
    }

    @Test
    void localAndOauthUsePrefixSelectedIdentifierField() {
        for (String identity : new String[]{"local:alice", "oidc:subject:123"}) {
            SysUser user = user();
            when(users.selectOne(any())).thenReturn(user);
            assertEquals(17L, service.resolveUser(identity).getId());
        }
        ArgumentCaptor<LambdaQueryWrapper<SysUser>> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(users, org.mockito.Mockito.times(2)).selectOne(captor.capture());
        String local = captor.getAllValues().get(0).getSqlSegment();
        String oauth = captor.getAllValues().get(1).getSqlSegment();
        assertTrue(local.contains("username"));
        assertTrue(local.contains("auth_source"));
        assertTrue(oauth.contains("external_id"));
        assertTrue(captor.getAllValues().get(1).getParamNameValuePairs().containsValue("subject:123"));
    }

    @Test
    void rejectsInvalidUnknownAndDisabledUsers() {
        assertThrows(IllegalArgumentException.class, () -> service.resolveUser("alice"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveUser("local:"));
        assertThrows(IllegalArgumentException.class, () -> service.resolveUser("oidc:unknown"));
        SysUser disabled = user();
        disabled.setEnabled(false);
        when(users.selectOne(any())).thenReturn(disabled);
        assertThrows(IllegalArgumentException.class, () -> service.resolveUser("local:alice"));
    }

    @Test
    void studioUsesPlatformIdAndRejectsAnonymous() {
        when(users.selectById("17")).thenReturn(user());
        UserContextHolder.setContext(new UserContext("17", "alice", "admin", "local"));
        assertEquals("17", service.currentUserId());
        UserContextHolder.setContext(new UserContext("17", "alice", "admin", "anonymous"));
        assertThrows(IllegalArgumentException.class, service::currentUserId);
    }

    @Test
    void missingMappingDoesNotOpenAnyConnection() throws Exception {
        DorisCluster cluster = new DorisCluster();
        when(clusters.selectById(1L)).thenReturn(cluster);
        assertThrows(IllegalArgumentException.class, () -> service.open(1L, "AGENT", "agent-a", "sales", true));
        verify(factory, never()).open(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void saveChecksConnectionBeforePersistingAndNeverSerializesPassword() throws Exception {
        DatasourceSubjectAccount account = new DatasourceSubjectAccount();
        account.setSubjectType("AGENT");
        account.setSubjectId("agent-a");
        account.setUsername("reader");
        account.setPassword("secret");
        DorisCluster cluster = new DorisCluster();
        cluster.setSourceType("MYSQL");
        when(clusters.selectById(1L)).thenReturn(cluster);
        when(factory.open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean()))
                .thenReturn(mock(Connection.class));
        service.save(1L, account);
        verify(accounts).insert(account);
        assertFalse(new ObjectMapper().writeValueAsString(account).contains("secret"));
        assertFalse(account.toString().contains("secret"));
    }

    @Test
    void failedConnectionDoesNotPersistMapping() throws Exception {
        DatasourceSubjectAccount account = new DatasourceSubjectAccount();
        account.setSubjectType("AGENT");
        account.setSubjectId("agent-a");
        account.setUsername("reader");
        account.setPassword("bad");
        DorisCluster cluster = new DorisCluster();
        cluster.setSourceType("MYSQL");
        when(clusters.selectById(1L)).thenReturn(cluster);
        when(factory.open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new SQLException("password must not leak"));
        assertThrows(IllegalArgumentException.class, () -> service.save(1L, account));
        verify(accounts, never()).insert(any());
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.setId(17L);
        user.setEnabled(true);
        return user;
    }
}
