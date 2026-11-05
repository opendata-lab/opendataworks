package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;
import lombok.Data;



/**
 * MinIO 环境配置
 */
@Data
@TableName("minio_config")
public class MinioConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String configName;

    private String endpoint;

    private String region;

    private String accessKey;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String secretKey;

    private Integer usePathStyle;

    private String description;

    private Integer isDefault;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}

