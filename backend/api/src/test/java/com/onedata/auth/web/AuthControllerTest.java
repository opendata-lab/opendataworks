package com.onedata.auth.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.context.PrincipalContextHolder;
import com.onedata.auth.jwt.Hs256JwtService;
import com.onedata.auth.jwt.SessionCookieIssuer;
import com.onedata.auth.user.SysUser;
import com.onedata.auth.user.SysUserService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerTest {

    private SysUserService sysUserService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        sysUserService = mock(SysUserService.class);
        OdwAuthProperties properties = new OdwAuthProperties();
        properties.getJwt().setSecret("unit-test-secret-0123456789-0123456789");
        SessionCookieIssuer issuer =
                new SessionCookieIssuer(new Hs256JwtService(properties), properties);
        mockMvc =
                MockMvcBuilders.standaloneSetup(new AuthController(sysUserService, issuer)).build();
    }

    @AfterEach
    void clearContext() {
        PrincipalContextHolder.clear();
    }

    @Test
    void loginSetsSessionCookieAndOmitsAgentUserId() throws Exception {
        SysUser user = new SysUser();
        user.setId(1L);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setRole("user");
        user.setAuthSource("local");
        user.setSessionVersion(2);
        when(sysUserService.login("alice", "secret123")).thenReturn(user);

        mockMvc.perform(
                        post("/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"username\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("odw_session=")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("1"))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.nickname").value("Alice"))
                .andExpect(jsonPath("$.data.role").value("user"))
                .andExpect(jsonPath("$.data.authSource").value("local"))
                .andExpect(jsonPath("$.data.agentUserId").doesNotExist());
    }

    @Test
    void meReturnsProfileWithoutAgentUserId() throws Exception {
        SysUser oauthUser = new SysUser();
        oauthUser.setId(9L);
        oauthUser.setUsername("sso:u-42");
        oauthUser.setNickname("Bob");
        oauthUser.setRole("user");
        oauthUser.setAuthSource("sso");
        when(sysUserService.findById(9L)).thenReturn(oauthUser);
        PrincipalContextHolder.setContext(new CurrentPrincipal("9", "sso:u-42", "user", "sso", 0));

        mockMvc.perform(get("/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("9"))
                .andExpect(jsonPath("$.data.username").value("sso:u-42"))
                .andExpect(jsonPath("$.data.nickname").value("Bob"))
                .andExpect(jsonPath("$.data.authSource").value("sso"))
                .andExpect(jsonPath("$.data.agentUserId").doesNotExist());
    }

    @Test
    void logoutClearsSessionCookie() throws Exception {
        mockMvc.perform(post("/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("odw_session=;")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("Max-Age=0")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("HttpOnly")))
                .andExpect(header().string(HttpHeaders.SET_COOKIE, containsString("SameSite=Lax")));
    }
}
