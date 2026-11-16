package com.onedata.portal.security;

import com.onedata.auth.user.OAuthUserProvisioner;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import lombok.extern.slf4j.Slf4j;

import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

/** OAuth 登录失败处理器：销毁临时 HTTP 会话，按错误码 302 跳转至 /login?error=... */
@Slf4j
final class OAuthLoginFailureHandler implements AuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException exception) {
        String registrationId = extractRegistrationId(request);
        String oauthErrorCode = null;
        if (exception instanceof OAuth2AuthenticationException oauthEx
                && oauthEx.getError() != null) {
            oauthErrorCode = oauthEx.getError().getErrorCode();
        }
        String mappedError = mapErrorCode(oauthErrorCode);
        log.warn(
                "OAuth login failed: registrationId={}, exception={}, errorCode={}",
                registrationId,
                exception.getClass().getSimpleName(),
                oauthErrorCode);
        HttpSession session = request.getSession(false);
        if (session != null) {
            try {
                session.invalidate();
            } catch (IllegalStateException ignored) {
                // session already invalidated
            }
        }
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader(HttpHeaders.LOCATION, "/login?error=" + mappedError);
    }

    static String mapErrorCode(String oauthErrorCode) {
        if (OAuthUserProvisioner.ERROR_ACCOUNT_DISABLED.equals(oauthErrorCode)) {
            return OAuthUserProvisioner.ERROR_ACCOUNT_DISABLED;
        }
        if ("access_denied".equals(oauthErrorCode)) {
            return "oauth_denied";
        }
        return OAuthUserProvisioner.ERROR_OAUTH_FAILED;
    }

    static String extractRegistrationId(HttpServletRequest request) {
        String path = request.getServletPath();
        if (path == null) {
            return "unknown";
        }
        for (String prefix : new String[] {"/oauth2/authorization/", "/login/oauth2/code/"}) {
            if (path.startsWith(prefix) && path.length() > prefix.length()) {
                String raw = path.substring(prefix.length());
                int slash = raw.indexOf('/');
                String candidate = slash >= 0 ? raw.substring(0, slash) : raw;
                return candidate.replaceAll("[\r\n]", "");
            }
        }
        return "unknown";
    }
}
