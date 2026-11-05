package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.mapper.WorkflowInstanceCacheMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;



/**
 * 管理 workflow_instance_cache 的工具服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowInstanceCacheService {

    private static final DateTimeFormatter DEFAULT_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WorkflowInstanceCacheMapper cacheMapper;

    public void replaceCache(DataWorkflow workflow,
                             List<WorkflowInstanceSummary> instances) {
        cacheMapper.delete(Wrappers.<WorkflowInstanceCache>lambdaQuery()
            .eq(WorkflowInstanceCache::getWorkflowId, workflow.getId()));
        if (CollectionUtils.isEmpty(instances)) {
            return;
        }
        List<WorkflowInstanceCache> caches = instances.stream()
            .map(instance -> buildCache(workflow.getId(), instance))
            .collect(Collectors.toList());
        caches.forEach(cacheMapper::insert);
    }

    public List<WorkflowInstanceCache> listRecent(Long workflowId, int limit) {
        return cacheMapper.selectList(
            Wrappers.<WorkflowInstanceCache>lambdaQuery()
                .eq(WorkflowInstanceCache::getWorkflowId, workflowId)
                .orderByDesc(WorkflowInstanceCache::getStartTime)
                .last("LIMIT " + limit)
        );
    }

    /**
     * 跨工作流取最近若干条缓存实例，用于执行监控全局查询失败时的降级。
     * 一次索引查询取代按工作流逐个读取。
     */
    public List<WorkflowInstanceCache> listRecentAcrossWorkflows(int limit) {
        return cacheMapper.selectList(
            Wrappers.<WorkflowInstanceCache>lambdaQuery()
                .orderByDesc(WorkflowInstanceCache::getStartTime)
                .last("LIMIT " + Math.max(limit, 1))
        );
    }

    public WorkflowInstanceCache findLatest(Long workflowId) {
        return cacheMapper.selectOne(
            Wrappers.<WorkflowInstanceCache>lambdaQuery()
                .eq(WorkflowInstanceCache::getWorkflowId, workflowId)
                .orderByDesc(WorkflowInstanceCache::getStartTime)
                .last("LIMIT 1")
        );
    }

    private WorkflowInstanceCache buildCache(Long workflowId,
                                             WorkflowInstanceSummary instance) {
        WorkflowInstanceCache cache = new WorkflowInstanceCache();
        cache.setWorkflowId(workflowId);
        cache.setInstanceId(instance.getInstanceId());
        cache.setState(instance.getState());
        cache.setTriggerType(instance.getCommandType());
        cache.setDurationMs(instance.getDurationMs());
        cache.setStartTime(parse(instance.getStartTime()));
        cache.setEndTime(parse(instance.getEndTime()));
        cache.setScheduleTime(parse(instance.getScheduleTime()));
        cache.setExtra(instance.getRawJson());
        return cache;
    }

    private Date parse(String dateTime) {
        if (dateTime == null || dateTime.isEmpty()) {
            return null;
        }
        try {
            LocalDateTime ldt;
            if (dateTime.contains("T")) {
                ldt = LocalDateTime.parse(dateTime.replace("Z", ""), DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } else {
                ldt = LocalDateTime.parse(dateTime, DEFAULT_FORMATTER);
            }
            return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (Exception ex) {
            log.warn("Failed to parse datetime {}", dateTime);
            return null;
        }
    }

    @Transactional
    public void deleteByWorkflowId(Long workflowId) {
        if (workflowId == null) {
            return;
        }
        cacheMapper.delete(
                Wrappers.<WorkflowInstanceCache>lambdaQuery()
                        .eq(WorkflowInstanceCache::getWorkflowId, workflowId));
    }
}
