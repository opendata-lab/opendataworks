package com.onedata.portal.service;

import com.onedata.portal.config.DorisJdbcProperties;
import com.onedata.portal.service.datasource.adapters.doris.DorisAdapter;
import com.onedata.portal.service.datasource.adapters.mysql.MysqlAdapter;
import com.onedata.portal.service.datasource.adapters.postgresql.PostgresqlAdapter;
import com.onedata.portal.service.datasource.core.DatasourceAdapterRegistry;
import com.onedata.portal.service.datasource.driver.JdbcDriverRegistry;

import java.util.Arrays;

public final class DatasourceAdapterFixtures {

    public static DatasourceAdapterRegistry registry(DorisJdbcProperties properties) {
        return new DatasourceAdapterRegistry(
                Arrays.asList(new DorisAdapter(properties), new MysqlAdapter(properties), new PostgresqlAdapter()),
                new JdbcDriverRegistry());
    }
}
