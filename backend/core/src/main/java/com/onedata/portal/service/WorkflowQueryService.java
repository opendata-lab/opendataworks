package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowPublishRecordMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;



/**
 * 工作流读取服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowQueryService {

    private static final DateTimeFormatter[] DATETIME_FORMATS = new DateTimeFormatter[] {
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    };

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final WorkflowPublishRecordMapper workflowPublishRecordMapper;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final WorkflowInstanceCacheService workflowInstanceCacheService;
    private final DolphinSchedulerService dolphinSchedulerService;

    public Page<DataWorkflow> list(WorkflowQueryRequest request) {
        LambdaQueryWrapper<DataWorkflow> wrapper = Wrappers.lambdaQuery();
        if (StringUtils.hasText(request.getKeyword())) {
            wrapper.like(DataWorkflow::getWorkflowName, request.getKeyword());
        }
        if (StringUtils.hasText(request.getStatus())) {
            wrapper.eq(DataWorkflow::getStatus, request.getStatus());
        }
        wrapper.orderByDesc(DataWorkflow::getUpdatedAt);
        Page<DataWorkflow> page = new Page<>(request.getPageNum(), request.getPageSize());
        Page<DataWorkflow> result = dataWorkflowMapper.selectPage(page, wrapper);
        attachLatestInstanceInfo(result.getRecords());
        attachCurrentVersionInfo(result.getRecords());
        return result;
    }

    public WorkflowDetailResponse getDetail(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowTaskRelation::getCreatedAt));
        List<WorkflowVersion> versions = workflowVersionService.listByWorkflow(workflowId);
        List<WorkflowPublishRecord> publishRecords = workflowPublishRecordMapper.selectList(
                Wrappers.<WorkflowPublishRecord>lambdaQuery()
                        .eq(WorkflowPublishRecord::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowPublishRecord::getCreatedAt));
        workflow.setCurrentVersionNo(versions.stream()
                .filter(version -> Objects.equals(version.getId(), workflow.getCurrentVersionId()))
                .map(WorkflowVersion::getVersionNo)
                .findFirst()
                .orElse(null));
        List<WorkflowInstanceCache> recentInstances = resolveRecentInstances(workflow, 10);
        return WorkflowDetailResponse.builder()
                .workflow(workflow)
                .taskRelations(relations)
                .versions(versions)
                .publishRecords(publishRecords)
                .recentInstances(recentInstances)
                .build();
    }

    private List<WorkflowInstanceCache> resolveRecentInstances(DataWorkflow workflow, int limit) {
        if (workflow == null || workflow.getId() == null) {
            return Collections.emptyList();
        }
        if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            return workflowInstanceCacheService.listRecent(workflow.getId(), limit);
        }
        try {
            List<WorkflowInstanceSummary> realtimeSummaries = dolphinSchedulerService
                    .listWorkflowInstances(workflow.getDolphinConfigId(), workflow.getWorkflowCode(), limit);
            workflowInstanceCacheService.replaceCache(workflow, realtimeSummaries);
            return mapSummariesToCaches(workflow.getId(), realtimeSummaries);
        } catch (Exception ex) {
            log.warn("Failed to fetch realtime instances for workflow {}: {}", workflow.getWorkflowName(), ex.getMessage());
            return workflowInstanceCacheService.listRecent(workflow.getId(), limit);
        }
    }

    private void attachCurrentVersionInfo(List<DataWorkflow> workflows) {
        if (CollectionUtils.isEmpty(workflows)) {
            return;
        }
        Set<Long> versionIds = workflows.stream()
                .map(DataWorkflow::getCurrentVersionId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (versionIds.isEmpty()) {
            return;
        }
        Map<Long, Integer> versionNoById = workflowVersionMapper.selectBatchIds(versionIds).stream()
                .collect(Collectors.toMap(WorkflowVersion::getId, WorkflowVersion::getVersionNo, (left, right) -> left));
        workflows.forEach(workflow -> workflow.setCurrentVersionNo(versionNoById.get(workflow.getCurrentVersionId())));
    }

    private void attachLatestInstanceInfo(List<DataWorkflow> workflows) {
        if (CollectionUtils.isEmpty(workflows)) {
            return;
        }
        for (DataWorkflow workflow : workflows) {
            if (workflow.getId() == null) {
                continue;
            }
            WorkflowInstanceCache latest = null;
            boolean realtimeLoaded = false;
            if (workflow.getWorkflowCode() != null && workflow.getWorkflowCode() > 0) {
                try {
                    List<WorkflowInstanceSummary> summaries = dolphinSchedulerService
                            .listWorkflowInstances(workflow.getDolphinConfigId(), workflow.getWorkflowCode(), 1);
                    realtimeLoaded = true;
                    if (!summaries.isEmpty()) {
                        latest = mapSummaryToCache(workflow.getId(), summaries.get(0));
                    }
                } catch (Exception ex) {
                    log.warn("Failed to fetch latest realtime instance for workflow {}: {}",
                            workflow.getWorkflowName(), ex.getMessage());
                }
            }
            if (latest == null && !realtimeLoaded) {
                latest = workflowInstanceCacheService.findLatest(workflow.getId());
            }
            if (latest != null) {
                applyInstance(
                        workflow,
                        latest.getInstanceId(),
                        latest.getState(),
                        latest.getStartTime(),
                        latest.getEndTime());
            }
        }
    }

    private void applyInstance(DataWorkflow workflow,
            Long instanceId,
            String state,
            Object start,
            Object end) {
        workflow.setLatestInstanceId(instanceId);
        workflow.setLatestInstanceState(state);
        workflow.setLatestInstanceStartTime(toLocalDateTime(start));
        workflow.setLatestInstanceEndTime(toLocalDateTime(end));
    }

    private LocalDateTime toLocalDateTime(Object temporal) {
        if (temporal == null) {
            return null;
        }
        if (temporal instanceof LocalDateTime) {
            return (LocalDateTime) temporal;
        }
        if (temporal instanceof Date) {
            return ((Date) temporal).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
        }
        if (temporal instanceof String) {
            String value = (String) temporal;
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                return parseFlexibleDateTime(value);
            } catch (DateTimeParseException ignore) {
                return null;
            }
        }
        return null;
    }

    private List<WorkflowInstanceCache> mapSummariesToCaches(Long workflowId,
            List<WorkflowInstanceSummary> summaries) {
        if (workflowId == null || CollectionUtils.isEmpty(summaries)) {
            return Collections.emptyList();
        }
        return summaries.stream()
                .map(summary -> mapSummaryToCache(workflowId, summary))
                .collect(Collectors.toList());
    }

    private WorkflowInstanceCache mapSummaryToCache(Long workflowId, WorkflowInstanceSummary summary) {
        WorkflowInstanceCache cache = new WorkflowInstanceCache();
        cache.setWorkflowId(workflowId);
        cache.setInstanceId(summary.getInstanceId());
        cache.setState(summary.getState());
        cache.setTriggerType(summary.getCommandType());
        cache.setDurationMs(summary.getDurationMs());
        cache.setStartTime(parseToDate(summary.getStartTime()));
        cache.setEndTime(parseToDate(summary.getEndTime()));
        cache.setExtra(summary.getRawJson());
        return cache;
    }

    private Date parseToDate(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            LocalDateTime ldt = parseFlexibleDateTime(text);
            if (ldt == null) {
                return null;
            }
            return Date.from(ldt.atZone(ZoneId.systemDefault()).toInstant());
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private LocalDateTime parseFlexibleDateTime(String raw) {
        String candidate = raw.replace("Z", "");
        for (DateTimeFormatter formatter : DATETIME_FORMATS) {
            try {
                return LocalDateTime.parse(candidate, formatter);
            } catch (DateTimeParseException ignore) {
                // try next
            }
        }
        return null;
    }
}
