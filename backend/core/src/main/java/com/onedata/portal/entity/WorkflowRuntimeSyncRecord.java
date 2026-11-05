package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 运行态反向同步记录
 */
@Data
@TableName("workflow_runtime_sync_record")
public class WorkflowRuntimeSyncRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private Long projectCode;

    private Long workflowCode;

    private String snapshotHash;

    private String snapshotJson;

    private String diffJson;

    /**
     * 成功同步后关联的版本ID
     */
    private Long versionId;

    /**
     * 运行态定义采集模式（当前固定 export_only）
     */
    private String ingestMode;

    /**
     * 原始运行态定义JSON（导出结果）
     */
    private String rawDefinitionJson;

    /**
     * success / failed
     */
    private String status;

    private String errorCode;

    private String errorMessage;

    private String operator;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
