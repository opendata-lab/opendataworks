package com.onedata.portal.service.permission;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.portal.entity.DatasourceSubjectAccount;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.DatasourceSubjectAccountService;
import com.onedata.portal.service.datasource.core.DatasourceConnectionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.sql.*;
import java.util.*;
import static com.onedata.portal.service.permission.PermissionInspectionJdbc.result;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DatasourcePermissionInspectionTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DorisClusterMapper clusters = mock(DorisClusterMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final DatasourceSubjectAccountService accounts = mock(DatasourceSubjectAccountService.class);
    private final DatasourceConnectionFactory connections = mock(DatasourceConnectionFactory.class);
    private final PermissionCredentialCipher cipher = mock(PermissionCredentialCipher.class);
    private final DatasourcePermissionService service = new DatasourcePermissionService(jdbc, null, null,
            clusters, users, accounts, connections, null, cipher, new ObjectMapper());
    private final DatasourceSubjectAccount account = new DatasourceSubjectAccount();

    @BeforeEach void setup() {
        DorisCluster source = new DorisCluster();
        source.setId(1L); source.setSourceType("MYSQL"); source.setUsername("collector_without_system_access");
        when(clusters.selectById(1L)).thenReturn(source);
        account.setSubjectType("USER"); account.setSubjectId("7");
        account.setUsername("reader"); account.setPassword("PRIVATE_MEMBER_PASSWORD");
        when(accounts.list(1L)).thenReturn(Collections.singletonList(account));
        SysUser user = new SysUser(); user.setAccountType("USER");
        when(users.selectById(7L)).thenReturn(user);
    }

    @ParameterizedTest @ValueSource(strings = {"USER", "SERVICE"})
    void readsOnlyVerifiedMemberConnectionWithoutOpeningOrReadingManagement(String type) throws Exception {
        account.setSubjectType(type);
        SysUser user = new SysUser(); user.setAccountType(type);
        when(users.selectById(7L)).thenReturn(user);
        PermissionInspectionJdbc nativeJdbc = nativeConnection();
        when(connections.open(eq("MYSQL"), any(), isNull(), eq("reader"), eq("PRIVATE_MEMBER_PASSWORD"), eq("SERVICE".equals(type))))
                .thenReturn(nativeJdbc.connection);
        Map<String,Object> response = service.inspect(1L, type, "7");
        assertEquals("reader", response.get("username"));
        assertEquals("'reader'@'specific-host'", response.get("nativeIdentity"));
        assertTrue(response.get("grants").toString().contains("GRANT SELECT"));
        verify(connections, times(1)).open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean());
        verify(nativeJdbc.connection).close();
        verifyNoInteractions(jdbc, cipher);
    }

    @Test void connectionFailureIdentifiesStageAndNativeCodeWithoutLeakingCredentials() throws Exception {
        when(connections.open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean()))
                .thenThrow(new SQLException("PRIVATE_DATABASE_MESSAGE", "08001", 2003));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.inspect(1L, "USER", "7"));
        assertTrue(error.getMessage().contains("连接成员数据库账号失败"));
        assertTrue(error.getMessage().contains("08001"));
        assertTrue(error.getMessage().contains("2003"));
        assertFalse(error.getMessage().contains("PRIVATE"));
        verifyNoInteractions(jdbc, cipher);
    }

    @Test void inspectionFailureClosesConnectionAndDoesNotReturnEmptySuccessOrFallback() throws Exception {
        PermissionInspectionJdbc nativeJdbc = nativeConnection();
        when(connections.open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean())).thenReturn(nativeJdbc.connection);
        when(nativeJdbc.connection.prepareStatement(contains("ENABLED_ROLES"))).thenThrow(new SQLException("PRIVATE", "42000", 1142));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> service.inspect(1L, "USER", "7"));
        assertTrue(error.getMessage().contains("读取账号原生权限失败"));
        assertTrue(error.getMessage().contains("1142"));
        verify(nativeJdbc.connection).close();
        verify(connections, times(1)).open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean());
        verifyNoInteractions(jdbc, cipher);
    }

    @Test void differentActualIdentityIsRejectedBeforeGrantInspection() throws Exception {
        PermissionInspectionJdbc nativeJdbc = new PermissionInspectionJdbc(sql -> {
            assertEquals("SELECT CURRENT_USER() AS identity", sql);
            return result(new String[]{"identity"}, new Object[]{"other@%"});
        });
        when(connections.open(anyString(), any(), isNull(), anyString(), anyString(), anyBoolean())).thenReturn(nativeJdbc.connection);
        assertThrows(IllegalArgumentException.class, () -> service.inspect(1L, "USER", "7"));
        assertEquals(1, nativeJdbc.queries.size());
        verify(nativeJdbc.connection).close();
    }

    @Test void missingMappingDoesNotOpenAnyConnection() {
        when(accounts.list(1L)).thenReturn(Collections.emptyList());
        assertThrows(IllegalArgumentException.class, () -> service.inspect(1L, "USER", "7"));
        verifyNoInteractions(connections, jdbc, cipher);
    }

    private PermissionInspectionJdbc nativeConnection() throws Exception {
        return new PermissionInspectionJdbc(sql -> {
            if (sql.equals("SELECT CURRENT_USER() AS identity")) return result(new String[]{"identity"}, new Object[]{"reader@specific-host"});
            if (sql.contains("information_schema.ENABLED_ROLES")) return result(new String[]{"name", "host"});
            assertEquals("SHOW GRANTS FOR CURRENT_USER", sql);
            return result(new String[]{"Grants"}, new Object[]{"GRANT SELECT ON `sales`.`orders` TO 'reader'@'specific-host'"});
        });
    }
}
