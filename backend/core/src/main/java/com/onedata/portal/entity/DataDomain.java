package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 数据域配置实体
 */
@Data
@TableName("data_domain")
public class DataDomain {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String domainCode;

    private String domainName;

    private String businessDomain;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
