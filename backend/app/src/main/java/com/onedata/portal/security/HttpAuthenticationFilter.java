package com.onedata.portal.security;

import com.onedata.auth.autoconfigure.OdwAuthProperties;
import com.onedata.auth.context.*;
import com.onedata.auth.jwt.JwtService;
import com.onedata.auth.user.SysUserService;
import com.onedata.portal.service.ServiceAccountTokenService;

import jakarta.servlet.*;
import jakarta.servlet.http.*;

import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;

public final class HttpAuthenticationFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = CurrentPrincipal.class.getName();
    private final OdwAuthProperties auth;
    private final HttpSecurityProperties http;
    private final JwtService jwt;
    private final SysUserService users;
    private final ServiceAccountTokenService tokens;
    private final AuthenticationRoutePolicy routes = new AuthenticationRoutePolicy();

    public HttpAuthenticationFilter(
            OdwAuthProperties auth,
            HttpSecurityProperties http,
            JwtService jwt,
            SysUserService users,
            ServiceAccountTokenService tokens) {
        this.auth = auth;
        this.http = http;
        this.jwt = jwt;
        this.users = users;
        this.tokens = tokens;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI().substring(req.getContextPath().length());
        try {
            String decoded =
                    org.springframework.web.util.UriUtils.decode(
                            path, java.nio.charset.StandardCharsets.UTF_8);
            if (decoded.contains(";")
                    || decoded.contains("\\")
                    || decoded.contains("//")
                    || Arrays.stream(decoded.split("/", -1))
                            .anyMatch(segment -> segment.equals(".") || segment.equals(".."))) {
                reject(res, 400, "请求路径不规范");
                return;
            }
            path = decoded;
        } catch (IllegalArgumentException invalidPath) {
            reject(res, 400, "请求路径编码无效");
            return;
        }
        boolean service = routes.classify(path) == AuthenticationRoutePolicy.Route.SERVICE;
        String origin = req.getHeader("Origin");
        if (origin != null) {
            if (!http.getAllowedOrigins().contains(origin)
                    && (service || !sameOrigin(req, origin))) {
                reject(res, 403, "来源未获允许");
                return;
            }
            if (!service) {
                res.setHeader("Access-Control-Allow-Origin", origin);
                res.setHeader("Vary", "Origin");
                res.setHeader("Access-Control-Allow-Credentials", "true");
                res.setHeader("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS,PATCH");
                res.setHeader("Access-Control-Allow-Headers", "Content-Type,Authorization");
            }
        }
        if ("OPTIONS".equals(req.getMethod()) && !service) {
            res.setStatus(204);
            return;
        }
        CurrentPrincipal principal = null;
        if (service) {
            if (http.isRequirePrivateNetwork() && !privateClient(req)) {
                reject(res, 403, "MCP 仅允许私网访问");
                return;
            }
            String bearer = bearer(req.getHeader("Authorization"));
            try {
                String id = tokens.authenticate(bearer);
                String delegate = req.getHeader("X-Agent-User-Id");
                principal =
                        CurrentPrincipal.service(
                                Long.valueOf(id),
                                delegate == null || delegate.isBlank() ? null : delegate.trim());
            } catch (IllegalArgumentException e) {
                reject(res, 401, "服务账号Token无效、已过期或账号已停用");
                return;
            }
        } else {
            String token = null;
            // Explicit authorization always wins, even when malformed or invalid.
            if (req.getHeader("Authorization") != null)
                token = bearer(req.getHeader("Authorization"));
            else if (req.getCookies() != null)
                for (Cookie c : req.getCookies())
                    if (auth.getSession().getCookieName().equals(c.getName())) token = c.getValue();
            if (token != null && !token.startsWith("odw_sa_"))
                principal = users.verifySession(jwt.verify(token));
            if (principal == null
                    && routes.classify(path) != AuthenticationRoutePolicy.Route.PUBLIC) {
                reject(res, 401, "未登录或会话已过期");
                return;
            }
        }
        try {
            if (principal != null) {
                PrincipalContextHolder.setContext(principal);
                req.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            }
            chain.doFilter(req, res);
        } finally {
            PrincipalContextHolder.clear();
        }
    }

    private boolean sameOrigin(HttpServletRequest request, String origin) {
        String host = request.getHeader("Host");
        if (host == null)
            host =
                    request.getServerName()
                            + ((request.getServerPort() == 80 && request.getScheme().equals("http"))
                                            || (request.getServerPort() == 443
                                                    && request.getScheme().equals("https"))
                                    ? ""
                                    : ":" + request.getServerPort());
        return origin.equals(request.getScheme() + "://" + host);
    }

    private String bearer(String header) {
        return header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
    }

    boolean privateClient(HttpServletRequest req) {
        try {
            String peer = req.getRemoteAddr();
            // Untrusted peers cannot influence address selection with forwarding headers.
            if (http.getTrustedProxies().contains(peer)) {
                String forwarded = req.getHeader("X-Forwarded-For");
                if (forwarded != null) {
                    String[] hops = forwarded.split(",", -1);
                    for (String hop : hops) literal(hop.trim());
                    for (int i = hops.length - 1;
                            i >= 0 && http.getTrustedProxies().contains(peer);
                            i--) peer = hops[i].trim();
                }
            }
            InetAddress addr = literal(peer);
            return addr.isLoopbackAddress()
                    || addr.isSiteLocalAddress()
                    || (addr.getAddress().length == 16 && (addr.getAddress()[0] & 0xfe) == 0xfc);
        } catch (Exception e) {
            return false;
        }
    }

    private InetAddress literal(String ip) throws java.net.UnknownHostException {
        if (ip.isEmpty() || !ip.matches("[0-9a-fA-F:.]+"))
            throw new java.net.UnknownHostException();
        return InetAddress.getByName(ip);
    }

    private void reject(HttpServletResponse res, int code, String msg) throws IOException {
        res.setStatus(code);
        res.setContentType("application/json");
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"code\":" + code + ",\"message\":\"" + msg + "\",\"data\":null}");
    }
}
