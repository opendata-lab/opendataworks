package com.onedata.portal.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.*;
import com.onedata.auth.jwt.JwtService;
import com.onedata.auth.user.SysUserService;
import com.onedata.portal.service.ServiceAccountTokenService;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;

import java.util.*;

class HttpAuthenticationFilterTest {
    private final HttpSecurityProperties policy = new HttpSecurityProperties();
    private final JwtService jwt = mock(JwtService.class);
    private final SysUserService users = mock(SysUserService.class);
    private final ServiceAccountTokenService tokens = mock(ServiceAccountTokenService.class);
    private final CurrentPrincipal human = new CurrentPrincipal("1", "admin", "admin", "local");
    private final HttpAuthenticationFilter filter =
            new HttpAuthenticationFilter(new OdwAuthProperties(), policy, jwt, users, tokens);

    private MockHttpServletRequest request(String path) {
        var req = new MockHttpServletRequest("POST", "/api" + path);
        req.setContextPath("/api");
        req.setServletPath(path);
        req.setRemoteAddr("127.0.0.1");
        return req;
    }

    @AfterEach
    void clear() {
        PrincipalContextHolder.clear();
    }

    @Test
    void mePasswordAndPortalRequireUserSessions() throws Exception {
        for (String path : List.of("/auth/me", "/auth/password", "/v1/tables")) {
            var res = new MockHttpServletResponse();
            filter.doFilter(request(path), res, new MockFilterChain());
            assertEquals(401, res.getStatus());
        }
    }

    @Test
    void explicitInvalidBearerDoesNotFallBackToValidCookie() throws Exception {
        var req = request("/v1/tables");
        req.setCookies(new Cookie("odw_session", "valid-cookie"));
        req.addHeader("Authorization", "Bearer bad");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(401, res.getStatus());
        verify(jwt).verify("bad");
        verify(jwt, never()).verify("valid-cookie");
    }

    @Test
    void serviceTokenCannotEnterPortalAndUserSessionCannotEnterMcp() throws Exception {
        var req = request("/v1/tables");
        req.addHeader("Authorization", "Bearer odw_sa_test");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(401, res.getStatus());
        verifyNoInteractions(tokens);
        req = request("/mcp");
        req.addHeader("Authorization", "Bearer human-jwt");
        res = new MockHttpServletResponse();
        when(tokens.authenticate("human-jwt")).thenThrow(new IllegalArgumentException());
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(401, res.getStatus());
    }

    @Test
    void mcpExposesAuthenticatedPrincipalAndCleansThread() throws Exception {
        when(tokens.authenticate("machine")).thenReturn("7");
        var req = request("/mcp/");
        req.addHeader("Authorization", "Bearer machine");
        req.addHeader("X-Agent-User-Id", "local:admin");
        var res = new MockHttpServletResponse();
        filter.doFilter(
                req,
                res,
                (r, s) -> {
                    assertEquals(
                            CurrentPrincipal.AccountType.SERVICE,
                            PrincipalContextHolder.require().getAccountType());
                    assertFalse(PrincipalContextHolder.require().isAdmin());
                    assertEquals(
                            "local:admin", PrincipalContextHolder.require().getDelegatedUserKey());
                    assertSame(
                            PrincipalContextHolder.require(),
                            req.getAttribute(HttpAuthenticationFilter.PRINCIPAL_ATTRIBUTE));
                });
        assertNull(PrincipalContextHolder.getContext());
    }

    @Test
    void originAndCorsDoNotGrantBrowserCredentialsOnServiceRoute() throws Exception {
        var req = request("/mcp");
        req.addHeader("Origin", "https://bad.example");
        var res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertEquals(403, res.getStatus());
        policy.setAllowedOrigins(List.of("https://portal.example"));
        req = request("/mcp");
        req.addHeader("Origin", "https://portal.example");
        req.addHeader("Authorization", "Bearer machine");
        when(tokens.authenticate("machine")).thenReturn("7");
        res = new MockHttpServletResponse();
        filter.doFilter(req, res, new MockFilterChain());
        assertNull(res.getHeader("Access-Control-Allow-Credentials"));
    }

    @Test
    void forwardingTrustStripsFromRightAndRejectsMalformedChains() {
        var req = request("/mcp");
        req.setRemoteAddr("203.0.113.10");
        req.addHeader("X-Forwarded-For", "127.0.0.1");
        assertFalse(filter.privateClient(req));
        policy.setTrustedProxies(List.of("127.0.0.1"));
        req = request("/mcp");
        req.addHeader("X-Forwarded-For", "10.0.0.1, 203.0.113.10");
        assertFalse(filter.privateClient(req));
        req = request("/mcp");
        req.addHeader("X-Forwarded-For", "10.0.0.1, malformed");
        assertFalse(filter.privateClient(req));
        req = request("/mcp");
        req.addHeader("X-Forwarded-For", "10.0.0.2");
        assertTrue(filter.privateClient(req));
    }

    @Test
    void containerParsedMcpPathsAlwaysRequireServiceAuthentication() throws Exception {
        when(tokens.authenticate(null)).thenThrow(new IllegalArgumentException());
        for (String uri : List.of("/mcp;v=1", "/mcp%3bv=1", "/m%63p", "//mcp", "/mcp/../mcp")) {
            var req = request(uri);
            // Tomcat supplies a decoded servletPath with path parameters removed.
            req.setServletPath("/mcp");
            req.setCookies(new Cookie("odw_session", "valid-cookie"));
            var res = new MockHttpServletResponse();
            filter.doFilter(req, res, (r, s) -> fail("User session reached MCP"));
            assertEquals(401, res.getStatus(), uri);
        }
        verifyNoInteractions(jwt, users);
        verify(tokens, times(5)).authenticate(null);
    }

    @Test
    void mcpRouteFamilyUsesOneServicePolicyWithoutMatchingUnrelatedPaths() {
        var routes = new AuthenticationRoutePolicy();
        for (String path : List.of("/mcp", "/mcp/", "/mcp;v=1", "/mcp/nested.js"))
            assertEquals(AuthenticationRoutePolicy.Route.SERVICE, routes.classify(path));
        assertEquals(AuthenticationRoutePolicy.Route.USER, routes.classify("/mcp-other"));
    }
}
