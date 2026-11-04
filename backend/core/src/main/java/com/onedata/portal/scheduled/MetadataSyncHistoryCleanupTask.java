package com.onedata.portal.scheduled;

import com.onedata.portal.service.MetadataSyncHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


/**
 * 元数据同步历史清理任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataSyncHistoryCleanupTask {

    private static final int RETENTION_DAYS = 90;

    private final MetadataSyncHistoryService metadataSyncHistoryService;

    /**
     * 每天凌晨 03:30 清理过期历史
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanup() {
        try {
            int deleted = metadataSyncHistoryService.cleanupOldHistory(RETENTION_DAYS);
            if (deleted > 0) {
                log.info("Metadata sync history cleanup completed, deleted={} retentionDays={}", deleted,
                        RETENTION_DAYS);
            }
        } catch (Exception e) {
            log.error("Metadata sync history cleanup failed", e);
        }
    }
}
