package com.onedata.portal.scheduled;

import com.onedata.portal.entity.SchemaBackupConfig;
import com.onedata.portal.service.SchemaBackupService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;



/**
 * Schema 每日自动备份任务
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SchemaBackupAutoTask {

    private final SchemaBackupService schemaBackupService;

    /**
     * 每分钟扫描一次，满足到点且当天未执行时触发备份。
     */
    @Scheduled(fixedDelay = 60_000)
    public void scheduleSchemaBackup() {
        LocalDateTime now = LocalDateTime.now();
        List<SchemaBackupConfig> configs = schemaBackupService.listEnabledConfigs();
        for (SchemaBackupConfig config : configs) {
            if (config == null) {
                continue;
            }
            try {
                if (!schemaBackupService.shouldRunNow(config, now)) {
                    continue;
                }
                schemaBackupService.triggerBackupByConfig(config, "auto");
                log.info("Auto schema backup submitted, clusterId={}, schema={}, backupTime={}",
                        config.getClusterId(), config.getSchemaName(), config.getBackupTime());
            } catch (Exception e) {
                log.error("Auto schema backup failed, clusterId={}, schema={}",
                        config.getClusterId(), config.getSchemaName(), e);
            }
        }
    }
}

