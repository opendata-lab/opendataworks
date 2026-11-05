package com.onedata.portal.scheduled;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.DorisMetadataSyncService;
import com.onedata.portal.service.MetadataSyncHistoryService;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;



/**
 * 元数据自动同步定时任务（基于数据源配置的 cron）
 *
 * 配置入口：管理员 -> 配置管理 -> 数据源
 * 说明：
 * - autoSync=1 且 status=active 的数据源才会参与调度
 * - lastSyncTime 用于计算下一次触发时间，并避免重复执行
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceMetadataAutoSyncTask {

    private final DorisClusterMapper dorisClusterMapper;
    private final DorisMetadataSyncService dorisMetadataSyncService;
    private final MetadataSyncHistoryService metadataSyncHistoryService;

    /**
     * 每分钟扫描一次启用自动同步的数据源，根据其 cron 判断是否需要执行同步。
     */
    @Scheduled(fixedDelay = 60_000)
    public void scheduleAutoSync() {
        LocalDateTime now = LocalDateTime.now();
        List<DorisCluster> clusters = dorisClusterMapper.selectList(
                new LambdaQueryWrapper<DorisCluster>()
                        .eq(DorisCluster::getAutoSync, 1)
                        .eq(DorisCluster::getStatus, "active")
                        .orderByDesc(DorisCluster::getIsDefault)
                        .orderByAsc(DorisCluster::getClusterName));

        for (DorisCluster cluster : clusters) {
            try {
                handleCluster(cluster, now);
            } catch (Exception e) {
                log.error("Auto sync task failed for datasource id={}, name={}", cluster.getId(),
                        cluster.getClusterName(), e);
            }
        }
    }

    private void handleCluster(DorisCluster cluster, LocalDateTime now) {
        if (cluster == null || cluster.getId() == null) {
            return;
        }
        if (!StringUtils.hasText(cluster.getSyncCron())) {
            log.warn("Datasource autoSync enabled but syncCron is empty, id={}, name={}", cluster.getId(),
                    cluster.getClusterName());
            return;
        }

        CronExpression cron;
        try {
            cron = CronExpression.parse(cluster.getSyncCron().trim());
        } catch (Exception e) {
            log.error("Invalid syncCron for datasource id={}, name={}, cron={}", cluster.getId(),
                    cluster.getClusterName(), cluster.getSyncCron(), e);
            return;
        }

        LocalDateTime lastSyncTime = cluster.getLastSyncTime();
        if (lastSyncTime == null) {
            // 兼容历史数据：未初始化 lastSyncTime 时，以当前时间作为调度基准，避免“补跑”导致立刻触发。
            updateLastSyncTime(cluster.getId(), now);
            log.info("Initialized lastSyncTime for datasource id={}, name={} to {}", cluster.getId(),
                    cluster.getClusterName(), now);
            return;
        }

        LocalDateTime next = cron.next(lastSyncTime);
        if (next == null || next.isAfter(now)) {
            return;
        }

        log.info("Auto metadata sync triggered, datasource id={}, name={}, type={}, cron={}, lastSyncTime={}, next={}",
                cluster.getId(), cluster.getClusterName(), cluster.getSourceType(), cluster.getSyncCron(), lastSyncTime,
                next);

        updateLastSyncTime(cluster.getId(), now);
        LocalDateTime syncStartedAt = LocalDateTime.now();
        DorisMetadataSyncService.SyncResult syncResult = null;
        try {
            syncResult = dorisMetadataSyncService.syncAllMetadata(cluster.getId());
            log.info("Auto metadata sync finished, datasource id={}, name={}", cluster.getId(), cluster.getClusterName());
        } catch (Exception e) {
            syncResult = new DorisMetadataSyncService.SyncResult();
            syncResult.addError("自动同步失败: " + e.getMessage());
            log.error("Auto metadata sync failed, datasource id={}, name={}", cluster.getId(), cluster.getClusterName(),
                    e);
        } finally {
            try {
                metadataSyncHistoryService.record(cluster, "auto", "all", null, syncStartedAt, syncResult);
            } catch (Exception historyEx) {
                log.error("Failed to record auto metadata sync history, datasource id={}, name={}",
                        cluster.getId(), cluster.getClusterName(), historyEx);
            }
        }
    }

    private void updateLastSyncTime(Long id, LocalDateTime time) {
        dorisClusterMapper.update(null, new LambdaUpdateWrapper<DorisCluster>()
                .eq(DorisCluster::getId, id)
                .set(DorisCluster::getLastSyncTime, time));
    }
}
