package com.onedata.portal.agentapi.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentApiConfigurationTest {

    @Test
    void addInterceptorsProtectsAllAgentApiRoutes() throws Exception {
        AgentApiAuthInterceptor interceptor = new AgentApiAuthInterceptor(new AgentApiProperties(), new ObjectMapper(), token -> "9");
        AgentApiConfiguration configuration = new AgentApiConfiguration(interceptor);
        InterceptorRegistry registry = new InterceptorRegistry();

        configuration.addInterceptors(registry);

        List<InterceptorRegistration> registrations = getField(registry, "registrations");
        assertEquals(1, registrations.size());
        assertEquals(Collections.singletonList("/v1/ai/**"), getField(registrations.get(0), "includePatterns"));
    }

    @SuppressWarnings("unchecked")
    private <T> T getField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }
}
