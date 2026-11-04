package com.onedata.portal.security;

public final class AuthenticationRoutePolicy {
    public enum Route {
        PUBLIC,
        USER,
        SERVICE
    }

    public Route classify(String path) {
        if (path.equals("/mcp") || path.equals("/mcp/")) return Route.SERVICE;
        if (path.equals("/auth/login")
                || path.equals("/auth/logout")
                || path.equals("/v1/health")
                || path.startsWith("/v1/health/")
                || path.equals("/actuator/health")
                || path.equals("/error")) return Route.PUBLIC;
        return Route.USER;
    }
}
