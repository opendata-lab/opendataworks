package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.Data;



@Data
@TableName("dolphin_config")
public class DolphinConfig {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String configName;

    private String url;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String token;

    private String projectName;

    private String projectCode;

    private String tenantCode;

    private String workerGroup;

    private String executionType;

    private String description;

    private Boolean isActive;

    private Integer isDefault;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = com.baomidou.mybatisplus.annotation.FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
