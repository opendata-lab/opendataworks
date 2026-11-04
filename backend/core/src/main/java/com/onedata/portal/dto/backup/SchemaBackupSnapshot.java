package com.onedata.portal.dto.backup;

import lombok.Data;


/**
 * Doris 快照信息
 */
@Data
public class SchemaBackupSnapshot {

    private String snapshotName;
    private String backupTimestamp;
    private String status;
    private String databaseName;
    private String details;
}

