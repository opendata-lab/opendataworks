package com.onedata.portal.service.datasource.core;

import com.onedata.portal.service.datasource.driver.JdbcDriverRegistry;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.datasource.model.DatasourceTypeDefinition;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import com.onedata.portal.service.datasource.spi.DatasourceDdlReader;
import com.onedata.portal.service.table.TableEngineHandler;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DatasourceAdapterRegistry {

    private final Map<String, DatasourceAdapter> adapters = new LinkedHashMap<>();
    private final JdbcDriverRegistry drivers;

    public DatasourceAdapterRegistry(List<DatasourceAdapter> implementations, JdbcDriverRegistry drivers) {
        this.drivers = drivers;
        // 描述列表的顺序决定前端类型下拉和新建时的默认类型，按适配器的 @Order 固定，不依赖 Bean 扫描顺序。
        List<DatasourceAdapter> ordered = new ArrayList<>(implementations);
        AnnotationAwareOrderComparator.sort(ordered);
        for (DatasourceAdapter adapter : ordered) {
            DatasourceDescriptor descriptor = adapter.descriptor();
            String key = normalize(descriptor.getType());
            if (!key.equals(descriptor.getType()) || descriptor.getSchemaVersion() != 1
                    || descriptor.getDefaultPort() < 1 || descriptor.getConnectionFields() == null
                    || adapter.metadata() == null || adapter.dialect() == null) {
                throw new IllegalStateException("INVALID_DATASOURCE_DESCRIPTOR: " + key);
            }
            descriptor.getCapabilities().keySet().forEach(DatasourceCapabilities::requireKnown);
            validateFields(descriptor);
            if (descriptor.supports("ddlExport") != (adapter instanceof DatasourceDdlReader)) {
                throw new IllegalStateException("DDL capability requires reader: " + key);
            }
            if (adapters.putIfAbsent(key, adapter) != null) {
                throw new IllegalStateException("Duplicate datasource type: " + key);
            }
        }
    }

    public void validateEditHandlers(List<TableEngineHandler> handlers) {
        for (TableEngineHandler handler : handlers) {
            require(handler.sourceType());
        }
        for (DatasourceAdapter adapter : adapters.values()) {
            DatasourceDescriptor descriptor = adapter.descriptor();
            if (descriptor.supports("structureEdit")
                    && handlers.stream().noneMatch(handler -> normalize(handler.sourceType()).equals(descriptor.getType()))) {
                throw new IllegalStateException("Structure edit capability requires handler: " + descriptor.getType());
            }
        }
    }

    public static String normalize(String type) {
        // 兼容历史记录和旧请求：缺省 sourceType 表示 DORIS。
        return type == null || type.trim().isEmpty() ? "DORIS" : type.trim().toUpperCase(Locale.ROOT);
    }

    public DatasourceAdapter require(String type) {
        DatasourceAdapter adapter = adapters.get(normalize(type));
        if (adapter == null) {
            throw new IllegalArgumentException("UNSUPPORTED_DATASOURCE_TYPE: " + type);
        }
        return adapter;
    }

    public DatasourceTypeDefinition definition(String type) {
        DatasourceDescriptor descriptor = require(type).descriptor();
        return new DatasourceTypeDefinition(descriptor, drivers.status(descriptor.getDriverClass()));
    }

    public List<DatasourceTypeDefinition> definitions() {
        return adapters.keySet().stream().map(this::definition).collect(Collectors.toList());
    }

    public void validate(String type, ConnectionConfig config) {
        DatasourceAdapter adapter = require(type);
        Set<String> allowed = adapter.descriptor().getConnectionFields().stream()
                .filter(field -> !BASE_FIELDS.contains(field.getKey()))
                .map(DatasourceDescriptor.ConnectionField::getKey).collect(Collectors.toSet());
        if (!allowed.containsAll(config.getOptions().keySet())) {
            throw new IllegalArgumentException("INVALID_CONNECTION_CONFIG: 未声明的连接选项");
        }
        for (DatasourceDescriptor.ConnectionField field : adapter.descriptor().getConnectionFields()) {
            Object value;
            switch (field.getKey()) {
                case "feHost":
                    value = config.getHost();
                    break;
                case "fePort":
                    value = config.getPort();
                    break;
                case "username":
                    value = config.getUsername();
                    break;
                case "databaseName":
                    value = config.getDatabaseName();
                    break;
                case "password":
                    // 凭据校验仍由既有服务负责。
                    continue;
                default:
                    value = config.getOptions().get(field.getKey());
                    validateOption(field, value);
            }
            if (field.isRequired() && (value == null || value.toString().trim().isEmpty())) {
                throw new IllegalArgumentException("INVALID_CONNECTION_CONFIG: " + field.getLabel() + "不能为空");
            }
        }
        if (config.getPort() == null || config.getPort() < 1 || config.getPort() > 65535) {
            throw new IllegalArgumentException("INVALID_CONNECTION_CONFIG: 端口范围为 1-65535");
        }
        adapter.validate(config);
    }

    private static final Set<String> BASE_FIELDS = new HashSet<>(Arrays.asList(
            "feHost", "fePort", "databaseName", "username", "password"));

    private static void validateFields(DatasourceDescriptor descriptor) {
        Set<String> keys = new HashSet<>();
        for (DatasourceDescriptor.ConnectionField field : descriptor.getConnectionFields()) {
            String key = field.getKey();
            if (key == null || !key.matches("[a-zA-Z][a-zA-Z0-9_]{0,63}") || !keys.add(key)) {
                throw new IllegalStateException("INVALID_CONNECTION_FIELD: " + key);
            }
            if (!BASE_FIELDS.contains(key)) {
                String reserved = key.toLowerCase(Locale.ROOT);
                if (Arrays.asList("id", "sourcetype", "clustername", "status", "autosync", "synccron",
                        "isdefault", "lastsynctime", "createdat", "updatedat", "deleted", "connectionoptions",
                        "typedefinition", "constructor", "prototype").contains(reserved)
                        || reserved.contains("url") || reserved.contains("readonly") || reserved.contains("driver")
                        || reserved.contains("socket") || reserved.contains("init") || reserved.contains("params")
                        || reserved.equals("properties") || reserved.equals("options")
                        || !Arrays.asList("text", "password", "number", "switch").contains(field.getControl())
                        || ("password".equals(field.getControl()) && field.getDefaultValue() != null
                            && !field.getDefaultValue().toString().isEmpty())) {
                    throw new IllegalStateException("UNSAFE_CONNECTION_FIELD: " + key);
                }
            }
        }
    }

    private static void validateOption(DatasourceDescriptor.ConnectionField field, Object value) {
        if (value == null) {
            return;
        }
        boolean valid;
        switch (field.getControl()) {
            case "number":
                valid = value instanceof Number && Double.isFinite(((Number) value).doubleValue());
                break;
            case "switch":
                valid = value instanceof Boolean;
                break;
            case "password":
                valid = value instanceof String && ((String) value).length() <= 4096;
                break;
            default:
                // 选项是语义值，不能作为任意 JDBC 参数片段使用；适配器再做领域校验。
                valid = value instanceof String && ((String) value).matches("[\\p{L}\\p{N}_ .:/-]{0,256}");
        }
        if (!valid) {
            throw new IllegalArgumentException("INVALID_CONNECTION_CONFIG: " + field.getLabel() + "格式不合法");
        }
    }

    public void requireDriver(DatasourceAdapter adapter) {
        drivers.requireAvailable(adapter.descriptor().getDriverClass());
    }

    public void requireCapability(String type, String name) {
        DatasourceCapabilities.requireKnown(name);
        DatasourceDescriptor descriptor = require(type).descriptor();
        if (!descriptor.supports(name)) {
            DatasourceDescriptor.Capability capability = descriptor.getCapabilities().getOrDefault(name, DatasourceCapabilities.defaults().get(name));
            throw new UnsupportedOperationException(
                    "UNSUPPORTED_CAPABILITY: " + (capability == null ? name : capability.getReason()));
        }
    }
}
