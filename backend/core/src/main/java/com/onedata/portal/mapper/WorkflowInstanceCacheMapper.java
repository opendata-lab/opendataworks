package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.WorkflowInstanceCache;
import org.apache.ibatis.annotations.Mapper;


/**
 * 工作流实例缓存 Mapper
 */
@Mapper
public interface WorkflowInstanceCacheMapper extends BaseMapper<WorkflowInstanceCache> {
}
