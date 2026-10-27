package com.onedata.portal.agentapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.config.AgentApiAuthInterceptor;
import com.onedata.portal.agentapi.config.AgentApiProperties;
import com.onedata.portal.agentapi.identity.AgentIdentityContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentIdentityContractTest {
    @AfterEach
    void clear() {
        AgentIdentityContext.clear();
    }

    @Test
    void serviceTokenAndUserAreBothRequiredForOrdinaryQueries() throws Exception {
        AgentApiProperties properties = new AgentApiProperties();
        properties.setServiceToken("service-secret");
        properties.setRequirePrivateNetwork(false);
        AgentApiAuthInterceptor interceptor = new AgentApiAuthInterceptor(properties, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/ai/query/read");
        request.addHeader("X-Agent-Id", "agent-a");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, this));
        assertEquals(401, response.getStatus());
        request.addHeader("X-Agent-Service-Token", "service-secret");
        assertFalse(interceptor.preHandle(request, new MockHttpServletResponse(), this));
        request.addHeader("X-Agent-User-Id", "local:alice");
        assertTrue(interceptor.preHandle(request, new MockHttpServletResponse(), this));
        assertEquals("local:alice", AgentIdentityContext.require().getUserId());
        interceptor.afterCompletion(request, response, this, null);
        assertThrows(IllegalArgumentException.class, AgentIdentityContext::require);
    }

    @Test
    void identityRequiresBothUserAndAgent() {
        assertThrows(IllegalArgumentException.class, () -> AgentIdentityContext.set(null, "agent-a"));
        assertThrows(IllegalArgumentException.class, () -> AgentIdentityContext.set("local:alice", null));
        AgentIdentityContext.set("local:alice", "agent-a");
        assertEquals("agent-a", AgentIdentityContext.require().getAgentId());
    }
}
