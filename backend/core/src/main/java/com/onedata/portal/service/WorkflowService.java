package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.dto.workflow.WorkflowSchedulerEngineRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;



/**
 * 工作流定义服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final WorkflowTopologyService workflowTopologyService;
    private final WorkflowQueryService workflowQueryService;
    private final WorkflowTaskRelationService workflowTaskRelationService;
    private final WorkflowExecutionService workflowExecutionService;
    private final WorkflowCommandService workflowCommandService;
    private final WorkflowDefinitionAssembler workflowDefinitionAssembler;

    public Page<DataWorkflow> list(WorkflowQueryRequest request) {
        return workflowQueryService.list(request);
    }

    public WorkflowDetailResponse getDetail(Long workflowId) {
        return workflowQueryService.getDetail(workflowId);
    }

    @Transactional
    public String buildDefinitionJsonForExport(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        if (StringUtils.hasText(workflow.getDefinitionJson())) {
            return workflowDefinitionAssembler.sanitizeDefinitionJsonForExport(workflow.getDefinitionJson());
        }

        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        List<WorkflowTaskBinding> bindings = workflowTaskRelationService.buildTaskBindingsFromRelations(relations);

        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(
                workflowTaskRelationService.collectTaskIds(bindings));
        String definitionJson = workflowDefinitionAssembler.resolveDefinitionJson(workflow, null, bindings, topology);
        workflow.setDefinitionJson(definitionJson);
        dataWorkflowMapper.updateById(workflow);
        return workflowDefinitionAssembler.sanitizeDefinitionJsonForExport(definitionJson);
    }

    @Transactional
    public DataWorkflow createWorkflow(WorkflowDefinitionRequest request) {
        return workflowCommandService.createWorkflow(request);
    }

    public String executeWorkflow(Long workflowId) {
        return workflowExecutionService.executeWorkflow(workflowId);
    }

    public String backfillWorkflow(Long workflowId, WorkflowBackfillRequest request) {
        return workflowExecutionService.backfillWorkflow(workflowId, request);
    }

    @Transactional
    public DataWorkflow updateWorkflow(Long workflowId, WorkflowDefinitionRequest request) {
        return workflowCommandService.updateWorkflow(workflowId, request);
    }

    @Transactional
    public DataWorkflow syncCurrentVersion(Long workflowId, String operator, String triggerSource) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        WorkflowDefinitionRequest request = new WorkflowDefinitionRequest();
        request.setWorkflowName(workflow.getWorkflowName());
        request.setDescription(workflow.getDescription());
        request.setTaskGroupName(workflow.getTaskGroupName());
        request.setGlobalParams(workflow.getGlobalParams());
        request.setDolphinConfigId(workflow.getDolphinConfigId());
        request.setProjectCode(workflow.getProjectCode());
        request.setTasks(workflowTaskRelationService.buildTaskBindingsFromRelations(relations));
        request.setOperator(resolveWorkflowOperator(workflow, operator));
        request.setTriggerSource(StringUtils.hasText(triggerSource) ? triggerSource.trim() : "publish_auto_save");
        return updateWorkflow(workflowId, request);
    }

    @Transactional
    public DataWorkflow normalizeAndPersistMetadata(Long workflowId, String operator) {
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId 不能为空");
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByAsc(WorkflowTaskRelation::getId));
        List<WorkflowTaskBinding> taskBindings = workflowTaskRelationService.buildTaskBindingsFromRelations(relations);
        List<Long> taskIdsInOrder = workflowTaskRelationService.collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setEntryTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflowDefinitionAssembler.normalizeWorkflowScheduleDefaults(workflow);
        workflowDefinitionAssembler.normalizeTaskMetadata(taskIdsInOrder, workflow.getTaskGroupName());
        workflow.setDefinitionJson(workflowDefinitionAssembler.resolveDefinitionJson(workflow, null, taskBindings, topology));
        if (StringUtils.hasText(operator)) {
            workflow.setUpdatedBy(operator.trim());
        }
        workflow.setUpdatedAt(LocalDateTime.now());
        dataWorkflowMapper.updateById(workflow);
        return workflow;
    }

    @Transactional
    public DataWorkflow switchSchedulerEngine(Long workflowId, WorkflowSchedulerEngineRequest request) {
        return workflowExecutionService.switchSchedulerEngine(workflowId, request);
    }

    private String resolveWorkflowOperator(DataWorkflow workflow, String operator) {
        if (StringUtils.hasText(operator)) {
            return operator.trim();
        }
        if (workflow != null && StringUtils.hasText(workflow.getUpdatedBy())) {
            return workflow.getUpdatedBy().trim();
        }
        if (workflow != null && StringUtils.hasText(workflow.getCreatedBy())) {
            return workflow.getCreatedBy().trim();
        }
        return "system";
    }

    /**
     * 重新计算工作流中所有任务的上下游关系
     * 用于在单个任务被添加/更新/删除后重新计算整个工作流的关系
     */
    public void refreshTaskRelations(Long workflowId) {
        workflowTaskRelationService.refreshTaskRelations(workflowId);
    }

    /**
     * 删除工作流
     * 软删除工作流定义；默认保留任务定义以便复用
     */
    @Transactional
    public void deleteWorkflow(Long workflowId) {
        workflowCommandService.deleteWorkflow(workflowId, false);
    }

    /**
     * 删除工作流
     *
     * @param workflowId          工作流ID
     * @param cascadeDeleteTasks  是否级联软删除绑定任务
     */
    @Transactional
    public void deleteWorkflow(Long workflowId, boolean cascadeDeleteTasks) {
        workflowCommandService.deleteWorkflow(workflowId, cascadeDeleteTasks);
    }
}
