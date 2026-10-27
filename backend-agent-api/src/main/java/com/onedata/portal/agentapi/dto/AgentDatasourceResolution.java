package com.onedata.portal.agentapi.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Map;

@Data
public class AgentDatasourceResolution {
    private String engine;

    @JsonIgnore
    private Map<String, Object> connectionOptions;
    private String database;

    /** PostgreSQL 连接的 database；database 字段对应其中的 schema。 */
    @JsonIgnore
    private String connectionDatabase;

    @JsonIgnore
    private String host;

    @JsonIgnore
    private Integer port;

    @JsonIgnore
    private String user;

    @JsonIgnore
    private String password;

    @JsonIgnore
    private String checkUser;

    @JsonIgnore
    private String checkPassword;

    @JsonIgnore
    private String userId;

    @JsonIgnore
    private String agentId;

    @JsonProperty("source_type")
    private String sourceType;

    @JsonProperty("cluster_id")
    private Long clusterId;

    @JsonProperty("cluster_name")
    private String clusterName;

    @JsonProperty("resolved_by")
    private String resolvedBy;
}
