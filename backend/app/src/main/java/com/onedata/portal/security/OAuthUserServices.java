package com.onedata.portal.security;

import com.onedata.auth.exception.OAuthProvisioningException;
import com.onedata.auth.user.OAuthUserProvisioner;
import com.onedata.auth.user.SysUser;

import lombok.RequiredArgsConstructor;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 包装框架默认 OAuth2 / OIDC 用户服务，在认证阶段完成本地用户开通并写入内部主体属性 */
@RequiredArgsConstructor
final class OAuthUserServices {

    static final String ATTR_USER_ID = OAuthUserServices.class.getName() + ".userId";
    static final String ATTR_USERNAME = OAuthUserServices.class.getName() + ".username";
    static final String ATTR_ROLE = OAuthUserServices.class.getName() + ".role";
    static final String ATTR_AUTH_SOURCE = OAuthUserServices.class.getName() + ".authSource";
    static final String ATTR_SESSION_VERSION =
            OAuthUserServices.class.getName() + ".sessionVersion";

    private static final List<String> NICKNAME_KEYS =
            List.of("preferred_username", "name", "nickname");

    private final OAuthUserProvisioner provisioner;
    private final OAuth2UserService<OAuth2UserRequest, OAuth2User> delegateOAuth2Service;
    private final OAuth2UserService<OidcUserRequest, OidcUser> delegateOidcService;

    OAuthUserServices(OAuthUserProvisioner provisioner) {
        this(provisioner, new DefaultOAuth2UserService(), new OidcUserService());
    }

    OAuth2UserService<OAuth2UserRequest, OAuth2User> oauth2UserService() {
        return userRequest -> {
            OAuth2User delegateUser = delegateOAuth2Service.loadUser(userRequest);
            String registrationId = userRequest.getClientRegistration().getRegistrationId();
            SysUser provisioned =
                    provisionUser(
                            registrationId, delegateUser.getName(), delegateUser.getAttributes());
            return new ProvisionedOAuth2User(delegateUser, provisioned);
        };
    }

    OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        return userRequest -> {
            OidcUser delegateUser = delegateOidcService.loadUser(userRequest);
            String registrationId = userRequest.getClientRegistration().getRegistrationId();
            SysUser provisioned =
                    provisionUser(
                            registrationId,
                            delegateUser.getSubject(),
                            delegateUser.getAttributes());
            return new ProvisionedOidcUser(delegateUser, provisioned);
        };
    }

    private SysUser provisionUser(
            String registrationId, String sub, Map<String, Object> attributes) {
        String nickname = resolveNickname(attributes, sub);
        String email = resolveEmail(attributes);
        try {
            return provisioner.provision(registrationId, sub, nickname, email);
        } catch (OAuthProvisioningException e) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(e.getErrorCode(), e.getMessage(), null), e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new OAuth2AuthenticationException(
                    new OAuth2Error(OAuthUserProvisioner.ERROR_OAUTH_FAILED),
                    "OAuth user provisioning failed",
                    e);
        }
    }

    static String resolveNickname(Map<String, Object> attributes, String sub) {
        if (attributes != null) {
            for (String key : NICKNAME_KEYS) {
                Object value = attributes.get(key);
                if (value instanceof String str && !str.isBlank()) {
                    return str.trim();
                }
            }
        }
        return sub;
    }

    static String resolveEmail(Map<String, Object> attributes) {
        if (attributes == null) {
            return null;
        }
        Object value = attributes.get("email");
        if (!(value instanceof String str)) {
            return null;
        }
        String trimmed = str.trim();
        if (trimmed.isEmpty() || trimmed.length() > OAuthUserProvisioner.MAX_EMAIL_LENGTH) {
            return null;
        }
        return trimmed;
    }

    private static Map<String, Object> buildAttributes(
            Map<String, Object> baseAttributes, SysUser user) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (baseAttributes != null) {
            merged.putAll(baseAttributes);
        }
        merged.put(ATTR_USER_ID, user.getId());
        merged.put(ATTR_USERNAME, user.getUsername());
        merged.put(ATTR_ROLE, user.getRole());
        merged.put(ATTR_AUTH_SOURCE, user.getAuthSource());
        merged.put(ATTR_SESSION_VERSION, user.getSessionVersion());
        return Collections.unmodifiableMap(merged);
    }

    private static final class ProvisionedOAuth2User implements OAuth2User {
        private final OAuth2User delegate;
        private final Map<String, Object> attributes;

        private ProvisionedOAuth2User(OAuth2User delegate, SysUser user) {
            this.delegate = delegate;
            this.attributes = buildAttributes(delegate.getAttributes(), user);
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return delegate.getAuthorities();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }
    }

    private static final class ProvisionedOidcUser implements OidcUser {
        private final OidcUser delegate;
        private final Map<String, Object> attributes;

        private ProvisionedOidcUser(OidcUser delegate, SysUser user) {
            this.delegate = delegate;
            this.attributes = buildAttributes(delegate.getAttributes(), user);
        }

        @Override
        public Map<String, Object> getClaims() {
            return attributes;
        }

        @Override
        public OidcUserInfo getUserInfo() {
            return delegate.getUserInfo();
        }

        @Override
        public OidcIdToken getIdToken() {
            return delegate.getIdToken();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return attributes;
        }

        @Override
        public Collection<? extends GrantedAuthority> getAuthorities() {
            return delegate.getAuthorities();
        }

        @Override
        public String getName() {
            return delegate.getName();
        }
    }
}
