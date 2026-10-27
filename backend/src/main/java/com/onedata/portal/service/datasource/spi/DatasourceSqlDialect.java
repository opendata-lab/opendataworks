package com.onedata.portal.service.datasource.spi;

public interface DatasourceSqlDialect {

    String quoteIdentifier(String identifier);

    String defaultQuery(String namespace, String table, int limit);
}
