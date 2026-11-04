package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.*;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * 业务域配置实体
 */
@Data
@TableName("business_domain")
public class BusinessDomain {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String domainCode;

    private String domainName;

    private String description;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
