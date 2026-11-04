package com.onedata.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TableDdlResponse {

    private String kind = "table_ddl";
    private String database;

    @JsonProperty("table_name")
    private String tableName;

    @JsonProperty("table_id")
    private Long tableId;

    @JsonProperty("cluster_id")
    private Long clusterId;

    @JsonProperty("cluster_name")
    private String clusterName;

    private String engine;

    @JsonProperty("source_type")
    private String sourceType;

    @JsonProperty("resolved_by")
    private String resolvedBy;

    @JsonProperty("table_comment")
    private String tableComment;

    private List<FieldMetadataDto> fields = new ArrayList<>();
    private String ddl;
}
