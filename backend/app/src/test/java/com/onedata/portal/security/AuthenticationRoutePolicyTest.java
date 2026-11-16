package com.onedata.portal.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import java.util.List;

class AuthenticationRoutePolicyTest {

    private final AuthenticationRoutePolicy policy = new AuthenticationRoutePolicy();

    @Test
    void classifiesOAuthAndAuthOptionsEndpointsAsPublic() {
        for (String path :
                List.of(
                        "/auth/login",
                        "/auth/logout",
                        "/auth/options",
                        "/oauth2/authorization/sso",
                        "/oauth2/authorization/corp-idp",
                        "/login/oauth2/code/sso",
                        "/login/oauth2/code/corp-idp")) {
            assertEquals(AuthenticationRoutePolicy.Route.PUBLIC, policy.classify(path), path);
        }
    }

    @Test
    void doesNotExposeUnrelatedPathsWithSimilarPrefixes() {
        for (String path :
                List.of(
                        "/auth/me",
                        "/auth/password",
                        "/auth/options/extra",
                        "/oauth2/authorization",
                        "/oauth2/authorization-other",
                        "/login/oauth2/code",
                        "/login/oauth2/code-other",
                        "/v1/tables")) {
            assertEquals(AuthenticationRoutePolicy.Route.USER, policy.classify(path), path);
        }
    }

    @Test
    void keepsMcpRoutesAsService() {
        for (String path : List.of("/mcp", "/mcp/", "/mcp/messages")) {
            assertEquals(AuthenticationRoutePolicy.Route.SERVICE, policy.classify(path), path);
        }
    }
}
