package com.onedata.portal.dto.backup;

import java.time.LocalDateTime;
import lombok.Data;



/**
 * 触发备份响应
 */
@Data
public class SchemaBackupTriggerResponse {

    private String schemaName;
    private String repositoryName;
    private String snapshotName;
    private String triggerType;
    private LocalDateTime submittedAt;
}

