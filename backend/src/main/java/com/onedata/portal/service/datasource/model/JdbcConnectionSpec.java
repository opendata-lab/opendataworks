package com.onedata.portal.service.datasource.model;

import lombok.Value;

@Value
public class JdbcConnectionSpec {

    String url;
    String driverClass;
}
