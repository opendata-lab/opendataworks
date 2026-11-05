package com.onedata.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TableMetadataDto {

    @JsonProperty("table_id")
    private Long tableId;

    @JsonProperty("cluster_id")
    private Long clusterId;

    @JsonProperty("db_name")
    private String dbName;

    @JsonProperty("table_name")
    private String tableName;

    @JsonProperty("table_comment")
    private String tableComment;

    // Governance attributes, so agents can answer layer/domain/status questions
    // without reading the platform metadata database.
    private String layer;

    @JsonProperty("business_domain")
    private String businessDomain;

    @JsonProperty("data_domain")
    private String dataDomain;

    private String status;

    private List<FieldMetadataDto> fields = new ArrayList<>();
}
