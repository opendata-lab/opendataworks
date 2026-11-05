package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.dto.DashboardIssueStatistics;
import com.onedata.portal.entity.InspectionIssue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;


/**
 * 巡检问题 Mapper
 */
@Mapper
public interface InspectionIssueMapper extends BaseMapper<InspectionIssue> {

    @Select("SELECT "
            + "COUNT(*) AS open_issues, "
            + "COALESCE(SUM(CASE WHEN severity = 'critical' THEN 1 ELSE 0 END), 0) AS critical_issues "
            + "FROM inspection_issue WHERE status = 'open'")
    DashboardIssueStatistics selectDashboardStatistics();
}
