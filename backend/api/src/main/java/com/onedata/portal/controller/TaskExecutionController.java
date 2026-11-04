package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.execution.WorkflowExecutionPage;
import com.onedata.portal.dto.execution.WorkflowTaskInstanceExecution;
import com.onedata.portal.service.WorkflowExecutionMonitorService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 任务执行监控控制器 */
@Slf4j
@RestController
@RequestMapping("/v1/executions")
@RequiredArgsConstructor
public class TaskExecutionController {

    private final WorkflowExecutionMonitorService workflowExecutionMonitorService;

    /**
     * Unified workflow-instance monitor for both platform and Dolphin scheduled executions.
     * Statistics and records are calculated from the same snapshot.
     *
     * <p>Without {@code workflowId} this returns the most recent instances across every workflow;
     * with it, the instances of that one workflow. There is no time-range filter — the monitor is a
     * recent-executions view.
     */
    @GetMapping("/workflow-instances")
    public Result<WorkflowExecutionPage> getWorkflowInstances(
            @RequestParam(required = false) Long workflowId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return Result.success(
                workflowExecutionMonitorService.listWorkflowInstances(
                        workflowId, status, refresh, pageNum, pageSize));
    }

    /** Load task instances only when a workflow-instance row is expanded. */
    @GetMapping("/workflows/{workflowId}/instances/{instanceId}/tasks")
    public Result<List<WorkflowTaskInstanceExecution>> getWorkflowTaskInstances(
            @PathVariable Long workflowId, @PathVariable Long instanceId) {
        return Result.success(
                workflowExecutionMonitorService.listTaskInstances(workflowId, instanceId));
    }
}
