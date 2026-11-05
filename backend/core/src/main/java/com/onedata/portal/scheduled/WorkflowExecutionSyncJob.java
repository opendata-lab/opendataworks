package com.onedata.portal.scheduled;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowInstanceCache;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.service.DolphinExecutionMapper;
import com.onedata.portal.service.DolphinSchedulerService;
import com.onedata.portal.service.WorkflowInstanceCacheService;
import com.onedata.portal.service.freshness.WorkflowFreshnessTrigger;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;



/**
 * 周期同步 Dolphin 工作流实例的定时任务。同步中识别「新变为成功」的实例，
 * 触发该工作流写出表的新鲜度检查。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowExecutionSyncJob {

    private static final String STATE_SUCCESS = "SUCCESS";

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowInstanceCacheService cacheService;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final WorkflowFreshnessTrigger workflowFreshnessTrigger;

    @Scheduled(cron = "0 */5 * * * ?")
    public void syncRecentInstances() {
        List<DataWorkflow> workflows = dataWorkflowMapper.selectList(
            Wrappers.<DataWorkflow>lambdaQuery()
                .eq(DataWorkflow::getStatus, "online")
                .isNotNull(DataWorkflow::getWorkflowCode)
        );
        for (DataWorkflow workflow : workflows) {
            try {
                // 同步前记录已成功的实例，用于识别本轮新变为成功的实例
                Set<Long> priorSuccess = successInstanceIds(cacheService.listRecent(workflow.getId(), 10));

                List<WorkflowInstanceSummary> instances =
                    dolphinSchedulerService.listWorkflowInstances(workflow.getWorkflowCode(), 10);
                cacheService.replaceCache(workflow, instances);

                latestNewlySucceeded(instances, priorSuccess).ifPresent(instance ->
                    workflowFreshnessTrigger.onWorkflowSucceeded(workflow.getId(), instance.getInstanceId()));
            } catch (Exception ex) {
                log.warn("Failed to sync workflow {}: {}", workflow.getWorkflowName(), ex.getMessage());
            }
        }
    }

    private Set<Long> successInstanceIds(List<WorkflowInstanceCache> caches) {
        Set<Long> ids = new HashSet<>();
        if (caches == null) {
            return ids;
        }
        for (WorkflowInstanceCache cache : caches) {
            if (isSuccess(cache.getState()) && cache.getInstanceId() != null) {
                ids.add(cache.getInstanceId());
            }
        }
        return ids;
    }

    /**
     * 挑出触发新鲜度检查的实例：新变为成功、非补数中，取实例ID 最大者（即最近一次运行）。
     * 检查反映的是「最新数据多旧」，因此归属到最近那次成功运行，并把其实例ID 落库以便反查执行。
     *
     * <p>排除补数（{@code COMPLEMENT_DATA}）：补数写的是过去的调度日期，不改变「最新数据多旧」，
     * 对当前新鲜度无意义；且在 metadata 模式下其物理写入会推进 {@code UPDATE_TIME} 造成假 pass。
     * 手动、定时（调度）成功实例照常触发。
     */
    Optional<WorkflowInstanceSummary> latestNewlySucceeded(List<WorkflowInstanceSummary> instances,
                                                           Set<Long> priorSuccess) {
        if (instances == null) {
            return Optional.empty();
        }
        WorkflowInstanceSummary latest = null;
        for (WorkflowInstanceSummary instance : instances) {
            if (isSuccess(instance.getState()) && instance.getInstanceId() != null
                && !priorSuccess.contains(instance.getInstanceId())
                && !isBackfill(instance.getCommandType())) {
                if (latest == null || instance.getInstanceId() > latest.getInstanceId()) {
                    latest = instance;
                }
            }
        }
        return Optional.ofNullable(latest);
    }

    private boolean isSuccess(String state) {
        return state != null && STATE_SUCCESS.equalsIgnoreCase(state.trim());
    }

    private boolean isBackfill(String commandType) {
        return "backfill".equals(DolphinExecutionMapper.mapTriggerType(commandType));
    }
}
