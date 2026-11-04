package com.onedata.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class LineageRecordDto {
    private Long id;

    @JsonProperty("lineage_type")
    private String lineageType;

    @JsonProperty("upstream_db")
    private String upstreamDb;

    @JsonProperty("upstream_table")
    private String upstreamTable;

    @JsonProperty("downstream_db")
    private String downstreamDb;

    @JsonProperty("downstream_table")
    private String downstreamTable;
}
