package com.onedata.portal.agentapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.agentapi.config.AgentApiAuthInterceptor;
import com.onedata.portal.agentapi.config.AgentApiProperties;
import com.onedata.portal.agentapi.controller.AgentMetadataController;
import com.onedata.portal.agentapi.dto.AgentDatasourceResolution;
import com.onedata.portal.agentapi.dto.AgentInspectResponse;
import com.onedata.portal.agentapi.dto.AgentTableDdlResponse;
import com.onedata.portal.agentapi.service.AgentMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AgentMetadataControllerTest {

    @Mock
    private AgentMetadataService agentMetadataService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        AgentApiProperties properties = new AgentApiProperties();
        properties.setServiceToken("test-token");
        properties.setRequirePrivateNetwork(false);
        AgentApiAuthInterceptor interceptor = new AgentApiAuthInterceptor(properties, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new AgentMetadataController(agentMetadataService))
                .addInterceptors(interceptor)
                .build();
    }

    @Test
    void inspectReturnsPayloadWhenTokenMatches() throws Exception {
        AgentInspectResponse response = new AgentInspectResponse();
        response.setDatabase("doris_ods");
        response.setTableCount(1);
        when(agentMetadataService.inspect("doris_ods", null, null, 12)).thenReturn(response);

        mockMvc.perform(get("/v1/ai/metadata/inspect")
                        .header("X-Agent-Service-Token", "test-token")
                        .header("X-Agent-User-Id", "local:tester").header("X-Agent-Id", "agent_test")
                        .queryParam("database", "doris_ods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("metadata_snapshot"))
                .andExpect(jsonPath("$.database").value("doris_ods"))
                .andExpect(jsonPath("$.table_count").value(1));

        verify(agentMetadataService).inspect("doris_ods", null, null, 12);
    }

    @Test
    void inspectRejectsMissingToken() throws Exception {
        mockMvc.perform(get("/v1/ai/metadata/inspect"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("agent api token 无效"));
    }

    @Test
    void exportTablesDelegatesToService() throws Exception {
        when(agentMetadataService.exportTables(null))
                .thenReturn(Collections.singletonList(Collections.singletonMap("db_name", "opendataworks")));

        mockMvc.perform(get("/v1/ai/metadata/export")
                        .header("X-Agent-Service-Token", "test-token")
                        .header("X-Agent-User-Id", "local:tester").header("X-Agent-Id", "agent_test")
                        .queryParam("kind", "tables"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].db_name").value("opendataworks"));

        verify(agentMetadataService).exportTables(null);
    }

    @Test
    void resolveDatasourceOmitsSensitiveFieldsFromJson() throws Exception {
        AgentDatasourceResolution response = new AgentDatasourceResolution();
        response.setDatabase("doris_ods");
        response.setEngine("doris");
        response.setHost("doris-fe");
        response.setPort(9030);
        response.setUser("readonly_user");
        response.setPassword("readonly_pass");
        response.setSourceType("DORIS");
        response.setClusterId(12L);
        response.setClusterName("cluster-a");
        response.setResolvedBy("readonly_user");
        when(agentMetadataService.resolveDatasource("doris_ods", null)).thenReturn(response);

        mockMvc.perform(get("/v1/ai/metadata/datasource/resolve")
                        .header("X-Agent-Service-Token", "test-token")
                        .header("X-Agent-User-Id", "local:tester").header("X-Agent-Id", "agent_test")
                        .queryParam("database", "doris_ods"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database").value("doris_ods"))
                .andExpect(jsonPath("$.engine").value("doris"))
                .andExpect(jsonPath("$.cluster_name").value("cluster-a"))
                .andExpect(jsonPath("$.host").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.user").doesNotExist())
                .andExpect(jsonPath("$.port").doesNotExist());

        verify(agentMetadataService).resolveDatasource("doris_ods", null);
    }

    @Test
    void ddlDelegatesToService() throws Exception {
        AgentTableDdlResponse response = new AgentTableDdlResponse();
        response.setDatabase("doris_ods");
        response.setTableName("ads_sales_di");
        response.setDdl("CREATE TABLE ...");
        when(agentMetadataService.ddl("doris_ods", "ads_sales_di", null)).thenReturn(response);

        mockMvc.perform(get("/v1/ai/metadata/ddl")
                        .header("X-Agent-Service-Token", "test-token")
                        .header("X-Agent-User-Id", "local:tester").header("X-Agent-Id", "agent_test")
                        .queryParam("database", "doris_ods")
                        .queryParam("table", "ads_sales_di"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("table_ddl"))
                .andExpect(jsonPath("$.database").value("doris_ods"))
                .andExpect(jsonPath("$.table_name").value("ads_sales_di"))
                .andExpect(jsonPath("$.ddl").value("CREATE TABLE ..."));

        verify(agentMetadataService).ddl("doris_ods", "ads_sales_di", null);
    }
}
