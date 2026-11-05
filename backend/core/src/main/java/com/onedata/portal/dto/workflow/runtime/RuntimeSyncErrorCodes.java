package com.onedata.portal.dto.workflow.runtime;



/**
 * 运行态同步错误码
 */
public final class RuntimeSyncErrorCodes {

    private RuntimeSyncErrorCodes() {
    }

    public static final String UNSUPPORTED_NODE_TYPE = "UNSUPPORTED_NODE_TYPE";
    public static final String SQL_TABLE_AMBIGUOUS = "SQL_TABLE_AMBIGUOUS";
    public static final String SQL_TABLE_UNMATCHED = "SQL_TABLE_UNMATCHED";
    public static final String SQL_LINEAGE_INCOMPLETE = "SQL_LINEAGE_INCOMPLETE";
    public static final String DATASOURCE_NOT_FOUND = "DATASOURCE_NOT_FOUND";
    public static final String DEFINITION_FORMAT_UNSUPPORTED = "DEFINITION_FORMAT_UNSUPPORTED";
    public static final String TASK_CODE_DUPLICATE = "TASK_CODE_DUPLICATE";
    public static final String WORKFLOW_BINDING_CONFLICT = "WORKFLOW_BINDING_CONFLICT";
    public static final String RELATION_DECISION_REQUIRED = "RELATION_DECISION_REQUIRED";
    public static final String RELATION_MISMATCH = "RELATION_MISMATCH";
    public static final String RUNTIME_SYNC_DISABLED = "RUNTIME_SYNC_DISABLED";
    public static final String RUNTIME_WORKFLOW_NOT_FOUND = "RUNTIME_WORKFLOW_NOT_FOUND";
    public static final String SYNC_FAILED = "SYNC_FAILED";
}
