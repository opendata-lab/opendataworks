package com.onedata.portal.service.datasource.core;

import com.onedata.portal.service.datasource.model.ConnectionConfig;
import com.onedata.portal.service.datasource.model.JdbcConnectionSpec;
import com.onedata.portal.service.datasource.spi.DatasourceAdapter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/** 所有业务 JDBC 路径共用的驱动检查、连接规格及会话初始化。 */
@Component
@RequiredArgsConstructor
public class DatasourceConnectionFactory {

    private final DatasourceAdapterRegistry registry;

    public Connection open(String type, ConnectionConfig config, String namespace,
            String username, String password, boolean readOnly) throws SQLException {
        DatasourceAdapter adapter = registry.require(type);
        registry.validate(type, config);
        registry.requireDriver(adapter);
        JdbcConnectionSpec spec = adapter.connectionSpec(config, namespace);
        Connection connection = DriverManager.getConnection(spec.getUrl(), username, password);
        try {
            if (readOnly) {
                adapter.applyReadOnly(connection);
            }
            adapter.initializeSession(connection, namespace);
            return connection;
        } catch (SQLException | RuntimeException e) {
            try {
                connection.close();
            } catch (SQLException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
    }
}
