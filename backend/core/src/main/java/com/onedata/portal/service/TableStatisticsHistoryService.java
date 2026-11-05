package com.onedata.portal.service;

import com.onedata.portal.dto.TableStatistics;
import com.onedata.portal.entity.TableStatisticsHistory;
import com.onedata.portal.mapper.TableStatisticsHistoryMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;



/**
 * 表统计历史记录服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TableStatisticsHistoryService {

    private final TableStatisticsHistoryMapper historyMapper;

    /**
     * 保存统计信息到历史记录
     */
    public void saveHistory(Long tableId, Long clusterId, TableStatistics statistics) {
        try {
            TableStatisticsHistory history = new TableStatisticsHistory();
            history.setTableId(tableId);
            history.setClusterId(clusterId);
            history.setDatabaseName(statistics.getDatabaseName());
            history.setTableName(statistics.getTableName());
            history.setRowCount(statistics.getRowCount());
            history.setDataSize(statistics.getDataSize());
            history.setPartitionCount(statistics.getPartitionCount());
            history.setReplicationNum(statistics.getReplicationNum());
            history.setBucketNum(statistics.getBucketNum());
            history.setTableLastUpdateTime(statistics.getLastUpdateTime());
            history.setStatisticsTime(statistics.getLastCheckTime());

            historyMapper.insert(history);
            log.info("Saved statistics history for table {} cluster {}", tableId, clusterId);
        } catch (Exception e) {
            log.error("Failed to save statistics history for table {} cluster {}", tableId, clusterId, e);
            // 不抛出异常，避免影响主流程
        }
    }

    /**
     * 获取最近N条历史记录
     */
    public List<TableStatisticsHistory> getRecentHistory(Long tableId, int limit) {
        return historyMapper.selectRecentHistory(tableId, limit);
    }

    /**
     * 获取指定时间范围内的历史记录
     */
    public List<TableStatisticsHistory> getHistoryByTimeRange(Long tableId, LocalDateTime startTime, LocalDateTime endTime) {
        return historyMapper.selectHistoryByTimeRange(tableId, startTime, endTime);
    }

    /**
     * 获取最近7天的历史记录
     */
    public List<TableStatisticsHistory> getLast7DaysHistory(Long tableId) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(7);
        return getHistoryByTimeRange(tableId, startTime, endTime);
    }

    /**
     * 获取最近30天的历史记录
     */
    public List<TableStatisticsHistory> getLast30DaysHistory(Long tableId) {
        LocalDateTime endTime = LocalDateTime.now();
        LocalDateTime startTime = endTime.minusDays(30);
        return getHistoryByTimeRange(tableId, startTime, endTime);
    }
}
