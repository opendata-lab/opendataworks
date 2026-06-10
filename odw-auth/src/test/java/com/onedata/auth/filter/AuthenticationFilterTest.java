package com.onedata.auth.filter;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.UserContext;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.auth.jwt.Hs256JwtService;
import com.onedata.auth.jwt.JwtService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationFilterTest {

    private OdwAuthProperties properties;
    private JwtService jwtService;
    private AuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        properties = new OdwAuthProperties();
        properties.getJwt().setSecret("unit-test-secret-0123456789-0123456789");
        properties.getJwt().setTtl(Duration.ofHours(1));
        jwtService = new Hs256JwtService(properties);
        filter = new AuthenticationFilter(properties, jwtService);
    }

    @AfterEach
    void tearDown() {
        UserContextHolder.clear();
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api" + path);
        request.setContextPath("/api");
        return request;
    }

    private AtomicReference<UserContext> contextCapturingChain(MockFilterChain ignored,
                                                               MockHttpServletRequest request,
                                                               MockHttpServletResponse response)
            throws ServletException, IOException {
        AtomicReference<UserContext> captured = new AtomicReference<>();
        filter.doFilter(request, response, (req, res) -> captured.set(UserContextHolder.getContext()));
        return captured;
    }

    @Test
    void whitelistedPathPassesWithoutSession() throws Exception {
        MockHttpServletRequest request = request("/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<UserContext> captured = contextCapturingChain(null, request, response);

        assertEquals(200, response.getStatus());
        assertNull(captured.get());
    }

    @Test
    void protectedPathWithoutSessionReturns401() throws Exception {
        MockHttpServletRequest request = request("/v1/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("\"code\":401"));
    }

    @Test
    void protectedPathWithValidCookiePasses() throws Exception {
        String token = jwtService.issue(new UserContext("1", "admin", "admin", "local"));
        MockHttpServletRequest request = request("/v1/workflows");
        request.setCookies(new Cookie("odw_session", token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<UserContext> captured = contextCapturingChain(null, request, response);

        assertEquals(200, response.getStatus());
        assertNotNull(captured.get());
        assertEquals("1", captured.get().getUserId());
        assertEquals("admin", captured.get().getUsername());
        assertNull(UserContextHolder.getContext());
    }

    @Test
    void protectedPathWithBearerTokenPasses() throws Exception {
        String token = jwtService.issue(new UserContext("2", "alice", "user", "local"));
        MockHttpServletRequest request = request("/v1/workflows");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<UserContext> captured = contextCapturingChain(null, request, response);

        assertEquals(200, response.getStatus());
        assertNotNull(captured.get());
        assertEquals("alice", captured.get().getUsername());
    }

    @Test
    void invalidTokenOnProtectedPathReturns401() throws Exception {
        MockHttpServletRequest request = request("/v1/workflows");
        request.setCookies(new Cookie("odw_session", "not-a-jwt"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
    }

    @Test
    void anonymousFallbackWhenEnabled() throws Exception {
        properties.getAnonymous().setEnabled(true);
        MockHttpServletRequest request = request("/v1/workflows");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<UserContext> captured = contextCapturingChain(null, request, response);

        assertEquals(200, response.getStatus());
        assertNotNull(captured.get());
        assertEquals("1", captured.get().getUserId());
        assertEquals("anonymous", captured.get().getAuthSource());
    }

    @Test
    void whitelistedPathStillPopulatesContextWhenSessionPresent() throws Exception {
        String token = jwtService.issue(new UserContext("1", "admin", "admin", "local"));
        MockHttpServletRequest request = request("/auth/me");
        request.setCookies(new Cookie("odw_session", token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<UserContext> captured = contextCapturingChain(null, request, response);

        assertEquals(200, response.getStatus());
        assertNotNull(captured.get());
        assertEquals("admin", captured.get().getUsername());
    }
}
