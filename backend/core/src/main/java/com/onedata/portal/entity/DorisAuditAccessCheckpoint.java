package com.onedata.portal.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;



@Data
@TableName("doris_audit_access_checkpoint")
public class DorisAuditAccessCheckpoint {

    @TableId(type = IdType.INPUT)
    private Long clusterId;

    private String auditSource;
    private LocalDateTime watermarkTime;
    private String watermarkEventKey;
    private LocalDateTime coverageStart;
    private String syncStatus;
    private LocalDateTime lastSyncedAt;
    private String lastError;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
