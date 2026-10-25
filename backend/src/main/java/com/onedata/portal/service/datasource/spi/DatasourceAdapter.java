package com.onedata.portal.service.datasource.spi;

import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.datasource.model.JdbcConnectionSpec;

import java.sql.Connection;
import java.sql.SQLException;

public interface DatasourceAdapter {

    DatasourceDescriptor descriptor();

    void validate(ConnectionConfig config);

    JdbcConnectionSpec connectionSpec(ConnectionConfig config, String namespace);

    void initializeSession(Connection connection, String namespace) throws SQLException;

    DatasourceMetadataReader metadata();

    DatasourceSqlDialect dialect();
}
