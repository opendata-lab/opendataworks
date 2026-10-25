package com.onedata.portal.service.datasource.core;

import com.onedata.portal.service.datasource.driver.JdbcDriverRegistry;
import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.datasource.model.DatasourceTypeDefinition;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import com.onedata.portal.service.datasource.spi.DatasourceDdlReader;
import com.onedata.portal.service.table.TableEngineHandler;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class DatasourceAdapterRegistry {

    private final Map<String, DatasourceAdapter> adapters = new LinkedHashMap<>();
    private final JdbcDriverRegistry drivers;

    public DatasourceAdapterRegistry(List<DatasourceAdapter> implementations, JdbcDriverRegistry drivers) {
        this.drivers = drivers;
        for (DatasourceAdapter adapter : implementations) {
            DatasourceDescriptor descriptor = adapter.descriptor();
            String key = normalize(descriptor.getType());
            if (!key.equals(descriptor.getType()) || descriptor.getSchemaVersion() != 1
                    || descriptor.getDefaultPort() < 1 || descriptor.getConnectionFields() == null
                    || adapter.metadata() == null || adapter.dialect() == null) {
                throw new IllegalStateException("INVALID_DATASOURCE_DESCRIPTOR: " + key);
            }
            if (descriptor.supports("ddlExport") != (adapter instanceof DatasourceDdlReader)) {
                throw new IllegalStateException("DDL capability requires reader: " + key);
            }
            if (adapters.putIfAbsent(key, adapter) != null) {
                throw new IllegalStateException("Duplicate datasource type: " + key);
            }
        }
    }

    public void validateEditHandlers(List<TableEngineHandler> handlers) {
        for (DatasourceAdapter adapter : adapters.values()) {
            DatasourceDescriptor descriptor = adapter.descriptor();
            if (descriptor.supports("structureEdit")
                    && handlers.stream().noneMatch(handler -> handler.sourceType().name().equals(descriptor.getType()))) {
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
        for (DatasourceDescriptor.ConnectionField field : adapter.descriptor().getConnectionFields()) {
            Object value;
            // 第一阶段只映射现有配置字段，额外连接选项需连同持久化与表单一起设计。
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
                    throw new IllegalStateException("Unsupported connection field: " + field.getKey());
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

    public void requireDriver(DatasourceAdapter adapter) {
        drivers.requireAvailable(adapter.descriptor().getDriverClass());
    }

    public void requireCapability(String type, String name) {
        DatasourceDescriptor descriptor = require(type).descriptor();
        if (!descriptor.supports(name)) {
            DatasourceDescriptor.Capability capability = descriptor.getCapabilities().get(name);
            throw new UnsupportedOperationException(
                    "UNSUPPORTED_CAPABILITY: " + (capability == null ? name : capability.getReason()));
        }
    }
}
