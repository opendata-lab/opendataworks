package com.onedata.portal.scheduled;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.mapper.DorisClusterMapper;
import com.onedata.portal.service.DorisMetadataSyncService;
import com.onedata.portal.service.MetadataSyncHistoryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 元数据统计信息自动同步任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataSourceMetadataStatisticsSyncTask {

    private final DorisClusterMapper dorisClusterMapper;
    private final DorisMetadataSyncService dorisMetadataSyncService;
    private final MetadataSyncHistoryService metadataSyncHistoryService;

    @Scheduled(fixedDelayString = "${metadata.statistics-sync.fixed-delay-ms:600000}")
    public void scheduleStatisticsSync() {
        List<DorisCluster> clusters = dorisClusterMapper.selectList(
                new LambdaQueryWrapper<DorisCluster>()
                        .eq(DorisCluster::getAutoSync, 1)
                        .eq(DorisCluster::getStatus, "active")
                        .orderByDesc(DorisCluster::getIsDefault)
                        .orderByAsc(DorisCluster::getClusterName));

        for (DorisCluster cluster : clusters) {
            syncClusterStatistics(cluster);
        }
    }

    private void syncClusterStatistics(DorisCluster cluster) {
        if (cluster == null || cluster.getId() == null) {
            return;
        }

        LocalDateTime startedAt = LocalDateTime.now();
        DorisMetadataSyncService.SyncResult result = null;
        try {
            log.info("Auto metadata statistics sync triggered, datasource id={}, name={}",
                    cluster.getId(), cluster.getClusterName());
            result = dorisMetadataSyncService.syncAllStatistics(cluster.getId());
            log.info("Auto metadata statistics sync finished, datasource id={}, name={}",
                    cluster.getId(), cluster.getClusterName());
        } catch (Exception e) {
            result = new DorisMetadataSyncService.SyncResult();
            result.addError("自动统计同步失败: " + e.getMessage());
            log.error("Auto metadata statistics sync failed, datasource id={}, name={}",
                    cluster.getId(), cluster.getClusterName(), e);
        } finally {
            try {
                metadataSyncHistoryService.record(cluster, "auto", "all", "statistics", startedAt, result);
            } catch (Exception historyEx) {
                log.error("Failed to record auto metadata statistics sync history, datasource id={}, name={}",
                        cluster.getId(), cluster.getClusterName(), historyEx);
            }
        }
    }
}
