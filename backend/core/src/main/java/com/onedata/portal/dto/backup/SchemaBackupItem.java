package com.onedata.portal.dto.backup;

import java.time.LocalDateTime;
import lombok.Data;



/**
 * Schema 备份列表项
 */
@Data
public class SchemaBackupItem {

    private Long clusterId;
    private String schemaName;
    private Integer hasConfig;

    private String repositoryName;

    private Long minioConfigId;
    private String minioConfigName;

    private String minioEndpoint;
    private String minioRegion;
    private String minioBucket;
    private String minioBasePath;
    private Integer usePathStyle;

    private Integer backupEnabled;
    private String backupTime;
    private LocalDateTime lastBackupTime;
    private String status;

    private Integer hasAccessKey;
    private Integer hasSecretKey;
}
