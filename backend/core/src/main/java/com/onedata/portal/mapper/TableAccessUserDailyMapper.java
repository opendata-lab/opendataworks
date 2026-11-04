package com.onedata.portal.mapper;

import com.onedata.portal.dto.TableAccessUserStat;
import com.onedata.portal.entity.TableAccessUserDaily;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;



@Mapper
public interface TableAccessUserDailyMapper {

    void upsertBatch(@Param("items") List<TableAccessUserDaily> items);

    Long countDistinctUsers(
            @Param("clusterId") Long clusterId,
            @Param("dbName") String dbName,
            @Param("tableName") String tableName,
            @Param("startDate") LocalDate startDate);

    List<TableAccessUserStat> selectTopUsers(
            @Param("clusterId") Long clusterId,
            @Param("dbName") String dbName,
            @Param("tableName") String tableName,
            @Param("startDate") LocalDate startDate,
            @Param("limit") int limit);

    @Delete("DELETE FROM table_access_user_daily WHERE access_date < #{threshold}")
    int deleteBefore(@Param("threshold") LocalDate threshold);
}
