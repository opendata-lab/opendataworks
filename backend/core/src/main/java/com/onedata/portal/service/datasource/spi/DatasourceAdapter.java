package com.onedata.portal.service.datasource.spi;

import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.DatasourceDescriptor;
import com.onedata.portal.service.datasource.model.JdbcConnectionSpec;
import com.onedata.portal.service.permission.NativePermissionAdapter;
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

    /** A new native permission adapter for this engine; engines without one do not support it. */
    default NativePermissionAdapter permissions() {
        throw new IllegalArgumentException("不支持该数据源权限管理");
    }
}
