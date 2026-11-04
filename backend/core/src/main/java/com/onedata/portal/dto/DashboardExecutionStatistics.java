package com.onedata.portal.dto;

import lombok.Data;


/**
 * Dashboard 执行日志条件聚合结果。
 */
@Data
public class DashboardExecutionStatistics {

    private Long totalExecutions;
    private Long successExecutions;
    private Long failedExecutions;
    private Long runningExecutions;
    private Long todayExecutions;
    private Long todaySuccessExecutions;
    private Long todayFailedExecutions;
}
