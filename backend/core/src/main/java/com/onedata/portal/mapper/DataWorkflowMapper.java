package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.DataWorkflow;
import org.apache.ibatis.annotations.Mapper;


/**
 * 工作流主数据 Mapper
 */
@Mapper
public interface DataWorkflowMapper extends BaseMapper<DataWorkflow> {
}
