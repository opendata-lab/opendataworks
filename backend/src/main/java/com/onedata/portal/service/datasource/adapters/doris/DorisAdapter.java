package com.onedata.portal.service.datasource.adapters.doris;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.service.datasource.adapters.Descriptors;
import com.onedata.portal.service.datasource.adapters.mysql.MysqlProtocolAdapter;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.sql.Connection;

@Component
@Order(10)
public class DorisAdapter extends MysqlProtocolAdapter {

    private final DatasourceDescriptor descriptor;

    public DorisAdapter(DorisJdbcProperties properties) {
        super(properties, true);
        this.descriptor = Descriptors.builder("DORIS", "Doris")
                .agentEngine("doris")
                .defaultPort(9030)
                .namespaceLabel("数据库")
                .identifierQuote("`")
                .editorDialect("mysql")
                .driverClass("com.mysql.cj.jdbc.Driver")
                .connectionDatabaseRequired(false)
                .capability("tabletInspection", true)
                .capability("auditAccess", true)
                .capability("catalog", true)
                .capability("query", true)
                .capability("preview", true)
                .capability("statistics", true)
                .capability("freshnessColumn", true)
                .capability("freshnessCustomSql", true)
                .capability("ddlExport", true)
                .capability("structureEdit", true)
                .capability("partitions", true)
                .capability("distribution", true)
                .capability("replication", true)
                .capability("backup", true)
                .capability("engineProperties", true)
                .capability("freshnessMetadata", true)
                .capability("existingPrimaryKeyEdit", false, "暂不支持在线修改已有字段的主键")
                .capability("newPrimaryKeyEdit", false, "此引擎不支持在线修改主键列")
                .build();
    }

    @Override
    public DatasourceDescriptor descriptor() {
        return descriptor;
    }

    /**
     * MySQL 驱动会把 setReadOnly 转成 SET SESSION TRANSACTION READ ONLY 发给服务端，
     * Doris 是否接受未经验证，因此保持原行为不设置；只读仍由只读 SQL 守卫和账号权限保证。
     */
    @Override
    public void applyReadOnly(Connection connection) {
    }
}
