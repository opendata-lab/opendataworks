package com.onedata.portal.agentapi.scope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

public final class AgentDataScopeContext {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final ThreadLocal<String> ENCODED_SCOPE = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private AgentDataScopeContext() {
    }

    public static void setEncodedScope(String encodedScope) {
        ACTIVE.set(true);
        ENCODED_SCOPE.set(encodedScope == null ? "" : encodedScope.trim());
    }

    public static boolean isActive() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    public static void clear() {
        ENCODED_SCOPE.remove();
        ACTIVE.remove();
    }

    public static boolean isDatabaseNameAllowed(String database) {
        if (!isActive()) {
            return true;
        }
        if (!StringUtils.hasText(database)) {
            return false;
        }
        String normalizedDatabase = database.trim();
        for (DataScopeItem item : currentScopes()) {
            if (normalizedDatabase.equals(item.getDatabase())) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAllowed(Long clusterId, String database) {
        if (!isActive()) {
            return true;
        }
        if (!StringUtils.hasText(database)) {
            return false;
        }
        String normalizedDatabase = database.trim();
        for (DataScopeItem item : currentScopes()) {
            if (!normalizedDatabase.equals(item.getDatabase())) {
                continue;
            }
            if (clusterId == null && item.getClusterId() == null) {
                return true;
            }
            if (clusterId != null && Objects.equals(clusterId, item.getClusterId())) {
                return true;
            }
        }
        return false;
    }

    public static void requireDatabaseNameAllowed(String database) {
        if (!isDatabaseNameAllowed(database)) {
            throw new IllegalArgumentException("数据范围限制: 未授权访问 database `" + trimToEmpty(database) + "`");
        }
    }

    public static void requireAllowed(Long clusterId, String database) {
        if (!isAllowed(clusterId, database)) {
            throw new IllegalArgumentException("数据范围限制: 未授权访问 database `" + trimToEmpty(database) + "`");
        }
    }

    public static void requireSqlSchemaAllowed(String schema) {
        if (!isDatabaseNameAllowed(schema)) {
            throw new IllegalArgumentException("数据范围限制: SQL 引用了未授权 schema `" + trimToEmpty(schema) + "`");
        }
    }

    public static List<String> allowedDatabases() {
        if (!isActive()) {
            return Collections.emptyList();
        }
        List<String> databases = new ArrayList<>();
        for (DataScopeItem item : currentScopes()) {
            if (StringUtils.hasText(item.getDatabase()) && !databases.contains(item.getDatabase())) {
                databases.add(item.getDatabase());
            }
        }
        return databases;
    }

    private static List<DataScopeItem> currentScopes() {
        String encoded = ENCODED_SCOPE.get();
        if (!StringUtils.hasText(encoded)) {
            return Collections.emptyList();
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(encoded.trim()));
            JsonNode root = OBJECT_MAPPER.readTree(new String(decoded, StandardCharsets.UTF_8));
            JsonNode scopes = root.path("allowed_scopes");
            if (!scopes.isArray()) {
                return Collections.emptyList();
            }
            List<DataScopeItem> result = new ArrayList<>();
            for (JsonNode scope : scopes) {
                String database = scope.path("database").asText("");
                if (!StringUtils.hasText(database)) {
                    continue;
                }
                DataScopeItem item = new DataScopeItem();
                item.setDatabase(database.trim());
                item.setClusterId(scope.hasNonNull("cluster_id") ? scope.path("cluster_id").asLong() : null);
                item.setSourceType(scope.path("source_type").asText(""));
                result.add(item);
            }
            return result;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 0) {
            return value;
        }
        StringBuilder builder = new StringBuilder(value);
        for (int i = remainder; i < 4; i++) {
            builder.append('=');
        }
        return builder.toString();
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class DataScopeItem {
        private Long clusterId;
        private String sourceType;
        private String database;

        Long getClusterId() {
            return clusterId;
        }

        void setClusterId(Long clusterId) {
            this.clusterId = clusterId;
        }

        String getDatabase() {
            return database;
        }

        void setDatabase(String database) {
            this.database = database;
        }

        void setSourceType(String sourceType) {
            this.sourceType = sourceType;
        }
    }
}
