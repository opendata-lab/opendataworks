package com.onedata.portal.service.freshness;

import java.time.LocalDateTime;


/**
 * 单次新鲜度检查结果（领域对象）。既用于持久化 {@code table_freshness_result}，
 * 也供巡检 handler 将非 {@code pass} 结果映射为问题。
 */
public final class FreshnessCheckResult {

    public static final String STATUS_PASS = "pass";
    public static final String STATUS_WARN = "warn";
    public static final String STATUS_ERROR = "error";
    public static final String STATUS_RUNTIME_ERROR = "runtime_error";

    public static final String REASON_NEVER_LOADED = "never_loaded";

    private Long tableId;
    private Long clusterId;
    private String dbName;
    private String tableName;
    private String mode;
    private String status;
    private String reason;
    private LocalDateTime maxLoadedAt;
    private LocalDateTime snapshottedAt;
    private Long ageSeconds;
    private Long warnAfterSeconds;
    private Long errorAfterSeconds;
    private String errorMessage;

    public boolean isPass() {
        return STATUS_PASS.equals(status);
    }

    public Long getTableId() {
        return tableId;
    }

    public void setTableId(Long tableId) {
        this.tableId = tableId;
    }

    public Long getClusterId() {
        return clusterId;
    }

    public void setClusterId(Long clusterId) {
        this.clusterId = clusterId;
    }

    public String getDbName() {
        return dbName;
    }

    public void setDbName(String dbName) {
        this.dbName = dbName;
    }

    public String getTableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getMaxLoadedAt() {
        return maxLoadedAt;
    }

    public void setMaxLoadedAt(LocalDateTime maxLoadedAt) {
        this.maxLoadedAt = maxLoadedAt;
    }

    public LocalDateTime getSnapshottedAt() {
        return snapshottedAt;
    }

    public void setSnapshottedAt(LocalDateTime snapshottedAt) {
        this.snapshottedAt = snapshottedAt;
    }

    public Long getAgeSeconds() {
        return ageSeconds;
    }

    public void setAgeSeconds(Long ageSeconds) {
        this.ageSeconds = ageSeconds;
    }

    public Long getWarnAfterSeconds() {
        return warnAfterSeconds;
    }

    public void setWarnAfterSeconds(Long warnAfterSeconds) {
        this.warnAfterSeconds = warnAfterSeconds;
    }

    public Long getErrorAfterSeconds() {
        return errorAfterSeconds;
    }

    public void setErrorAfterSeconds(Long errorAfterSeconds) {
        this.errorAfterSeconds = errorAfterSeconds;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
