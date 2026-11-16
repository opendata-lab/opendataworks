package com.onedata.portal.security;

import com.onedata.auth.jwt.SessionCookieIssuer;
import com.onedata.auth.user.OAuthUserProvisioner;

import jakarta.annotation.PostConstruct;

import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;

/**
 * Spring Security 仅处理 OAuth2 授权跳转与回调两个端点； 其余全部请求由 {@link HttpAuthenticationFilter}（最高优先级）按既有策略鉴权。
 */
@Configuration
@RequiredArgsConstructor
@EnableConfigurationProperties(OAuth2ClientProperties.class)
public class OAuthLoginSecurityConfiguration {

    private final OAuth2ClientProperties oauth2ClientProperties;

    @PostConstruct
    void validateRegistrations() {
        if (oauth2ClientProperties.getRegistration() == null) {
            return;
        }
        for (String registrationId : oauth2ClientProperties.getRegistration().keySet()) {
            OAuthUserProvisioner.validateRegistrationId(registrationId);
        }
    }

    @Bean
    SecurityFilterChain oauthSecurityFilterChain(
            HttpSecurity http,
            ObjectProvider<ClientRegistrationRepository> clientRegistrations,
            OAuthUserProvisioner provisioner,
            SessionCookieIssuer sessionCookieIssuer)
            throws Exception {
        http.securityMatcher("/oauth2/authorization/**", "/login/oauth2/code/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .securityContext(
                        sc ->
                                sc.securityContextRepository(
                                        new RequestAttributeSecurityContextRepository()));

        if (clientRegistrations.getIfAvailable() != null) {
            OAuthLoginSuccessHandler successHandler =
                    new OAuthLoginSuccessHandler(sessionCookieIssuer);
            OAuthLoginFailureHandler failureHandler = new OAuthLoginFailureHandler();
            OAuthUserServices userServices = new OAuthUserServices(provisioner);
            http.oauth2Login(
                    oauth2 -> {
                        oauth2.userInfoEndpoint(
                                        userInfo ->
                                                userInfo.userService(
                                                                userServices.oauth2UserService())
                                                        .oidcUserService(
                                                                userServices.oidcUserService()))
                                .successHandler(successHandler)
                                .failureHandler(failureHandler);
                        oauth2.addObjectPostProcessor(
                                new ObjectPostProcessor<Object>() {
                                    @Override
                                    public <O> O postProcess(O object) {
                                        if (object
                                                instanceof
                                                OAuth2AuthorizationRequestRedirectFilter
                                                                redirectFilter) {
                                            redirectFilter.setAuthenticationFailureHandler(
                                                    failureHandler);
                                        }
                                        return object;
                                    }
                                });
                    });
        }

        return http.build();
    }
}
