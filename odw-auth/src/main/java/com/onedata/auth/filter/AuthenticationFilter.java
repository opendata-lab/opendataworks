package com.onedata.auth.filter;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.UserContext;
import com.onedata.auth.context.UserContextHolder;
import com.onedata.auth.jwt.JwtService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 会话认证过滤器：默认拦截全部请求 + 白名单放行
 * 从 odw_session Cookie（或 Authorization: Bearer 兜底）取会话 JWT，
 * 校验通过则填充 UserContextHolder；白名单路径不强制会话但仍尽量填充上下文
 */
@Slf4j
public class AuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final OdwAuthProperties properties;
    private final JwtService jwtService;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    public AuthenticationFilter(OdwAuthProperties properties, JwtService jwtService) {
        this.properties = properties;
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            UserContext user = resolveSession(request);
            if (user == null && properties.getAnonymous().isEnabled()) {
                user = new UserContext(
                        properties.getAnonymous().getUserId(),
                        properties.getAnonymous().getUsername(),
                        UserContext.ROLE_ADMIN,
                        UserContext.AUTH_SOURCE_ANONYMOUS);
            }
            if (user != null) {
                UserContextHolder.setContext(user);
            }

            if (user == null && !isWhitelisted(request)) {
                writeUnauthorized(response);
                return;
            }

            filterChain.doFilter(request, response);
        } finally {
            UserContextHolder.clear();
        }
    }

    private UserContext resolveSession(HttpServletRequest request) {
        String token = resolveToken(request);
        if (token == null || token.isEmpty()) {
            return null;
        }
        return jwtService.verify(token);
    }

    private String resolveToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            String cookieName = properties.getSession().getCookieName();
            for (Cookie cookie : cookies) {
                if (cookieName.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith(BEARER_PREFIX)) {
            return authorization.substring(BEARER_PREFIX.length());
        }
        return null;
    }

    private boolean isWhitelisted(HttpServletRequest request) {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        for (String pattern : properties.getWhitelist()) {
            if (pathMatcher.match(pattern, path)) {
                return true;
            }
        }
        return false;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"code\":401,\"message\":\"未登录或会话已过期\",\"data\":null}");
    }
}
