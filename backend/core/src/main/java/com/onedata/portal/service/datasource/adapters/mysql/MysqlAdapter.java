package com.onedata.portal.service.datasource.adapters.mysql;

import com.onedata.portal.config.DatasourceJdbcProperties;
import com.onedata.portal.service.datasource.adapters.Descriptors;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.permission.NativePermissionAdapter;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class MysqlAdapter extends MysqlProtocolAdapter {

    private final DatasourceDescriptor descriptor;

    public MysqlAdapter(DatasourceJdbcProperties properties) {
        super(properties, false);
        this.descriptor =
                Descriptors.builder("MYSQL", "MySQL")
                        .agentEngine("mysql")
                        .defaultPort(3306)
                        .namespaceLabel("数据库")
                        .identifierQuote("`")
                        .editorDialect("mysql")
                        .driverClass("com.mysql.cj.jdbc.Driver")
                        .connectionDatabaseRequired(false)
                        .capability("catalog", true)
                        .capability("query", true)
                        .capability("preview", true)
                        .capability("statistics", true)
                        .capability("freshnessColumn", true)
                        .capability("freshnessCustomSql", true)
                        .capability("ddlExport", true)
                        .capability("structureEdit", true)
                        .capability("partitions", false, "MySQL 暂不支持此引擎操作")
                        .capability("distribution", false, "MySQL 暂不支持此引擎操作")
                        .capability("replication", false, "MySQL 暂不支持此引擎操作")
                        .capability("backup", false, "MySQL 暂不支持此引擎操作")
                        .capability("engineProperties", false, "MySQL 暂不支持此引擎操作")
                        .capability("freshnessMetadata", true)
                        .capability("existingPrimaryKeyEdit", false, "暂不支持在线修改已有字段的主键")
                        .capability("newPrimaryKeyEdit", true, "此引擎不支持在线修改主键列")
                        .build();
    }

    @Override
    public DatasourceDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public NativePermissionAdapter permissions() {
        return new MysqlPermissionAdapter();
    }
}
