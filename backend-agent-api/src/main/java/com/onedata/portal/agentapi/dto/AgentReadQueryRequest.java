package com.onedata.portal.agentapi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class AgentReadQueryRequest {

    @NotBlank(message = "database 不能为空")
    private String database;

    @NotBlank(message = "sql 不能为空")
    private String sql;

    private String preferredEngine;

    private Integer limit;

    private Integer timeoutSeconds;

    /**
     * 导出模式：由本地导出脚本设置。结果写盘、不进模型上下文，因此跳过结果字节守卫
     * （仍受行数上限约束）。普通查询保持守卫不变。
     */
    @JsonProperty("for_export")
    private Boolean forExport;
}
