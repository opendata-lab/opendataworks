package com.onedata.portal.mcp;

import com.fasterxml.jackson.databind.*;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

/** The published tool schemas are also the only argument validation contract. */
@Component
public class McpToolSchemaRegistry {
    private final List<JsonNode> tools;
    private final Map<String, Map<String, Object>> schemas = new LinkedHashMap<>();
    private final JsonSchemaValidator validator = McpJsonDefaults.getSchemaValidator();
    private final ObjectMapper mapper;

    public McpToolSchemaRegistry(ObjectMapper mapper) throws IOException {
        this.mapper = mapper;
        try (var in = new ClassPathResource("mcp/tools.json").getInputStream()) {
            tools = new ArrayList<>();
            for (JsonNode node : mapper.readTree(in)) {
                tools.add(node);
                schemas.put(
                        node.path("name").asText(),
                        mapper.convertValue(
                                node.get("inputSchema"),
                                new com.fasterxml.jackson.core.type.TypeReference<
                                        Map<String, Object>>() {}));
            }
        }
    }

    public List<JsonNode> tools() {
        return List.copyOf(tools);
    }

    public void validate(String name, JsonNode input) {
        Map<String, Object> schema = schemas.get(name);
        if (schema == null) throw new IllegalArgumentException("未知工具");
        var result = validator.validate(schema, mapper.convertValue(input, Object.class));
        if (!result.valid()) throw new IllegalArgumentException("参数无效: " + result.errorMessage());
    }
}
