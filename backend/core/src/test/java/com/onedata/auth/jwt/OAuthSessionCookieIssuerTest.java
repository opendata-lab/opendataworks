package com.onedata.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.user.SysUser;

import org.junit.jupiter.api.Test;

import java.time.Duration;

class OAuthSessionCookieIssuerTest {

    private static final String SECRET = "unit-test-secret-0123456789-0123456789";

    @Test
    void issuesAndClearsSessionCookieMatchingPasswordLoginContract() {
        OdwAuthProperties properties = new OdwAuthProperties();
        properties.getJwt().setSecret(SECRET);
        properties.getJwt().setTtl(Duration.ofHours(2));
        properties.getSession().setSecure(true);

        Hs256JwtService jwtService = new Hs256JwtService(properties);
        SessionCookieIssuer issuer = new SessionCookieIssuer(jwtService, properties);

        SysUser user = new SysUser();
        user.setId(42L);
        user.setUsername("sso:u-100");
        user.setRole("user");
        user.setAuthSource("sso");
        user.setSessionVersion(3);

        String setCookie = issuer.issueCookie(user);
        assertTrue(setCookie.startsWith("odw_session="));
        assertTrue(setCookie.contains("; Path=/; HttpOnly; Max-Age=7200; SameSite=Lax; Secure"));

        String token = setCookie.substring("odw_session=".length(), setCookie.indexOf(';'));
        CurrentPrincipal verified = jwtService.verify(token);
        assertNotNull(verified);
        assertEquals("42", verified.getUserId());
        assertEquals("sso:u-100", verified.getUsername());
        assertEquals("user", verified.getRole());
        assertEquals("sso", verified.getAuthSource());
        assertEquals(3, verified.getSessionVersion());

        String cleared = issuer.clearCookie();
        assertEquals("odw_session=; Path=/; HttpOnly; Max-Age=0; SameSite=Lax; Secure", cleared);
    }

    @Test
    void omitsSecureFlagWhenDisabled() {
        OdwAuthProperties properties = new OdwAuthProperties();
        properties.getJwt().setSecret(SECRET);
        properties.getSession().setSecure(false);
        SessionCookieIssuer issuer =
                new SessionCookieIssuer(new Hs256JwtService(properties), properties);

        String cookie = issuer.issueCookie(new CurrentPrincipal("1", "admin", "admin", "local", 0));
        assertFalse(cookie.contains("; Secure"));
    }
}
