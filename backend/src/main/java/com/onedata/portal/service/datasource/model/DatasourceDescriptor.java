package com.onedata.portal.service.datasource.model;

import com.onedata.portal.service.datasource.core.DatasourceCapabilities;
import lombok.Value;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Value
public class DatasourceDescriptor {

    int schemaVersion = 1;
    String type;
    String label;
    int defaultPort;
    String namespaceLabel;
    String identifierQuote;
    String editorDialect;
    String iconUrl;
    List<ConnectionField> connectionFields;
    Map<String, Capability> capabilities;
    String driverClass;
    String agentEngine;

    public DatasourceDescriptor(String type, String label, int defaultPort, String namespaceLabel,
            String identifierQuote, String editorDialect, String iconUrl, List<ConnectionField> connectionFields,
            Map<String, Capability> capabilities, String driverClass, String agentEngine) {
        this.type = type;
        this.label = label;
        this.defaultPort = defaultPort;
        this.namespaceLabel = namespaceLabel;
        this.identifierQuote = identifierQuote;
        this.editorDialect = editorDialect;
        this.iconUrl = iconUrl;
        this.connectionFields = Collections.unmodifiableList(new ArrayList<>(connectionFields));
        Map<String, Capability> complete = DatasourceCapabilities.defaults();
        complete.putAll(capabilities);
        this.capabilities = Collections.unmodifiableMap(complete);
        this.driverClass = driverClass;
        this.agentEngine = agentEngine;
    }

    public boolean supports(String name) {
        return capabilities.containsKey(name) && capabilities.get(name).isSupported();
    }

    @Value
    public static class ConnectionField {

        String key;
        String label;
        String control;
        boolean required;
        Object defaultValue;
        String help;
    }

    @Value
    public static class Capability {

        boolean supported;
        String reason;
    }
}
