package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 任务与工作流关联
 */
@Data
@TableName("workflow_task_relation")
public class WorkflowTaskRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private Long taskId;

    private String nodeAttrs;

    private Boolean isEntry;

    private Boolean isExit;

    private Long versionId;

    private Integer upstreamTaskCount;

    private Integer downstreamTaskCount;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
