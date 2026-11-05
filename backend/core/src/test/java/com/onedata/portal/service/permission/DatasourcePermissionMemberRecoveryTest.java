package com.onedata.portal.service.permission;

import static com.onedata.portal.service.permission.PermissionInspectionJdbc.result;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.context.*;
import com.onedata.auth.user.*;
import com.onedata.portal.entity.*;
import com.onedata.portal.mapper.*;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;
import com.onedata.portal.service.permission.PermissionModels.MemberRequest;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.sql.*;
import java.util.*;

import javax.sql.DataSource;

class DatasourcePermissionMemberRecoveryTest {
    private final DatasourceAccessPermissionMapper permissions =
            mock(DatasourceAccessPermissionMapper.class);
    private final DorisClusterMapper clusters = mock(DorisClusterMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final DatasourceSubjectAccountMapper accounts =
            mock(DatasourceSubjectAccountMapper.class);
    private final DatasourceConnectionFactory connections = mock(DatasourceConnectionFactory.class);
    private final PermissionCredentialCipher cipher = mock(PermissionCredentialCipher.class);
    private final DataSource platform = mock(DataSource.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    private final DatasourcePermissionService service =
            new DatasourcePermissionService(
                    permissions,
                    platform,
                    transactions,
                    clusters,
                    users,
                    accounts,
                    connections,
                    null,
                    cipher,
                    new ObjectMapper());
    private final Set<Long> bindings = new LinkedHashSet<>(List.of(101L));
    private final Map<String, Object> old =
            new LinkedHashMap<>(Map.of("id", 31L, "revision", 4, "status", "ACTIVE"));
    private final MemberRequest request = new MemberRequest();

    @BeforeEach
    void setup() throws Exception {
        PrincipalContextHolder.setContext(new CurrentPrincipal("1", "admin", "admin", "local"));
        DorisCluster source = new DorisCluster();
        source.setId(2L);
        source.setSourceType("MYSQL");
        source.setUsername("manager");
        source.setPassword("private-manager");
        when(clusters.selectById(2L)).thenReturn(source);
        SysUser user = new SysUser();
        user.setId(7L);
        user.setEnabled(true);
        user.setAccountType("USER");
        when(users.selectById(7L)).thenReturn(user);
        when(permissions.findMember(2L, "USER", "7")).thenReturn(List.of(old));
        when(permissions.findRole(102L, 2L))
                .thenReturn(
                        List.of(Map.of("id", 102L, "status", "ACTIVE", "nativeName", "role_new")));
        when(permissions.listRoleSnapshots(2L))
                .thenReturn(
                        List.of(
                                Map.of("nativeName", "role_old"),
                                Map.of("nativeName", "role_new")));
        when(permissions.listMemberRoleIds(31L))
                .thenAnswer(
                        call ->
                                bindings.stream()
                                        .map(id -> Map.<String, Object>of("role_id", id))
                                        .toList());
        when(permissions.clearMemberRoles(31L))
                .thenAnswer(
                        call -> {
                            bindings.clear();
                            return 1;
                        });
        when(permissions.addMemberRole(eq(31L), any()))
                .thenAnswer(
                        call -> {
                            bindings.add(call.getArgument(1));
                            return 1;
                        });
        when(permissions.createMemberOperation(any(), any(), any(), any(), any(), any()))
                .thenReturn(99L);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        DatasourceSubjectAccount account = new DatasourceSubjectAccount();
        account.setSourceId(2L);
        account.setSubjectType("USER");
        account.setSubjectId("7");
        account.setUsername("member");
        account.setPassword("private-member");
        when(accounts.selectList(any())).thenReturn(List.of(account));
        Connection lock = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(platform.getConnection()).thenReturn(lock);
        when(lock.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getInt(1)).thenReturn(1);
        request.setRoleIds(List.of(102L));
        request.setExpectedRevision(4);
    }

    @AfterEach
    void clear() {
        PrincipalContextHolder.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "APPLYING", "ERROR"})
    void definiteMemberConnectionDenialRestoresPreviousRolesButNeverRestoresApplying(String status)
            throws Exception {
        old.put("status", status);
        when(connections.open(eq("MYSQL"), any(), isNull(), eq("member"), anyString(), eq(false)))
                .thenAnswer(
                        call -> {
                            assertEquals(
                                    Set.of(101L, 102L),
                                    bindings,
                                    "Both role intents must be protected before native I/O");
                            throw new SQLException("CONNECT denied", "42000", 1044);
                        });
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.putMember(2L, "USER", "7", request));
        assertTrue(error.getMessage().contains("成员数据库账号权限不足"));
        assertFalse(error.getMessage().contains("授权管理连接"));
        assertTrue(error.getMessage().contains("FAILED"));
        assertEquals(Set.of(101L), bindings);
        verify(permissions).setMemberStatus(status.equals("ACTIVE") ? "ACTIVE" : "ERROR", 31L);
    }

    @Test
    void partiallyAppliedGrantRetainsBothRoleReferencesAndMarksError() throws Exception {
        PermissionInspectionJdbc member =
                new PermissionInspectionJdbc(
                        sql -> {
                            assertEquals("SELECT CURRENT_USER() AS identity", sql);
                            return result(new String[] {"identity"}, new Object[] {"member@%"});
                        });
        when(connections.open(eq("MYSQL"), any(), isNull(), eq("member"), anyString(), eq(false)))
                .thenReturn(member.connection);
        PermissionInspectionJdbc manager =
                new PermissionInspectionJdbc(
                        sql -> {
                            assertEquals(Set.of(101L, 102L), bindings);
                            if (sql.startsWith("SELECT User"))
                                return result(new String[] {"User"}, new Object[] {"member"});
                            if (sql.contains("mysql.role_edges"))
                                return result(new String[] {"name"}, new Object[] {"role_old"});
                            if (sql.contains("mysql.default_roles"))
                                return result(new String[] {"name", "host"});
                            throw new AssertionError(sql);
                        });
        Statement statement = mock(Statement.class);
        when(manager.connection.createStatement()).thenReturn(statement);
        doAnswer(
                        call -> {
                            String sql = call.getArgument(0);
                            if (sql.startsWith("SET DEFAULT ROLE"))
                                throw new SQLException("default role denied", "42000", 1227);
                            return false;
                        })
                .when(statement)
                .execute(anyString());
        when(connections.open(eq("MYSQL"), any(), isNull(), eq("manager"), anyString(), eq(false)))
                .thenReturn(manager.connection);
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.putMember(2L, "USER", "7", request));
        assertTrue(error.getMessage().contains("PARTIAL"), error.getMessage());
        assertTrue(error.getMessage().contains("授权管理连接"));
        assertEquals(
                Set.of(101L, 102L),
                bindings,
                "Native grant may exist, so deleting either referenced role must remain blocked");
        verify(statement).execute("GRANT 'role_new'@'%' TO 'member'@'%'");
        verify(permissions).setMemberStatus("ERROR", 31L);
        verify(permissions)
                .finishOperation(eq("PARTIAL"), anyString(), contains("SUCCESS"), eq(99L));
    }

    @Test
    void uncertainMemberConnectionKeepsProtectedRolesAndMarksError() throws Exception {
        when(connections.open(anyString(), any(), isNull(), eq("member"), anyString(), eq(false)))
                .thenThrow(new SQLTimeoutException("connection timed out"));
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.putMember(2L, "USER", "7", request));
        assertTrue(error.getMessage().contains("UNKNOWN"));
        assertEquals(Set.of(101L, 102L), bindings);
        verify(permissions).setMemberStatus("ERROR", 31L);
    }

    @Test
    void creatingLoginForExistingUnmappedMemberAlsoProtectsPreviousRoles() throws Exception {
        when(accounts.selectList(any())).thenReturn(List.of());
        when(cipher.encrypt(anyString())).thenReturn("encrypted");
        when(connections.open(eq("MYSQL"), any(), isNull(), eq("manager"), anyString(), eq(false)))
                .thenAnswer(
                        call -> {
                            assertEquals(Set.of(101L, 102L), bindings);
                            throw new SQLException("CREATE USER denied", "42000", 1227);
                        });
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> service.putMember(2L, "USER", "7", request));
        assertTrue(error.getMessage().contains("FAILED"));
        assertTrue(error.getMessage().contains("授权管理连接"));
        assertEquals(Set.of(101L, 102L), bindings);
        verify(permissions).setMemberStatus("ERROR", 31L);
        verify(accounts).insert(any(DatasourceSubjectAccount.class));
    }
}
