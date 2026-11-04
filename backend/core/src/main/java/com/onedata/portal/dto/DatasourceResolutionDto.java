package com.onedata.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class DatasourceResolutionDto {
    private String engine;

    private String database;

    /** PostgreSQL 连接的 database；database 字段对应其中的 schema。 */
    @JsonProperty("source_type")
    private String sourceType;

    @JsonProperty("cluster_id")
    private Long clusterId;

    @JsonProperty("cluster_name")
    private String clusterName;

    @JsonProperty("resolved_by")
    private String resolvedBy;
}
