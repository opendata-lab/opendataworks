package com.onedata.portal.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.auth.context.*;
import com.onedata.auth.user.*;
import com.onedata.portal.entity.*;
import com.onedata.portal.mapper.*;

import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

import java.util.*;

class ServiceAccountTokenServiceTest {
    private final ServiceAccountTokenMapper tokens = mock(ServiceAccountTokenMapper.class);
    private final PlatformUserOperationMapper operations = mock(PlatformUserOperationMapper.class);
    private final SysUserMapper users = mock(SysUserMapper.class);
    private final ServiceAccountTokenService service =
            new ServiceAccountTokenService(
                    tokens, operations, new ObjectMapper().findAndRegisterModules(), users);

    @BeforeEach
    void before() {
        PrincipalContextHolder.setContext(new CurrentPrincipal("1", "admin", "admin", "local"));
        SysUser u = new SysUser();
        u.setId(7L);
        u.setAccountType("SERVICE");
        u.setEnabled(true);
        when(users.selectById(7L)).thenReturn(u);
    }

    @AfterEach
    void clear() {
        PrincipalContextHolder.clear();
    }

    @Test
    void createReturnsRandomTokenOnceAndPersistsOnlyDigest() {
        when(tokens.insert(any()))
                .thenAnswer(
                        i -> {
                            ((ServiceAccountToken) i.getArgument(0)).setId(12L);
                            return 1;
                        });
        var request = new ServiceAccountTokenService.TokenRequest();
        request.setName("Portal");
        request.setExpiresInDays(90);
        var first = service.create(7L, request);
        var second = service.create(7L, request);
        String raw = (String) first.get("token");
        assertTrue(raw.matches("odw_sa_[A-Za-z0-9_-]{43}"));
        assertNotEquals(raw, second.get("token"));
        var stored = ArgumentCaptor.forClass(ServiceAccountToken.class);
        verify(tokens, times(2)).insert(stored.capture());
        assertEquals(
                ServiceAccountTokenService.hash(raw), stored.getAllValues().get(0).getTokenHash());
        assertFalse(first.containsKey("tokenHash"));
        verify(users, times(2)).lockUserById(7L);
    }

    @Test
    void disabledAndHumanAccountsCannotIssueTokens() {
        SysUser disabled = new SysUser();
        disabled.setAccountType("SERVICE");
        disabled.setEnabled(false);
        when(users.selectById(7L)).thenReturn(disabled);
        assertThrows(
                IllegalArgumentException.class,
                () -> service.create(7L, new ServiceAccountTokenService.TokenRequest()));
        when(users.selectById(8L)).thenReturn(new SysUser());
        assertThrows(IllegalArgumentException.class, () -> service.list(8L));
    }

    @Test
    void expiryAndOwnershipAreEnforced() {
        var request = new ServiceAccountTokenService.TokenRequest();
        request.setName("Portal");
        request.setExpiresInDays(366);
        assertThrows(IllegalArgumentException.class, () -> service.create(7L, request));
        request.setExpiresInDays(0);
        assertThrows(IllegalArgumentException.class, () -> service.create(7L, request));
        assertThrows(IllegalArgumentException.class, () -> service.revoke(7L, 99L));
        ServiceAccountToken other = new ServiceAccountToken();
        other.setUserId(8L);
        when(tokens.selectById(99L)).thenReturn(other);
        assertThrows(IllegalArgumentException.class, () -> service.revoke(7L, 99L));
        verify(tokens, never()).revoke(any(), any());
    }

    @Test
    void malformedOrRevokedTokensNeverAuthenticate() {
        when(tokens.authenticate(anyString())).thenReturn(null);
        for (String raw :
                List.of(
                        "",
                        "old-shared-token",
                        "Bearer x",
                        "odw_sa_short",
                        "odw_sa_" + "a".repeat(43)))
            assertThrows(IllegalArgumentException.class, () -> service.authenticate(raw));
        verify(tokens, never()).touchLastUsedAt(any());
    }

    @Test
    void authenticatedTokenTouchesUsageAndReturnsMappedSubject() {
        String raw = "odw_sa_" + "a".repeat(43);
        when(tokens.authenticate(ServiceAccountTokenService.hash(raw))).thenReturn(7L);
        assertEquals("7", service.authenticate(raw));
        verify(tokens).touchLastUsedAt(ServiceAccountTokenService.hash(raw));
    }
}
