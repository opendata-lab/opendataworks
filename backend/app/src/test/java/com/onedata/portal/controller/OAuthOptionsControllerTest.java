package com.onedata.portal.controller;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OAuthOptionsControllerTest {

    @Test
    void returnsEmptyOAuthListWhenClientPropertiesAbsent() throws Exception {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new AuthOptionsController(
                                        beans.getBeanProvider(OAuth2ClientProperties.class)))
                        .build();

        mvc.perform(get("/auth/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.localLogin").value(true))
                .andExpect(jsonPath("$.data.oauth").isArray())
                .andExpect(jsonPath("$.data.oauth.length()").value(0));
    }

    @Test
    void returnsConfiguredRegistrationsUsingClientNameOrIdFallbackWithoutSecrets()
            throws Exception {
        OAuth2ClientProperties properties = new OAuth2ClientProperties();
        OAuth2ClientProperties.Registration sso = new OAuth2ClientProperties.Registration();
        sso.setClientId("id-1");
        sso.setClientSecret("super-secret");
        sso.setClientName("企业单点登录");
        properties.getRegistration().put("sso", sso);

        OAuth2ClientProperties.Registration corp = new OAuth2ClientProperties.Registration();
        corp.setClientId("id-2");
        corp.setClientSecret("another-secret");
        properties.getRegistration().put("corp", corp);

        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("oauth2ClientProperties", properties);
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new AuthOptionsController(
                                        beans.getBeanProvider(OAuth2ClientProperties.class)))
                        .build();

        mvc.perform(get("/auth/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.localLogin").value(true))
                .andExpect(jsonPath("$.data.oauth.length()").value(2))
                .andExpect(jsonPath("$.data.oauth[?(@.id=='sso')].name", contains("企业单点登录")))
                .andExpect(jsonPath("$.data.oauth[?(@.id=='sso')].clientSecret").isEmpty())
                .andExpect(jsonPath("$.data.oauth[?(@.id=='sso')].clientId").isEmpty())
                .andExpect(jsonPath("$.data.oauth[?(@.id=='corp')].name", contains("corp")));
    }
}
