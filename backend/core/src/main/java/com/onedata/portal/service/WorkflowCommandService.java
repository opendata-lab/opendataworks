package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onedata.portal.dto.workflow.WorkflowDefinitionRequest;
import com.onedata.portal.dto.workflow.WorkflowTaskBinding;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.DolphinConfig;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.entity.WorkflowVersion;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowVersionMapper;
import com.onedata.portal.util.JsonCanonicalizer;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;



/**
 * 工作流写命令服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowCommandService {

    private static final int SNAPSHOT_SCHEMA_VERSION_DEFINITION = 3;

    private final DataWorkflowMapper dataWorkflowMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataTaskMapper dataTaskMapper;
    private final DataTaskIdentityArchiver dataTaskIdentityArchiver;
    private final DataLineageMapper dataLineageMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final WorkflowVersionService workflowVersionService;
    private final WorkflowVersionMapper workflowVersionMapper;
    private final ObjectMapper objectMapper;
    private final WorkflowTopologyService workflowTopologyService;
    private final DolphinConfigService dolphinConfigService;
    private final WorkflowTaskRelationService workflowTaskRelationService;
    private final WorkflowDefinitionAssembler workflowDefinitionAssembler;

    public DataWorkflow createWorkflow(WorkflowDefinitionRequest request) {
        DataWorkflow workflow = new DataWorkflow();
        LocalDateTime now = LocalDateTime.now();
        List<WorkflowTaskBinding> taskBindings = normalizeTaskBindings(request.getTasks());
        request.setTasks(taskBindings);
        List<Long> taskIdsInOrder = workflowTaskRelationService.collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setDefinitionJson(workflowDefinitionAssembler.defaultJson(request.getDefinitionJson()));
        workflow.setEntryTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflow.setGlobalParams(request.getGlobalParams());
        workflow.setTaskGroupName(request.getTaskGroupName());
        workflow.setStatus("draft");
        workflow.setPublishStatus("never");
        workflow.setDolphinConfigId(resolveDolphinConfigId(request.getDolphinConfigId()));
        workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        workflow.setCreatedBy(request.getOperator());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setCreatedAt(now);
        workflow.setUpdatedAt(now);
        workflowDefinitionAssembler.normalizeWorkflowScheduleDefaults(workflow);
        dataWorkflowMapper.insert(workflow);

        workflowTaskRelationService.persistTaskRelations(workflow.getId(), taskBindings, null, topology);
        workflowDefinitionAssembler.normalizeTaskMetadata(taskIdsInOrder, workflow.getTaskGroupName());

        String resolvedDefinitionJson = workflowDefinitionAssembler.resolveDefinitionJson(workflow, request, taskBindings, topology);
        workflow.setDefinitionJson(resolvedDefinitionJson);
        dataWorkflowMapper.updateById(workflow);

        WorkflowVersion version = snapshotWorkflow(workflow, request, resolvedDefinitionJson);
        workflow.setCurrentVersionId(version.getId());
        dataWorkflowMapper.updateById(workflow);

        updateRelationVersion(workflow.getId(), version.getId());
        return workflow;
    }

    public DataWorkflow updateWorkflow(Long workflowId, WorkflowDefinitionRequest request) {
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        List<WorkflowTaskBinding> taskBindings = normalizeTaskBindings(request.getTasks());
        request.setTasks(taskBindings);
        List<Long> taskIdsInOrder = workflowTaskRelationService.collectTaskIds(taskBindings);
        WorkflowTopologyResult topology = workflowTopologyService.buildTopology(taskIdsInOrder);
        workflow.setWorkflowName(request.getWorkflowName());
        workflow.setDescription(request.getDescription());
        workflow.setEntryTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getEntryTaskIds(), taskIdsInOrder)));
        workflow.setExitTaskIds(workflowDefinitionAssembler.toJson(
                workflowDefinitionAssembler.orderTaskIds(topology.getExitTaskIds(), taskIdsInOrder)));
        workflow.setGlobalParams(request.getGlobalParams());
        workflow.setTaskGroupName(request.getTaskGroupName());
        workflow.setUpdatedBy(request.getOperator());
        workflow.setUpdatedAt(LocalDateTime.now());
        if (workflow.getDolphinConfigId() == null) {
            workflow.setDolphinConfigId(resolveDolphinConfigId(request.getDolphinConfigId()));
        }
        if (workflow.getProjectCode() == null || workflow.getProjectCode() == 0) {
            workflow.setProjectCode(resolveProjectCode(request.getProjectCode()));
        }
        workflowDefinitionAssembler.normalizeWorkflowScheduleDefaults(workflow);

        workflowTaskRelationService.persistTaskRelations(workflowId, taskBindings, workflow.getCurrentVersionId(), topology);
        workflowDefinitionAssembler.normalizeTaskMetadata(taskIdsInOrder, workflow.getTaskGroupName());

        String resolvedDefinitionJson = workflowDefinitionAssembler.resolveDefinitionJson(workflow, request, taskBindings, topology);
        workflow.setDefinitionJson(resolvedDefinitionJson);
        dataWorkflowMapper.updateById(workflow);

        if (shouldCreateNewVersion(workflow, resolvedDefinitionJson)) {
            WorkflowVersion version = snapshotWorkflow(workflow, request, resolvedDefinitionJson);
            workflow.setCurrentVersionId(version.getId());
            dataWorkflowMapper.updateById(workflow);
            updateRelationVersion(workflowId, version.getId());
        }
        return workflow;
    }

    public void deleteWorkflow(Long workflowId, boolean cascadeDeleteTasks) {
        if (workflowId == null) {
            throw new IllegalArgumentException("工作流ID不能为空");
        }

        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            log.warn("工作流不存在: {}", workflowId);
            return;
        }

        List<Long> taskIds = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId))
                .stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        log.info("开始删除工作流: workflowId={}, workflowCode={}, cascadeDeleteTasks={}, taskCount={}",
                workflowId, workflow.getWorkflowCode(), cascadeDeleteTasks, taskIds.size());

        try {
            if (workflow.getWorkflowCode() != null && workflow.getWorkflowCode() > 0) {
                try {
                    Long dolphinConfigId = workflow.getDolphinConfigId();
                    boolean dolphinWorkflowExists = dolphinSchedulerService.checkWorkflowExists(
                            dolphinConfigId, workflow.getWorkflowCode());
                    if (!dolphinWorkflowExists) {
                        log.info("DolphinScheduler中不存在工作流，跳过同步删除: {}", workflow.getWorkflowCode());
                    } else {
                        if (workflow.getDolphinScheduleId() != null && workflow.getDolphinScheduleId() > 0) {
                            try {
                                dolphinSchedulerService.offlineWorkflowSchedule(
                                        dolphinConfigId, workflow.getDolphinScheduleId());
                            } catch (Exception ex) {
                                log.warn("Failed to offline schedule {} before workflow delete: {}",
                                        workflow.getDolphinScheduleId(), ex.getMessage());
                            }
                        }
                        dolphinSchedulerService.setWorkflowReleaseState(
                                dolphinConfigId, workflow.getWorkflowCode(), "OFFLINE");
                        dolphinSchedulerService.deleteWorkflow(dolphinConfigId, workflow.getWorkflowCode());
                        log.info("已删除DolphinScheduler中的工作流定义: {}", workflow.getWorkflowCode());
                    }
                } catch (Exception e) {
                    log.warn("删除DolphinScheduler工作流定义失败: {}", e.getMessage());
                }
            }

            if (cascadeDeleteTasks && !taskIds.isEmpty()) {
                dataLineageMapper.delete(
                        Wrappers.<DataLineage>lambdaQuery()
                                .in(DataLineage::getTaskId, taskIds));
                tableTaskRelationMapper.delete(
                        Wrappers.<TableTaskRelation>lambdaQuery()
                                .in(TableTaskRelation::getTaskId, taskIds));
                // 与单任务删除一致：软删除前归档唯一键，否则同名任务再次级联删除会触发 duplicate key
                dataTaskIdentityArchiver.archiveByIds(taskIds);
                dataTaskMapper.deleteBatchIds(taskIds);
                log.info("已级联软删除任务: workflowId={}, taskCount={}", workflowId, taskIds.size());
            }

            workflowTaskRelationMapper.hardDeleteByWorkflowId(workflowId);
            log.info("已删除工作流任务关联关系: workflowId={}", workflowId);

            dataWorkflowMapper.deleteById(workflowId);
            log.info("已软删除工作流定义: {}", workflowId);

            log.info("工作流删除完成: workflowId={}", workflowId);
        } catch (Exception e) {
            log.error("删除工作流失败: {}", workflowId, e);
            throw new RuntimeException("删除工作流失败: " + e.getMessage(), e);
        }
    }

    private void updateRelationVersion(Long workflowId, Long versionId) {
        WorkflowTaskRelation update = new WorkflowTaskRelation();
        update.setVersionId(versionId);
        workflowTaskRelationMapper.update(update,
                Wrappers.<WorkflowTaskRelation>lambdaUpdate()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
    }

    private WorkflowVersion snapshotWorkflow(DataWorkflow workflow,
            WorkflowDefinitionRequest request,
            String snapshotJson) {
        boolean isInitial = workflow.getCurrentVersionId() == null;
        String changeSummary = isInitial ? "initial workflow definition" : "updated workflow definition";
        return workflowVersionService.createVersion(
                workflow.getId(),
                snapshotJson,
                StringUtils.hasText(request.getDescription()) ? request.getDescription() : changeSummary,
                request.getTriggerSource(),
                request.getOperator(),
                SNAPSHOT_SCHEMA_VERSION_DEFINITION,
                null);
    }

    private boolean shouldCreateNewVersion(DataWorkflow workflow, String incomingSnapshotJson) {
        if (workflow == null) {
            return true;
        }
        if (workflow.getCurrentVersionId() == null) {
            return true;
        }
        WorkflowVersion currentVersion = workflowVersionMapper.selectById(workflow.getCurrentVersionId());
        if (currentVersion == null || !StringUtils.hasText(currentVersion.getStructureSnapshot())) {
            return true;
        }
        if (!Objects.equals(currentVersion.getSnapshotSchemaVersion(), SNAPSHOT_SCHEMA_VERSION_DEFINITION)) {
            return true;
        }
        String currentHash = snapshotContentHash(currentVersion.getStructureSnapshot());
        String incomingHash = snapshotContentHash(incomingSnapshotJson);
        if (!StringUtils.hasText(currentHash) || !StringUtils.hasText(incomingHash)) {
            return true;
        }
        return !Objects.equals(currentHash, incomingHash);
    }

    private String snapshotContentHash(String snapshotJson) {
        if (!StringUtils.hasText(snapshotJson)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(snapshotJson);
            if (node != null && node.isObject()) {
                ((ObjectNode) node).remove("meta");
            }
            String normalized = node != null ? JsonCanonicalizer.canonicalize(node) : snapshotJson.trim();
            return JsonCanonicalizer.sha256(normalized);
        } catch (Exception e) {
            // 非法 JSON 时退化为对原始文本取哈希
            log.trace("规范化快照 JSON 失败，回退原始文本哈希", e);
            return JsonCanonicalizer.sha256(snapshotJson.trim());
        }
    }

    private List<WorkflowTaskBinding> normalizeTaskBindings(List<WorkflowTaskBinding> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return Collections.emptyList();
        }
        return tasks;
    }

    private Long resolveProjectCode(Long requestProjectCode) {
        if (requestProjectCode != null && requestProjectCode > 0) {
            return requestProjectCode;
        }
        return null;
    }

    private Long resolveDolphinConfigId(Long requestDolphinConfigId) {
        if (requestDolphinConfigId != null && requestDolphinConfigId > 0) {
            return dolphinConfigService.getEnabledConfig(requestDolphinConfigId).getId();
        }
        DolphinConfig config = dolphinConfigService.getDefaultConfig();
        return config != null ? config.getId() : null;
    }
}
