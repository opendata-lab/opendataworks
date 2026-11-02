package com.onedata.portal.service;

import com.onedata.auth.context.*;
import com.onedata.auth.user.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class ServiceAccountTokenServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final SysUserMapper users=mock(SysUserMapper.class);
    private final ServiceAccountTokenService service=new ServiceAccountTokenService(jdbc,users);
    @BeforeEach void before() {
        UserContextHolder.setContext(new UserContext("1","admin","admin","local"));
        SysUser u=new SysUser();u.setId(7L);u.setAccountType("SERVICE");u.setEnabled(true);when(users.selectById(7L)).thenReturn(u);
    }
    @AfterEach void clear(){UserContextHolder.clear();}
    @Test void createReturnsRandomTokenOnceButStoresOnlyDigest() {
        ServiceAccountTokenService.TokenRequest r=new ServiceAccountTokenService.TokenRequest();r.setName("Portal");r.setExpiresInDays(90);
        when(jdbc.queryForMap(anyString(),anyString())).thenReturn(Collections.singletonMap("id",12L));
        Map<String,Object> first=service.create(7L,r),second=service.create(7L,r);
        String token=(String)first.get("token");assertTrue(token.matches("odw_sa_[A-Za-z0-9_-]{43}"));assertNotEquals(token,second.get("token"));
        ArgumentCaptor<String> digest=ArgumentCaptor.forClass(String.class);
        verify(jdbc,times(2)).update(startsWith("INSERT INTO service_account_token"),eq(7L),eq("Portal"),digest.capture(),anyString(),eq(90),eq("1"));
        assertEquals(ServiceAccountTokenService.hash(token),digest.getAllValues().get(0));assertNotEquals(token,digest.getAllValues().get(0));
    }
    @Test void disabledAccountCannotIssueAndHumanCannotManageTokens() {
        SysUser disabled=new SysUser();disabled.setAccountType("SERVICE");disabled.setEnabled(false);when(users.selectById(7L)).thenReturn(disabled);
        assertThrows(IllegalArgumentException.class,()->service.create(7L,new ServiceAccountTokenService.TokenRequest()));
        SysUser human=new SysUser();human.setAccountType("USER");when(users.selectById(8L)).thenReturn(human);
        assertThrows(IllegalArgumentException.class,()->service.list(8L));
    }
    @Test void tokenLifetimeBoundedAndRevocationChecksOwnership() {
        ServiceAccountTokenService.TokenRequest r=new ServiceAccountTokenService.TokenRequest();r.setName("Portal");r.setExpiresInDays(366);
        assertThrows(IllegalArgumentException.class,()->service.create(7L,r));r.setExpiresInDays(0);assertThrows(IllegalArgumentException.class,()->service.create(7L,r));
        when(jdbc.queryForList(anyString(),eq(99L),eq(7L))).thenReturn(Collections.emptyList());
        assertThrows(IllegalArgumentException.class,()->service.revoke(7L,99L));
    }
    @Test void malformedExpiredAndRevokedTokensNeverAuthenticate() {
        for(String value:Arrays.asList("", "old-shared-token", "Bearer x", "odw_sa_short"))assertThrows(IllegalArgumentException.class,()->service.authenticate(value));
        assertThrows(IllegalArgumentException.class,()->service.authenticate("odw_sa_"+String.join("",Collections.nCopies(43,"a"))));
        verify(jdbc,never()).update(startsWith("UPDATE service_account_token t"),anyString());
    }
    @Test void tokenTimesUseUtcForStorageComparisonAndExplicitWireTimezone() {
        service.list(7L);
        verify(jdbc).queryForList(contains("DATE_FORMAT(expires_at,'%Y-%m-%dT%H:%i:%sZ')"),eq(7L));
        String raw="odw_sa_"+String.join("",Collections.nCopies(43,"a"));
        when(jdbc.queryForList(anyString(),eq(Long.class),anyString())).thenReturn(Collections.singletonList(7L));
        assertEquals("7",service.authenticate(raw));
        verify(jdbc).queryForList(contains("t.expires_at>UTC_TIMESTAMP()"),eq(Long.class),eq(ServiceAccountTokenService.hash(raw)));
        verify(jdbc).update(contains("SET t.last_used_at=UTC_TIMESTAMP()"),eq(ServiceAccountTokenService.hash(raw)));
    }
}
