package com.onedata.portal.agentapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Data
public class AgentReadQueryResponse {

    private String kind = "query_result";
    private String database;
    private String engine;
    private String sql;
    private Integer limit;

    @JsonProperty("row_count")
    private Integer rowCount;

    @JsonProperty("has_more")
    private Boolean hasMore;

    @JsonProperty("duration_ms")
    private Integer durationMs;

    @JsonProperty("truncated_by_size")
    private Boolean truncatedBySize;

    private String notice;

    private List<Map<String, Object>> rows = new ArrayList<>();
}
