package com.onedata.portal.dto.backup;

import lombok.Data;


/**
 * Schema 备份配置请求
 */
@Data
public class SchemaBackupConfigRequest {

    private String repositoryName;
    private Long minioConfigId;
    private String minioEndpoint;
    private String minioRegion;
    private String minioAccessKey;
    private String minioSecretKey;
    private String minioBucket;
    private String minioBasePath;
    private Integer usePathStyle;
    private Integer backupEnabled;
    private String backupTime;
    private String status;
}
