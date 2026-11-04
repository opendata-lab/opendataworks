package com.onedata.portal.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.Data;



/**
 * SQL 查询请求
 */
@Data
public class SqlQueryRequest {

    /**
     * 客户端查询ID（用于 Stop/终止执行）
     * 建议传入 DataStudio 的 tabId
     */
    private String clientQueryId;

    /**
     * Doris 集群ID，可为空表示使用默认集群
     */
    private Long clusterId;

    /**
     * 目标数据库（必填）
     */
    @NotBlank(message = "数据库不能为空")
    private String database;

    /**
     * 待执行 SQL 文本
     */
    @NotBlank(message = "SQL 不能为空")
    private String sql;

    /**
     * 返回数据的最大行数
     */
    private Integer limit;

    /**
     * 高风险语句确认信息（按 statementIndex 对应）
     */
    private List<SqlConfirmation> confirmations;

    @Data
    public static class SqlConfirmation {
        /**
         * 语句索引（从 1 开始）
         */
        private Integer statementIndex;

        /**
         * 目标对象（表名或库表名）
         */
        private String targetObject;

        /**
         * 用户输入的确认文本
         */
        private String inputText;

        /**
         * 由 analyze 返回的确认令牌
         */
        private String confirmToken;
    }
}
