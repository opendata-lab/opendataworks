package com.onedata.portal.dto;

import lombok.Data;


/**
 * Dashboard 巡检问题条件聚合结果。
 */
@Data
public class DashboardIssueStatistics {

    private Long openIssues;
    private Long criticalIssues;
}
