package com.onedata.portal.service.dolphin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.service.DolphinConfigService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

@ExtendWith(MockitoExtension.class)
class DolphinOpenApiClientExportCompatibilityTest {

    @Mock private DolphinConfigService dolphinConfigService;

    private HttpServer server;
    private DolphinOpenApiClient client;
    private DolphinConfig targetConfig;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();

        int port = server.getAddress().getPort();
        DolphinConfig config = new DolphinConfig();
        config.setUrl("http://127.0.0.1:" + port);
        config.setToken("test-token");
        targetConfig = config;

        client = new DolphinOpenApiClient(new ObjectMapper(), RestClient.builder());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void exportShouldUseWorkflowDefinitionPathFirstWhenAvailable() {
        AtomicInteger workflowHits = new AtomicInteger();
        AtomicInteger processHits = new AtomicInteger();

        server.createContext(
                "/projects/1/workflow-definition/batch-export",
                jsonHandler(
                        200,
                        "{\"workflowDefinition\":{\"code\":1001,\"name\":\"wf_primary\"},\"taskDefinitionList\":[]}",
                        workflowHits));
        server.createContext(
                "/projects/1/process-definition/batch-export",
                jsonHandler(
                        200,
                        "{\"processDefinition\":{\"code\":1001,\"name\":\"wf_legacy\"},\"taskDefinitionList\":[]}",
                        processHits));

        JsonNode exported = client.exportDefinitionByCode(targetConfig, 1L, 1001L);

        assertNotNull(exported);
        assertEquals(1, workflowHits.get(), "新路径应被调用一次");
        assertEquals(0, processHits.get(), "新路径成功时不应再请求旧路径");
        assertEquals("wf_primary", exported.path("workflowDefinition").path("name").asText());
    }

    @Test
    void exportShouldFallbackToProcessDefinitionPathWhenWorkflowPathFails() {
        AtomicInteger workflowHits = new AtomicInteger();
        AtomicInteger processHits = new AtomicInteger();

        server.createContext(
                "/projects/1/workflow-definition/batch-export",
                jsonHandler(405, "{\"msg\":\"Method Not Allowed\"}", workflowHits));
        server.createContext(
                "/projects/1/process-definition/batch-export",
                jsonHandler(
                        200,
                        "{\"processDefinition\":{\"code\":1002,\"name\":\"wf_legacy\"},\"taskDefinitionList\":[]}",
                        processHits));

        JsonNode exported = client.exportDefinitionByCode(targetConfig, 1L, 1002L);

        assertNotNull(exported);
        assertEquals(1, workflowHits.get(), "新路径应先尝试一次");
        assertEquals(1, processHits.get(), "新路径失败后应回退旧路径");
        assertEquals("wf_legacy", exported.path("processDefinition").path("name").asText());
    }

    @Test
    void exportShouldExposeBothAttemptErrorsWhenBothPathsFail() {
        AtomicInteger workflowHits = new AtomicInteger();
        AtomicInteger processHits = new AtomicInteger();

        server.createContext(
                "/projects/1/workflow-definition/batch-export",
                jsonHandler(405, "{\"msg\":\"Method Not Allowed\"}", workflowHits));
        server.createContext(
                "/projects/1/process-definition/batch-export",
                jsonHandler(500, "{\"msg\":\"Internal Error\"}", processHits));

        RuntimeException ex =
                assertThrows(
                        RuntimeException.class,
                        () -> client.exportDefinitionByCode(targetConfig, 1L, 1003L));

        assertEquals(1, workflowHits.get());
        assertEquals(1, processHits.get());
        assertTrue(
                ex.getMessage().contains("/workflow-definition/batch-export [status=405"),
                ex.getMessage());
        assertTrue(
                ex.getMessage().contains("/process-definition/batch-export [status=500"),
                ex.getMessage());
    }

    private HttpHandler jsonHandler(int status, String body, AtomicInteger counter) {
        return exchange -> {
            try {
                counter.incrementAndGet();
                drainRequest(exchange);
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        };
    }

    private void drainRequest(HttpExchange exchange) throws IOException {
        byte[] buffer = new byte[256];
        while (exchange.getRequestBody().read(buffer) >= 0) {
            // drain request body
        }
    }
}
