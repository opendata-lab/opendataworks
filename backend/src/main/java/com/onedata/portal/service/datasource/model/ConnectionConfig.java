package com.onedata.portal.service.datasource.model;

import com.onedata.portal.entity.DorisCluster;
import lombok.Value;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 连接坐标与声明的选项；账号密码由调用方完成授权选择。 */
@Value
public class ConnectionConfig {

    String host;
    Integer port;
    String username;
    String databaseName;
    Map<String, Object> options;

    public ConnectionConfig(String host, Integer port, String username, String databaseName) {
        this(host, port, username, databaseName, Collections.emptyMap());
    }

    public ConnectionConfig(String host, Integer port, String username, String databaseName,
            Map<String, Object> options) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.databaseName = databaseName;
        this.options = Collections.unmodifiableMap(new LinkedHashMap<>(
                options == null ? Collections.emptyMap() : options));
    }

    public static ConnectionConfig from(DorisCluster cluster) {
        return new ConnectionConfig(cluster.getFeHost(), cluster.getFePort(), cluster.getUsername(),
                cluster.getDatabaseName(), cluster.getConnectionOptions());
    }
}
