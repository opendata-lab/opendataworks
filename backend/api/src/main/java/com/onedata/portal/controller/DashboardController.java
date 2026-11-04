package com.onedata.portal.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.onedata.portal.dto.DashboardExecutionStatistics;
import com.onedata.portal.dto.DashboardIssueStatistics;
import com.onedata.portal.dto.DashboardStatistics;
import com.onedata.portal.dto.DashboardTableAccessSummary;
import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.DataDomain;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.mapper.DataDomainMapper;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.InspectionIssueMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.service.DorisTableAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 控制台统计 Controller
 */
@RestController
@RequestMapping("/v1/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DataTableMapper dataTableMapper;
    private final DataTaskMapper dataTaskMapper;
    private final DataDomainMapper dataDomainMapper;
    private final TaskExecutionLogMapper taskExecutionLogMapper;
    private final InspectionIssueMapper inspectionIssueMapper;
    private final DorisTableAccessService dorisTableAccessService;

    /**
     * 获取控制台统计数据
     */
    @GetMapping("/statistics")
    public Result<DashboardStatistics> getStatistics(@RequestParam(required = false) Long clusterId) {
        try {
            // 1. 统计表数量
            QueryWrapper<DataTable> tableWrapper = new QueryWrapper<>();
            if (clusterId != null) {
                tableWrapper.eq("cluster_id", clusterId);
            }
            Long totalTables = dataTableMapper.selectCount(tableWrapper);

            // 2. 统计任务数量
            Long totalTasks = dataTaskMapper.selectCount(null);

            // 3. 统计域数量
            Long totalDomains = dataDomainMapper.selectCount(null);

            // 4-11. 一次条件聚合获取执行总量、状态与今日统计
            LocalDateTime todayStart = LocalDate.now().atStartOfDay();
            LocalDateTime tomorrowStart = todayStart.plusDays(1);
            DashboardExecutionStatistics executionStats =
                    taskExecutionLogMapper.selectDashboardStatistics(todayStart, tomorrowStart);
            Long totalExecutions = executionStats == null || executionStats.getTotalExecutions() == null
                    ? 0L : executionStats.getTotalExecutions();
            Long successExecutions = executionStats == null || executionStats.getSuccessExecutions() == null
                    ? 0L : executionStats.getSuccessExecutions();
            Long failedExecutions = executionStats == null || executionStats.getFailedExecutions() == null
                    ? 0L : executionStats.getFailedExecutions();
            Long runningExecutions = executionStats == null || executionStats.getRunningExecutions() == null
                    ? 0L : executionStats.getRunningExecutions();

            // 8. 计算执行成功率
            Double executionSuccessRate = 0.0;
            if (totalExecutions > 0) {
                executionSuccessRate = (successExecutions * 100.0) / totalExecutions;
                executionSuccessRate = Math.round(executionSuccessRate * 100.0) / 100.0; // 保留两位小数
            }

            Long todayExecutions = executionStats == null || executionStats.getTodayExecutions() == null
                    ? 0L : executionStats.getTodayExecutions();
            Long todaySuccessExecutions =
                    executionStats == null || executionStats.getTodaySuccessExecutions() == null
                            ? 0L : executionStats.getTodaySuccessExecutions();
            Long todayFailedExecutions =
                    executionStats == null || executionStats.getTodayFailedExecutions() == null
                            ? 0L : executionStats.getTodayFailedExecutions();

            // 12. 统计待解决问题数（巡检 - open状态）
            Long openIssues = 0L;
            Long criticalIssues = 0L;
            try {
                DashboardIssueStatistics issueStats = inspectionIssueMapper.selectDashboardStatistics();
                if (issueStats != null) {
                    openIssues = issueStats.getOpenIssues() == null ? 0L : issueStats.getOpenIssues();
                    criticalIssues = issueStats.getCriticalIssues() == null ? 0L : issueStats.getCriticalIssues();
                }
            } catch (Exception e) {
                // 如果巡检表不存在或查询失败，使用默认值0
                System.out.println("Failed to query inspection issues: " + e.getMessage());
            }

            // 构建统计结果
            DashboardTableAccessSummary accessSummary = dorisTableAccessService.getDashboardAccessSummary(
                    clusterId, 30, 10, 90, 10);
            DashboardStatistics statistics = DashboardStatistics.builder()
                    .totalTables(totalTables)
                    .totalTasks(totalTasks)
                    .totalDomains(totalDomains)
                    .totalExecutions(totalExecutions)
                    .successExecutions(successExecutions)
                    .failedExecutions(failedExecutions)
                    .runningExecutions(runningExecutions)
                    .executionSuccessRate(executionSuccessRate)
                    .openIssues(openIssues)
                    .criticalIssues(criticalIssues)
                    .todayExecutions(todayExecutions)
                    .todaySuccessExecutions(todaySuccessExecutions)
                    .todayFailedExecutions(todayFailedExecutions)
                    .hotWindowDays(accessSummary.getHotWindowDays())
                    .coldWindowDays(accessSummary.getColdWindowDays())
                    .hotTables(accessSummary.getHotTables())
                    .longUnusedTables(accessSummary.getLongUnusedTables())
                    .dorisAuditEnabled(accessSummary.getDorisAuditEnabled())
                    .dorisAuditSource(accessSummary.getDorisAuditSource())
                    .tableAccessSyncStatus(accessSummary.getTableAccessSyncStatus())
                    .tableAccessCoverageStart(accessSummary.getTableAccessCoverageStart())
                    .tableAccessCoverageComplete(accessSummary.getTableAccessCoverageComplete())
                    .tableAccessLastSyncedAt(accessSummary.getTableAccessLastSyncedAt())
                    .tableAccessNote(accessSummary.getNote())
                    .build();

            return Result.success(statistics);
        } catch (Exception e) {
            return Result.fail("获取统计数据失败: " + e.getMessage());
        }
    }
}
