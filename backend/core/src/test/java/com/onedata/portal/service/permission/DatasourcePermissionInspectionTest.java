package com.onedata.portal.service.permission;

import static com.onedata.portal.service.permission.PermissionInspectionJdbc.result;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.portal.entity.DatasourceSubjectAccount;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.*;
import java.util.*;

class DatasourcePermissionInspectionTest {
    private final com.onedata.portal.mapper.DatasourceAccessPermissionMapper permissions =
            mock(com.onedata.portal.mapper.DatasourceAccessPermissionMapper.class);
    private final DorisClusterMapper clusters = mock(DorisClusterMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final com.onedata.portal.mapper.DatasourceSubjectAccountMapper accounts =
            mock(com.onedata.portal.mapper.DatasourceSubjectAccountMapper.class);
    private final DatasourceConnectionFactory connections = mock(DatasourceConnectionFactory.class);
    private final PermissionCredentialCipher cipher = mock(PermissionCredentialCipher.class);
    private final DatasourcePermissionService service =
            new DatasourcePermissionService(
                    permissions,
                    null,
                    null,
                    clusters,
                    users,
                    accounts,
                    connections,
                    null,
                    cipher,
                    new ObjectMapper());
    private final DatasourceSubjectAccount account = new DatasourceSubjectAccount();

    @BeforeEach
    void setup() {
        DorisCluster source = new DorisCluster();
        source.setId(1L);
        source.setSourceType("MYSQL");
        source.setUsername("collector_without_system_access");
        when(clusters.selectById(1L)).thenReturn(source);
        account.setSubjectType("USER");
        account.setSubjectId("7");
        account.setUsername("reader");
        account.setPassword("PRIVATE_MEMBER_PASSWORD");
        when(accounts.selectList(any())).thenReturn(Collections.singletonList(account));
        SysUser user = new SysUser();
        user.setAccountType("USER");
        when(users.selectById(7L)).thenReturn(user);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USER", "SERVICE"})
    void readsOnlyVerifiedMemberConnectionWithoutOpeningOrReadingManagement(String type)
            throws Exception {
        account.setSubjectType(type);
        SysUser user = new SysUser();
        user.setAccountType(type);
        when(users.selectById(7L)).thenReturn(user);
        PermissionInspectionJdbc nativeJdbc = nativeConnection();
        when(connections.open(
                        eq("MYSQL"),
                        any(),
                        isNull(),
                        eq("reader"),
                        eq("PRIVATE_MEMBER_PASSWORD"),
                        eq("SERVICE".equals(type))))
                .thenReturn(nativeJdbc.connection);
        Map<String, Object> response = service.inspect(1L, type, "7");
        assertEquals("reader", response.get("username"));
        assertEquals("'reader'@'specific-host'", response.get("nativeIdentity"));
        assertNull(response.get("connectionError"));
        assertEquals("ACTIVE", response.get("memberStatus"));
        assertNotNull(response.get("expectedRoles"));
        assertTrue(response.get("grants").toString().contains("GRANT SELECT"));
        verify(connections, times(1))
                .open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean());
        verify(nativeJdbc.connection).close();
        verifyNoInteractions(cipher);
    }

    @Test
    void connectionFailureIdentifiesStageAndNativeCodeWithoutLeakingCredentials() throws Exception {
        when(connections.open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new SQLException("PRIVATE_DATABASE_MESSAGE", "08001", 2003));
        Map<String, Object> response = service.inspect(1L, "USER", "7");
        String error = (String) response.get("connectionError");
        assertNotNull(error);
        assertTrue(error.contains("连接成员数据库账号失败"));
        assertTrue(error.contains("08001"));
        assertTrue(error.contains("2003"));
        assertFalse(error.contains("PRIVATE"));
        assertEquals(Collections.emptyList(), response.get("grants"));
        assertEquals("reader", response.get("username"));
        assertNull(response.get("nativeIdentity"));
        assertNotNull(response.get("expectedRoles"));
        verifyNoInteractions(cipher);
    }

    @Test
    void inspectionFailureClosesConnectionAndDoesNotReturnEmptySuccessOrFallback()
            throws Exception {
        PermissionInspectionJdbc nativeJdbc = nativeConnection();
        when(connections.open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean()))
                .thenReturn(nativeJdbc.connection);
        when(nativeJdbc.connection.prepareStatement(contains("ENABLED_ROLES")))
                .thenThrow(new SQLException("PRIVATE", "42000", 1142));
        Map<String, Object> response = service.inspect(1L, "USER", "7");
        String error = (String) response.get("connectionError");
        assertNotNull(error);
        assertTrue(error.contains("读取账号原生权限失败"));
        assertTrue(error.contains("1142"));
        assertEquals(Collections.emptyList(), response.get("grants"));
        verify(nativeJdbc.connection).close();
        verify(connections, times(1))
                .open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean());
        verifyNoInteractions(cipher);
    }

    @Test
    void differentActualIdentityIsRejectedBeforeGrantInspection() throws Exception {
        PermissionInspectionJdbc nativeJdbc =
                new PermissionInspectionJdbc(
                        sql -> {
                            assertEquals("SELECT CURRENT_USER() AS identity", sql);
                            return result(new String[] {"identity"}, new Object[] {"other@%"});
                        });
        when(connections.open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean()))
                .thenReturn(nativeJdbc.connection);
        Map<String, Object> response = service.inspect(1L, "USER", "7");
        String error = (String) response.get("connectionError");
        assertNotNull(error);
        assertTrue(error.contains("数据库实际身份与填写账号不一致"));
        assertEquals(Collections.emptyList(), response.get("grants"));
        assertEquals(1, nativeJdbc.queries.size());
        verify(nativeJdbc.connection).close();
    }

    @Test
    void missingMappingDoesNotOpenAnyConnection() {
        when(accounts.selectList(any())).thenReturn(Collections.emptyList());
        Map<String, Object> response = service.inspect(1L, "USER", "7");
        assertEquals("尚未绑定数据库账号", response.get("connectionError"));
        assertEquals(Collections.emptyList(), response.get("grants"));
        assertEquals("NEEDS_ACCOUNT", response.get("memberStatus"));
        assertNull(response.get("username"));
        verifyNoInteractions(connections, cipher);
    }

    @Test
    void returnsExpectedRolesWithDraftStatusAndLastFailedOperation() throws Exception {
        // Setup member record with id 50
        Map<String, Object> memberRow = new LinkedHashMap<>();
        memberRow.put("id", 50L);
        memberRow.put("source_id", 1L);
        memberRow.put("subject_type", "USER");
        memberRow.put("subject_id", "7");
        memberRow.put("status", "ERROR");
        when(permissions.findMember(1L, "USER", "7"))
                .thenReturn(Collections.singletonList(memberRow));

        // Member has role 101
        Map<String, Object> roleIdRow = new LinkedHashMap<>();
        roleIdRow.put("role_id", 101L);
        when(permissions.listMemberRoleIds(50L)).thenReturn(Collections.singletonList(roleIdRow));

        // Role 101 is DRAFT
        Map<String, Object> roleRow = new LinkedHashMap<>();
        roleRow.put("id", 101L);
        roleRow.put("source_id", 1L);
        roleRow.put("name", "财务分析");
        roleRow.put("status", "DRAFT");
        roleRow.put("template", "READONLY");
        roleRow.put("native_name", "odw_fin_ro");
        when(permissions.findRole(101L, 1L)).thenReturn(Collections.singletonList(roleRow));

        // Grants for role 101
        Map<String, Object> grantRow = new LinkedHashMap<>();
        grantRow.put("id", 201L);
        grantRow.put("role_id", 101L);
        grantRow.put("resource_type", "TABLE");
        grantRow.put("database_name", "fin");
        grantRow.put("table_name", "ledger");
        grantRow.put("actions", "[\"QUERY\"]");
        when(permissions.listRoleGrants(101L)).thenReturn(Collections.singletonList(grantRow));
        when(permissions.countRoleMembers(101L)).thenReturn(1L);

        // Failed operation for this subject
        Map<String, Object> opRow = new LinkedHashMap<>();
        opRow.put("id", 108L);
        opRow.put("source_id", 1L);
        opRow.put("subject_type", "USER");
        opRow.put("subject_id", "7");
        opRow.put("state", "FAILED");
        opRow.put("message", "GRANT Role 失败 (SQLState=42000)");
        opRow.put("steps", "[{\"sql\":\"GRANT odv_fin_ro TO reader\",\"state\":\"FAILED\"}]");
        // A newer partial failure on the member's assigned role (role operations carry no subject)
        Map<String, Object> roleOpRow = new LinkedHashMap<>();
        roleOpRow.put("id", 110L);
        roleOpRow.put("source_id", 1L);
        roleOpRow.put("role_id", 101L);
        roleOpRow.put("state", "PARTIAL");
        roleOpRow.put("message", "部分资源授权失败");
        roleOpRow.put("steps", "[]");
        // Another role's newer failure is unrelated to this member
        Map<String, Object> otherRoleOp = new LinkedHashMap<>(roleOpRow);
        otherRoleOp.put("id", 111L);
        otherRoleOp.put("role_id", 999L);
        otherRoleOp.put("state", "FAILED");
        when(permissions.listOperations(1L)).thenReturn(List.of(otherRoleOp, roleOpRow, opRow));

        PermissionInspectionJdbc nativeJdbc = nativeConnection();
        when(connections.open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean()))
                .thenReturn(nativeJdbc.connection);

        Map<String, Object> response = service.inspect(1L, "USER", "7");
        assertEquals("ERROR", response.get("memberStatus"));
        assertNull(response.get("connectionError"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> expectedRoles =
                (List<Map<String, Object>>) response.get("expectedRoles");
        assertEquals(1, expectedRoles.size());
        assertEquals("财务分析", expectedRoles.get(0).get("name"));
        assertEquals("DRAFT", expectedRoles.get(0).get("status"));

        @SuppressWarnings("unchecked")
        Map<String, Object> lastFailedOp =
                (Map<String, Object>) response.get("lastFailedOperation");
        assertNotNull(lastFailedOp);
        assertEquals(110L, lastFailedOp.get("operationId"));
        assertEquals("PARTIAL", lastFailedOp.get("state"));
    }

    private PermissionInspectionJdbc nativeConnection() throws Exception {
        return new PermissionInspectionJdbc(
                sql -> {
                    if (sql.equals("SELECT CURRENT_USER() AS identity"))
                        return result(
                                new String[] {"identity"}, new Object[] {"reader@specific-host"});
                    if (sql.contains("information_schema.ENABLED_ROLES"))
                        return result(new String[] {"name", "host"});
                    assertEquals("SHOW GRANTS FOR CURRENT_USER", sql);
                    return result(
                            new String[] {"Grants"},
                            new Object[] {
                                "GRANT SELECT ON `sales`.`orders` TO 'reader'@'specific-host'"
                            });
                });
    }
}
