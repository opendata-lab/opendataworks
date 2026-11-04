package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 表与任务关联关系实体
 */
@Data
@TableName("table_task_relation")
public class TableTaskRelation {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tableId;

    private Long taskId;

    private String relationType; // read, write

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
