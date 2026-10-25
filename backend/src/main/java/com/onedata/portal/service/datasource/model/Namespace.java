package com.onedata.portal.service.datasource.model;

import lombok.Value;

@Value
public class Namespace {

    String catalog;
    String schema;
    String name;
}
