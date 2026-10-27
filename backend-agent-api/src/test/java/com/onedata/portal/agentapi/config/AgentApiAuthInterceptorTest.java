package com.onedata.portal.agentapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentApiAuthInterceptorTest {

    @Test
    void preHandleAllowsValidPrivateRequest() throws Exception {
        AgentApiProperties properties = new AgentApiProperties();
        properties.setServiceToken("test-token");
        AgentApiAuthInterceptor interceptor = new AgentApiAuthInterceptor(properties, new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/ai/metadata/inspect");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Agent-Service-Token", "test-token");
        request.addHeader("X-Agent-User-Id", "local:alice");
        request.addHeader("X-Agent-Id", "agent-a");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        interceptor.afterCompletion(request, response, this, null);
    }

    @Test
    void preHandleRejectsInvalidToken() throws Exception {
        AgentApiProperties properties = new AgentApiProperties();
        properties.setServiceToken("test-token");
        AgentApiAuthInterceptor interceptor = new AgentApiAuthInterceptor(properties, new ObjectMapper());

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/ai/metadata/inspect");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Agent-Service-Token", "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
    }
}
