package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.TableStatisticsHistory;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;



/**
 * 表统计历史记录 Mapper
 */
@Mapper
public interface TableStatisticsHistoryMapper extends BaseMapper<TableStatisticsHistory> {

    /**
     * 获取指定表的最近N条统计记录
     */
    List<TableStatisticsHistory> selectRecentHistory(
            @Param("tableId") Long tableId,
            @Param("limit") int limit
    );

    /**
     * 获取指定时间范围内的统计记录
     */
    List<TableStatisticsHistory> selectHistoryByTimeRange(
            @Param("tableId") Long tableId,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    /**
     * 获取多个表的最新一条统计记录
     */
    List<TableStatisticsHistory> selectLatestByTableIds(@Param("tableIds") List<Long> tableIds);
}
