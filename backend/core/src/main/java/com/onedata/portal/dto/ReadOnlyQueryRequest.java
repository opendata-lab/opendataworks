package com.onedata.portal.dto;

import jakarta.validation.constraints.NotBlank;

import lombok.Data;

@Data
public class ReadOnlyQueryRequest {

    @NotBlank(message = "database 不能为空")
    private String database;

    @NotBlank(message = "sql 不能为空")
    private String sql;

    private String preferredEngine;

    private Integer limit;

    private Integer timeoutSeconds;
}
