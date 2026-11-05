package com.onedata.portal.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class TableInspectionResponse {
    private String kind = "metadata_snapshot";
    private String database;
    private String table;
    private String keyword;

    @JsonProperty("table_count")
    private int tableCount;

    private List<TableMetadataDto> tables = new ArrayList<>();
    private List<LineageRecordDto> lineage = new ArrayList<>();
    private String error;
}
