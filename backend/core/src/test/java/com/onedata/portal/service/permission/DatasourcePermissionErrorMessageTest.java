package com.onedata.portal.service.permission;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.context.PrincipalContextHolder;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DatasourceAccessPermissionMapper;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;
import com.onedata.portal.service.permission.PermissionModels.PreviewRequest;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

import javax.sql.DataSource;

class DatasourcePermissionErrorMessageTest {
    private final DatasourceAccessPermissionMapper permissions =
            mock(DatasourceAccessPermissionMapper.class);
    private final DatasourceConnectionFactory connections = mock(DatasourceConnectionFactory.class);
    private final DorisClusterMapper clusters = mock(DorisClusterMapper.class);
    private final DataSource platform = mock(DataSource.class);
    private final DatasourcePermissionService service =
            new DatasourcePermissionService(
                    permissions,
                    platform,
                    null,
                    clusters,
                    null,
                    null,
                    connections,
                    PermissionTestAdapters.registry(),
                    null,
                    new ObjectMapper());
    private final PreviewRequest preview = new PreviewRequest();

    @BeforeEach
    void setup() throws Exception {
        PrincipalContextHolder.setContext(new CurrentPrincipal("1", "admin", "admin", "local"));
        DorisCluster source = new DorisCluster();
        source.setId(2L);
        source.setSourceType("MYSQL");
        source.setUsername("collector");
        source.setPassword("private-password");
        when(clusters.selectById(2L)).thenReturn(source);
        when(permissions.findRole(9L, 2L))
                .thenReturn(
                        List.of(
                                new LinkedHashMap<>(
                                        Map.of(
                                                "id",
                                                9L,
                                                "revision",
                                                0,
                                                "status",
                                                "ACTIVE",
                                                "nativeName",
                                                "test_role"))));
        preview.setExpectedRevision(0);
        Connection lock = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet result = mock(ResultSet.class);
        when(platform.getConnection()).thenReturn(lock);
        when(lock.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getInt(1)).thenReturn(1);
    }

    @AfterEach
    void clear() {
        PrincipalContextHolder.clear();
    }

    private void failNativeConnection(Exception error) throws Exception {
        when(connections.open(
                        eq("MYSQL"),
                        any(),
                        isNull(),
                        eq("collector"),
                        eq("private-password"),
                        eq(false)))
                .thenThrow(error);
    }

    static Stream<Arguments> denied() {
        return Stream.concat(
                Stream.of(1044, 1142, 1143, 1227, 1370).map(code -> Arguments.of("42000", code)),
                Stream.of(Arguments.of("42501", 0)));
    }

    @ParameterizedTest
    @MethodSource("denied")
    void deniedRolePreviewPointsToManagementConnectionAndKeepsDatabaseDetail(
            String sqlState, int code) throws Exception {
        String detail = "SELECT command denied to user 'collector' for table 'user'";
        failNativeConnection(new SQLException(detail, sqlState, code));
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class, () -> service.preview(2L, 9L, preview));
        assertTrue(error.getMessage().contains("权限配置 → 授权管理连接"));
        assertTrue(error.getMessage().contains("联系 DBA"));
        assertTrue(error.getMessage().contains(detail));
        verify(connections, times(1))
                .open(anyString(), any(), isNull(), anyString(), anyString(), eq(false));
    }

    @ParameterizedTest
    @CsvSource({"42000,1064", "28000,1045", "08001,0"})
    void unrelatedErrorsAreNotMisreportedAsInsufficientPrivileges(String sqlState, int code)
            throws Exception {
        failNativeConnection(new SQLException("original database error", sqlState, code));
        IllegalArgumentException error =
                assertThrows(
                        IllegalArgumentException.class, () -> service.preview(2L, 9L, preview));
        assertTrue(error.getMessage().contains("original database error"));
        assertFalse(error.getMessage().contains("权限不足"));
        assertFalse(error.getMessage().contains("授权管理连接"));
    }

    @Test
    void deniedResourcesKeepActionableHintAndOriginalCause() throws Exception {
        SQLException nativeError = new SQLException("SELECT denied", "42000", 1142);
        failNativeConnection(nativeError);
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> service.resources(2L, null));
        assertTrue(error.getMessage().contains("授权管理连接"));
        assertSame(nativeError, error.getCause());
    }

    @Test
    void wrappedPrivilegeDenialIsRecordedWithHintWithoutChangingFailureState() throws Exception {
        failNativeConnection(
                new IllegalStateException(
                        "native administration failed",
                        new SQLException("SELECT denied", "42000", 1142)));
        when(permissions.createRoleOperation(2L, 9L, "1", "APPLYING")).thenReturn(25L);
        Map<String, Object> record = new LinkedHashMap<>(Map.of("id", 25L, "steps", "[]"));
        when(permissions.finishOperation(any(), any(), any(), eq(25L)))
                .thenAnswer(
                        call -> {
                            record.put("state", call.getArgument(0));
                            record.put("message", call.getArgument(1));
                            return 1;
                        });
        when(permissions.findOperation(25L)).thenAnswer(call -> List.of(record));
        Map<String, Object> result = service.deleteRole(2L, 9L);
        assertEquals("FAILED", result.get("state"));
        assertTrue(result.get("message").toString().contains("授权管理连接"));
        verify(permissions, never()).deleteRole(9L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "APPLYING", "ERROR"})
    void memberFailureAlwaysEndsInErrorWithoutRewritingRoleBindings(String previousStatus)
            throws Exception {
        var users = mock(com.onedata.auth.user.SysUserMapper.class);
        var accounts = mock(com.onedata.portal.mapper.DatasourceSubjectAccountMapper.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "users", users);
        org.springframework.test.util.ReflectionTestUtils.setField(
                service, "accountMapper", accounts);
        var user = new com.onedata.auth.user.SysUser();
        user.setId(7L);
        user.setEnabled(true);
        user.setAccountType("USER");
        when(users.selectById(7L)).thenReturn(user);
        var account = new com.onedata.portal.entity.DatasourceSubjectAccount();
        account.setSourceId(2L);
        account.setSubjectType("USER");
        account.setSubjectId("7");
        account.setUsername("member");
        account.setPassword("private-member");
        when(accounts.selectList(any())).thenReturn(List.of(account));
        when(permissions.findMember(2L, "USER", "7"))
                .thenReturn(List.of(Map.of("id", 31L, "revision", 0, "status", previousStatus)));
        when(connections.open(eq("MYSQL"), any(), isNull(), eq("member"), anyString(), eq(false)))
                .thenThrow(new SQLException("CONNECT denied", "42000", 1044));
        var input = new com.onedata.portal.service.permission.PermissionModels.MemberRequest();
        input.setExpectedRevision(0);
        input.setRoleIds(List.of(9L));
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.putMember(2L, "USER", "7", input));
        assertTrue(error.getMessage().contains("成员数据库账号权限不足"));
        assertFalse(error.getMessage().contains("授权管理连接"));
        verify(permissions).setMemberStatus("ERROR", 31L);
        verify(permissions, never()).clearMemberRoles(any());
        verify(permissions, never()).addMemberRole(any(), any());
    }
}
