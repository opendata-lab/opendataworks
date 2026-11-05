package com.onedata.portal.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * Doris 审计访问统计增量同步配置。
 */
@Data
@Component
@ConfigurationProperties(prefix = "doris.audit-access-sync")
public class DorisAuditAccessSyncProperties {

    private boolean enabled = true;
    private long fixedDelayMs = 600_000L;
    private int safetyLagMinutes = 2;
    private int overlapMinutes = 10;
    private int batchSize = 5_000;
    private int summaryRetentionDays = 400;
    private int processedEventRetentionDays = 7;
}
