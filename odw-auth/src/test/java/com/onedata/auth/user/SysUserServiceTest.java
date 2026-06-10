package com.onedata.auth.user;

import com.onedata.auth.exception.AuthFailureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysUserServiceTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();
    private static final String PASSWORD = "admin123";
    private static final String PASSWORD_HASH = ENCODER.encode(PASSWORD);

    @Mock
    private SysUserMapper sysUserMapper;

    private SysUserService service;

    @BeforeEach
    void setUp() {
        service = new SysUserService(sysUserMapper, ENCODER);
    }

    private SysUser adminUser() {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("admin");
        user.setPasswordHash(PASSWORD_HASH);
        user.setRole("admin");
        user.setEnabled(true);
        user.setAuthSource("local");
        user.setFailedAttempts(0);
        return user;
    }

    @Test
    void loginSucceedsWithCorrectPassword() {
        when(sysUserMapper.selectOne(any())).thenReturn(adminUser());

        SysUser user = service.login("admin", PASSWORD);

        assertNotNull(user);
        assertEquals("admin", user.getUsername());
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getFailedAttempts());
        assertNotNull(captor.getValue().getLastLoginAt());
    }

    @Test
    void loginFailsWithWrongPasswordAndIncrementsCounter() {
        when(sysUserMapper.selectOne(any())).thenReturn(adminUser());

        assertThrows(AuthFailureException.class, () -> service.login("admin", "wrong-password"));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getFailedAttempts());
    }

    @Test
    void loginLocksAfterMaxFailedAttempts() {
        SysUser user = adminUser();
        user.setFailedAttempts(SysUserService.MAX_FAILED_ATTEMPTS - 1);
        when(sysUserMapper.selectOne(any())).thenReturn(user);

        assertThrows(AuthFailureException.class, () -> service.login("admin", "wrong-password"));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).updateById(captor.capture());
        assertEquals(SysUserService.MAX_FAILED_ATTEMPTS, captor.getValue().getFailedAttempts());
        assertNotNull(captor.getValue().getLockedUntil());
        assertTrue(captor.getValue().getLockedUntil().isAfter(LocalDateTime.now()));
    }

    @Test
    void loginRejectedWhileLocked() {
        SysUser user = adminUser();
        user.setLockedUntil(LocalDateTime.now().plusMinutes(10));
        when(sysUserMapper.selectOne(any())).thenReturn(user);

        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> service.login("admin", PASSWORD));
        assertTrue(e.getMessage().contains("锁定"));
    }

    @Test
    void loginAllowedAfterLockExpires() {
        SysUser user = adminUser();
        user.setLockedUntil(LocalDateTime.now().minusMinutes(1));
        when(sysUserMapper.selectOne(any())).thenReturn(user);

        assertNotNull(service.login("admin", PASSWORD));
    }

    @Test
    void loginRejectedForUnknownUser() {
        when(sysUserMapper.selectOne(any())).thenReturn(null);

        assertThrows(AuthFailureException.class, () -> service.login("ghost", PASSWORD));
    }

    @Test
    void loginRejectedForDisabledUser() {
        SysUser user = adminUser();
        user.setEnabled(false);
        when(sysUserMapper.selectOne(any())).thenReturn(user);

        AuthFailureException e = assertThrows(AuthFailureException.class,
                () -> service.login("admin", PASSWORD));
        assertTrue(e.getMessage().contains("停用"));
    }

    @Test
    void changePasswordVerifiesOldPassword() {
        when(sysUserMapper.selectById(1L)).thenReturn(adminUser());

        assertThrows(AuthFailureException.class,
                () -> service.changePassword(1L, "wrong-old", "new-password-123"));
    }

    @Test
    void changePasswordRejectsShortPassword() {
        when(sysUserMapper.selectById(1L)).thenReturn(adminUser());

        assertThrows(AuthFailureException.class,
                () -> service.changePassword(1L, PASSWORD, "short"));
    }

    @Test
    void changePasswordStoresNewHash() {
        when(sysUserMapper.selectById(1L)).thenReturn(adminUser());

        service.changePassword(1L, PASSWORD, "new-password-123");

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).updateById(captor.capture());
        assertTrue(ENCODER.matches("new-password-123", captor.getValue().getPasswordHash()));
    }
}
