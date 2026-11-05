package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.entity.WorkflowTaskRelation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;


/**
 * 工作流-任务关系 Mapper
 */
@Mapper
public interface WorkflowTaskRelationMapper extends BaseMapper<WorkflowTaskRelation> {

    /**
     * 物理删除指定工作流下的所有任务关系，避免逻辑删除记录命中 uk_task 唯一索引。
     */
    @Delete("DELETE FROM workflow_task_relation WHERE workflow_id = #{workflowId}")
    int hardDeleteByWorkflowId(Long workflowId);

    /**
     * 物理删除指定任务的工作流关系，避免逻辑删除记录命中 uk_task 唯一索引。
     */
    @Delete("DELETE FROM workflow_task_relation WHERE task_id = #{taskId}")
    int hardDeleteByTaskId(Long taskId);
}
