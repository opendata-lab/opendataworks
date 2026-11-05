package com.onedata.portal.service.dolphin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.dolphin.DolphinTaskInstance;
import org.junit.jupiter.api.Test;
import org.springframework.util.MultiValueMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DolphinTaskInstanceCompatibilityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void sendsBothWorkflowInstanceParameterNames() {
        MultiValueMap<String, String> query =
                DolphinOpenApiClient.buildTaskInstanceQuery(1, 100, 9001L, 7001L);

        assertEquals("9001", query.getFirst("processInstanceId"));
        assertEquals("9001", query.getFirst("workflowInstanceId"));
        assertEquals("7001", query.getFirst("taskCode"));
    }

    @Test
    void deserializesDolphin32ProcessInstanceFields() throws Exception {
        DolphinTaskInstance instance = objectMapper.readValue(
                "{\"id\":1,\"taskCode\":2,\"processInstanceId\":3,"
                        + "\"processInstanceName\":\"wf-32\",\"state\":\"SUCCESS\"}",
                DolphinTaskInstance.class);

        assertEquals(3L, instance.getWorkflowInstanceId());
        assertEquals("wf-32", instance.getWorkflowInstanceName());
    }

    @Test
    void deserializesDolphin34WorkflowInstanceFields() throws Exception {
        DolphinTaskInstance instance = objectMapper.readValue(
                "{\"id\":1,\"taskCode\":2,\"workflowInstanceId\":4,"
                        + "\"workflowInstanceName\":\"wf-34\",\"state\":\"FAILURE\","
                        + "\"duration\":\"00:01:05\"}",
                DolphinTaskInstance.class);

        assertEquals(4L, instance.getWorkflowInstanceId());
        assertEquals("wf-34", instance.getWorkflowInstanceName());
        assertEquals("00:01:05", instance.getDuration());
    }
}
