package com.onedata.portal.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.onedata.portal.dto.PageResult;
import com.onedata.portal.dto.Result;
import com.onedata.portal.dto.WorkflowFreshnessResponse;
import com.onedata.portal.dto.workflow.WorkflowApprovalRequest;
import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import com.onedata.portal.dto.workflow.WorkflowExportJsonResponse;
import com.onedata.portal.dto.workflow.WorkflowLineageConsistencyResponse;
import com.onedata.portal.dto.workflow.WorkflowImportCommitRequest;
import com.onedata.portal.dto.workflow.WorkflowImportCommitResponse;
import com.onedata.portal.dto.workflow.WorkflowImportPreviewRequest;
import com.onedata.portal.dto.workflow.WorkflowImportPreviewResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionCompareResponse;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowDetailResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishPreviewResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRepairRequest;
import com.onedata.portal.dto.workflow.WorkflowPublishRepairResponse;
import com.onedata.portal.dto.workflow.WorkflowPublishRequest;
import com.onedata.portal.dto.workflow.WorkflowQueryRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackRequest;
import com.onedata.portal.dto.workflow.WorkflowVersionRollbackResponse;
import com.onedata.portal.dto.workflow.WorkflowVersionDeleteResponse;
import com.onedata.portal.dto.workflow.WorkflowScheduleRequest;
import com.onedata.portal.dto.workflow.WorkflowSchedulerEngineRequest;
import com.onedata.portal.dto.workflow.runtime.DolphinRuntimeWorkflowOption;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.WorkflowPublishRecord;
import com.onedata.portal.service.WorkflowPublishService;
import com.onedata.portal.service.WorkflowDefinitionLifecycleService;
import com.onedata.portal.service.WorkflowScheduleService;
import com.onedata.portal.service.WorkflowService;
import com.onedata.portal.service.freshness.TableFreshnessService;
import com.onedata.portal.service.lineage.TaskLineageConsistencyChecker;
import com.onedata.portal.service.WorkflowVersionOperationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 工作流管理 API
 */
@RestController
@RequestMapping("/v1/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;
    private final WorkflowPublishService workflowPublishService;
    private final WorkflowScheduleService workflowScheduleService;
    private final WorkflowVersionOperationService workflowVersionOperationService;
    private final WorkflowDefinitionLifecycleService workflowDefinitionLifecycleService;
    private final TaskLineageConsistencyChecker lineageConsistencyChecker;
    private final TableFreshnessService tableFreshnessService;

    @GetMapping
    public Result<PageResult<DataWorkflow>> list(WorkflowQueryRequest request) {
        Page<DataWorkflow> page = workflowService.list(request);
        return Result.success(PageResult.of(page.getTotal(), page.getRecords()));
    }

    @GetMapping("/{id}")
    public Result<WorkflowDetailResponse> detail(@PathVariable Long id) {
        return Result.success(workflowService.getDetail(id));
    }

    /**
     * 工作流数据新鲜度：写出表最新状态汇总、每次运行的问题表数、逐表最新结果。
     */
    @GetMapping("/{id}/freshness")
    public Result<WorkflowFreshnessResponse> freshness(@PathVariable Long id) {
        try {
            return Result.success(tableFreshnessService.workflowFreshness(id));
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    @PostMapping("/{id}/versions/compare")
    public Result<WorkflowVersionCompareResponse> compareVersions(@PathVariable Long id,
                                                                  @RequestBody WorkflowVersionCompareRequest request) {
        return Result.success(workflowVersionOperationService.compare(id, request));
    }

    @PostMapping("/{id}/versions/{versionId}/rollback")
    public Result<WorkflowVersionRollbackResponse> rollbackVersion(@PathVariable Long id,
                                                                   @PathVariable Long versionId,
                                                                   @RequestBody WorkflowVersionRollbackRequest request) {
        return Result.success(workflowVersionOperationService.rollback(id, versionId, request));
    }

    @DeleteMapping("/{id}/versions/{versionId}")
    public Result<WorkflowVersionDeleteResponse> deleteVersion(@PathVariable Long id,
                                                               @PathVariable Long versionId) {
        return Result.success(workflowVersionOperationService.deleteVersion(id, versionId));
    }

    @PostMapping
    public Result<DataWorkflow> create(@RequestBody WorkflowDefinitionRequest request) {
        DataWorkflow workflow = workflowService.createWorkflow(request);
        return Result.success(workflow);
    }

    @PostMapping("/import/preview")
    public Result<WorkflowImportPreviewResponse> previewImport(@RequestBody WorkflowImportPreviewRequest request) {
        return Result.success(workflowDefinitionLifecycleService.preview(request));
    }

    @GetMapping("/import/dolphin")
    public Result<PageResult<DolphinRuntimeWorkflowOption>> listDolphinImportWorkflows(
            @RequestParam(required = false) Long dolphinConfigId,
            @RequestParam(required = false) Long projectCode,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(required = false) String keyword) {
        return Result.success(workflowDefinitionLifecycleService.listDolphinWorkflows(
                dolphinConfigId, projectCode, pageNum, pageSize, keyword));
    }

    @GetMapping("/import/dolphin/{workflowCode}")
    public Result<DolphinRuntimeWorkflowOption> findDolphinImportWorkflow(
            @PathVariable Long workflowCode,
            @RequestParam(required = false) Long dolphinConfigId) {
        return Result.success(workflowDefinitionLifecycleService.findDolphinWorkflow(dolphinConfigId, workflowCode));
    }

    @PostMapping("/import/commit")
    public Result<WorkflowImportCommitResponse> commitImport(@RequestBody WorkflowImportCommitRequest request) {
        return Result.success(workflowDefinitionLifecycleService.commit(request));
    }

    @PutMapping("/{id}")
    public Result<DataWorkflow> update(@PathVariable Long id,
                                       @RequestBody WorkflowDefinitionRequest request) {
        DataWorkflow workflow = workflowService.updateWorkflow(id, request);
        return Result.success(workflow);
    }

    @PutMapping("/{id}/scheduler-engine")
    public Result<DataWorkflow> switchSchedulerEngine(@PathVariable Long id,
                                                      @RequestBody WorkflowSchedulerEngineRequest request) {
        return Result.success(workflowService.switchSchedulerEngine(id, request));
    }

    @GetMapping("/{id}/export-json")
    public Result<WorkflowExportJsonResponse> exportJson(@PathVariable Long id) {
        return Result.success(workflowDefinitionLifecycleService.exportJson(id));
    }

    /**
     * 只读血缘一致性报告。用于在切换 {@code workflow.lineage-consistency.enforcement-mode}
     * 之前扫描存量工作流，确认阻断范围。
     */
    @GetMapping("/{id}/lineage-consistency")
    public Result<WorkflowLineageConsistencyResponse> lineageConsistency(@PathVariable Long id) {
        return Result.success(lineageConsistencyChecker.report(id));
    }

    @PostMapping("/{id}/publish")
    public Result<WorkflowPublishRecord> publish(@PathVariable Long id,
                                                 @RequestBody WorkflowPublishRequest request) {
        WorkflowPublishRecord record = workflowPublishService.publish(id, request);
        return Result.success(record);
    }

    @GetMapping("/{id}/publish/preview")
    public Result<WorkflowPublishPreviewResponse> previewPublish(@PathVariable Long id) {
        return Result.success(workflowPublishService.previewPublish(id));
    }

    @PostMapping("/{id}/publish/repair-metadata")
    public Result<WorkflowPublishRepairResponse> repairPublishMetadata(@PathVariable Long id,
                                                                       @RequestBody(required = false) WorkflowPublishRepairRequest request) {
        return Result.success(workflowPublishService.repairPublishMetadata(id, request));
    }

    @PostMapping("/{id}/publish/{recordId}/approve")
    public Result<WorkflowPublishRecord> approve(@PathVariable Long id,
                                                 @PathVariable Long recordId,
                                                 @RequestBody WorkflowApprovalRequest request) {
        WorkflowPublishRecord record = workflowPublishService.approve(id, recordId, request);
        return Result.success(record);
    }

    @PostMapping("/{id}/execute")
    public Result<String> execute(@PathVariable Long id) {
        String executionId = workflowService.executeWorkflow(id);
        return Result.success(executionId);
    }

    @PostMapping("/{id}/backfill")
    public Result<String> backfill(@PathVariable Long id, @RequestBody WorkflowBackfillRequest request) {
        String triggerId = workflowService.backfillWorkflow(id, request);
        return Result.success(triggerId);
    }

    @PutMapping("/{id}/schedule")
    public Result<DataWorkflow> upsertSchedule(@PathVariable Long id, @RequestBody WorkflowScheduleRequest request) {
        DataWorkflow workflow = workflowScheduleService.upsertSchedule(id, request);
        return Result.success(workflow);
    }

    @PostMapping("/{id}/schedule/online")
    public Result<DataWorkflow> onlineSchedule(@PathVariable Long id) {
        DataWorkflow workflow = workflowScheduleService.onlineSchedule(id);
        return Result.success(workflow);
    }

    @PostMapping("/{id}/schedule/offline")
    public Result<DataWorkflow> offlineSchedule(@PathVariable Long id) {
        DataWorkflow workflow = workflowScheduleService.offlineSchedule(id);
        return Result.success(workflow);
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id,
                               @RequestParam(defaultValue = "false") Boolean cascadeDeleteTasks) {
        workflowService.deleteWorkflow(id, Boolean.TRUE.equals(cascadeDeleteTasks));
        return Result.success();
    }
}
