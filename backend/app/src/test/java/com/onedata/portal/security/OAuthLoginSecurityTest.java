package com.onedata.portal.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.jwt.Hs256JwtService;
import com.onedata.auth.jwt.JwtService;
import com.onedata.auth.jwt.SessionCookieIssuer;
import com.onedata.auth.user.OAuthUserProvisioner;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserMapper;
import com.onedata.auth.user.SysUserService;
import com.onedata.portal.controller.AuthOptionsController;
import com.onedata.portal.service.ServiceAccountTokenService;
import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.http.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class OAuthLoginSecurityTest {

    private static final String JWT_SECRET = "unit-test-secret-0123456789-0123456789";

    private WebApplicationContextRunner contextRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                ConfigurationPropertiesAutoConfiguration.class,
                                PropertyPlaceholderAutoConfiguration.class,
                                HttpMessageConvertersAutoConfiguration.class,
                                JacksonAutoConfiguration.class,
                                WebMvcAutoConfiguration.class,
                                SecurityAutoConfiguration.class,
                                SecurityFilterAutoConfiguration.class,
                                OAuth2ClientAutoConfiguration.class))
                .withUserConfiguration(TestSecurityApplication.class);
    }

    @Test
    void oauthFlowWithFakeIdpCoversSuccessDisabledDeniedInvalidStateAndUnknownId()
            throws Exception {
        AtomicReference<String> userInfoJson =
                new AtomicReference<>(
                        "{\"sub\":\"u-100\",\"preferred_username\":\"Alice\",\"email\":\"alice@example.com\"}");
        AtomicReference<Integer> tokenStatus = new AtomicReference<>(200);
        HttpServer idp = startFakeIdp(userInfoJson, tokenStatus);
        int port = idp.getAddress().getPort();

        try {
            contextRunner()
                    .withPropertyValues(
                            "spring.security.oauth2.client.registration.sso.client-id=odw-client",
                            "spring.security.oauth2.client.registration.sso.client-secret=odw-secret",
                            "spring.security.oauth2.client.registration.sso.client-name=SSO",
                            "spring.security.oauth2.client.registration.sso.client-authentication-method=client_secret_post",
                            "spring.security.oauth2.client.registration.sso.authorization-grant-type=authorization_code",
                            "spring.security.oauth2.client.registration.sso.redirect-uri=http://localhost/api/login/oauth2/code/sso",
                            "spring.security.oauth2.client.registration.sso.scope=read",
                            "spring.security.oauth2.client.provider.sso.authorization-uri=http://127.0.0.1:"
                                    + port
                                    + "/oauth/authorize",
                            "spring.security.oauth2.client.provider.sso.token-uri=http://127.0.0.1:"
                                    + port
                                    + "/oauth/token",
                            "spring.security.oauth2.client.provider.sso.user-info-uri=http://127.0.0.1:"
                                    + port
                                    + "/oauth/userinfo",
                            "spring.security.oauth2.client.provider.sso.user-name-attribute=sub")
                    .run(
                            context -> {
                                MockMvc mvc = buildMvc(context);
                                SysUserMapper mapper = context.getBean(SysUserMapper.class);
                                SysUserService sysUserService =
                                        context.getBean(SysUserService.class);
                                JwtService jwtService = context.getBean(JwtService.class);

                                // 1. /auth/options is public and does not expose secrets
                                mvc.perform(apiGet("/auth/options"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.code").value(200))
                                        .andExpect(jsonPath("$.data.localLogin").value(true))
                                        .andExpect(jsonPath("$.data.oauth[0].id").value("sso"))
                                        .andExpect(jsonPath("$.data.oauth[0].name").value("SSO"))
                                        .andExpect(
                                                jsonPath("$.data.oauth[0].clientSecret")
                                                        .doesNotExist());

                                // 2. /oauth2/authorization/sso redirects 302 to fake IdP with state
                                MvcResult authStart =
                                        mvc.perform(apiGet("/oauth2/authorization/sso"))
                                                .andExpect(status().isFound())
                                                .andReturn();
                                String location =
                                        authStart.getResponse().getHeader(HttpHeaders.LOCATION);
                                assertNotNull(location);
                                assertTrue(
                                        location.startsWith(
                                                "http://127.0.0.1:" + port + "/oauth/authorize"));
                                String rawState =
                                        UriComponentsBuilder.fromUriString(location)
                                                .build()
                                                .getQueryParams()
                                                .getFirst("state");
                                assertNotNull(rawState);
                                String state = URLDecoder.decode(rawState, StandardCharsets.UTF_8);
                                MockHttpSession session =
                                        (MockHttpSession) authStart.getRequest().getSession(false);
                                assertNotNull(session);

                                // 3. Happy path callback -> provisions user, sets odw_session,
                                // invalidates HTTP session, 302 /login?oauth=success
                                reset(mapper);
                                when(mapper.findAllByUsername("sso:u-100")).thenReturn(null);
                                when(mapper.insert(any(SysUser.class)))
                                        .thenAnswer(
                                                call -> {
                                                    SysUser u = call.getArgument(0);
                                                    u.setId(42L);
                                                    return 1;
                                                });

                                MvcResult callbackResult =
                                        mvc.perform(
                                                        apiGet("/login/oauth2/code/sso")
                                                                .queryParam("code", "valid-code")
                                                                .queryParam("state", state)
                                                                .session(session))
                                                .andExpect(status().isFound())
                                                .andExpect(redirectedUrl("/login?oauth=success"))
                                                .andReturn();
                                assertTrue(session.isInvalid());
                                String setCookie =
                                        callbackResult
                                                .getResponse()
                                                .getHeader(HttpHeaders.SET_COOKIE);
                                assertNotNull(setCookie);
                                assertTrue(setCookie.startsWith("odw_session="));
                                String token =
                                        setCookie.substring(
                                                "odw_session=".length(), setCookie.indexOf(';'));
                                CurrentPrincipal principal = jwtService.verify(token);
                                assertNotNull(principal);
                                assertEquals("42", principal.getUserId());
                                assertEquals("sso:u-100", principal.getUsername());
                                assertEquals("sso", principal.getAuthSource());
                                assertEquals("user", principal.getRole());

                                // 4. Non-OAuth paths keep existing HttpAuthenticationFilter
                                // behavior and are untouched by Spring Security
                                mvc.perform(apiGet("/v1/tables"))
                                        .andExpect(status().isUnauthorized())
                                        .andExpect(jsonPath("$.code").value(401));

                                reset(sysUserService);
                                when(sysUserService.verifySession(any())).thenReturn(principal);
                                mvc.perform(
                                                apiGet("/v1/tables")
                                                        .cookie(new Cookie("odw_session", token)))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.path").value("tables"));

                                // 5. Disabled user redirects to /login?error=oauth_account_disabled
                                userInfoJson.set("{\"sub\":\"u-disabled\",\"name\":\"Disabled\"}");
                                SysUser disabledUser = new SysUser();
                                disabledUser.setId(77L);
                                disabledUser.setUsername("sso:u-disabled");
                                disabledUser.setAuthSource("sso");
                                disabledUser.setEnabled(false);
                                disabledUser.setDeleted(0);
                                reset(mapper);
                                when(mapper.findAllByUsername("sso:u-disabled"))
                                        .thenReturn(disabledUser);

                                AuthAttempt disabledAttempt = startAuth(mvc);
                                MvcResult disabledRes =
                                        mvc.perform(
                                                        apiGet("/login/oauth2/code/sso")
                                                                .queryParam("code", "valid-code")
                                                                .queryParam(
                                                                        "state",
                                                                        disabledAttempt.state)
                                                                .session(disabledAttempt.session))
                                                .andExpect(status().isFound())
                                                .andExpect(
                                                        redirectedUrl(
                                                                "/login?error=oauth_account_disabled"))
                                                .andReturn();
                                assertTrue(disabledAttempt.session.isInvalid());
                                assertNull(
                                        disabledRes
                                                .getResponse()
                                                .getHeader(HttpHeaders.SET_COOKIE));

                                // 6. IdP access_denied redirects to /login?error=oauth_denied
                                AuthAttempt deniedAttempt = startAuth(mvc);
                                mvc.perform(
                                                apiGet("/login/oauth2/code/sso")
                                                        .queryParam("error", "access_denied")
                                                        .queryParam("state", deniedAttempt.state)
                                                        .session(deniedAttempt.session))
                                        .andExpect(status().isFound())
                                        .andExpect(redirectedUrl("/login?error=oauth_denied"));
                                assertTrue(deniedAttempt.session.isInvalid());

                                // 7. Invalid state / token failure redirects to
                                // /login?error=oauth_failed
                                AuthAttempt invalidStateAttempt = startAuth(mvc);
                                mvc.perform(
                                                apiGet("/login/oauth2/code/sso")
                                                        .queryParam("code", "valid-code")
                                                        .queryParam("state", "wrong-state")
                                                        .session(invalidStateAttempt.session))
                                        .andExpect(status().isFound())
                                        .andExpect(redirectedUrl("/login?error=oauth_failed"));
                                assertTrue(invalidStateAttempt.session.isInvalid());

                                // 8. Unknown registration id redirects to /login?error=oauth_failed
                                // instead of returning 500
                                mvc.perform(apiGet("/oauth2/authorization/unknown"))
                                        .andExpect(status().isFound())
                                        .andExpect(redirectedUrl("/login?error=oauth_failed"));
                            });
        } finally {
            idp.stop(0);
        }
    }

    @Test
    void returns404ForOAuthEndpointsWhenNoClientRegistrationConfigured() {
        contextRunner()
                .run(
                        context -> {
                            MockMvc mvc = buildMvc(context);

                            mvc.perform(apiGet("/auth/options"))
                                    .andExpect(status().isOk())
                                    .andExpect(jsonPath("$.data.localLogin").value(true))
                                    .andExpect(jsonPath("$.data.oauth.length()").value(0));

                            mvc.perform(apiGet("/oauth2/authorization/sso"))
                                    .andExpect(status().isNotFound());

                            mvc.perform(apiGet("/login/oauth2/code/sso"))
                                    .andExpect(status().isNotFound());

                            mvc.perform(apiGet("/v1/tables")).andExpect(status().isUnauthorized());
                        });
    }

    @Test
    void startupFailsForInvalidOrReservedRegistrationIds() {
        for (String badId :
                List.of(
                        "local",
                        "LOCAL",
                        "service",
                        "anonymous",
                        ".bad",
                        "bad:id",
                        "a".repeat(33))) {
            contextRunner()
                    .withPropertyValues(
                            "spring.security.oauth2.client.registration["
                                    + badId
                                    + "].client-id=cid",
                            "spring.security.oauth2.client.registration["
                                    + badId
                                    + "].client-secret=sec",
                            "spring.security.oauth2.client.registration["
                                    + badId
                                    + "].authorization-grant-type=authorization_code",
                            "spring.security.oauth2.client.registration["
                                    + badId
                                    + "].redirect-uri=https://odw.example.com/api/login/oauth2/code/sso",
                            "spring.security.oauth2.client.provider["
                                    + badId
                                    + "].authorization-uri=https://idp.example.com/auth",
                            "spring.security.oauth2.client.provider["
                                    + badId
                                    + "].token-uri=https://idp.example.com/token",
                            "spring.security.oauth2.client.provider["
                                    + badId
                                    + "].user-info-uri=https://idp.example.com/userinfo",
                            "spring.security.oauth2.client.provider["
                                    + badId
                                    + "].user-name-attribute=sub")
                    .run(
                            context ->
                                    assertNotNull(
                                            context.getStartupFailure(),
                                            "Expected startup failure for registration id: "
                                                    + badId));
        }
    }

    @Test
    void oidcUserServiceWrapperProvisionsUserAndMapsDomainRejection() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        OAuthUserProvisioner provisioner = new OAuthUserProvisioner(mapper);
        @SuppressWarnings("unchecked")
        OAuth2UserService<OidcUserRequest, OidcUser> delegate = mock(OAuth2UserService.class);
        OAuthUserServices services = new OAuthUserServices(provisioner, mock(), delegate);

        ClientRegistration reg =
                ClientRegistration.withRegistrationId("corp")
                        .clientId("cid")
                        .clientSecret("sec")
                        .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://odw.example.com/api/login/oauth2/code/corp")
                        .authorizationUri("https://idp.example.com/auth")
                        .tokenUri("https://idp.example.com/token")
                        .userInfoUri("https://idp.example.com/userinfo")
                        .userNameAttributeName("sub")
                        .scope("openid")
                        .build();
        OidcIdToken idToken =
                new OidcIdToken(
                        "id-tok",
                        Instant.now(),
                        Instant.now().plusSeconds(60),
                        Map.of("sub", "oidc-9", "name", "OIDC Alice", "email", "oidc@example.com"));
        OidcUser delegateUser =
                new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_USER")), idToken);
        OidcUserRequest request =
                new OidcUserRequest(
                        reg,
                        new OAuth2AccessToken(
                                OAuth2AccessToken.TokenType.BEARER,
                                "acc",
                                Instant.now(),
                                Instant.now().plusSeconds(60)),
                        idToken);
        when(delegate.loadUser(request)).thenReturn(delegateUser);
        when(mapper.findAllByUsername("corp:oidc-9")).thenReturn(null);
        when(mapper.insert(any(SysUser.class)))
                .thenAnswer(
                        call -> {
                            SysUser u = call.getArgument(0);
                            u.setId(88L);
                            return 1;
                        });

        OidcUser wrapped = services.oidcUserService().loadUser(request);
        assertEquals(88L, wrapped.<Long>getAttribute(OAuthUserServices.ATTR_USER_ID));
        assertEquals("corp:oidc-9", wrapped.getAttribute(OAuthUserServices.ATTR_USERNAME));
        assertEquals("oidc-9", wrapped.getSubject());

        SysUser disabled = new SysUser();
        disabled.setId(88L);
        disabled.setUsername("corp:oidc-9");
        disabled.setAuthSource("corp");
        disabled.setEnabled(false);
        disabled.setDeleted(0);
        when(mapper.findAllByUsername("corp:oidc-9")).thenReturn(disabled);

        OAuth2AuthenticationException ex =
                assertThrows(
                        OAuth2AuthenticationException.class,
                        () -> services.oidcUserService().loadUser(request));
        assertEquals("oauth_account_disabled", ex.getError().getErrorCode());
    }

    @Test
    void successHandlerInvalidatesSessionAndRedirectsFailedOnUnexpectedError() {
        OdwAuthProperties props = new OdwAuthProperties();
        props.getJwt().setSecret(JWT_SECRET);
        SessionCookieIssuer issuer = new SessionCookieIssuer(new Hs256JwtService(props), props);
        OAuthLoginSuccessHandler handler = new OAuthLoginSuccessHandler(issuer);

        MockHttpServletRequest req =
                new MockHttpServletRequest("GET", "/api/login/oauth2/code/sso");
        req.setServletPath("/login/oauth2/code/sso");
        MockHttpSession session = new MockHttpSession();
        req.setSession(session);
        MockHttpServletResponse res = new MockHttpServletResponse();

        handler.onAuthenticationSuccess(
                req, res, new TestingAuthenticationToken("missing-oauth-principal", "n/a"));

        assertEquals(302, res.getStatus());
        assertEquals("/login?error=oauth_failed", res.getRedirectedUrl());
        assertTrue(session.isInvalid());
        assertNull(res.getHeader(HttpHeaders.SET_COOKIE));
    }

    @Test
    void resolvesNicknameFallbackOrderAndRejectsInvalidEmailAttributes() {
        assertEquals(
                "pref",
                OAuthUserServices.resolveNickname(
                        Map.of("preferred_username", "pref", "name", "Name", "nickname", "Nick"),
                        "sub-1"));
        assertEquals(
                "Name",
                OAuthUserServices.resolveNickname(
                        Map.of("preferred_username", " ", "name", "Name", "nickname", "Nick"),
                        "sub-1"));
        assertEquals(
                "Nick",
                OAuthUserServices.resolveNickname(
                        Map.of("name", 123, "nickname", "Nick"), "sub-1"));
        assertEquals("sub-1", OAuthUserServices.resolveNickname(Map.of(), "sub-1"));

        assertEquals(
                "alice@example.com",
                OAuthUserServices.resolveEmail(Map.of("email", " alice@example.com ")));
        assertNull(OAuthUserServices.resolveEmail(Map.of("email", List.of("a@b.c"))));
        assertNull(OAuthUserServices.resolveEmail(Map.of("email", "x".repeat(129))));
        assertNull(OAuthUserServices.resolveEmail(Map.of("email", "  ")));
    }

    @Test
    void dataPortalApplicationExcludesUserDetailsServiceAutoConfiguration() {
        SpringBootApplication annotation =
                com.onedata.portal.DataPortalApplication.class.getAnnotation(
                        SpringBootApplication.class);
        assertNotNull(annotation);
        assertTrue(
                Arrays.asList(annotation.exclude())
                        .contains(UserDetailsServiceAutoConfiguration.class));
    }

    private static MockMvc buildMvc(WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context)
                .addFilters(
                        context.getBean(HttpAuthenticationFilter.class),
                        context.getBean("springSecurityFilterChain", Filter.class))
                .build();
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            apiGet(String servletPath) {
        return get("/api" + servletPath).contextPath("/api").servletPath(servletPath);
    }

    private static AuthAttempt startAuth(MockMvc mvc) throws Exception {
        MvcResult authStart =
                mvc.perform(apiGet("/oauth2/authorization/sso"))
                        .andExpect(status().isFound())
                        .andReturn();
        String location = authStart.getResponse().getHeader(HttpHeaders.LOCATION);
        String rawState =
                UriComponentsBuilder.fromUriString(location)
                        .build()
                        .getQueryParams()
                        .getFirst("state");
        String state = URLDecoder.decode(rawState, StandardCharsets.UTF_8);
        MockHttpSession session = (MockHttpSession) authStart.getRequest().getSession(false);
        assertFalse(session.isInvalid());
        return new AuthAttempt(state, session);
    }

    private record AuthAttempt(String state, MockHttpSession session) {}

    private static HttpServer startFakeIdp(
            AtomicReference<String> userInfoJson, AtomicReference<Integer> tokenStatus)
            throws IOException {
        HttpServer server =
                HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(
                "/oauth/token",
                exchange -> {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    String form = new String(body, StandardCharsets.UTF_8);
                    int status = tokenStatus.get();
                    if (status != 200 || !form.contains("grant_type=authorization_code")) {
                        writeJson(exchange, 400, "{\"error\":\"invalid_request\"}");
                        return;
                    }
                    writeJson(
                            exchange,
                            200,
                            "{\"access_token\":\"idp-access-token\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
                });
        server.createContext(
                "/oauth/userinfo",
                exchange -> {
                    String auth = exchange.getRequestHeaders().getFirst(HttpHeaders.AUTHORIZATION);
                    if (!"Bearer idp-access-token".equals(auth)) {
                        writeJson(exchange, 401, "{\"error\":\"invalid_token\"}");
                        return;
                    }
                    writeJson(exchange, 200, userInfoJson.get());
                });
        server.start();
        return server;
    }

    private static void writeJson(
            com.sun.net.httpserver.HttpExchange exchange, int status, String json)
            throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set(HttpHeaders.CONTENT_TYPE, "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @SpringBootConfiguration
    @Import({OAuthLoginSecurityConfiguration.class, AuthOptionsController.class})
    static class TestSecurityApplication {

        @Bean
        OdwAuthProperties odwAuthProperties() {
            OdwAuthProperties props = new OdwAuthProperties();
            props.getJwt().setSecret(JWT_SECRET);
            return props;
        }

        @Bean
        HttpSecurityProperties httpSecurityProperties() {
            return new HttpSecurityProperties();
        }

        @Bean
        JwtService jwtService(OdwAuthProperties properties) {
            return new Hs256JwtService(properties);
        }

        @Bean
        SessionCookieIssuer sessionCookieIssuer(
                JwtService jwtService, OdwAuthProperties properties) {
            return new SessionCookieIssuer(jwtService, properties);
        }

        @Bean
        SysUserMapper sysUserMapper() {
            return mock(SysUserMapper.class);
        }

        @Bean
        SysUserService sysUserService() {
            return mock(SysUserService.class);
        }

        @Bean
        ServiceAccountTokenService serviceAccountTokenService() {
            return mock(ServiceAccountTokenService.class);
        }

        @Bean
        OAuthUserProvisioner oAuthUserProvisioner(SysUserMapper mapper) {
            return new OAuthUserProvisioner(mapper);
        }

        @Bean
        HttpAuthenticationFilter httpAuthenticationFilter(
                OdwAuthProperties auth,
                HttpSecurityProperties http,
                JwtService jwt,
                SysUserService users,
                ServiceAccountTokenService tokens) {
            return new HttpAuthenticationFilter(auth, http, jwt, users, tokens);
        }

        @RestController
        static class DummyPortalController {
            @GetMapping("/v1/tables")
            Map<String, String> tables() {
                return Map.of("path", "tables");
            }
        }
    }
}
