package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import com.onedata.portal.dto.workflow.WorkflowSchedulerEngineRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;



/**
 * 工作流运行触发服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowExecutionService {

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final TaskExecutionLogMapper taskExecutionLogMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DolphinConfigService dolphinConfigService;
    private final RuntimeBindingLock runtimeBindingLock;
    private final WorkflowDefinitionAssembler workflowDefinitionAssembler;

    public String executeWorkflow(Long workflowId) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，请先上线后再执行");
        }
        TaskExecutionLog executionLog = createWorkflowExecutionLog(workflowId, "manual");
        try {
            String executionId = dolphinSchedulerService.startProcessInstance(
                    workflow.getDolphinConfigId(),
                    workflowCode,
                    null,
                    workflow.getWorkflowName());
            if (executionLog != null) {
                executionLog.setExecutionId(executionId);
                executionLog.setStatus("running");
                taskExecutionLogMapper.updateById(executionLog);
            }
            return executionId;
        } catch (RuntimeException ex) {
            markExecutionFailed(executionLog, ex);
            throw ex;
        }
    }

    public String backfillWorkflow(Long workflowId, WorkflowBackfillRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long workflowCode = workflow.getWorkflowCode();
        if (workflowCode == null || workflowCode <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，请先上线后再补数");
        }
        // 先校验再落日志，避免被拒的请求在 task_execution_log 留下垃圾行。
        WorkflowBackfillValidator.validate(request);

        TaskExecutionLog executionLog = createWorkflowExecutionLog(workflowId, "backfill");
        try {
            String triggerId = dolphinSchedulerService.backfillProcessInstance(
                    workflow.getDolphinConfigId(), workflowCode, request);
            if (executionLog != null) {
                executionLog.setExecutionId(triggerId);
                executionLog.setStatus("running");
                taskExecutionLogMapper.updateById(executionLog);
            }
            return triggerId;
        } catch (RuntimeException ex) {
            markExecutionFailed(executionLog, ex);
            throw ex;
        }
    }

    public DataWorkflow switchSchedulerEngine(Long workflowId, WorkflowSchedulerEngineRequest request) {
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId 不能为空");
        }
        if (request == null || request.getDolphinConfigId() == null || request.getDolphinConfigId() <= 0) {
            throw new IllegalArgumentException("dolphinConfigId 不能为空");
        }
        // 切换会把工作流从原运行态解绑并指向目标 Dolphin。必须在外层事务第一次读库前取锁，
        // 否则目标配置可能在绑定数量检查之后被并发删除，留下悬空的 dolphin_config_id。
        runtimeBindingLock.acquire();
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        Long targetConfigId = request.getDolphinConfigId();
        DolphinConfig targetConfig = dolphinConfigService.getEnabledConfig(targetConfigId);
        if (!dolphinSchedulerService.testConnection(targetConfigId)) {
            throw new IllegalStateException("目标 Dolphin 环境连接失败: " + targetConfig.getConfigName());
        }
        Long targetProjectCode = dolphinSchedulerService.getProjectCode(targetConfigId, true);
        if (targetProjectCode == null || targetProjectCode <= 0) {
            throw new IllegalStateException("目标 Dolphin 项目不可用: " + targetConfig.getProjectName());
        }

        String operator = StringUtils.hasText(request.getOperator()) ? request.getOperator().trim() : "system";
        LocalDateTime updatedAt = LocalDateTime.now();

        workflow.setDolphinConfigId(targetConfigId);
        workflow.setWorkflowCode(null);
        workflow.setProjectCode(targetProjectCode);
        workflow.setDolphinScheduleId(null);
        workflow.setScheduleState("OFFLINE");
        workflow.setStatus("offline");
        workflow.setPublishStatus("never");
        workflow.setLastPublishedVersionId(null);
        workflow.setRuntimeSyncStatus(null);
        workflow.setRuntimeSyncMessage(null);
        workflow.setRuntimeSyncHash(null);
        workflow.setRuntimeSyncAt(null);
        workflow.setUpdatedBy(operator);
        workflow.setUpdatedAt(updatedAt);
        workflow.setDefinitionJson(workflowDefinitionAssembler.refreshRuntimeBindings(
                workflow.getDefinitionJson(),
                targetConfigId,
                targetProjectCode));
        dataWorkflowMapper.update(null, Wrappers.<DataWorkflow>lambdaUpdate()
                .eq(DataWorkflow::getId, workflowId)
                .set(DataWorkflow::getDolphinConfigId, targetConfigId)
                .set(DataWorkflow::getWorkflowCode, null)
                .set(DataWorkflow::getProjectCode, targetProjectCode)
                .set(DataWorkflow::getDolphinScheduleId, null)
                .set(DataWorkflow::getScheduleState, "OFFLINE")
                .set(DataWorkflow::getStatus, "offline")
                .set(DataWorkflow::getPublishStatus, "never")
                .set(DataWorkflow::getLastPublishedVersionId, null)
                .set(DataWorkflow::getRuntimeSyncStatus, null)
                .set(DataWorkflow::getRuntimeSyncMessage, null)
                .set(DataWorkflow::getRuntimeSyncHash, null)
                .set(DataWorkflow::getRuntimeSyncAt, null)
                .set(DataWorkflow::getDefinitionJson, workflow.getDefinitionJson())
                .set(DataWorkflow::getUpdatedBy, operator)
                .set(DataWorkflow::getUpdatedAt, updatedAt));
        return workflow;
    }

    private TaskExecutionLog createWorkflowExecutionLog(Long workflowId, String triggerType) {
        Long taskId = resolveMonitorTaskId(workflowId);
        if (taskId == null) {
            log.warn("No task relation found for workflow {}, skip execution log creation", workflowId);
            return null;
        }
        TaskExecutionLog logRecord = new TaskExecutionLog();
        logRecord.setTaskId(taskId);
        logRecord.setStatus("pending");
        logRecord.setStartTime(LocalDateTime.now());
        logRecord.setTriggerType(StringUtils.hasText(triggerType) ? triggerType : "manual");
        taskExecutionLogMapper.insert(logRecord);
        return logRecord;
    }

    private void markExecutionFailed(TaskExecutionLog executionLog, RuntimeException ex) {
        if (executionLog == null) {
            return;
        }
        executionLog.setStatus("failed");
        executionLog.setEndTime(LocalDateTime.now());
        executionLog.setErrorMessage(ex.getMessage());
        taskExecutionLogMapper.updateById(executionLog);
    }

    private Long resolveMonitorTaskId(Long workflowId) {
        if (workflowId == null) {
            return null;
        }
        WorkflowTaskRelation relation = workflowTaskRelationMapper.selectOne(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId)
                        .orderByDesc(WorkflowTaskRelation::getIsEntry)
                        .orderByAsc(WorkflowTaskRelation::getId)
                        .last("LIMIT 1"));
        return relation != null ? relation.getTaskId() : null;
    }
}
