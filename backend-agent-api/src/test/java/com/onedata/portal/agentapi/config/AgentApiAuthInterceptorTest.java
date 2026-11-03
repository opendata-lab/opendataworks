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
        AgentApiAuthInterceptor interceptor = new AgentApiAuthInterceptor(properties, new ObjectMapper(), token -> "9");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/ai/metadata/inspect");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Agent-Service-Token", "test-token");
        request.addHeader("X-Agent-User-Id", "local:alice");
        request.addHeader("Authorization", "Bearer test-service-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        interceptor.afterCompletion(request, response, this, null);
    }

    @Test
    void preHandleRejectsInvalidToken() throws Exception {
        AgentApiProperties properties = new AgentApiProperties();
        properties.setServiceToken("test-token");
        AgentApiAuthInterceptor interceptor = new AgentApiAuthInterceptor(properties, new ObjectMapper(), token -> "9");

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/ai/metadata/inspect");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Agent-Service-Token", "wrong-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(request, response, new Object()));
    }

    @Test void tokenIdentityCannotBeReplacedByAgentHeaderAndUserIsOptional() throws Exception {
        AgentApiProperties p=new AgentApiProperties();p.setServiceToken("hop");
        AgentApiAuthInterceptor interceptor=new AgentApiAuthInterceptor(p,new ObjectMapper(),token -> "19");
        MockHttpServletRequest request=new MockHttpServletRequest("POST","/v1/ai/query/read");request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Agent-Service-Token","hop");request.addHeader("Authorization","Bearer account-token");request.addHeader("X-Agent-Id","attacker-picked-account");
        assertTrue(interceptor.preHandle(request,new MockHttpServletResponse(),this));
        org.junit.jupiter.api.Assertions.assertEquals("19",com.onedata.portal.agentapi.identity.AgentIdentityContext.require().getAgentId());
        org.junit.jupiter.api.Assertions.assertNull(com.onedata.portal.agentapi.identity.AgentIdentityContext.require().getUserId());
        interceptor.afterCompletion(request,new MockHttpServletResponse(),this,null);
    }
    @Test void accountTokenRequiredEvenWithValidTransportAndHumanHeader() throws Exception {
        AgentApiProperties p=new AgentApiProperties();p.setServiceToken("hop");
        AgentApiAuthInterceptor interceptor=new AgentApiAuthInterceptor(p,new ObjectMapper(),token -> "19");
        MockHttpServletRequest request=new MockHttpServletRequest("GET","/v1/ai/metadata/inspect");request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Agent-Service-Token","hop");request.addHeader("X-Agent-User-Id","local:admin");
        MockHttpServletResponse response=new MockHttpServletResponse();assertFalse(interceptor.preHandle(request,response,this));org.junit.jupiter.api.Assertions.assertEquals(401,response.getStatus());
    }
    @Test void serviceTokenCannotMutateOrCallUnscopedAgentRoutes() throws Exception {
        AgentApiProperties p=new AgentApiProperties();p.setServiceToken("hop");
        AgentApiAuthInterceptor interceptor=new AgentApiAuthInterceptor(p,new ObjectMapper(),token -> "19");
        for(String uri:new String[]{"/v1/ai/task","/v1/ai/metadata/complete","/v1/ai/workflow/7/publish"}) {
            MockHttpServletRequest r=new MockHttpServletRequest("POST",uri);r.setRemoteAddr("127.0.0.1");r.addHeader("X-Agent-Service-Token","hop");r.addHeader("Authorization","Bearer account-token");r.addHeader("X-Agent-Operator","admin");
            MockHttpServletResponse response=new MockHttpServletResponse();assertFalse(interceptor.preHandle(r,response,this));org.junit.jupiter.api.Assertions.assertEquals(403,response.getStatus());
        }
    }
    @Test void revokedTokenRejectedByAuthoritativeAuthenticator() throws Exception {
        AgentApiProperties p=new AgentApiProperties();p.setServiceToken("hop");
        AgentApiAuthInterceptor interceptor=new AgentApiAuthInterceptor(p,new ObjectMapper(),token -> {throw new IllegalArgumentException("Token revoked");});
        MockHttpServletRequest r=new MockHttpServletRequest("GET","/v1/ai/auth/identity");r.setRemoteAddr("127.0.0.1");r.addHeader("X-Agent-Service-Token","hop");r.addHeader("Authorization","Bearer revoked");
        MockHttpServletResponse response=new MockHttpServletResponse();assertFalse(interceptor.preHandle(r,response,this));org.junit.jupiter.api.Assertions.assertEquals(401,response.getStatus());
    }
}
