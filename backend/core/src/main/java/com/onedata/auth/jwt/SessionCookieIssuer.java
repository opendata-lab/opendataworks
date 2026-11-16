package com.onedata.auth.jwt;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.CurrentPrincipal;
import com.onedata.auth.user.SysUser;

import lombok.RequiredArgsConstructor;

import java.util.Objects;

/** 会话 JWT 签发与 odw_session Set-Cookie 构造，供密码登录与 OAuth 登录共用 */
@RequiredArgsConstructor
public class SessionCookieIssuer {

    private final JwtService jwtService;
    private final OdwAuthProperties properties;

    public static CurrentPrincipal toPrincipal(SysUser user) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(user.getId(), "user.id must not be null");
        int sessionVersion = user.getSessionVersion() == null ? 0 : user.getSessionVersion();
        String role = user.getRole() == null ? CurrentPrincipal.ROLE_USER : user.getRole();
        return new CurrentPrincipal(
                String.valueOf(user.getId()),
                user.getUsername(),
                role,
                user.getAuthSource(),
                sessionVersion);
    }

    public String issueCookie(CurrentPrincipal principal) {
        String token = jwtService.issue(principal);
        return buildSessionCookie(token, properties.getJwt().getTtl().getSeconds());
    }

    public String issueCookie(SysUser user) {
        return issueCookie(toPrincipal(user));
    }

    public String clearCookie() {
        return buildSessionCookie("", 0);
    }

    public String buildSessionCookie(String value, long maxAgeSeconds) {
        OdwAuthProperties.Session session = properties.getSession();
        StringBuilder cookie =
                new StringBuilder()
                        .append(session.getCookieName())
                        .append('=')
                        .append(value)
                        .append("; Path=/; HttpOnly; Max-Age=")
                        .append(maxAgeSeconds)
                        .append("; SameSite=")
                        .append(session.getSameSite());
        if (session.isSecure()) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }
}
