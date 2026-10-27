package com.onedata.portal.service.datasource.adapters;

import com.onedata.portal.service.datasource.spi.DatasourceSqlDialect;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class QuotedLimitDialect implements DatasourceSqlDialect {

    private final String quote;

    @Override
    public String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        return quote + identifier.replace(quote, quote + quote) + quote;
    }

    @Override
    public String defaultQuery(String namespace, String table, int limit) {
        return "SELECT *\nFROM " + quoteIdentifier(namespace) + "." + quoteIdentifier(table)
                + "\nLIMIT " + Math.max(1, Math.min(limit, 1000)) + ";";
    }
}
