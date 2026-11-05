package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;


/**
 * 字段定义实体
 */
@Data
@TableName("data_field")
public class DataField {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long tableId;

    private String fieldName;

    private String fieldType;

    private String fieldComment;

    private Integer isNullable;

    private Integer isPartition;

    private Integer isPrimary;

    private String defaultValue;

    private Integer fieldOrder;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
