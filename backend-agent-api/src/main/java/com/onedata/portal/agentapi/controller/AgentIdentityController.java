package com.onedata.portal.agentapi.controller;

import com.onedata.portal.agentapi.identity.AgentIdentityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Collections;
import java.util.Map;

@RestController
public class AgentIdentityController {
    @GetMapping("/v1/ai/auth/identity")
    public Map<String,String> identity() {
        return Collections.singletonMap("serviceAccountId",AgentIdentityContext.require().getAgentId());
    }
}
