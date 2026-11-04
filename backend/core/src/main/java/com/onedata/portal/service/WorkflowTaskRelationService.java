package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;



/**
 * 工作流任务关系服务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowTaskRelationService {

    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final DataTaskMapper dataTaskMapper;
    private final WorkflowTopologyService workflowTopologyService;
    private final ObjectMapper objectMapper;

    public List<WorkflowTaskBinding> buildTaskBindingsFromRelations(List<WorkflowTaskRelation> relations) {
        if (CollectionUtils.isEmpty(relations)) {
            return Collections.emptyList();
        }
        List<WorkflowTaskBinding> bindings = new ArrayList<>();
        for (WorkflowTaskRelation relation : relations) {
            if (relation == null || relation.getTaskId() == null) {
                continue;
            }
            WorkflowTaskBinding binding = new WorkflowTaskBinding();
            binding.setTaskId(relation.getTaskId());
            binding.setEntry(relation.getIsEntry());
            binding.setExit(relation.getIsExit());
            if (StringUtils.hasText(relation.getNodeAttrs())) {
                try {
                    binding.setNodeAttrs(objectMapper.readValue(relation.getNodeAttrs(), Map.class));
                } catch (Exception e) {
                    // 节点属性 JSON 非法时跳过，保留其余绑定信息
                    log.trace("解析节点属性 nodeAttrs 失败，跳过", e);
                }
            }
            bindings.add(binding);
        }
        return bindings;
    }

    public List<Long> collectTaskIds(List<WorkflowTaskBinding> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        LinkedHashSet<Long> ordered = new LinkedHashSet<>();
        for (WorkflowTaskBinding task : tasks) {
            if (task != null && task.getTaskId() != null) {
                ordered.add(task.getTaskId());
            }
        }
        return new ArrayList<>(ordered);
    }

    public void persistTaskRelations(Long workflowId,
            List<WorkflowTaskBinding> tasks,
            Long previousVersionId,
            WorkflowTopologyResult topology) {
        // Rebuilding workflow topology reuses the same task ids, so logical delete would
        // immediately conflict with workflow_task_relation.uk_task on reinsert.
        workflowTaskRelationMapper.hardDeleteByWorkflowId(workflowId);
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        Set<Long> entrySet = topology != null && topology.getEntryTaskIds() != null
                ? topology.getEntryTaskIds()
                : Collections.emptySet();
        Set<Long> exitSet = topology != null && topology.getExitTaskIds() != null
                ? topology.getExitTaskIds()
                : Collections.emptySet();
        for (WorkflowTaskBinding binding : tasks) {
            if (binding.getTaskId() == null) {
                continue;
            }
            ensureTaskAssignable(binding.getTaskId(), workflowId);
            WorkflowTaskRelation relation = new WorkflowTaskRelation();
            relation.setWorkflowId(workflowId);
            relation.setTaskId(binding.getTaskId());
            relation.setIsEntry(entrySet.contains(binding.getTaskId()));
            relation.setIsExit(exitSet.contains(binding.getTaskId()));
            relation.setNodeAttrs(toJson(binding.getNodeAttrs()));
            relation.setVersionId(previousVersionId);
            relation.setUpstreamTaskCount(tableTaskRelationMapper.countUpstreamTasks(binding.getTaskId()));
            relation.setDownstreamTaskCount(tableTaskRelationMapper.countDownstreamTasks(binding.getTaskId()));
            workflowTaskRelationMapper.insert(relation);
        }
    }

    /**
     * 重新计算工作流中所有任务的上下游关系。
     */
    public void refreshTaskRelations(Long workflowId) {
        List<WorkflowTaskRelation> existingRelations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));

        List<WorkflowTaskBinding> taskBindings = buildTaskBindingsFromRelations(existingRelations);
        Long versionId = null;
        if (!CollectionUtils.isEmpty(existingRelations)) {
            for (WorkflowTaskRelation relation : existingRelations) {
                versionId = relation.getVersionId();
            }
        }

        WorkflowTopologyResult topology = null;
        if (!taskBindings.isEmpty()) {
            topology = workflowTopologyService.buildTopology(collectTaskIds(taskBindings));
        }

        persistTaskRelations(workflowId, taskBindings, versionId, topology);
    }

    private void ensureTaskAssignable(Long taskId, Long workflowId) {
        DataTask dataTask = dataTaskMapper.selectById(taskId);
        if (dataTask == null) {
            throw new IllegalArgumentException("Task not found: " + taskId);
        }
        WorkflowTaskRelation existing = workflowTaskRelationMapper.selectOne(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getTaskId, taskId));
        if (existing != null && !existing.getWorkflowId().equals(workflowId)) {
            throw new IllegalStateException("任务已归属其他工作流, taskId=" + taskId);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize json", e);
        }
    }
}
