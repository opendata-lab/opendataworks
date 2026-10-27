package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * SQL 查询历史记录
 */
@Data
@TableName("data_query_history")
public class DataQueryHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long clusterId;

    private String clusterName;

    private String databaseName;

    @TableField("sql_text")
    private String sqlText;

    private Integer previewRowCount;

    private Long durationMs;

    private Integer hasMore;

    private String resultPreview;

    private String executedBy;

    private String agentId;

    private String checkAccount;

    private String executionAccount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime executedAt;
}
