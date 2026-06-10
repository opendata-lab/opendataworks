package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.SqlQueryRequest;
import com.onedata.portal.dto.SqlQueryResponse;
import com.onedata.portal.dto.TaskExecutionStatus;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.DorisCluster;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.exception.BusinessException;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

/**
 * 任务服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataTaskService {

    private static final int DEFAULT_TASK_PRIORITY = 5;
    private static final int DEFAULT_TASK_VERSION = 1;
    private static final int DEFAULT_TASK_RETRY_TIMES = 1;
    private static final int DEFAULT_TASK_RETRY_INTERVAL = 1;
    private static final int DEFAULT_TASK_TIMEOUT_SECONDS = 60;
    private static final String DEFAULT_DOLPHIN_FLAG = "YES";
    private static final String DEFAULT_OPERATOR = "system";

    private final DataTaskMapper dataTaskMapper;
    private final DataLineageMapper dataLineageMapper;
    private final TaskExecutionLogMapper executionLogMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataQueryService dataQueryService;
    private final DorisClusterService dorisClusterService;
    private final WorkflowService workflowService;

    /**
     * 分页查询任务列表
     */
    public Page<DataTask> list(int pageNum,
            int pageSize,
            String taskType,
            String dolphinNodeType,
            String status,
            String taskName,
            Long workflowId,
            Long upstreamTaskId,
            Long downstreamTaskId) {
        Page<DataTask> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DataTask> wrapper = new LambdaQueryWrapper<>();

        if (StringUtils.hasText(taskName)) {
            wrapper.like(DataTask::getTaskName, taskName);
        }

        if (taskType != null && !taskType.isEmpty()) {
            wrapper.eq(DataTask::getTaskType, taskType);
        }
        if (StringUtils.hasText(dolphinNodeType)) {
            wrapper.eq(DataTask::getDolphinNodeType, dolphinNodeType);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(DataTask::getStatus, status);
        }

        if (workflowId != null) {
            List<Long> workflowTaskIds = findTaskIdsByWorkflow(workflowId);
            applyIdFilter(wrapper, workflowTaskIds);
        }

        if (upstreamTaskId != null) {
            List<Long> downstreamTaskIds = findDownstreamTaskIds(upstreamTaskId);
            applyIdFilter(wrapper, downstreamTaskIds);
        }

        if (downstreamTaskId != null) {
            List<Long> upstreamTaskIdsForDownstream = findUpstreamTaskIds(downstreamTaskId);
            applyIdFilter(wrapper, upstreamTaskIdsForDownstream);
        }

        wrapper.orderByDesc(DataTask::getCreatedAt);
        Page<DataTask> result = dataTaskMapper.selectPage(page, wrapper);
        enrichWorkflowMetadata(result.getRecords());
        attachExecutionStatus(result.getRecords());
        return result;
    }

    /**
     * 分页查询指定用户的任务列表
     */
    public Page<DataTask> listByOwner(String owner,
            int pageNum,
            int pageSize,
            String taskType,
            String status,
            Long workflowId,
            Long upstreamTaskId,
            Long downstreamTaskId) {
        Page<DataTask> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<DataTask> wrapper = new LambdaQueryWrapper<>();

        // 添加owner过滤条件
        wrapper.eq(DataTask::getOwner, owner);

        if (taskType != null && !taskType.isEmpty()) {
            wrapper.eq(DataTask::getTaskType, taskType);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(DataTask::getStatus, status);
        }

        if (workflowId != null) {
            List<Long> workflowTaskIds = findTaskIdsByWorkflow(workflowId);
            applyIdFilter(wrapper, workflowTaskIds);
        }

        if (upstreamTaskId != null) {
            List<Long> downstreamTaskIds = findDownstreamTaskIds(upstreamTaskId);
            applyIdFilter(wrapper, downstreamTaskIds);
        }

        if (downstreamTaskId != null) {
            List<Long> upstreamTaskIdsForDownstream = findUpstreamTaskIds(downstreamTaskId);
            applyIdFilter(wrapper, upstreamTaskIdsForDownstream);
        }

        wrapper.orderByDesc(DataTask::getCreatedAt);
        Page<DataTask> result = dataTaskMapper.selectPage(page, wrapper);
        enrichWorkflowMetadata(result.getRecords());
        attachExecutionStatus(result.getRecords());
        return result;
    }

    /**
     * 根据ID获取任务
     */
    public DataTask getById(Long id) {
        DataTask task = dataTaskMapper.selectById(id);
        if (task == null) {
            return null;
        }
        enrichWorkflowMetadata(Collections.singletonList(task));
        return task;
    }

    /**
     * 获取 DolphinScheduler 数据源选项
     */
    public List<DolphinDatasourceOption> listDatasourceOptions(String type, String keyword) {
        return dolphinSchedulerService.listDatasources(type, keyword);
    }

    public List<DolphinDatasourceOption> listDatasourceOptions(String type, String keyword, Long dolphinConfigId) {
        return dolphinSchedulerService.listDatasources(type, keyword, dolphinConfigId);
    }

    /**
     * 创建任务
     */
    @Transactional
    public DataTask create(DataTask task, List<Long> inputTableIds, List<Long> outputTableIds) {
        validateTask(task, inputTableIds, outputTableIds);

        // 检查任务名称是否已存在
        if (isTaskNameExists(task.getTaskName())) {
            throw new BusinessException("任务名称已存在: " + task.getTaskName());
        }

        if (!StringUtils.hasText(task.getTaskCode())) {
            task.setTaskCode(generateUniqueTaskCode(task.getTaskName()));
        }
        DataTask exists = dataTaskMapper.selectOne(new LambdaQueryWrapper<DataTask>()
                .eq(DataTask::getTaskCode, task.getTaskCode()));
        if (exists != null) {
            throw new BusinessException("任务编码已存在: " + task.getTaskCode());
        }

        task.setStatus("draft");

        dataTaskMapper.insert(task);
        log.info("Created task: {}", task.getTaskName());

        // 创建血缘关系
        if (inputTableIds != null) {
            for (Long tableId : inputTableIds) {
                DataLineage lineage = new DataLineage();
                lineage.setTaskId(task.getId());
                lineage.setUpstreamTableId(tableId);
                lineage.setLineageType("input");
                dataLineageMapper.insert(lineage);
            }
        }

        if (outputTableIds != null) {
            for (Long tableId : outputTableIds) {
                DataLineage lineage = new DataLineage();
                lineage.setTaskId(task.getId());
                lineage.setDownstreamTableId(tableId);
                lineage.setLineageType("output");
                dataLineageMapper.insert(lineage);
            }
        }

        // 维护表与任务的关联关系
        saveTableTaskRelations(task.getId(), inputTableIds, outputTableIds);

        // 如果提供了 wokflowId，建立工作流关联
        if (task.getWorkflowId() != null) {
            WorkflowTaskRelation existingRelation = workflowTaskRelationMapper.selectOne(
                    Wrappers.<WorkflowTaskRelation>lambdaQuery()
                            .eq(WorkflowTaskRelation::getWorkflowId, task.getWorkflowId())
                            .eq(WorkflowTaskRelation::getTaskId, task.getId()));

            if (existingRelation == null) {
                WorkflowTaskRelation relation = new WorkflowTaskRelation();
                relation.setWorkflowId(task.getWorkflowId());
                relation.setTaskId(task.getId());
                relation.setUpstreamTaskCount(tableTaskRelationMapper.countUpstreamTasks(task.getId()));
                relation.setDownstreamTaskCount(tableTaskRelationMapper.countDownstreamTasks(task.getId()));
                workflowTaskRelationMapper.insert(relation);
            }

            // 重新计算该工作流中所有任务的上下游关系
            workflowService.refreshTaskRelations(task.getWorkflowId());
        }

        normalizeTaskMetadataOnPersist(task.getId(), task.getWorkflowId(), null, task.getOwner());
        DataTask persisted = dataTaskMapper.selectById(task.getId());
        return persisted != null ? persisted : task;
    }

    /**
     * 更新任务
     */
    @Transactional
    public DataTask update(DataTask task, List<Long> inputTableIds, List<Long> outputTableIds) {
        validateTask(task, inputTableIds, outputTableIds);
        DataTask exists = dataTaskMapper.selectById(task.getId());
        if (exists == null) {
            throw new BusinessException("任务不存在");
        }

        WorkflowTaskRelation workflowRelation = workflowTaskRelationMapper.selectOne(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getTaskId, task.getId()));
        Long previousWorkflowId = workflowRelation != null ? workflowRelation.getWorkflowId() : null;

        // 检查任务名称是否已被其他任务使用
        if (!StringUtils.hasText(task.getTaskName())) {
            // taskName 为空时不检查
        } else if (!task.getTaskName().equals(exists.getTaskName())
                && isTaskNameExists(task.getTaskName(), task.getId())) {
            throw new BusinessException("任务名称已存在: " + task.getTaskName());
        }

        // 更新任务基本信息
        dataTaskMapper.updateById(task);
        log.info("Updated task: {}", task.getTaskName());

        // 删除旧的血缘关系
        dataLineageMapper.delete(
                new LambdaQueryWrapper<DataLineage>()
                        .eq(DataLineage::getTaskId, task.getId()));

        clearTableTaskRelations(task.getId());

        // 创建新的输入血缘关系
        if (inputTableIds != null) {
            for (Long tableId : inputTableIds) {
                DataLineage lineage = new DataLineage();
                lineage.setTaskId(task.getId());
                lineage.setUpstreamTableId(tableId);
                lineage.setLineageType("input");
                dataLineageMapper.insert(lineage);
            }
        }

        // 创建新的输出血缘关系
        if (outputTableIds != null) {
            for (Long tableId : outputTableIds) {
                DataLineage lineage = new DataLineage();
                lineage.setTaskId(task.getId());
                lineage.setDownstreamTableId(tableId);
                lineage.setLineageType("output");
                dataLineageMapper.insert(lineage);
            }
        }

        saveTableTaskRelations(task.getId(), inputTableIds, outputTableIds);

        syncWorkflowRelation(task.getId(), task.getWorkflowId(), workflowRelation, previousWorkflowId);

        normalizeTaskMetadataOnPersist(task.getId(), task.getWorkflowId(), previousWorkflowId, task.getOwner());
        DataTask persisted = dataTaskMapper.selectById(task.getId());
        return persisted != null ? persisted : task;
    }

    /**
     * 更新任务（仅基本信息，不更新血缘）
     * 
     * @deprecated 使用 update(DataTask, List<Long>, List<Long>) 代替
     */
    @Deprecated
    @Transactional
    public DataTask update(DataTask task) {
        com.onedata.portal.controller.DataTaskController.TaskLineageResponse lineage = getTaskLineage(task.getId());
        return update(task, lineage.getInputTableIds(), lineage.getOutputTableIds());
    }

    /**
     * 发布任务到 DolphinScheduler。该操作会同步所有 Dolphin 引擎的任务定义，
     * 根据血缘信息自动建立上下游依赖。
     */
    @Transactional
    public void publish(Long taskId) {
        log.info("开始发布任务: taskId={}", taskId);

        DataTask target = dataTaskMapper.selectById(taskId);
        if (target == null) {
            log.error("任务不存在: taskId={}", taskId);
            throw new RuntimeException("任务不存在: ID=" + taskId);
        }
        if (!"dolphin".equalsIgnoreCase(target.getEngine())) {
            log.error("不支持的引擎类型: taskId={}, engine={}", taskId, target.getEngine());
            throw new RuntimeException("仅支持 Dolphin 引擎任务发布，当前引擎: " + target.getEngine());
        }

        log.info("任务信息: taskCode={}, taskName={}, engine={}",
                target.getTaskCode(), target.getTaskName(), target.getEngine());

        // 查询全部 Dolphin 任务，构建统一工作流
        log.info("查询所有 Dolphin 引擎任务...");
        List<DataTask> dolphinTasks = dataTaskMapper.selectList(
                new LambdaQueryWrapper<DataTask>()
                        .eq(DataTask::getEngine, "dolphin")
                        .orderByAsc(DataTask::getId));
        if (dolphinTasks.isEmpty()) {
            log.error("未找到任何 Dolphin 引擎任务");
            throw new RuntimeException("未找到任何 Dolphin 引擎任务");
        }
        log.info("找到 {} 个 Dolphin 引擎任务", dolphinTasks.size());
        validatePublishMetadata(dolphinTasks);
        target = dolphinTasks.stream()
                .filter(task -> Objects.equals(task.getId(), taskId))
                .findFirst()
                .orElse(target);

        // 强制刷新 project code 缓存,确保使用最新的项目信息
        // 这对于 DolphinScheduler 重置后获取正确的 projectCode 很重要
        dolphinSchedulerService.clearProjectCodeCache();
        log.info("已清除 project code 缓存");

        // 获取已存在的 workflow code (如果有的话)
        // 如果数据库中有记录,尝试复用;如果没有或者 syncWorkflow 失败,会创建新的
        Long existingWorkflowCode = dolphinTasks.stream()
                .map(DataTask::getDolphinProcessCode)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);

        long workflowCode = existingWorkflowCode != null ? existingWorkflowCode : 0L;
        log.info("使用 workflow code: {} ({})", workflowCode,
                workflowCode > 0 ? "更新现有工作流" : "创建新工作流");

        Map<Long, DataTask> taskMap = dolphinTasks.stream()
                .collect(java.util.stream.Collectors.toMap(DataTask::getId, t -> t));

        List<Map<String, Object>> definitions = new ArrayList<>();
        List<DolphinSchedulerService.TaskRelationPayload> relations = new ArrayList<>();
        List<DolphinSchedulerService.TaskLocationPayload> locations = new ArrayList<>();

        List<DolphinTaskGroupOption> allTaskGroups = dolphinSchedulerService.listTaskGroups(null);
        Map<String, DolphinTaskGroupOption> taskGroupMap = allTaskGroups.stream()
                .collect(Collectors.toMap(DolphinTaskGroupOption::getName, opt -> opt,
                        (v1, v2) -> v1));
        Map<String, DolphinDatasourceOption> datasourceByName = dolphinTasks.stream()
                .anyMatch(task -> StringUtils.hasText(task.getDatasourceName())
                        || StringUtils.hasText(task.getTargetDatasourceName()))
                                ? loadDatasourceCatalogByName()
                                : Collections.emptyMap();

        int index = 0;
        for (DataTask dataTask : dolphinTasks) {
            String priority = mapPriority(dataTask.getPriority(), dataTask);
            int version = dataTask.getDolphinTaskVersion();
            String nodeType = dataTask.getDolphinNodeType().trim();
            String sqlOrScript;

            // For SQL node, use raw SQL; for SHELL, wrap in shell script
            if ("SQL".equalsIgnoreCase(nodeType)) {
                sqlOrScript = dataTask.getTaskSql();
            } else if ("DATAX".equalsIgnoreCase(nodeType)) {
                sqlOrScript = null; // DataX doesn't use sqlOrScript
            } else {
                sqlOrScript = dolphinSchedulerService.buildShellScript(dataTask.getTaskSql());
            }

            Long datasourceId = null;
            Long targetDatasourceId = null;
            String targetDatasourceType = null;
            if ("DATAX".equalsIgnoreCase(nodeType)) {
                // For DataX, get both source and target datasource IDs and types
                if (StringUtils.hasText(dataTask.getDatasourceName())) {
                    DolphinDatasourceOption sourceOption = resolveDatasourceOptionByName(
                            datasourceByName, dataTask.getDatasourceName());
                    datasourceId = sourceOption != null ? sourceOption.getId() : null;
                    if (datasourceId == null) {
                        throw new IllegalStateException(String.format(
                                "Source datasource '%s' not found for task '%s'",
                                dataTask.getDatasourceName(), taskLabel(dataTask)));
                    }
                }
                if (StringUtils.hasText(dataTask.getTargetDatasourceName())) {
                    DolphinDatasourceOption targetOption = resolveDatasourceOptionByName(
                            datasourceByName, dataTask.getTargetDatasourceName());
                    targetDatasourceId = targetOption != null ? targetOption.getId() : null;
                    if (targetDatasourceId == null) {
                        throw new IllegalStateException(String.format(
                                "Target datasource '%s' not found for task '%s'",
                                dataTask.getTargetDatasourceName(), taskLabel(dataTask)));
                    }
                    targetDatasourceType = resolveDatasourceType(targetOption, null);
                }
                if (datasourceId == null || targetDatasourceId == null) {
                    throw new IllegalStateException(String.format(
                            "DataX task '%s' 缺少有效数据源映射(source=%s,target=%s)",
                            taskLabel(dataTask), dataTask.getDatasourceName(), dataTask.getTargetDatasourceName()));
                }
            } else if (StringUtils.hasText(dataTask.getDatasourceName())) {
                // For SQL, only get source datasource
                DolphinDatasourceOption datasourceOption = resolveDatasourceOptionByName(
                        datasourceByName, dataTask.getDatasourceName());
                datasourceId = datasourceOption != null ? datasourceOption.getId() : null;
                syncTaskDatasourceTypeFromCatalog(dataTask, datasourceOption);
            }
            if ("SQL".equalsIgnoreCase(nodeType) && datasourceId == null) {
                throw new IllegalStateException(String.format(
                        "Datasource '%s' not found for task '%s'",
                        dataTask.getDatasourceName(), taskLabel(dataTask)));
            }

            Integer taskGroupId = null;
            if (StringUtils.hasText(dataTask.getTaskGroupName())) {
                String groupName = dataTask.getTaskGroupName();
                DolphinTaskGroupOption group = taskGroupMap.get(groupName);
                if (group == null) {
                    List<DolphinTaskGroupOption> refreshed = dolphinSchedulerService.listTaskGroups(groupName);
                    group = refreshed.stream()
                            .filter(g -> Objects.equals(g.getName(), groupName))
                            .findFirst()
                            .orElse(null);
                }
                if (group == null) {
                    throw new IllegalStateException(String.format(
                            "Task group '%s' not found for task '%s'. Please check if the task group exists in DolphinScheduler.",
                            groupName, dataTask.getTaskName()));
                }
                taskGroupId = group.getId();
            }

            Map<String, Object> definition = dolphinSchedulerService.buildTaskDefinition(
                    dataTask.getDolphinTaskCode(),
                    version,
                    dataTask.getTaskName(),
                    dataTask.getTaskDesc(),
                    sqlOrScript,
                    priority,
                    dataTask.getRetryTimes(),
                    dataTask.getRetryInterval(),
                    dataTask.getTimeoutSeconds(),
                    nodeType,
                    datasourceId,
                    resolveDatasourceType(resolveDatasourceOptionByName(datasourceByName, dataTask.getDatasourceName()),
                            dataTask.getDatasourceType()),
                    targetDatasourceId,
                    targetDatasourceType,
                    dataTask.getSourceTable(),
                    dataTask.getTargetTable(),
                    dataTask.getColumnMapping(),
                    dataTask.getDolphinFlag(),
                    taskGroupId,
                    null);
            definitions.add(definition);

            List<DataTask> upstreamTasks = resolveUpstreamTasks(dataTask.getId(), taskMap);
            if (upstreamTasks.isEmpty()) {
                relations.add(dolphinSchedulerService.buildRelation(
                        0L, 0, dataTask.getDolphinTaskCode(), version));
            } else {
                for (DataTask upstream : upstreamTasks) {
                    relations.add(dolphinSchedulerService.buildRelation(
                            upstream.getDolphinTaskCode(),
                            upstream.getDolphinTaskVersion(),
                            dataTask.getDolphinTaskCode(),
                            version));
                }
            }

            int lane = computeLaneByLayer(dataTask);
            locations.add(dolphinSchedulerService.buildLocation(
                    dataTask.getDolphinTaskCode(),
                    index++,
                    lane));
        }

        // syncWorkflow 返回实际的 workflow code (如果是新建则返回新的 code)
        log.info("开始同步工作流到 DolphinScheduler: workflowCode={}, taskCount={}",
                workflowCode, definitions.size());
        try {
            if (workflowCode > 0) {
                log.info("将现有工作流置为 OFFLINE 后再同步: workflowCode={}", workflowCode);
                dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
            }

            String workflowName = resolveWorkflowDisplayName(target);

            long actualWorkflowCode = dolphinSchedulerService.syncWorkflow(
                    workflowCode,
                    workflowName,
                    null,
                    definitions,
                    relations,
                    locations,
                    null);
            log.info("工作流同步成功: actualWorkflowCode={}", actualWorkflowCode);

            // 更新所有 dolphin 任务的 dolphinProcessCode
            log.info("更新任务的 workflow code...");
            for (DataTask dataTask : dolphinTasks) {
                if (!Objects.equals(dataTask.getDolphinProcessCode(), actualWorkflowCode)) {
                    dataTask.setDolphinProcessCode(actualWorkflowCode);
                    dataTaskMapper.updateById(dataTask);
                }
            }

            // Auto-release workflow to ONLINE state
            log.info("设置工作流状态为 ONLINE: workflowCode={}", actualWorkflowCode);
            dolphinSchedulerService.setWorkflowReleaseState(actualWorkflowCode, "ONLINE");

            target.setStatus("published");
            target.setDolphinProcessCode(actualWorkflowCode);
            dataTaskMapper.updateById(target);
            log.info("任务发布成功: taskId={}, taskName={}, workflowCode={}, status=ONLINE",
                    taskId, target.getTaskName(), actualWorkflowCode);
        } catch (Exception e) {
            log.error("发布任务失败: taskId={}, taskName={}, error={}",
                    taskId, target.getTaskName(), e.getMessage(), e);
            throw new RuntimeException("发布任务到 DolphinScheduler 失败: " + e.getMessage(), e);
        }
    }

    private Map<String, DolphinDatasourceOption> loadDatasourceCatalogByName() {
        List<DolphinDatasourceOption> options = dolphinSchedulerService.listDatasources(null, null);
        if (CollectionUtils.isEmpty(options)) {
            return Collections.emptyMap();
        }
        Map<String, DolphinDatasourceOption> datasourceByName = new LinkedHashMap<>();
        for (DolphinDatasourceOption option : options) {
            if (option == null || option.getId() == null || option.getId() <= 0) {
                continue;
            }
            String name = normalizeText(option.getName());
            if (StringUtils.hasText(name)) {
                datasourceByName.putIfAbsent(name, option);
            }
        }
        return datasourceByName;
    }

    private DolphinDatasourceOption resolveDatasourceOptionByName(Map<String, DolphinDatasourceOption> datasourceByName,
            String datasourceName) {
        if (CollectionUtils.isEmpty(datasourceByName) || !StringUtils.hasText(datasourceName)) {
            return null;
        }
        return datasourceByName.get(datasourceName.trim());
    }

    private String resolveDatasourceType(DolphinDatasourceOption datasourceOption, String fallbackType) {
        String catalogType = datasourceOption == null ? null : normalizeText(datasourceOption.getType());
        return StringUtils.hasText(catalogType) ? catalogType : normalizeText(fallbackType);
    }

    private void syncTaskDatasourceTypeFromCatalog(DataTask task, DolphinDatasourceOption datasourceOption) {
        if (task == null) {
            return;
        }
        String resolvedType = resolveDatasourceType(datasourceOption, task.getDatasourceType());
        if (!Objects.equals(task.getDatasourceType(), resolvedType)) {
            task.setDatasourceType(resolvedType);
            dataTaskMapper.updateById(task);
        }
    }

    private List<DataTask> resolveUpstreamTasks(Long taskId, Map<Long, DataTask> taskMap) {
        List<DataLineage> inputLineage = dataLineageMapper.selectList(
                new LambdaQueryWrapper<DataLineage>()
                        .eq(DataLineage::getTaskId, taskId)
                        .eq(DataLineage::getLineageType, "input"));

        Set<Long> upstreamTableIds = inputLineage.stream()
                .map(DataLineage::getUpstreamTableId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (upstreamTableIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<DataLineage> outputLineage = dataLineageMapper.selectList(
                new LambdaQueryWrapper<DataLineage>()
                        .in(DataLineage::getDownstreamTableId, upstreamTableIds)
                        .eq(DataLineage::getLineageType, "output"));

        return outputLineage.stream()
                .map(DataLineage::getTaskId)
                .filter(Objects::nonNull)
                .map(taskMap::get)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private void validatePublishMetadata(List<DataTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            throw new IllegalStateException("未找到可发布任务");
        }
        List<String> invalidTasks = new ArrayList<>();
        for (DataTask task : tasks) {
            if (task == null) {
                continue;
            }
            List<String> missingFields = new ArrayList<>();
            if (task.getDolphinTaskCode() == null || task.getDolphinTaskCode() <= 0) {
                missingFields.add("dolphinTaskCode");
            }
            if (task.getDolphinTaskVersion() == null || task.getDolphinTaskVersion() <= 0) {
                missingFields.add("dolphinTaskVersion");
            }
            String nodeType = normalizeText(task.getDolphinNodeType());
            if (!StringUtils.hasText(nodeType)) {
                missingFields.add("dolphinNodeType");
            }
            if (task.getPriority() == null) {
                missingFields.add("priority");
            }
            if (task.getRetryTimes() == null) {
                missingFields.add("retryTimes");
            }
            if (task.getRetryInterval() == null) {
                missingFields.add("retryInterval");
            }
            if (task.getTimeoutSeconds() == null || task.getTimeoutSeconds() <= 0) {
                missingFields.add("timeoutSeconds");
            }
            if ("SQL".equalsIgnoreCase(nodeType) && !StringUtils.hasText(task.getDatasourceName())) {
                missingFields.add("datasourceName");
            }
            if ("DATAX".equalsIgnoreCase(nodeType)) {
                if (!StringUtils.hasText(task.getDatasourceName())) {
                    missingFields.add("datasourceName");
                }
                if (!StringUtils.hasText(task.getTargetDatasourceName())) {
                    missingFields.add("targetDatasourceName");
                }
            }
            if (!missingFields.isEmpty()) {
                invalidTasks.add(taskLabel(task) + " missing=" + String.join(",", missingFields));
            }
        }
        if (!invalidTasks.isEmpty()) {
            throw new IllegalStateException("检测到任务元数据缺失，请先保存修复后再发布: "
                    + String.join("; ", invalidTasks));
        }
    }

    private String mapPriority(Integer value, DataTask task) {
        if (value == null) {
            throw new IllegalStateException(String.format(
                    "Task '%s' 缺少 priority 元数据，请先保存后再发布",
                    taskLabel(task)));
        }
        int priority = value;
        if (priority >= 9) {
            return "HIGHEST";
        } else if (priority >= 7) {
            return "HIGH";
        } else if (priority >= 5) {
            return "MEDIUM";
        } else if (priority >= 3) {
            return "LOW";
        }
        return "LOWEST";
    }

    private int computeLaneByLayer(DataTask task) {
        String type = task.getTaskType() == null ? "batch" : task.getTaskType().toLowerCase();
        if ("stream".equals(type)) {
            return 1;
        }
        if ("dim".equals(type) || "dimension".equals(type)) {
            return 2;
        }
        return 0;
    }

    private void normalizeTaskMetadataOnPersist(Long taskId, Long workflowId, Long previousWorkflowId, String operator) {
        if (taskId == null) {
            return;
        }
        DataTask task = dataTaskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        boolean changed = false;

        String datasourceName = normalizeText(task.getDatasourceName());
        if (!Objects.equals(task.getDatasourceName(), datasourceName)) {
            task.setDatasourceName(datasourceName);
            changed = true;
        }
        String datasourceType = normalizeText(task.getDatasourceType());
        if (!Objects.equals(task.getDatasourceType(), datasourceType)) {
            task.setDatasourceType(datasourceType);
            changed = true;
        }
        String taskGroupName = normalizeText(task.getTaskGroupName());
        if (!Objects.equals(task.getTaskGroupName(), taskGroupName)) {
            task.setTaskGroupName(taskGroupName);
            changed = true;
        }
        String dolphinFlag = normalizeDolphinFlag(task.getDolphinFlag());
        if (!Objects.equals(task.getDolphinFlag(), dolphinFlag)) {
            task.setDolphinFlag(dolphinFlag);
            changed = true;
        }

        if ("dolphin".equalsIgnoreCase(task.getEngine())) {
            if (task.getDolphinTaskCode() == null || task.getDolphinTaskCode() <= 0) {
                task.setDolphinTaskCode(nextAvailableTaskCode());
                changed = true;
            }
            if (task.getDolphinTaskVersion() == null || task.getDolphinTaskVersion() <= 0) {
                task.setDolphinTaskVersion(DEFAULT_TASK_VERSION);
                changed = true;
            }
            if (task.getPriority() == null) {
                task.setPriority(DEFAULT_TASK_PRIORITY);
                changed = true;
            }
            if (task.getRetryTimes() == null) {
                task.setRetryTimes(DEFAULT_TASK_RETRY_TIMES);
                changed = true;
            }
            if (task.getRetryInterval() == null) {
                task.setRetryInterval(DEFAULT_TASK_RETRY_INTERVAL);
                changed = true;
            }
            if (task.getTimeoutSeconds() == null || task.getTimeoutSeconds() <= 0) {
                task.setTimeoutSeconds(DEFAULT_TASK_TIMEOUT_SECONDS);
                changed = true;
            }
        }

        if (!StringUtils.hasText(task.getTaskGroupName()) && workflowId != null) {
            DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
            if (workflow != null && StringUtils.hasText(workflow.getTaskGroupName())) {
                task.setTaskGroupName(workflow.getTaskGroupName().trim());
                changed = true;
            }
        }

        if (changed) {
            dataTaskMapper.updateById(task);
        }

        String normalizedOperator = resolveOperator(operator);
        if (workflowId != null) {
            workflowService.normalizeAndPersistMetadata(workflowId, normalizedOperator);
        }
        if (previousWorkflowId != null && !Objects.equals(previousWorkflowId, workflowId)) {
            workflowService.normalizeAndPersistMetadata(previousWorkflowId, normalizedOperator);
        }
    }

    private Long nextAvailableTaskCode() {
        List<Long> existingCodes = dataTaskMapper.selectList(
                Wrappers.<DataTask>lambdaQuery()
                        .select(DataTask::getDolphinTaskCode)).stream()
                .filter(Objects::nonNull)
                .map(DataTask::getDolphinTaskCode)
                .filter(Objects::nonNull)
                .filter(code -> code > 0)
                .collect(Collectors.toList());
        dolphinSchedulerService.alignSequenceWithExistingTasks(existingCodes);
        return dolphinSchedulerService.nextTaskCode();
    }

    private String resolveOperator(String operator) {
        if (StringUtils.hasText(operator)) {
            return operator.trim();
        }
        return DEFAULT_OPERATOR;
    }

    private String taskLabel(DataTask task) {
        if (task == null) {
            return "unknown-task";
        }
        if (StringUtils.hasText(task.getTaskName())) {
            return task.getTaskName().trim();
        }
        return task.getId() != null ? "task#" + task.getId() : "unknown-task";
    }

    private String normalizeText(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeDolphinFlag(String value) {
        if (!StringUtils.hasText(value)) {
            return DEFAULT_DOLPHIN_FLAG;
        }
        String normalized = value.trim().toUpperCase();
        if ("YES".equals(normalized) || "NO".equals(normalized)) {
            return normalized;
        }
        throw new IllegalArgumentException("dolphinFlag 仅支持 YES 或 NO");
    }

    /**
     * @deprecated 使用 testExecute 代替
     */
    @Deprecated
    @Transactional
    public void executeTask(Long taskId) {
        DataTask task = getById(taskId);
        if (task != null) {
            executeWorkflow(taskId);
        }
    }

    /**
     * 执行整个工作流（原有逻辑）
     */
    @Transactional
    public void executeWorkflow(Long taskId) {
        DataTask task = dataTaskMapper.selectById(taskId);
        if (task == null) {
            throw new RuntimeException("任务不存在");
        }

        if (task.getDolphinProcessCode() == null) {
            throw new RuntimeException("任务未发布到工作流");
        }

        // 创建执行日志
        TaskExecutionLog executionLog = new TaskExecutionLog();
        executionLog.setTaskId(taskId);
        executionLog.setStatus("pending");
        executionLog.setStartTime(LocalDateTime.now());
        executionLog.setTriggerType("manual");
        executionLogMapper.insert(executionLog);

        // 执行统一工作流
        String executionId = dolphinSchedulerService.startProcessInstance(
                task.getDolphinProcessCode(),
                null,
                resolveWorkflowDisplayName(task));
        executionLog.setExecutionId(executionId);
        executionLog.setStatus("running");
        executionLogMapper.updateById(executionLog);

        log.info("Started workflow execution: task={} workflow={} execution={}",
                task.getTaskName(), task.getDolphinProcessCode(), executionId);
    }

    /**
     * @deprecated 使用 executeTask 或 executeWorkflow 代替
     */
    @Deprecated
    @Transactional
    public void execute(Long taskId) {
        executeWorkflow(taskId);
    }

    /**
     * 删除任务
     */
    @Transactional
    public void delete(Long id) {
        WorkflowTaskRelation workflowRelation = workflowTaskRelationMapper.selectOne(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getTaskId, id));
        Long workflowId = workflowRelation != null ? workflowRelation.getWorkflowId() : null;

        DataTask task = dataTaskMapper.selectById(id);
        if (task == null) {
            log.warn("Attempted to delete non-existent task: {}", id);
            return;
        }

        // 删除血缘关系
        dataLineageMapper.delete(
                new LambdaQueryWrapper<DataLineage>()
                        .eq(DataLineage::getTaskId, id));

        clearTableTaskRelations(id);

        // 删除工作流任务关联关系
        workflowTaskRelationMapper.hardDeleteByTaskId(id);

        dataTaskMapper.deleteById(id);
        log.info("Deleted task: {}", id);

        // 如果任务属于工作流，重新计算工作流中所有任务的上下游关系
        if (workflowId != null) {
            workflowService.refreshTaskRelations(workflowId);
            workflowService.normalizeAndPersistMetadata(workflowId, DEFAULT_OPERATOR);
        }
    }

    private void saveTableTaskRelations(Long taskId, List<Long> inputTableIds, List<Long> outputTableIds) {
        if (taskId == null) {
            return;
        }

        if (inputTableIds != null) {
            LinkedHashSet<Long> uniqueInputs = inputTableIds.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (Long tableId : uniqueInputs) {
                TableTaskRelation relation = new TableTaskRelation();
                relation.setTaskId(taskId);
                relation.setTableId(tableId);
                relation.setRelationType("read");
                tableTaskRelationMapper.insert(relation);
            }
        }

        if (outputTableIds != null) {
            LinkedHashSet<Long> uniqueOutputs = outputTableIds.stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            for (Long tableId : uniqueOutputs) {
                TableTaskRelation relation = new TableTaskRelation();
                relation.setTaskId(taskId);
                relation.setTableId(tableId);
                relation.setRelationType("write");
                tableTaskRelationMapper.insert(relation);
            }
        }
    }

    private void syncWorkflowRelation(Long taskId,
            Long workflowId,
            WorkflowTaskRelation workflowRelation,
            Long previousWorkflowId) {
        if (taskId == null) {
            return;
        }

        // Remove workflow binding.
        if (workflowId == null) {
            if (workflowRelation != null) {
                workflowTaskRelationMapper.hardDeleteByTaskId(taskId);
            }
            if (previousWorkflowId != null) {
                workflowService.refreshTaskRelations(previousWorkflowId);
            }
            return;
        }

        // Create or update workflow binding.
        if (workflowRelation == null) {
            WorkflowTaskRelation relation = new WorkflowTaskRelation();
            relation.setWorkflowId(workflowId);
            relation.setTaskId(taskId);
            relation.setUpstreamTaskCount(tableTaskRelationMapper.countUpstreamTasks(taskId));
            relation.setDownstreamTaskCount(tableTaskRelationMapper.countDownstreamTasks(taskId));
            workflowTaskRelationMapper.insert(relation);
        } else {
            boolean workflowChanged = !Objects.equals(previousWorkflowId, workflowId);
            workflowRelation.setWorkflowId(workflowId);
            workflowRelation.setUpstreamTaskCount(tableTaskRelationMapper.countUpstreamTasks(taskId));
            workflowRelation.setDownstreamTaskCount(tableTaskRelationMapper.countDownstreamTasks(taskId));
            if (workflowChanged) {
                workflowRelation.setNodeAttrs(null);
                workflowRelation.setIsEntry(null);
                workflowRelation.setIsExit(null);
                workflowRelation.setVersionId(null);
            }
            workflowTaskRelationMapper.updateById(workflowRelation);
        }

        // Recompute topology/entry/exit & counts.
        workflowService.refreshTaskRelations(workflowId);
        if (previousWorkflowId != null && !previousWorkflowId.equals(workflowId)) {
            workflowService.refreshTaskRelations(previousWorkflowId);
        }
    }

    private void clearTableTaskRelations(Long taskId) {
        if (taskId == null) {
            return;
        }
        // 使用物理删除以避免逻辑删除记录命中唯一索引(uk_table_task)
        tableTaskRelationMapper.hardDeleteByTaskId(taskId);
    }

    private String resolveWorkflowDisplayName(DataTask task) {
        if (task == null) {
            return "legacy-workflow";
        }
        if (StringUtils.hasText(task.getWorkflowName())) {
            return task.getWorkflowName();
        }
        if (StringUtils.hasText(task.getTaskName())) {
            return "task-" + task.getTaskName();
        }
        if (task.getId() != null) {
            return "task-workflow-" + task.getId();
        }
        return "legacy-workflow";
    }

    /**
     * 获取任务的最近一次执行状态
     */
    public TaskExecutionStatus getLatestExecutionStatus(Long taskId) {
        DataTask task = dataTaskMapper.selectById(taskId);
        if (task == null) {
            return null;
        }

        // 获取最近一次执行记录
        TaskExecutionLog latestLog = executionLogMapper.selectOne(
                new LambdaQueryWrapper<TaskExecutionLog>()
                        .eq(TaskExecutionLog::getTaskId, taskId)
                        .orderByDesc(TaskExecutionLog::getCreatedAt)
                        .last("LIMIT 1"));

        TaskExecutionStatus status = new TaskExecutionStatus();
        status.setTaskId(taskId);
        status.setDolphinWorkflowCode(task.getDolphinProcessCode());
        status.setDolphinTaskCode(task.getDolphinTaskCode());
        status.setDolphinProjectName(resolveWorkflowDisplayName(task));

        if (latestLog != null) {
            status.setExecutionId(latestLog.getExecutionId());
            status.setStatus(latestLog.getStatus());
            status.setStartTime(latestLog.getStartTime());
            status.setEndTime(latestLog.getEndTime());
            status.setDurationSeconds(latestLog.getDurationSeconds());
            status.setErrorMessage(latestLog.getErrorMessage());
            status.setLogUrl(latestLog.getLogUrl());
            status.setTriggerType(latestLog.getTriggerType());

            // 如果有 workflow code 和 execution id，尝试从 DolphinScheduler 获取实时状态
            if (task.getDolphinProcessCode() != null && latestLog.getExecutionId() != null) {
                try {
                    JsonNode instanceData = dolphinSchedulerService.getWorkflowInstanceStatus(
                            task.getDolphinProcessCode(),
                            latestLog.getExecutionId());

                    if (instanceData != null) {
                        // 更新状态信息
                        String state = instanceData.path("state").asText(null);
                        if (state != null) {
                            status.setStatus(mapDolphinStateToStatus(state));
                        }

                        // 更新时间信息
                        String startTimeStr = instanceData.path("startTime").asText(null);
                        String endTimeStr = instanceData.path("endTime").asText(null);
                        if (startTimeStr != null && !startTimeStr.isEmpty()) {
                            // 时间格式转换根据实际情况调整
                            status.setStartTime(LocalDateTime.parse(startTimeStr));
                        }
                        if (endTimeStr != null && !endTimeStr.isEmpty()) {
                            status.setEndTime(LocalDateTime.parse(endTimeStr));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to get real-time status from DolphinScheduler for task {}: {}",
                            taskId, e.getMessage());
                }
            }
        }

        // 生成 DolphinScheduler Web UI 跳转链接
        if (task.getDolphinProcessCode() != null) {
            status.setDolphinWorkflowUrl(
                    dolphinSchedulerService.getWorkflowDefinitionUrl(task.getDolphinProcessCode()));
        }
        if (task.getDolphinTaskCode() != null) {
            status.setDolphinTaskUrl(dolphinSchedulerService.getTaskDefinitionUrl(task.getDolphinTaskCode()));
        }

        return status;
    }

    /**
     * 将 DolphinScheduler 状态映射到本地状态
     */
    private String mapDolphinStateToStatus(String dolphinState) {
        if (dolphinState == null) {
            return "pending";
        }
        switch (dolphinState.toUpperCase()) {
            case "RUNNING_EXECUTION":
            case "SUBMITTED_SUCCESS":
                return "running";
            case "SUCCESS":
                return "success";
            case "FAILURE":
            case "FAILED":
                return "failed";
            case "STOP":
            case "KILL":
                return "killed";
            default:
                return "pending";
        }
    }

    /**
     * 检查任务名称是否已存在
     */
    public boolean isTaskNameExists(String taskName) {
        if (!StringUtils.hasText(taskName)) {
            return false;
        }
        return dataTaskMapper.selectCount(
                Wrappers.<DataTask>lambdaQuery()
                        .eq(DataTask::getTaskName, taskName)
                        .eq(DataTask::getDeleted, 0)) > 0;
    }

    /**
     * 检查任务名称是否已存在（用于更新时排除自身）
     */
    public boolean isTaskNameExists(String taskName, Long excludeTaskId) {
        if (!StringUtils.hasText(taskName) || excludeTaskId == null) {
            return isTaskNameExists(taskName);
        }
        return dataTaskMapper.selectCount(
                Wrappers.<DataTask>lambdaQuery()
                        .eq(DataTask::getTaskName, taskName)
                        .ne(DataTask::getId, excludeTaskId)
                        .eq(DataTask::getDeleted, 0)) > 0;
    }

    /**
     * 生成唯一的任务编码
     * 规则: task_ + 时间戳 + 随机数
     */
    private String generateUniqueTaskCode(String taskName) {
        long timestamp = System.currentTimeMillis();
        int random = (int) (Math.random() * 1000);
        return String.format("task_%d_%03d", timestamp, random);
    }

    /**
     * 获取任务的血缘关系（输入表和输出表ID列表）
     */
    public com.onedata.portal.controller.DataTaskController.TaskLineageResponse getTaskLineage(Long taskId) {
        // 获取输入表
        List<DataLineage> inputLineages = dataLineageMapper.selectList(
                new LambdaQueryWrapper<DataLineage>()
                        .eq(DataLineage::getTaskId, taskId)
                        .eq(DataLineage::getLineageType, "input"));
        List<Long> inputTableIds = inputLineages.stream()
                .map(DataLineage::getUpstreamTableId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        // 获取输出表
        List<DataLineage> outputLineages = dataLineageMapper.selectList(
                new LambdaQueryWrapper<DataLineage>()
                        .eq(DataLineage::getTaskId, taskId)
                        .eq(DataLineage::getLineageType, "output"));
        List<Long> outputTableIds = outputLineages.stream()
                .map(DataLineage::getDownstreamTableId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        return new com.onedata.portal.controller.DataTaskController.TaskLineageResponse(
                inputTableIds,
                outputTableIds);
    }

    private void attachExecutionStatus(List<DataTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        List<Long> taskIds = tasks.stream()
                .map(DataTask::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return;
        }

        List<TaskExecutionLog> logs = executionLogMapper.selectList(
                Wrappers.<TaskExecutionLog>lambdaQuery()
                        .in(TaskExecutionLog::getTaskId, taskIds)
                        .orderByDesc(TaskExecutionLog::getStartTime));

        Map<Long, TaskExecutionLog> latestLogByTask = new LinkedHashMap<>();
        for (TaskExecutionLog log : logs) {
            if (log.getTaskId() == null) {
                continue;
            }
            // 第一条即最新（按开始时间倒序）
            latestLogByTask.putIfAbsent(log.getTaskId(), log);
        }

        for (DataTask task : tasks) {
            TaskExecutionLog latestLog = latestLogByTask.get(task.getId());
            task.setExecutionStatus(buildExecutionStatus(latestLog, task));
        }
    }

    private TaskExecutionStatus buildExecutionStatus(TaskExecutionLog log, DataTask task) {
        if (log == null || task == null) {
            return null;
        }
        TaskExecutionStatus status = new TaskExecutionStatus();
        status.setTaskId(task.getId());
        status.setExecutionId(log.getExecutionId());
        status.setStatus(log.getStatus());
        status.setStartTime(log.getStartTime());
        status.setEndTime(log.getEndTime());
        status.setDurationSeconds(log.getDurationSeconds());
        status.setErrorMessage(log.getErrorMessage());
        status.setLogUrl(log.getLogUrl());
        status.setTriggerType(log.getTriggerType());
        status.setDolphinWorkflowCode(task.getDolphinProcessCode());
        status.setDolphinTaskCode(task.getDolphinTaskCode());
        status.setDolphinProjectName(resolveWorkflowDisplayName(task));
        if (task.getDolphinProcessCode() != null) {
            status.setDolphinWorkflowUrl(
                    dolphinSchedulerService.getWorkflowDefinitionUrl(task.getDolphinProcessCode()));
        }
        if (task.getDolphinTaskCode() != null) {
            status.setDolphinTaskUrl(dolphinSchedulerService.getTaskDefinitionUrl(task.getDolphinTaskCode()));
        }
        return status;
    }

    private void validateTask(DataTask task, List<Long> inputTableIds, List<Long> outputTableIds) {
        if (task == null) {
            throw new IllegalArgumentException("任务不能为空");
        }
        task.setDolphinFlag(normalizeDolphinFlag(task.getDolphinFlag()));
        boolean enforceLineage = task.getId() == null || inputTableIds != null || outputTableIds != null;
        if (enforceLineage && isEmptyTableSelection(outputTableIds)) {
            if ("SQL".equalsIgnoreCase(task.getDolphinNodeType())) {
                throw new IllegalArgumentException("SQL 任务必须至少配置一个输出表");
            }
            throw new IllegalArgumentException("任务必须至少配置一个输出表");
        }
    }

    private boolean isEmptyTableSelection(List<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return true;
        }
        return tableIds.stream().noneMatch(Objects::nonNull);
    }

    private List<Long> findTaskIdsByWorkflow(Long workflowId) {
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
        return relations.stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<Long> findDownstreamTaskIds(Long upstreamTaskId) {
        List<TableTaskRelation> writes = tableTaskRelationMapper.selectList(
                Wrappers.<TableTaskRelation>lambdaQuery()
                        .eq(TableTaskRelation::getTaskId, upstreamTaskId)
                        .eq(TableTaskRelation::getRelationType, "write"));
        Set<Long> tableIds = writes.stream()
                .map(TableTaskRelation::getTableId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (tableIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<TableTaskRelation> reads = tableTaskRelationMapper.selectList(
                Wrappers.<TableTaskRelation>lambdaQuery()
                        .in(TableTaskRelation::getTableId, tableIds)
                        .eq(TableTaskRelation::getRelationType, "read"));
        return reads.stream()
                .map(TableTaskRelation::getTaskId)
                .filter(id -> id != null && !Objects.equals(id, upstreamTaskId))
                .distinct()
                .collect(Collectors.toList());
    }

    private List<Long> findUpstreamTaskIds(Long downstreamTaskId) {
        List<TableTaskRelation> reads = tableTaskRelationMapper.selectList(
                Wrappers.<TableTaskRelation>lambdaQuery()
                        .eq(TableTaskRelation::getTaskId, downstreamTaskId)
                        .eq(TableTaskRelation::getRelationType, "read"));
        Set<Long> tableIds = reads.stream()
                .map(TableTaskRelation::getTableId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (tableIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<TableTaskRelation> writes = tableTaskRelationMapper.selectList(
                Wrappers.<TableTaskRelation>lambdaQuery()
                        .in(TableTaskRelation::getTableId, tableIds)
                        .eq(TableTaskRelation::getRelationType, "write"));
        return writes.stream()
                .map(TableTaskRelation::getTaskId)
                .filter(id -> id != null && !Objects.equals(id, downstreamTaskId))
                .distinct()
                .collect(Collectors.toList());
    }

    private void applyIdFilter(LambdaQueryWrapper<DataTask> wrapper, List<Long> taskIds) {
        if (CollectionUtils.isEmpty(taskIds)) {
            // 返回空结果
            wrapper.eq(DataTask::getId, -1L);
        } else {
            wrapper.in(DataTask::getId, taskIds);
        }
    }

    private void enrichWorkflowMetadata(List<DataTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        List<Long> taskIds = tasks.stream()
                .map(DataTask::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return;
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .in(WorkflowTaskRelation::getTaskId, taskIds));
        if (relations.isEmpty()) {
            return;
        }
        Map<Long, WorkflowTaskRelation> relationMap = relations.stream()
                .collect(Collectors.toMap(WorkflowTaskRelation::getTaskId, r -> r));
        List<Long> workflowIds = relations.stream()
                .map(WorkflowTaskRelation::getWorkflowId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, DataWorkflow> workflowMap = workflowIds.isEmpty()
                ? Collections.emptyMap()
                : dataWorkflowMapper.selectBatchIds(workflowIds).stream()
                        .collect(Collectors.toMap(DataWorkflow::getId, w -> w));
        for (DataTask task : tasks) {
            WorkflowTaskRelation relation = relationMap.get(task.getId());
            if (relation == null) {
                continue;
            }
            task.setWorkflowId(relation.getWorkflowId());
            task.setUpstreamTaskCount(relation.getUpstreamTaskCount());
            task.setDownstreamTaskCount(relation.getDownstreamTaskCount());
            DataWorkflow workflow = workflowMap.get(relation.getWorkflowId());
            if (workflow != null) {
                task.setWorkflowName(workflow.getWorkflowName());
            }
        }
    }
}
