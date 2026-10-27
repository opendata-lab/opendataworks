package com.onedata.portal.controller;

import com.onedata.portal.service.SysConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentSettingsControllerTest {

    @Mock
    private SysConfigService sysConfigService;

    @InjectMocks
    private AgentSettingsController controller;

    @Test
    void returnsBothAssistantSelections() {
        when(sysConfigService.get(AgentSettingsController.METADATA_AGENT_ID_KEY)).thenReturn("agent_meta");
        when(sysConfigService.get(AgentSettingsController.QUERY_AGENT_ID_KEY)).thenReturn("agent_query");

        Map<String, String> settings = controller.getSettings().getData();

        assertEquals("agent_meta", settings.get("metadataAgentId"));
        assertEquals("agent_query", settings.get("queryAgentId"));
    }

    @Test
    void updatesOnlyTheFieldsPresentInTheRequest() {
        controller.updateSettings(Collections.singletonMap("queryAgentId", "agent_query"));

        verify(sysConfigService).set(eq(AgentSettingsController.QUERY_AGENT_ID_KEY), eq("agent_query"), anyString());
        verify(sysConfigService, never()).set(eq(AgentSettingsController.METADATA_AGENT_ID_KEY), anyString(), anyString());
    }

    @Test
    void clearsAFieldSentAsEmpty() {
        Map<String, String> body = new HashMap<>();
        body.put("metadataAgentId", "");
        controller.updateSettings(body);

        verify(sysConfigService).set(eq(AgentSettingsController.METADATA_AGENT_ID_KEY), eq(""), anyString());
        verify(sysConfigService, never()).set(eq(AgentSettingsController.QUERY_AGENT_ID_KEY), anyString(), anyString());
    }
}
