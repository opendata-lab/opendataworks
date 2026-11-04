package com.onedata.portal.dto;

import java.util.List;
import java.util.Map;
import lombok.Data;



/**
 * 单个 SQL 结果集
 */
@Data
public class SqlQueryResultSet {

    /**
     * 结果集序号（从 1 开始）
     */
    private Integer index;

    /**
     * 原始语句序号（从 1 开始）
     */
    private Integer statementIndex;

    /**
     * 语句状态（SUCCESS/BLOCKED/ERROR/SKIPPED）
     */
    private String status;

    /**
     * 结果类型（RESULT_SET/UPDATE_COUNT/BLOCKED/ERROR/SKIPPED）
     */
    private String resultType;

    /**
     * 列名
     */
    private List<String> columns;

    /**
     * 数据行（预览数据）
     */
    private List<Map<String, Object>> rows;

    /**
     * 返回的行数
     */
    private Integer previewRowCount;

    /**
     * 是否还有更多数据未返回
     */
    private boolean hasMore;

    /**
     * 非结果集语句的受影响行数
     */
    private Long affectedRows;

    /**
     * 语句提示信息（成功/阻断/错误原因）
     */
    private String message;

    /**
     * SQL 摘要
     */
    private String sqlSnippet;

    /**
     * 语句耗时（毫秒）
     */
    private Long durationMs;
}
