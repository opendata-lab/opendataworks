package com.onedata.portal.dto.backup;

import lombok.Data;


/**
 * 快照恢复请求
 */
@Data
public class SchemaBackupRestoreRequest {

    private String snapshotName;
    private String backupTimestamp;
    private String tableName;
}

