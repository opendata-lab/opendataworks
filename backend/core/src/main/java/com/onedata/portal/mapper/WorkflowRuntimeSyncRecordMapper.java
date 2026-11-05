package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.WorkflowRuntimeSyncRecord;
import org.apache.ibatis.annotations.Mapper;


/**
 * 运行态同步记录 Mapper
 */
@Mapper
public interface WorkflowRuntimeSyncRecordMapper extends BaseMapper<WorkflowRuntimeSyncRecord> {
}
