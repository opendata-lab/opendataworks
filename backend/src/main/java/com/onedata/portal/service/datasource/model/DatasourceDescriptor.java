package com.onedata.portal.service.datasource.model;

import lombok.Value;

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
