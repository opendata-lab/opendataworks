package com.onedata.portal.dto;

import lombok.Data;


/**
 * SQL 表匹配请求
 */
@Data
public class SqlTableMatchRequest {

    private String sql;

    /**
     * 任务节点类型（可选）
     */
    private String nodeType;
}
