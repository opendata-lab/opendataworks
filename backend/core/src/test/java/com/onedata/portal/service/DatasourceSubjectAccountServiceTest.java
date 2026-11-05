package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.context.PrincipalContextHolder;
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

class DatasourceSubjectAccessServiceTest {
    private final DatasourceSubjectAccountMapper accounts =
            mock(DatasourceSubjectAccountMapper.class);
    private final DorisClusterMapper clusters = mock(DorisClusterMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final DatasourceConnectionFactory factory = mock(DatasourceConnectionFactory.class);
    private final com.onedata.portal.mapper.DatasourceAccessPermissionMapper permissions =
            mock(com.onedata.portal.mapper.DatasourceAccessPermissionMapper.class);
    private final com.onedata.portal.service.permission.PermissionCredentialCipher cipher =
            new com.onedata.portal.service.permission.PermissionCredentialCipher(
                    java.util.Base64.getEncoder().encodeToString(new byte[32]));
    private final DatasourceSubjectAccessService service =
            new DatasourceSubjectAccessService(
                    mock(com.onedata.portal.mapper.DataTableMapper.class),
                    accounts,
                    clusters,
                    users,
                    factory,
                    permissions,
                    cipher,
                    DatasourceAdapterFixtures.registry(
                            new com.onedata.portal.config.DatasourceJdbcProperties()));

    @BeforeEach
    void metadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                DatasourceSubjectAccount.class);
        SysUser serviceUser = user();
        serviceUser.setId(19L);
        serviceUser.setAccountType("SERVICE");
        when(users.selectById(19L)).thenReturn(serviceUser);
    }

    @AfterEach
    void clear() {
        PrincipalContextHolder.clear();
    }

    @Test
    void localAndOauthUsePrefixSelectedIdentifierField() {
        for (String identity : new String[] {"local:alice", "oidc:subject:123"}) {
            SysUser user = user();
            when(users.selectOne(any())).thenReturn(user);
            assertEquals(17L, service.resolveUser(identity).getId());
        }
        ArgumentCaptor<LambdaQueryWrapper<SysUser>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(users, org.mockito.Mockito.times(2)).selectOne(captor.capture());
        String local = captor.getAllValues().get(0).getSqlSegment();
        String oauth = captor.getAllValues().get(1).getSqlSegment();
        assertTrue(local.contains("username"));
        assertTrue(local.contains("auth_source"));
        assertTrue(oauth.contains("external_id"));
        assertTrue(
                captor.getAllValues().get(1).getParamNameValuePairs().containsValue("subject:123"));
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
        PrincipalContextHolder.setContext(new CurrentPrincipal("17", "alice", "admin", "local"));
        assertEquals("17", service.currentUserId());
        PrincipalContextHolder.setContext(
                new CurrentPrincipal("17", "alice", "admin", "anonymous"));
        assertThrows(IllegalArgumentException.class, service::currentUserId);
    }

    @Test
    void missingMappingDoesNotOpenAnyConnection() throws Exception {
        DorisCluster cluster = new DorisCluster();
        when(clusters.selectById(1L)).thenReturn(cluster);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.open(1L, "SERVICE", "19", "sales", true));
        verify(factory, never()).open(any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void managedConnectionDecryptsOnlyAtTheConnectionBoundary() throws Exception {
        DorisCluster cluster = new DorisCluster();
        cluster.setSourceType("MYSQL");
        when(clusters.selectById(1L)).thenReturn(cluster);
        DatasourceSubjectAccount account = new DatasourceSubjectAccount();
        account.setUsername("odw_managed");
        account.setManaged(true);
        account.setPassword(cipher.encrypt("private-native-password"));
        when(accounts.selectOne(any())).thenReturn(account);
        service.open(1L, "SERVICE", "19", "sales", true);
        verify(factory)
                .open(
                        eq("MYSQL"),
                        any(),
                        eq("sales"),
                        eq("odw_managed"),
                        eq("private-native-password"),
                        eq(true));
        assertFalse(
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(account)
                        .contains("password"));
    }

    @Test
    void invalidManagedCiphertextNeverOpensAConnection() throws Exception {
        when(clusters.selectById(1L)).thenReturn(new DorisCluster());
        DatasourceSubjectAccount account = new DatasourceSubjectAccount();
        account.setManaged(true);
        account.setPassword("not-an-envelope");
        when(accounts.selectOne(any())).thenReturn(account);
        assertThrows(
                IllegalStateException.class,
                () -> service.open(1L, "SERVICE", "19", "sales", true));
        verifyNoInteractions(factory);
    }

    @Test
    void existingExternalPasswordIsNeverInterpretedAsCiphertext() throws Exception {
        DorisCluster cluster = new DorisCluster();
        cluster.setSourceType("MYSQL");
        when(clusters.selectById(1L)).thenReturn(cluster);
        DatasourceSubjectAccount account = new DatasourceSubjectAccount();
        account.setUsername("legacy");
        account.setPassword("v1:external-plaintext-password");
        when(accounts.selectOne(any())).thenReturn(account);
        service.open(1L, "SERVICE", "19", "sales", true);
        verify(factory)
                .open(
                        eq("MYSQL"),
                        any(),
                        eq("sales"),
                        eq("legacy"),
                        eq("v1:external-plaintext-password"),
                        eq(true));
    }

    private SysUser user() {
        SysUser user = new SysUser();
        user.setId(17L);
        user.setEnabled(true);
        return user;
    }

    @Test
    void resolvingDatasourceRejectsMissingDisabledOrUnmappedDelegates() {
        DorisCluster source = new DorisCluster();
        source.setId(1L);
        source.setSourceType("MYSQL");
        when(clusters.selectById(1L)).thenReturn(source);
        when(accounts.selectOne(any())).thenReturn(new DatasourceSubjectAccount());
        PrincipalContextHolder.setContext(CurrentPrincipal.service(19L, "local:alice"));
        assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveClusterDatasource(1L, "demo", null, "test"));
        SysUser delegate = user();
        delegate.setEnabled(false);
        when(users.selectOne(any())).thenReturn(delegate);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveClusterDatasource(1L, "demo", null, "test"));
        delegate.setEnabled(true);
        when(users.selectById(17L)).thenReturn(delegate);
        when(accounts.selectOne(any()))
                .thenAnswer(
                        call -> {
                            LambdaQueryWrapper<?> query = call.getArgument(0);
                            query.getSqlSegment();
                            return query.getParamNameValuePairs().containsValue("17")
                                    ? null
                                    : new DatasourceSubjectAccount();
                        });
        assertThrows(
                IllegalArgumentException.class,
                () -> service.resolveClusterDatasource(1L, "demo", null, "test"));
        verifyNoInteractions(factory);
    }
}
