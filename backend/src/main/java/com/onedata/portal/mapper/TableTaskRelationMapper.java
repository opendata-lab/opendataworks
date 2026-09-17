package com.onedata.portal.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.onedata.portal.dto.WorkflowTableRelationItem;
import com.onedata.portal.entity.TableTaskRelation;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 表任务关联 Mapper
 */
@Mapper
public interface TableTaskRelationMapper extends BaseMapper<TableTaskRelation> {

    /**
     * 某工作流写出的表 ID（经写关系关联的表）。用于工作流执行成功后触发新鲜度检查。
     */
    @Select("SELECT DISTINCT r.table_id " +
        "FROM table_task_relation r " +
        "JOIN workflow_task_relation wtr ON r.task_id = wtr.task_id " +
        "JOIN data_task t ON r.task_id = t.id " +
        "WHERE wtr.workflow_id = #{workflowId} " +
        "  AND wtr.deleted = 0 " +
        "  AND r.relation_type = 'write' " +
        "  AND r.deleted = 0 " +
        "  AND t.deleted = 0")
    List<Long> selectWriteTableIdsByWorkflow(Long workflowId);

    /**
     * 某工作流关联的所有表关系（经读/写关系关联的表 ID 与关系类型）。
     */
    @Select("SELECT r.table_id, r.relation_type " +
        "FROM table_task_relation r " +
        "JOIN workflow_task_relation wtr ON r.task_id = wtr.task_id " +
        "JOIN data_task t ON r.task_id = t.id " +
        "WHERE wtr.workflow_id = #{workflowId} " +
        "  AND wtr.deleted = 0 " +
        "  AND r.deleted = 0 " +
        "  AND t.deleted = 0")
    List<WorkflowTableRelationItem> selectWorkflowTableRelations(Long workflowId);

    /**
     * 某工作流关联的所有表 ID（包含读取与写入表）。用于工作流执行成功后触发新鲜度检查。
     */
    @Select("SELECT DISTINCT r.table_id " +
        "FROM table_task_relation r " +
        "JOIN workflow_task_relation wtr ON r.task_id = wtr.task_id " +
        "JOIN data_task t ON r.task_id = t.id " +
        "WHERE wtr.workflow_id = #{workflowId} " +
        "  AND wtr.deleted = 0 " +
        "  AND r.deleted = 0 " +
        "  AND t.deleted = 0")
    List<Long> selectAllTableIdsByWorkflow(Long workflowId);

    /**
     * 物理删除指定任务的所有关联关系，避免逻辑删除导致唯一键冲突
     */
    @Delete("DELETE FROM table_task_relation WHERE task_id = #{taskId}")
    int hardDeleteByTaskId(Long taskId);

    @Select("SELECT COUNT(DISTINCT t_write.task_id) " +
        "FROM table_task_relation t_read " +
        "JOIN table_task_relation t_write ON t_read.table_id = t_write.table_id " +
        "WHERE t_read.task_id = #{taskId} " +
        "  AND t_read.deleted = 0 " +
        "  AND t_write.deleted = 0 " +
        "  AND t_read.relation_type = 'read' " +
        "  AND t_write.relation_type = 'write' " +
        "  AND t_write.task_id <> t_read.task_id")
    int countUpstreamTasks(Long taskId);

    @Select("SELECT COUNT(DISTINCT t_read.task_id) " +
        "FROM table_task_relation t_write " +
        "JOIN table_task_relation t_read ON t_write.table_id = t_read.table_id " +
        "WHERE t_write.task_id = #{taskId} " +
        "  AND t_write.deleted = 0 " +
        "  AND t_read.deleted = 0 " +
        "  AND t_write.relation_type = 'write' " +
        "  AND t_read.relation_type = 'read' " +
        "  AND t_read.task_id <> t_write.task_id")
    int countDownstreamTasks(Long taskId);
}
