package com.onedata.portal.agentapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.config.AgentApiAuthInterceptor;
import com.onedata.portal.agentapi.config.AgentApiProperties;
import com.onedata.portal.agentapi.controller.AgentQueryController;
import com.onedata.portal.agentapi.dto.AgentReadQueryResponse;
import com.onedata.portal.agentapi.service.AgentQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentQueryControllerTest {

    @Mock
    private AgentQueryService agentQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentApiProperties properties = new AgentApiProperties();
        properties.setServiceToken("test-token");
        properties.setRequirePrivateNetwork(false);
        AgentApiAuthInterceptor interceptor = new AgentApiAuthInterceptor(properties, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentQueryController(agentQueryService))
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void readDelegatesToServiceWhenTokenMatches() throws Exception {
        AgentReadQueryResponse response = new AgentReadQueryResponse();
        response.setDatabase("opendataworks");
        response.setEngine("mysql");
        response.setRowCount(1);
        when(agentQueryService.readQuery(any())).thenReturn(response);

        mockMvc.perform(post("/v1/ai/query/read")
                        .header("X-Agent-Service-Token", "test-token")
                        .header("X-Agent-User-Id", "local:tester").header("X-Agent-Id", "agent_test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"database\":\"opendataworks\",\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("query_result"))
                .andExpect(jsonPath("$.database").value("opendataworks"))
                .andExpect(jsonPath("$.engine").value("mysql"))
                .andExpect(jsonPath("$.row_count").value(1));

        verify(agentQueryService).readQuery(any());
    }

    @Test
    void readRejectsMissingToken() throws Exception {
        mockMvc.perform(post("/v1/ai/query/read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"database\":\"opendataworks\",\"sql\":\"SELECT 1\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("agent api token 无效"));
    }
}
