package com.onedata.portal.security;

import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.jwt.SessionCookieIssuer;
import com.onedata.auth.user.OAuthUserProvisioner;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import java.util.Map;

/** OAuth 登录成功处理器：签发 odw_session Cookie、销毁临时 HTTP 会话并 302 跳回前端登录页 */
@Slf4j
@RequiredArgsConstructor
final class OAuthLoginSuccessHandler implements AuthenticationSuccessHandler {

    static final String TARGET_SUCCESS = "/login?oauth=success";
    static final String TARGET_FAILED = "/login?error=" + OAuthUserProvisioner.ERROR_OAUTH_FAILED;

    private final SessionCookieIssuer sessionCookieIssuer;

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        String registrationId = extractRegistrationId(authentication, request);
        try {
            CurrentPrincipal principal = toPrincipal(authentication);
            response.addHeader(HttpHeaders.SET_COOKIE, sessionCookieIssuer.issueCookie(principal));
            invalidateSessionQuietly(request);
            log.info(
                    "OAuth login succeeded: registrationId={}, username={}",
                    registrationId,
                    principal.getUsername());
            redirect(response, TARGET_SUCCESS);
        } catch (Exception e) {
            log.warn(
                    "OAuth login post-processing failed: registrationId={}, exception={}",
                    registrationId,
                    e.getClass().getSimpleName());
            invalidateSessionQuietly(request);
            redirect(response, TARGET_FAILED);
        }
    }

    private static CurrentPrincipal toPrincipal(Authentication authentication) {
        if (authentication == null
                || !(authentication.getPrincipal() instanceof OAuth2User oauth2User)) {
            throw new IllegalStateException("Missing OAuth2User principal");
        }
        Map<String, Object> attrs = oauth2User.getAttributes();
        Object userIdObj = attrs.get(OAuthUserServices.ATTR_USER_ID);
        Object usernameObj = attrs.get(OAuthUserServices.ATTR_USERNAME);
        Object authSourceObj = attrs.get(OAuthUserServices.ATTR_AUTH_SOURCE);
        Object roleObj = attrs.get(OAuthUserServices.ATTR_ROLE);
        Object versionObj = attrs.get(OAuthUserServices.ATTR_SESSION_VERSION);
        if (!(userIdObj instanceof Number userId)
                || !(usernameObj instanceof String username)
                || !(authSourceObj instanceof String authSource)) {
            throw new IllegalStateException("Missing provisioned user attributes");
        }
        String role = roleObj instanceof String r && !r.isBlank() ? r : CurrentPrincipal.ROLE_USER;
        int sessionVersion = versionObj instanceof Number n ? n.intValue() : 0;
        return new CurrentPrincipal(
                String.valueOf(userId.longValue()), username, role, authSource, sessionVersion);
    }

    private static String extractRegistrationId(
            Authentication authentication, HttpServletRequest request) {
        if (authentication instanceof OAuth2AuthenticationToken token
                && token.getAuthorizedClientRegistrationId() != null) {
            return token.getAuthorizedClientRegistrationId();
        }
        return OAuthLoginFailureHandler.extractRegistrationId(request);
    }

    private static void invalidateSessionQuietly(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // session already invalidated
            }
        }
    }

    private static void redirect(HttpServletResponse response, String target) {
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, target);
    }
}
