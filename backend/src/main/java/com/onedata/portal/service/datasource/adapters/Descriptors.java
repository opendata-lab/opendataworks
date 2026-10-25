package com.onedata.portal.service.datasource.adapters;

import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor.Capability;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor.ConnectionField;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 构造通用描述结构；各数据库的能力与不支持原因由适配器声明。
 */
public final class Descriptors {

    private Descriptors() {
    }

    public static Builder builder(String type, String label) {
        return new Builder(type, label);
    }

    public static final class Builder {

        private final String type;
        private final String label;
        private final Map<String, Capability> capabilities = new LinkedHashMap<>();
        private int defaultPort;
        private String namespaceLabel;
        private String identifierQuote;
        private String editorDialect;
        private String driverClass;
        private boolean connectionDatabaseRequired;

        private Builder(String type, String label) {
            this.type = type;
            this.label = label;
        }

        public Builder defaultPort(int defaultPort) {
            this.defaultPort = defaultPort;
            return this;
        }

        public Builder namespaceLabel(String namespaceLabel) {
            this.namespaceLabel = namespaceLabel;
            return this;
        }

        public Builder identifierQuote(String identifierQuote) {
            this.identifierQuote = identifierQuote;
            return this;
        }

        public Builder editorDialect(String editorDialect) {
            this.editorDialect = editorDialect;
            return this;
        }

        public Builder driverClass(String driverClass) {
            this.driverClass = driverClass;
            return this;
        }

        public Builder connectionDatabaseRequired(boolean connectionDatabaseRequired) {
            this.connectionDatabaseRequired = connectionDatabaseRequired;
            return this;
        }

        public Builder capability(String name, boolean supported) {
            return capability(name, supported, supported ? "" : label + " 暂不支持此操作");
        }

        public Builder capability(String name, boolean supported, String reason) {
            capabilities.put(name, new Capability(supported, reason));
            return this;
        }

        public DatasourceDescriptor build() {
            List<ConnectionField> fields = Arrays.asList(
                    new ConnectionField("feHost", "地址", "text", true, "", ""),
                    new ConnectionField("fePort", "端口", "number", true, defaultPort, ""),
                    new ConnectionField("databaseName", "连接数据库", "text", connectionDatabaseRequired, "",
                            connectionDatabaseRequired ? "同步当前数据库内可访问的 schema" : "可选"),
                    new ConnectionField("username", "用户名", "text", true, "", ""),
                    new ConnectionField("password", "密码", "password", false, "", "编辑时留空表示不修改"));
            // 描述保持不可变，后续复用 builder 不应修改已生成的能力集合。
            return new DatasourceDescriptor(type, label, defaultPort, namespaceLabel, identifierQuote, editorDialect,
                    "/datasource-icons/" + type.toLowerCase(Locale.ROOT) + ".svg",
                    Collections.unmodifiableList(fields),
                    Collections.unmodifiableMap(new LinkedHashMap<>(capabilities)), driverClass);
        }
    }
}
