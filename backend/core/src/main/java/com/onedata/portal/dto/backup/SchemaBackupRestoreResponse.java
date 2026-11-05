package com.onedata.portal.dto.backup;

import java.time.LocalDateTime;
import lombok.Data;



/**
 * 快照恢复响应
 */
@Data
public class SchemaBackupRestoreResponse {

    private String schemaName;
    private String snapshotName;
    private String backupTimestamp;
    private String tableName;
    private String repositoryName;
    private LocalDateTime submittedAt;
}

