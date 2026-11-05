package com.onedata.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LineageInspectionResponse {
    private String kind = "lineage_snapshot";

    @JsonProperty("db_name")
    private String dbName;

    private String table;

    @JsonProperty("table_id")
    private Long tableId;

    private Integer depth;
    private List<LineageRecordDto> lineage = new ArrayList<>();
    private String error;
}
