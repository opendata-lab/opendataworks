package com.onedata.portal.scheduled;

import com.onedata.portal.config.DorisAuditAccessSyncProperties;
import com.onedata.portal.service.audit.DorisAuditAccessSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * Doris 审计访问汇总定时任务。
 * <p>
 * 关闭同步只停止读取 Doris 审计表，保留数据清理，避免回退开关同时让保留期失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DorisAuditAccessSyncTask {

    private final DorisAuditAccessSyncProperties properties;
    private final DorisAuditAccessSyncService syncService;

    @Scheduled(fixedDelayString = "${doris.audit-access-sync.fixed-delay-ms:600000}")
    public void syncAuditAccess() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            syncService.syncActiveClusters();
        } catch (Exception e) {
            log.warn("Failed to sync Doris audit access summaries: {}", e.getMessage(), e);
        }
    }

    @Scheduled(cron = "0 45 3 * * ?")
    public void cleanupAuditAccess() {
        try {
            syncService.cleanupExpiredData();
        } catch (Exception e) {
            log.warn("Failed to clean Doris audit access summaries: {}", e.getMessage(), e);
        }
    }
}
