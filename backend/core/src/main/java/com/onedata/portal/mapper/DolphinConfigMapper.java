package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DolphinConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;


@Mapper
public interface DolphinConfigMapper extends BaseMapper<DolphinConfig> {

    @Select("SELECT COUNT(1) FROM data_workflow "
            + "WHERE dolphin_config_id = #{configId} "
            + "AND workflow_code IS NOT NULL "
            + "AND workflow_code > 0 "
            + "AND (deleted IS NULL OR deleted = 0)")
    Long countRuntimeBoundWorkflows(@Param("configId") Long configId);

    /**
     * 统计仍未绑定环境、却已经绑定运行态的工作流。这些工作流跟随当前默认环境运行，
     * 改动或删除默认环境时必须把它们算进去。V51 已做过一次回填、发布路径也会写回该字段，
     * 这里是回填时没有默认环境等残留情况的兜底。
     */
    @Select("SELECT COUNT(1) FROM data_workflow "
            + "WHERE dolphin_config_id IS NULL "
            + "AND workflow_code IS NOT NULL "
            + "AND workflow_code > 0 "
            + "AND (deleted IS NULL OR deleted = 0)")
    Long countRuntimeBoundWorkflowsWithoutConfig();

    /**
     * 把仍未绑定环境的运行态工作流固定到指定环境。用于在切换/修改/删除默认环境之前，
     * 先把这些隐式跟随默认环境的工作流归属到它们当下实际使用的那个环境。
     */
    @Update("UPDATE data_workflow SET dolphin_config_id = #{configId} "
            + "WHERE dolphin_config_id IS NULL "
            + "AND workflow_code IS NOT NULL "
            + "AND workflow_code > 0 "
            + "AND (deleted IS NULL OR deleted = 0)")
    int pinRuntimeBoundWorkflowsWithoutConfig(@Param("configId") Long configId);
}
