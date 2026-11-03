package com.onedata.portal.service;
import com.onedata.auth.context.*;
import com.onedata.auth.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class PlatformUserManagementServiceTest {
    private final SysUserMapper users=mock(SysUserMapper.class);
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final BCryptPasswordEncoder encoder=new BCryptPasswordEncoder();
    private final PlatformUserManagementService service=new PlatformUserManagementService(users,encoder,jdbc);
    @BeforeEach void login(){UserContextHolder.setContext(new UserContext("1","admin","admin","local"));}
    @AfterEach void clear(){UserContextHolder.clear();}
    private SysUser user(long id,String role) {SysUser u=new SysUser();u.setId(id);u.setUsername("alice");u.setRole(role);u.setAuthSource("local");u.setEnabled(true);u.setSessionVersion(2);return u;}
    @Test void createOnlyPersistsHashAndNeverReturnsIt() throws Exception {
        PlatformUserManagementService.UserRequest input=new PlatformUserManagementService.UserRequest();input.setUsername("alice");input.setPassword("test-password-123");
        when(jdbc.queryForObject(anyString(),eq(Long.class),eq("alice"))).thenReturn(0L);
        when(users.insert(any())).thenAnswer(call->{SysUser user=call.getArgument(0);user.setId(9L);return 1;});
        when(users.selectById(9L)).thenAnswer(call->{SysUser u=user(9,"user");u.setPasswordHash("must-not-be-returned");return u;});
        SysUser result=service.create(input);
        ArgumentCaptor<SysUser> captor=ArgumentCaptor.forClass(SysUser.class);verify(users).insert(captor.capture());
        assertTrue(encoder.matches(input.getPassword(),captor.getValue().getPasswordHash()));
        assertFalse(new ObjectMapper().writeValueAsString(result).contains("must-not-be-returned"));
        assertEquals("user",captor.getValue().getRole());
    }
    @Test void cannotDisableOrDowngradeCurrentAdministrator() {
        when(users.selectById(1L)).thenReturn(user(1,"admin"));
        PlatformUserManagementService.UserRequest input=new PlatformUserManagementService.UserRequest();input.setEnabled(false);
        assertThrows(IllegalArgumentException.class,()->service.update(1L,input));
        assertThrows(IllegalArgumentException.class,()->service.delete(1L));
        verify(users,never()).updateById(any());
    }
    @Test void cannotRemoveLastEnabledAdministrator() {
        when(users.selectById(2L)).thenReturn(user(2,"admin"));
        when(jdbc.queryForObject(anyString(),eq(Long.class))).thenReturn(1L);
        assertThrows(IllegalArgumentException.class,()->service.delete(2L));
    }
    @Test void oauthPasswordMustBeChangedAtProvider() {
        SysUser oauth=user(3,"user");oauth.setAuthSource("oidc");when(users.selectById(3L)).thenReturn(oauth);
        assertThrows(IllegalArgumentException.class,()->service.resetPassword(3L,"new-password-123"));
    }
    @Test void malformedNamesAndPlatformRolesCannotBeCreated() {
        PlatformUserManagementService.UserRequest input=new PlatformUserManagementService.UserRequest();input.setUsername("../../admin");input.setPassword("test-password-123");
        assertThrows(IllegalArgumentException.class,()->service.create(input));input.setUsername("alice");input.setRole("root");
        assertThrows(IllegalArgumentException.class,()->service.create(input));
    }

    @Test void serviceAccountsHaveNoPasswordOrAdminAndCannotChangeType() {
        PlatformUserManagementService.UserRequest input=new PlatformUserManagementService.UserRequest();input.setAccountType("SERVICE");input.setNickname("数据服务");
        when(jdbc.queryForObject(anyString(),eq(Long.class),anyString())).thenReturn(0L);
        when(users.insert(any())).thenAnswer(call->{SysUser u=call.getArgument(0);u.setId(7L);return 1;});
        service.create(input);
        ArgumentCaptor<SysUser> account=ArgumentCaptor.forClass(SysUser.class);verify(users).insert(account.capture());
        SysUser u=account.getValue();assertEquals("SERVICE",u.getAccountType());assertTrue(u.getUsername().startsWith("svc_"));assertNull(u.getPasswordHash());assertEquals("user",u.getRole());
        when(users.selectById(7L)).thenReturn(u);input.setAccountType("USER");assertThrows(IllegalArgumentException.class,()->service.update(7L,input));
        assertThrows(IllegalArgumentException.class,()->service.resetPassword(7L,"secret-password"));
        input.setAccountType("SERVICE");input.setRole("admin");assertThrows(IllegalArgumentException.class,()->service.create(input));
        input.setRole("user");input.setPassword("forbidden-password");assertThrows(IllegalArgumentException.class,()->service.create(input));
    }
}
