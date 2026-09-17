package com.onedata.portal.service.freshness;

import com.onedata.portal.config.FreshnessCheckProperties;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 工作流执行成功后触发新鲜度检查：回答「任务报成功了，数据真的到了吗」。
 * 只覆盖该工作流经写关系关联的表；未配置契约的表在 {@link FreshnessCheckService} 内被跳过。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkflowFreshnessTrigger {

    private final FreshnessCheckProperties properties;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final DataTableMapper dataTableMapper;
    private final FreshnessCheckService freshnessCheckService;

    /**
     * 工作流实例新变为成功时调用。{@code instanceId} 是触发本次检查的 Dolphin 实例ID，
     * 会随结果落库以便按「每次运行」聚合并反查执行。触发失败只记日志，不影响调用方主流程。
     */
    public void onWorkflowSucceeded(Long workflowId, Long instanceId) {
        if (!properties.isEnabled() || workflowId == null) {
            return;
        }
        try {
            List<Long> tableIds = tableTaskRelationMapper.selectAllTableIdsByWorkflow(workflowId);
            if (tableIds == null || tableIds.isEmpty()) {
                return;
            }
            List<DataTable> tables = dataTableMapper.selectBatchIds(tableIds).stream()
                .filter(t -> "active".equals(t.getStatus()))
                .collect(Collectors.toList());
            if (tables.isEmpty()) {
                return;
            }
            List<FreshnessCheckResult> results =
                freshnessCheckService.checkBatch(tables, "workflow", "system", instanceId);
            if (!results.isEmpty()) {
                log.info("Workflow {} instance {} freshness check done: associatedTables={}, checked={}",
                    workflowId, instanceId, tables.size(), results.size());
            }
        } catch (Exception e) {
            log.warn("Workflow freshness trigger failed for workflow {}: {}", workflowId, e.getMessage());
        }
    }
}
