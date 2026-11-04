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
 * Schema 备份配置
 */
@Data
@TableName("schema_backup_config")
public class SchemaBackupConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long clusterId;

    private String schemaName;

    private String repositoryName;

    private Long minioConfigId;

    private String minioEndpoint;

    private String minioRegion;

    private String minioAccessKey;

    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String minioSecretKey;

    private String minioBucket;

    private String minioBasePath;

    private Integer usePathStyle;

    private Integer backupEnabled;

    private String backupTime;

    private LocalDateTime lastBackupTime;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
