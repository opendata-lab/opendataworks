package com.onedata.portal.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import lombok.Data;



/**
 * SQL 查询响应
 */
@Data
public class SqlQueryResponse {

    /**
     * 多结果集（多语句执行）
     * - index 从 1 开始，对齐 Navicat 的 Result 1/2/3...
     */
    private List<SqlQueryResultSet> resultSets;

    /**
     * 结果集数量
     */
    private Integer resultSetCount;

    /**
     * 是否已被 Stop 终止
     */
    private boolean cancelled;

    /**
     * 执行信息（可用于 Message 面板）
     */
    private String message;

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
     * 查询耗时（毫秒）
     */
    private Long durationMs;

    /**
     * 历史记录ID
     */
    private Long historyId;

    /**
     * 执行时间
     */
    private LocalDateTime executedAt;
}
