package com.onedata.portal.service.datasource.model;

import lombok.Value;

/**
 * 非敏感连接坐标；凭据仍由连接服务选择。
 */
@Value
public class ConnectionConfig {

    String host;
    Integer port;
    String username;
    String databaseName;
}
