package com.onedata.portal.agentapi.scope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDataScopeContextTest {

    @AfterEach
    void clearContext() {
        AgentDataScopeContext.clear();
    }

    @Test
    void omittedOrExplicitlyEmptyScopeIsUnrestricted() {
        AgentDataScopeContext.setEncodedScope(null);
        assertFalse(AgentDataScopeContext.isActive());
        assertTrue(AgentDataScopeContext.isDatabaseNameAllowed("ads_user"));

        AgentDataScopeContext.setEncodedScope(encode("{\"allowed_scopes\":[]}"));
        assertFalse(AgentDataScopeContext.isActive());
        assertTrue(AgentDataScopeContext.isDatabaseNameAllowed("ads_user"));
    }

    @Test
    void validNonEmptyScopeAllowsOnlyConfiguredDatabase() {
        AgentDataScopeContext.setEncodedScope(encode(
                "{\"allowed_scopes\":[{\"cluster_id\":3,\"database\":\"ads_user\",\"source_type\":\"DORIS\"}]}"
        ));

        assertTrue(AgentDataScopeContext.isActive());
        assertTrue(AgentDataScopeContext.isAllowed(3L, "ads_user"));
        assertFalse(AgentDataScopeContext.isAllowed(4L, "ads_user"));
        assertFalse(AgentDataScopeContext.isDatabaseNameAllowed("other_db"));
    }

    @Test
    void suppliedMalformedScopeFailsClosed() {
        assertFailsClosed("not-base64");
        assertFailsClosed(encode("not-json"));
        assertFailsClosed(encode("{}"));
        assertFailsClosed(encode("{\"allowed_scopes\":\"all\"}"));
        assertFailsClosed(encode("{\"allowed_scopes\":[{}]}"));
        assertFailsClosed(encode(
                "{\"allowed_scopes\":[{\"cluster_id\":\"not-a-number\",\"database\":\"ads_user\"}]}"
        ));
    }

    private static void assertFailsClosed(String encodedScope) {
        AgentDataScopeContext.setEncodedScope(encodedScope);
        assertTrue(AgentDataScopeContext.isActive());
        assertFalse(AgentDataScopeContext.isDatabaseNameAllowed("ads_user"));
        assertTrue(AgentDataScopeContext.allowedDatabases().isEmpty());
        AgentDataScopeContext.clear();
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
