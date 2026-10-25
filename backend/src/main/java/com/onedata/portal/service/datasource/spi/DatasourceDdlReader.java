package com.onedata.portal.service.datasource.spi;

import java.sql.Connection;
import java.util.Map;

/**
 * 可选的完整 DDL 读取能力，包含既有 Doris 物理属性。
 */
public interface DatasourceDdlReader {

    Map<String, Object> createInfo(Connection connection, String namespace, String table);
}
