package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 工作流发布记录
 */
@Data
@TableName("workflow_publish_record")
public class WorkflowPublishRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private Long versionId;

    private String targetEngine;

    private Long dolphinConfigId;

    private String operation;

    private String status;

    private Long engineWorkflowCode;

    private String log;

    private String operator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
