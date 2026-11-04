package com.onedata.portal.mcp;

import com.onedata.auth.context.*;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.WebMvcStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;

import org.springframework.context.annotation.*;
import org.springframework.web.servlet.function.*;

import java.util.*;

@Configuration
public class McpServerConfiguration {
    @Bean
    RouterFunction<ServerResponse> mcpRoutes(
            McpToolSchemaRegistry schemas, McpToolHandlers handlers) {
        // Both concrete paths reach the same handlers without POST redirects.
        RouterFunction<ServerResponse> routes = null;
        for (String path : List.of("/mcp", "/mcp/")) {
            var transport =
                    WebMvcStatelessServerTransport.builder()
                            .messageEndpoint(path)
                            .contextExtractor(
                                    request -> {
                                        CurrentPrincipal principal =
                                                (CurrentPrincipal)
                                                        request.servletRequest()
                                                                .getAttribute(
                                                                        CurrentPrincipal.class
                                                                                .getName());
                                        return McpTransportContext.create(
                                                Map.of("principal", principal));
                                    })
                            .build();
            var server = McpServer.sync(transport).serverInfo("opendataworks", "1.5.0");
            for (var definition : schemas.tools()) {
                String name = definition.path("name").asText();
                boolean read = definition.path("readOnly").asBoolean();
                var tool =
                        McpSchema.Tool.builder()
                                .name(name)
                                .description(definition.path("description").asText())
                                .inputSchema(
                                        McpJsonDefaults.getMapper(),
                                        definition.get("inputSchema").toString())
                                .annotations(
                                        new McpSchema.ToolAnnotations(
                                                name,
                                                read,
                                                definition.path("destructive").asBoolean(),
                                                read,
                                                true,
                                                false))
                                .build();
                server.toolCall(
                        tool,
                        (context, request) ->
                                PrincipalContextHolder.runWith(
                                        (CurrentPrincipal) context.get("principal"),
                                        () -> handlers.call(name, request.arguments())));
            }
            server.build();
            var transportRoutes =
                    transport
                            .getRouterFunction()
                            .andRoute(
                                    RequestPredicates.DELETE(path),
                                    request -> ServerResponse.status(405).build());
            routes = routes == null ? transportRoutes : routes.and(transportRoutes);
        }
        return routes;
    }
}
