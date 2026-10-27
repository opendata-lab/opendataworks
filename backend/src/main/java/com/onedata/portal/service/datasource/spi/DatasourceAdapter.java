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

    /**
     * 为只读路径设置连接级只读。默认交给驱动；服务端不一定接受该设置的引擎可以覆盖。
     */
    default void applyReadOnly(Connection connection) throws SQLException {
        connection.setReadOnly(true);
    }

    DatasourceMetadataReader metadata();

    DatasourceSqlDialect dialect();
}
