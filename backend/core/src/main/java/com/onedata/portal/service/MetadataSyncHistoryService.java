package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.MetadataSyncHistory;
import com.onedata.portal.mapper.MetadataSyncHistoryMapper;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;



/**
 * 元数据同步历史服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataSyncHistoryService {

    private static final int DEFAULT_RETENTION_DAYS = 90;

    private final MetadataSyncHistoryMapper metadataSyncHistoryMapper;
    private final ObjectMapper objectMapper;

    public MetadataSyncHistory record(DorisCluster cluster,
            String triggerType,
            String scopeType,
            String scopeTarget,
            LocalDateTime startedAt,
            DorisMetadataSyncService.SyncResult result) {
        LocalDateTime begin = startedAt != null ? startedAt : LocalDateTime.now();
        LocalDateTime finishedAt = LocalDateTime.now();

        MetadataSyncHistory history = new MetadataSyncHistory();
        history.setClusterId(cluster != null ? cluster.getId() : null);
        history.setClusterName(cluster != null ? cluster.getClusterName() : null);
        history.setSourceType(cluster != null ? cluster.getSourceType() : null);
        history.setTriggerType(StringUtils.hasText(triggerType) ? triggerType : "manual");
        history.setScopeType(StringUtils.hasText(scopeType) ? scopeType : "all");
        history.setScopeTarget(scopeTarget);
        history.setStatus(result != null ? result.getStatus() : "FAILED");
        history.setStartedAt(begin);
        history.setFinishedAt(finishedAt);
        history.setDurationMs(Math.max(0L, java.time.Duration.between(begin, finishedAt).toMillis()));

        if (result != null) {
            history.setNewTables(result.getNewTables());
            history.setUpdatedTables(result.getUpdatedTables());
            history.setDeletedTables(result.getDeletedTables());
            history.setBlockedDeletedTables(result.getBlockedDeletedTables());
            history.setInactivatedTables(result.getInactivatedTables());
            history.setNewFields(result.getNewFields());
            history.setUpdatedFields(result.getUpdatedFields());
            history.setDeletedFields(result.getDeletedFields());

            List<String> errors = result.getErrors() != null ? result.getErrors() : Collections.emptyList();
            history.setErrorCount(errors.size());
            history.setErrorSummary(buildErrorSummary(errors));
            history.setErrorDetails(toJson(errors));
            history.setChangeDetails(toJson(result.getChangeDetails()));
        } else {
            history.setNewTables(0);
            history.setUpdatedTables(0);
            history.setDeletedTables(0);
            history.setBlockedDeletedTables(0);
            history.setInactivatedTables(0);
            history.setNewFields(0);
            history.setUpdatedFields(0);
            history.setDeletedFields(0);
            history.setErrorCount(1);
            history.setErrorSummary("同步结果为空");
            history.setErrorDetails("[]");
            history.setChangeDetails("{\"added\":[],\"updated\":[],\"deleted\":[]}");
        }

        metadataSyncHistoryMapper.insert(history);
        return history;
    }

    public Page<MetadataSyncHistory> listByCluster(Long clusterId,
            int pageNum,
            int pageSize,
            String status,
            String triggerType) {
        Page<MetadataSyncHistory> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<MetadataSyncHistory> wrapper = new LambdaQueryWrapper<MetadataSyncHistory>()
                .eq(MetadataSyncHistory::getClusterId, clusterId)
                .orderByDesc(MetadataSyncHistory::getStartedAt)
                .orderByDesc(MetadataSyncHistory::getId);

        if (StringUtils.hasText(status)) {
            wrapper.eq(MetadataSyncHistory::getStatus, status.trim().toUpperCase());
        }
        if (StringUtils.hasText(triggerType)) {
            wrapper.eq(MetadataSyncHistory::getTriggerType, triggerType.trim().toLowerCase());
        }

        return metadataSyncHistoryMapper.selectPage(page, wrapper);
    }

    public MetadataSyncHistory getById(Long id) {
        return metadataSyncHistoryMapper.selectById(id);
    }

    public int cleanupOldHistory(Integer retentionDays) {
        int days = (retentionDays != null && retentionDays > 0) ? retentionDays : DEFAULT_RETENTION_DAYS;
        LocalDateTime threshold = LocalDateTime.now().minusDays(days);
        QueryWrapper<MetadataSyncHistory> wrapper = new QueryWrapper<>();
        wrapper.lambda().lt(MetadataSyncHistory::getCreatedAt, threshold);
        int deleted = metadataSyncHistoryMapper.delete(wrapper);
        if (deleted > 0) {
            log.info("Cleaned {} metadata sync history records older than {} days", deleted, days);
        }
        return deleted;
    }

    private String buildErrorSummary(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        String first = errors.get(0);
        if (!StringUtils.hasText(first)) {
            return "同步存在错误";
        }
        return first.length() > 500 ? first.substring(0, 500) : first;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Collections.emptyList() : value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize metadata sync payload", e);
            return "[]";
        }
    }
}
