package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DataTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;


/**
 * 任务定义 Mapper
 */
@Mapper
public interface DataTaskMapper extends BaseMapper<DataTask> {

    @Select("SELECT COUNT(1) FROM data_task WHERE task_code = #{taskCode}")
    Long countByTaskCodeIncludingDeleted(@Param("taskCode") String taskCode);

    @Select("SELECT id, task_name, task_code, deleted "
            + "FROM data_task WHERE task_code = #{taskCode} AND deleted = 1 LIMIT 1")
    DataTask selectDeletedByTaskCode(@Param("taskCode") String taskCode);

    @Update("UPDATE data_task SET task_code = #{taskCode}, task_name = #{taskName}, "
            + "updated_at = CURRENT_TIMESTAMP WHERE id = #{id} AND deleted = #{expectedDeleted}")
    int archiveUniqueIdentity(@Param("id") Long id,
            @Param("taskCode") String taskCode,
            @Param("taskName") String taskName,
            @Param("expectedDeleted") Integer expectedDeleted);
}
