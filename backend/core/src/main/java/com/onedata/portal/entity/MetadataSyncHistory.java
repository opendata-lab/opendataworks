package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 元数据同步历史
 */
@Data
@TableName("metadata_sync_history")
public class MetadataSyncHistory {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long clusterId;

    private String clusterName;

    private String sourceType;

    private String triggerType;

    private String scopeType;

    private String scopeTarget;

    private String status;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private Long durationMs;

    private Integer newTables;

    private Integer updatedTables;

    private Integer deletedTables;

    private Integer blockedDeletedTables;

    private Integer inactivatedTables;

    private Integer newFields;

    private Integer updatedFields;

    private Integer deletedFields;

    private Integer errorCount;

    private String errorSummary;

    private String errorDetails;

    private String changeDetails;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
