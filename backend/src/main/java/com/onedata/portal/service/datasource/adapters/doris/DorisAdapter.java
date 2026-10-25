package com.onedata.portal.service.datasource.adapters.doris;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.service.datasource.adapters.Descriptors;
import com.onedata.portal.service.datasource.adapters.mysql.MysqlProtocolAdapter;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import org.springframework.stereotype.Component;

@Component
public class DorisAdapter extends MysqlProtocolAdapter {

    public DorisAdapter(DorisJdbcProperties properties) {
        super(properties, true);
    }

    @Override
    public DatasourceDescriptor descriptor() {
        return Descriptors.builder("DORIS", "Doris")
                .defaultPort(9030)
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
                .capability("partitions", true)
                .capability("distribution", true)
                .capability("replication", true)
                .capability("backup", true)
                .capability("dorisProperties", true)
                .capability("freshnessMetadata", true)
                .capability("existingPrimaryKeyEdit", false, "暂不支持在线修改已有字段的主键")
                .capability("newPrimaryKeyEdit", false, "此引擎不支持在线修改主键列")
                .build();
    }
}
