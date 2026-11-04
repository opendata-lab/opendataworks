package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.dto.DashboardExecutionStatistics;
import com.onedata.portal.entity.TaskExecutionLog;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;



/**
 * 任务执行日志 Mapper
 */
@Mapper
public interface TaskExecutionLogMapper extends BaseMapper<TaskExecutionLog> {

    @Select("SELECT "
            + "COUNT(*) AS total_executions, "
            + "COALESCE(SUM(CASE WHEN status = 'success' THEN 1 ELSE 0 END), 0) AS success_executions, "
            + "COALESCE(SUM(CASE WHEN status = 'failed' THEN 1 ELSE 0 END), 0) AS failed_executions, "
            + "COALESCE(SUM(CASE WHEN status = 'running' THEN 1 ELSE 0 END), 0) AS running_executions, "
            + "COALESCE(SUM(CASE WHEN start_time >= #{todayStart} AND start_time < #{tomorrowStart} "
            + "THEN 1 ELSE 0 END), 0) AS today_executions, "
            + "COALESCE(SUM(CASE WHEN status = 'success' AND start_time >= #{todayStart} "
            + "AND start_time < #{tomorrowStart} THEN 1 ELSE 0 END), 0) AS today_success_executions, "
            + "COALESCE(SUM(CASE WHEN status = 'failed' AND start_time >= #{todayStart} "
            + "AND start_time < #{tomorrowStart} THEN 1 ELSE 0 END), 0) AS today_failed_executions "
            + "FROM task_execution_log")
    DashboardExecutionStatistics selectDashboardStatistics(
            @Param("todayStart") LocalDateTime todayStart,
            @Param("tomorrowStart") LocalDateTime tomorrowStart);
}
