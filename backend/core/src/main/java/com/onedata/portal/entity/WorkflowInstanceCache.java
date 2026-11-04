package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.util.Date;
import lombok.Data;



/**
 * 最近执行历史缓存
 */
@Data
@TableName("workflow_instance_cache")
public class WorkflowInstanceCache {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long workflowId;

    private Long instanceId;

    private String state;

    private Date startTime;

    private Date endTime;

    /** DolphinScheduler 运行实例上的调度日期 */
    private Date scheduleTime;

    private String triggerType;

    private Long durationMs;

    private String extra;

    @TableField(fill = FieldFill.INSERT)
    private Date createdAt;
}
