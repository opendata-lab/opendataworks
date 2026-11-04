package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 工作流版本快照
 */
@Data
@TableName("workflow_version")
public class WorkflowVersion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private Integer versionNo;

    private String structureSnapshot;

    private String changeSummary;

    private String triggerSource;

    private String createdBy;

    /**
     * 快照结构版本，1=legacy，2=canonical
     */
    private Integer snapshotSchemaVersion;

    /**
     * 回退来源版本ID（仅回退产生的新版本会记录）
     */
    private Long rollbackFromVersionId;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
