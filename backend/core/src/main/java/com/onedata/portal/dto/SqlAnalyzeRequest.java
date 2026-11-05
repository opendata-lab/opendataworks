package com.onedata.portal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;



/**
 * SQL 风险分析请求
 */
@Data
public class SqlAnalyzeRequest {

    /**
     * 客户端查询ID（建议传入 DataStudio 的 tabId）
     */
    private String clientQueryId;

    /**
     * Doris 集群ID，可为空表示使用默认集群
     */
    private Long clusterId;

    /**
     * 目标数据库
     */
    @NotBlank(message = "数据库不能为空")
    private String database;

    /**
     * SQL 文本
     */
    @NotBlank(message = "SQL 不能为空")
    private String sql;
}
