package com.onedata.portal.service.datasource.spi;

import java.sql.Connection;

/** 智能体参考结构；不意味着支持可恢复的 DDL 导出。 */
public interface DatasourceReferenceDdlReader {

    String referenceDdl(Connection connection, String database, String tableName, int timeoutSeconds);
}
