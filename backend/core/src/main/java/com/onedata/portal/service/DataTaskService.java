package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.DolphinDatasourceOption;
import com.onedata.portal.dto.DolphinTaskGroupOption;
import com.onedata.portal.dto.TaskDefinitionRequest;
import com.onedata.portal.dto.TaskExecutionStatus;
import com.onedata.portal.dto.dolphin.DolphinTaskInstance;
import com.onedata.portal.dto.task.TaskLineageResponse;
import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.exception.BusinessException;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.service.lineage.LineageValidationMode;
import com.onedata.portal.service.lineage.TaskLineageConsistencyChecker;
import com.onedata.portal.service.lineage.TaskLineageWriteService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

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

/** 任务服务 */
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
    private final DataTaskIdentityArchiver dataTaskIdentityArchiver;
    private final DataLineageMapper dataLineageMapper;
    private final TaskExecutionLogMapper executionLogMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final DataWorkflowMapper dataWorkflowMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final DataQueryService dataQueryService;
    private final DorisClusterService dorisClusterService;
    private final WorkflowService workflowService;
    private final TaskLineageWriteService taskLineageWriteService;
    private final TaskLineageConsistencyChecker taskLineageConsistencyChecker;

    /** 分页查询任务列表 */
    public Page<DataTask> list(
            int pageNum,
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

    /** 分页查询指定用户的任务列表 */
    public Page<DataTask> listByOwner(
            String owner,
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

    /** 根据ID获取任务 */
    public DataTask getById(Long id) {
        DataTask task = dataTaskMapper.selectById(id);
        if (task == null) {
            return null;
        }
        enrichWorkflowMetadata(Collections.singletonList(task));
        return task;
    }

    /** 获取 DolphinScheduler 数据源选项 */
    public List<DolphinDatasourceOption> listDatasourceOptions(String type, String keyword) {
        return dolphinSchedulerService.listDatasources(type, keyword);
    }

    public List<DolphinDatasourceOption> listDatasourceOptions(
            String type, String keyword, Long dolphinConfigId) {
        return dolphinSchedulerService.listDatasources(type, keyword, dolphinConfigId);
    }

    /** 创建任务 */
    @Transactional
    public DataTask create(DataTask task, List<Long> inputTableIds, List<Long> outputTableIds) {
        return create(task, inputTableIds, outputTableIds, LineageValidationMode.LENIENT);
    }

    /** 创建任务。创建时血缘没有"原值"可保留，两侧都按传入值处理，输出必须至少一个。 */
    @Transactional
    public DataTask create(
            DataTask task,
            List<Long> inputTableIds,
            List<Long> outputTableIds,
            LineageValidationMode validationMode) {
        validateTask(task, inputTableIds, outputTableIds, validationMode);

        // 检查任务名称是否已存在
        if (isTaskNameExists(task.getTaskName())) {
            throw new BusinessException("任务名称已存在: " + task.getTaskName());
        }

        if (!StringUtils.hasText(task.getTaskCode())) {
            task.setTaskCode(generateUniqueTaskCode(task.getTaskName()));
        }
        DataTask exists =
                dataTaskMapper.selectOne(
                        new LambdaQueryWrapper<DataTask>()
                                .eq(DataTask::getTaskCode, task.getTaskCode()));
        if (exists != null) {
            throw new BusinessException("任务编码已存在: " + task.getTaskCode());
        }
        dataTaskIdentityArchiver.releaseDeletedTaskCode(task.getTaskCode());

        task.setStatus("draft");

        dataTaskMapper.insert(task);
        log.info("Created task: {}", task.getTaskName());

        // 血缘与表任务关系走统一写入口，保证与工作流定义同源
        taskLineageWriteService.replaceTaskLineage(task.getId(), inputTableIds, outputTableIds);

        // 如果提供了 wokflowId，建立工作流关联
        if (task.getWorkflowId() != null) {
            WorkflowTaskRelation existingRelation =
                    workflowTaskRelationMapper.selectOne(
                            Wrappers.<WorkflowTaskRelation>lambdaQuery()
                                    .eq(WorkflowTaskRelation::getWorkflowId, task.getWorkflowId())
                                    .eq(WorkflowTaskRelation::getTaskId, task.getId()));

            if (existingRelation == null) {
                WorkflowTaskRelation relation = new WorkflowTaskRelation();
                relation.setWorkflowId(task.getWorkflowId());
                relation.setTaskId(task.getId());
                relation.setUpstreamTaskCount(
                        tableTaskRelationMapper.countUpstreamTasks(task.getId()));
                relation.setDownstreamTaskCount(
                        tableTaskRelationMapper.countDownstreamTasks(task.getId()));
                workflowTaskRelationMapper.insert(relation);
            }

            // 重新计算该工作流中所有任务的上下游关系
            workflowService.refreshTaskRelations(task.getWorkflowId());
        }

        normalizeTaskMetadataOnPersist(task.getId(), task.getWorkflowId(), null, task.getOwner());
        DataTask persisted = dataTaskMapper.selectById(task.getId());
        return persisted != null ? persisted : task;
    }

    /** 更新任务（宽松校验） */
    @Transactional
    public DataTask update(DataTask task, List<Long> inputTableIds, List<Long> outputTableIds) {
        return update(task, inputTableIds, outputTableIds, LineageValidationMode.LENIENT);
    }

    /**
     * 更新任务。
     *
     * <p>血缘按侧独立合并，语义固定为：
     *
     * <ul>
     *   <li>{@code null}（字段省略）：保留原值
     *   <li>{@code []}：清空该侧
     *   <li>非空数组：全量替换该侧
     * </ul>
     *
     * <p>执行顺序是"读取已有血缘 → 按侧合并 → 校验最终列表 → 写入"。校验失败前不删除任何记录， 避免出现"校验抛错但血缘已被清空"的中间态。
     */
    @Transactional
    public DataTask update(
            DataTask task,
            List<Long> inputTableIds,
            List<Long> outputTableIds,
            LineageValidationMode validationMode) {
        if (task == null) {
            throw new IllegalArgumentException("任务不能为空");
        }
        DataTask exists = dataTaskMapper.selectById(task.getId());
        if (exists == null) {
            throw new BusinessException("任务不存在");
        }

        // 先按侧合并出最终血缘，再用最终结果校验。
        // 只传一侧时，另一侧必须沿用库里的既有值，否则"只替换输入"会被误判成"输出为空"。
        boolean lineageProvided = inputTableIds != null || outputTableIds != null;
        TaskLineageResponse currentLineage = getTaskLineage(task.getId());
        List<Long> finalInputTableIds =
                inputTableIds != null ? inputTableIds : currentLineage.getInputTableIds();
        List<Long> finalOutputTableIds =
                outputTableIds != null ? outputTableIds : currentLineage.getOutputTableIds();

        validateTask(task, finalInputTableIds, finalOutputTableIds, validationMode, exists);

        WorkflowTaskRelation workflowRelation =
                workflowTaskRelationMapper.selectOne(
                        Wrappers.<WorkflowTaskRelation>lambdaQuery()
                                .eq(WorkflowTaskRelation::getTaskId, task.getId()));
        Long previousWorkflowId =
                workflowRelation != null ? workflowRelation.getWorkflowId() : null;

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

        // 两侧都省略时完全跳过血缘写入，避免无谓的删除重建。
        if (lineageProvided) {
            taskLineageWriteService.replaceTaskLineage(
                    task.getId(), finalInputTableIds, finalOutputTableIds);
        }

        syncWorkflowRelation(
                task.getId(), task.getWorkflowId(), workflowRelation, previousWorkflowId);

        normalizeTaskMetadataOnPersist(
                task.getId(), task.getWorkflowId(), previousWorkflowId, task.getOwner());
        DataTask persisted = dataTaskMapper.selectById(task.getId());
        return persisted != null ? persisted : task;
    }

    /** 发布任务到 DolphinScheduler。该操作会同步所有 Dolphin 引擎的任务定义， 根据血缘信息自动建立上下游依赖。 */
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

        log.info(
                "任务信息: taskCode={}, taskName={}, engine={}",
                target.getTaskCode(),
                target.getTaskName(),
                target.getEngine());

        // 查询全部 Dolphin 任务，构建统一工作流
        log.info("查询所有 Dolphin 引擎任务...");
        List<DataTask> dolphinTasks =
                dataTaskMapper.selectList(
                        new LambdaQueryWrapper<DataTask>()
                                .eq(DataTask::getEngine, "dolphin")
                                .orderByAsc(DataTask::getId));
        if (dolphinTasks.isEmpty()) {
            log.error("未找到任何 Dolphin 引擎任务");
            throw new RuntimeException("未找到任何 Dolphin 引擎任务");
        }
        log.info("找到 {} 个 Dolphin 引擎任务", dolphinTasks.size());
        validatePublishMetadata(dolphinTasks);
        target =
                dolphinTasks.stream()
                        .filter(task -> Objects.equals(task.getId(), taskId))
                        .findFirst()
                        .orElse(target);

        // 强制刷新 project code 缓存,确保使用最新的项目信息
        // 这对于 DolphinScheduler 重置后获取正确的 projectCode 很重要
        dolphinSchedulerService.clearProjectCodeCache();
        log.info("已清除 project code 缓存");

        // 获取已存在的 workflow code (如果有的话)
        // 如果数据库中有记录,尝试复用;如果没有或者 syncWorkflow 失败,会创建新的
        Long existingWorkflowCode =
                dolphinTasks.stream()
                        .map(DataTask::getDolphinProcessCode)
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElse(null);

        long workflowCode = existingWorkflowCode != null ? existingWorkflowCode : 0L;
        log.info(
                "使用 workflow code: {} ({})", workflowCode, workflowCode > 0 ? "更新现有工作流" : "创建新工作流");

        Map<Long, DataTask> taskMap =
                dolphinTasks.stream()
                        .collect(java.util.stream.Collectors.toMap(DataTask::getId, t -> t));

        List<Map<String, Object>> definitions = new ArrayList<>();
        List<DolphinSchedulerService.TaskRelationPayload> relations = new ArrayList<>();
        List<DolphinSchedulerService.TaskLocationPayload> locations = new ArrayList<>();

        List<DolphinTaskGroupOption> allTaskGroups = dolphinSchedulerService.listTaskGroups(null);
        Map<String, DolphinTaskGroupOption> taskGroupMap =
                allTaskGroups.stream()
                        .collect(
                                Collectors.toMap(
                                        DolphinTaskGroupOption::getName,
                                        opt -> opt,
                                        (v1, v2) -> v1));
        Map<String, DolphinDatasourceOption> datasourceByName =
                dolphinTasks.stream()
                                .anyMatch(
                                        task ->
                                                StringUtils.hasText(task.getDatasourceName())
                                                        || StringUtils.hasText(
                                                                task.getTargetDatasourceName()))
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
                    DolphinDatasourceOption sourceOption =
                            resolveDatasourceOptionByName(
                                    datasourceByName, dataTask.getDatasourceName());
                    datasourceId = sourceOption != null ? sourceOption.getId() : null;
                    if (datasourceId == null) {
                        throw new IllegalStateException(
                                String.format(
                                        "Source datasource '%s' not found for task '%s'",
                                        dataTask.getDatasourceName(), taskLabel(dataTask)));
                    }
                }
                if (StringUtils.hasText(dataTask.getTargetDatasourceName())) {
                    DolphinDatasourceOption targetOption =
                            resolveDatasourceOptionByName(
                                    datasourceByName, dataTask.getTargetDatasourceName());
                    targetDatasourceId = targetOption != null ? targetOption.getId() : null;
                    if (targetDatasourceId == null) {
                        throw new IllegalStateException(
                                String.format(
                                        "Target datasource '%s' not found for task '%s'",
                                        dataTask.getTargetDatasourceName(), taskLabel(dataTask)));
                    }
                    targetDatasourceType = resolveDatasourceType(targetOption, null);
                }
                if (datasourceId == null || targetDatasourceId == null) {
                    throw new IllegalStateException(
                            String.format(
                                    "DataX task '%s' 缺少有效数据源映射(source=%s,target=%s)",
                                    taskLabel(dataTask),
                                    dataTask.getDatasourceName(),
                                    dataTask.getTargetDatasourceName()));
                }
            } else if (StringUtils.hasText(dataTask.getDatasourceName())) {
                // For SQL, only get source datasource
                DolphinDatasourceOption datasourceOption =
                        resolveDatasourceOptionByName(
                                datasourceByName, dataTask.getDatasourceName());
                datasourceId = datasourceOption != null ? datasourceOption.getId() : null;
                syncTaskDatasourceTypeFromCatalog(dataTask, datasourceOption);
            }
            if ("SQL".equalsIgnoreCase(nodeType) && datasourceId == null) {
                throw new IllegalStateException(
                        String.format(
                                "Datasource '%s' not found for task '%s'",
                                dataTask.getDatasourceName(), taskLabel(dataTask)));
            }

            Integer taskGroupId = null;
            if (StringUtils.hasText(dataTask.getTaskGroupName())) {
                String groupName = dataTask.getTaskGroupName();
                DolphinTaskGroupOption group = taskGroupMap.get(groupName);
                if (group == null) {
                    List<DolphinTaskGroupOption> refreshed =
                            dolphinSchedulerService.listTaskGroups(groupName);
                    group =
                            refreshed.stream()
                                    .filter(g -> Objects.equals(g.getName(), groupName))
                                    .findFirst()
                                    .orElse(null);
                }
                if (group == null) {
                    throw new IllegalStateException(
                            String.format(
                                    "Task group '%s' not found for task '%s'. Please check if the"
                                        + " task group exists in DolphinScheduler.",
                                    groupName, dataTask.getTaskName()));
                }
                taskGroupId = group.getId();
            }

            Map<String, Object> definition =
                    dolphinSchedulerService.buildTaskDefinition(
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
                            resolveDatasourceType(
                                    resolveDatasourceOptionByName(
                                            datasourceByName, dataTask.getDatasourceName()),
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
                relations.add(
                        dolphinSchedulerService.buildRelation(
                                0L, 0, dataTask.getDolphinTaskCode(), version));
            } else {
                for (DataTask upstream : upstreamTasks) {
                    relations.add(
                            dolphinSchedulerService.buildRelation(
                                    upstream.getDolphinTaskCode(),
                                    upstream.getDolphinTaskVersion(),
                                    dataTask.getDolphinTaskCode(),
                                    version));
                }
            }

            int lane = computeLaneByLayer(dataTask);
            locations.add(
                    dolphinSchedulerService.buildLocation(
                            dataTask.getDolphinTaskCode(), index++, lane));
        }

        // syncWorkflow 返回实际的 workflow code (如果是新建则返回新的 code)
        log.info(
                "开始同步工作流到 DolphinScheduler: workflowCode={}, taskCount={}",
                workflowCode,
                definitions.size());
        try {
            if (workflowCode > 0) {
                log.info("将现有工作流置为 OFFLINE 后再同步: workflowCode={}", workflowCode);
                dolphinSchedulerService.setWorkflowReleaseState(workflowCode, "OFFLINE");
            }

            String workflowName = resolveWorkflowDisplayName(target);

            long actualWorkflowCode =
                    dolphinSchedulerService.syncWorkflow(
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
            log.info(
                    "任务发布成功: taskId={}, taskName={}, workflowCode={}, status=ONLINE",
                    taskId,
                    target.getTaskName(),
                    actualWorkflowCode);
        } catch (Exception e) {
            log.error(
                    "发布任务失败: taskId={}, taskName={}, error={}",
                    taskId,
                    target.getTaskName(),
                    e.getMessage(),
                    e);
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

    private DolphinDatasourceOption resolveDatasourceOptionByName(
            Map<String, DolphinDatasourceOption> datasourceByName, String datasourceName) {
        if (CollectionUtils.isEmpty(datasourceByName) || !StringUtils.hasText(datasourceName)) {
            return null;
        }
        return datasourceByName.get(datasourceName.trim());
    }

    private String resolveDatasourceType(
            DolphinDatasourceOption datasourceOption, String fallbackType) {
        String catalogType =
                datasourceOption == null ? null : normalizeText(datasourceOption.getType());
        return StringUtils.hasText(catalogType) ? catalogType : normalizeText(fallbackType);
    }

    private void syncTaskDatasourceTypeFromCatalog(
            DataTask task, DolphinDatasourceOption datasourceOption) {
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
        List<DataLineage> inputLineage =
                dataLineageMapper.selectList(
                        new LambdaQueryWrapper<DataLineage>()
                                .eq(DataLineage::getTaskId, taskId)
                                .eq(DataLineage::getLineageType, "input"));

        Set<Long> upstreamTableIds =
                inputLineage.stream()
                        .map(DataLineage::getUpstreamTableId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        if (upstreamTableIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<DataLineage> outputLineage =
                dataLineageMapper.selectList(
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
            if ("SQL".equalsIgnoreCase(nodeType)
                    && !StringUtils.hasText(task.getDatasourceName())) {
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
            throw new IllegalStateException(
                    "检测到任务元数据缺失，请先保存修复后再发布: " + String.join("; ", invalidTasks));
        }
    }

    private String mapPriority(Integer value, DataTask task) {
        if (value == null) {
            throw new IllegalStateException(
                    String.format("Task '%s' 缺少 priority 元数据，请先保存后再发布", taskLabel(task)));
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

    private void normalizeTaskMetadataOnPersist(
            Long taskId, Long workflowId, Long previousWorkflowId, String operator) {
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
        List<Long> existingCodes =
                dataTaskMapper
                        .selectList(
                                Wrappers.<DataTask>lambdaQuery()
                                        .select(DataTask::getDolphinTaskCode))
                        .stream()
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

    /** 执行整个工作流（原有逻辑） */

    /**
     * @deprecated 使用 executeTask 或 executeWorkflow 代替
     */

    /** 删除任务 */
    @Transactional
    public void delete(Long id) {
        WorkflowTaskRelation workflowRelation =
                workflowTaskRelationMapper.selectOne(
                        Wrappers.<WorkflowTaskRelation>lambdaQuery()
                                .eq(WorkflowTaskRelation::getTaskId, id));
        Long workflowId = workflowRelation != null ? workflowRelation.getWorkflowId() : null;

        DataTask task = dataTaskMapper.selectById(id);
        if (task == null) {
            log.warn("Attempted to delete non-existent task: {}", id);
            return;
        }

        if (!dataTaskIdentityArchiver.archive(task)) {
            log.info("Task was already deleted while archiving identity: taskId={}", id);
            return;
        }

        // 删除血缘关系
        taskLineageWriteService.clearTaskLineage(id);

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

    private void syncWorkflowRelation(
            Long taskId,
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
            workflowRelation.setUpstreamTaskCount(
                    tableTaskRelationMapper.countUpstreamTasks(taskId));
            workflowRelation.setDownstreamTaskCount(
                    tableTaskRelationMapper.countDownstreamTasks(taskId));
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

    /** 获取任务的最近一次执行状态 */

    /** 检查任务名称是否已存在 */
    public boolean isTaskNameExists(String taskName) {
        if (!StringUtils.hasText(taskName)) {
            return false;
        }
        return dataTaskMapper.selectCount(
                        Wrappers.<DataTask>lambdaQuery()
                                .eq(DataTask::getTaskName, taskName)
                                .eq(DataTask::getDeleted, 0))
                > 0;
    }

    /** 检查任务名称是否已存在（用于更新时排除自身） */
    public boolean isTaskNameExists(String taskName, Long excludeTaskId) {
        if (!StringUtils.hasText(taskName) || excludeTaskId == null) {
            return isTaskNameExists(taskName);
        }
        return dataTaskMapper.selectCount(
                        Wrappers.<DataTask>lambdaQuery()
                                .eq(DataTask::getTaskName, taskName)
                                .ne(DataTask::getId, excludeTaskId)
                                .eq(DataTask::getDeleted, 0))
                > 0;
    }

    /** 生成唯一的任务编码 规则: task_ + 时间戳 + 随机数 */
    private String generateUniqueTaskCode(String taskName) {
        long timestamp = System.currentTimeMillis();
        int random = (int) (Math.random() * 1000);
        return String.format("task_%d_%03d", timestamp, random);
    }

    /** 获取任务的血缘关系（输入表和输出表ID列表） */
    public TaskLineageResponse getTaskLineage(Long taskId) {
        // 获取输入表
        List<DataLineage> inputLineages =
                dataLineageMapper.selectList(
                        new LambdaQueryWrapper<DataLineage>()
                                .eq(DataLineage::getTaskId, taskId)
                                .eq(DataLineage::getLineageType, "input"));
        List<Long> inputTableIds =
                inputLineages.stream()
                        .map(DataLineage::getUpstreamTableId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

        // 获取输出表
        List<DataLineage> outputLineages =
                dataLineageMapper.selectList(
                        new LambdaQueryWrapper<DataLineage>()
                                .eq(DataLineage::getTaskId, taskId)
                                .eq(DataLineage::getLineageType, "output"));
        List<Long> outputTableIds =
                outputLineages.stream()
                        .map(DataLineage::getDownstreamTableId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

        return new TaskLineageResponse(inputTableIds, outputTableIds);
    }

    private void attachExecutionStatus(List<DataTask> tasks) {
        if (CollectionUtils.isEmpty(tasks)) {
            return;
        }
        List<Long> taskIds =
                tasks.stream()
                        .map(DataTask::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return;
        }

        List<TaskExecutionLog> logs =
                executionLogMapper.selectList(
                        Wrappers.<TaskExecutionLog>lambdaQuery()
                                .in(TaskExecutionLog::getTaskId, taskIds)
                                .orderByDesc(TaskExecutionLog::getStartTime));

        Map<Long, TaskExecutionLog> latestLogByTask = new LinkedHashMap<>();
        for (TaskExecutionLog executionLog : logs) {
            if (executionLog.getTaskId() == null) {
                continue;
            }
            // 第一条即最新（按开始时间倒序）
            latestLogByTask.putIfAbsent(executionLog.getTaskId(), executionLog);
        }

        Set<Long> workflowIds =
                tasks.stream()
                        .map(DataTask::getWorkflowId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, DataWorkflow> workflowById =
                workflowIds.isEmpty()
                        ? Collections.emptyMap()
                        : dataWorkflowMapper.selectBatchIds(workflowIds).stream()
                                .collect(
                                        Collectors.toMap(
                                                DataWorkflow::getId, workflow -> workflow));

        Map<Long, List<DataTask>> deployedTasksByWorkflow = new LinkedHashMap<>();
        for (DataTask task : tasks) {
            DataWorkflow workflow = workflowById.get(task.getWorkflowId());
            if (isDeployedTask(task, workflow)) {
                deployedTasksByWorkflow
                        .computeIfAbsent(workflow.getId(), ignored -> new ArrayList<>())
                        .add(task);
            } else {
                task.setExecutionStatus(
                        buildLocalExecutionStatus(
                                latestLogByTask.get(task.getId()), task, workflow));
            }
        }

        for (Map.Entry<Long, List<DataTask>> entry : deployedTasksByWorkflow.entrySet()) {
            DataWorkflow workflow = workflowById.get(entry.getKey());
            List<DataTask> workflowTasks = entry.getValue();
            try {
                List<WorkflowInstanceSummary> workflowInstances =
                        dolphinSchedulerService.listWorkflowInstances(
                                workflow.getDolphinConfigId(), workflow.getWorkflowCode(), 1);
                if (workflowInstances.isEmpty()) {
                    workflowTasks.forEach(task -> task.setExecutionStatus(null));
                    continue;
                }

                WorkflowInstanceSummary latestWorkflowInstance = workflowInstances.get(0);
                List<DolphinTaskInstance> taskInstances =
                        dolphinSchedulerService.listTaskInstances(
                                workflow.getDolphinConfigId(),
                                latestWorkflowInstance.getInstanceId());
                Map<Long, DolphinTaskInstance> instanceByTaskCode =
                        taskInstances.stream()
                                .filter(
                                        instance ->
                                                instance != null && instance.getTaskCode() != null)
                                .collect(
                                        Collectors.toMap(
                                                DolphinTaskInstance::getTaskCode,
                                                instance -> instance,
                                                this::newerTaskInstance,
                                                LinkedHashMap::new));

                for (DataTask task : workflowTasks) {
                    DolphinTaskInstance taskInstance =
                            instanceByTaskCode.get(task.getDolphinTaskCode());
                    task.setExecutionStatus(
                            taskInstance == null
                                    ? buildMissingTaskExecutionStatus(
                                            task, workflow, latestWorkflowInstance)
                                    : buildDolphinTaskExecutionStatus(
                                            task, workflow, latestWorkflowInstance, taskInstance));
                }
            } catch (Exception ex) {
                log.warn(
                        "Failed to resolve task instances for workflow {}: {}",
                        workflow.getId(),
                        ex.getMessage());
                workflowTasks.forEach(
                        task ->
                                task.setExecutionStatus(
                                        buildUnavailableExecutionStatus(task, workflow)));
            }
        }
    }

    private boolean isDeployedTask(DataTask task, DataWorkflow workflow) {
        return task != null
                && workflow != null
                && workflow.getWorkflowCode() != null
                && workflow.getWorkflowCode() > 0
                && task.getDolphinTaskCode() != null
                && task.getDolphinTaskCode() > 0;
    }

    private DolphinTaskInstance newerTaskInstance(
            DolphinTaskInstance left, DolphinTaskInstance right) {
        if (left.getId() == null) {
            return right;
        }
        if (right.getId() == null) {
            return left;
        }
        return right.getId() > left.getId() ? right : left;
    }

    private TaskExecutionStatus buildDolphinTaskExecutionStatus(
            DataTask task,
            DataWorkflow workflow,
            WorkflowInstanceSummary workflowInstance,
            DolphinTaskInstance taskInstance) {
        TaskExecutionStatus status = buildDolphinStatusBase(task, workflow);
        LocalDateTime startTime = DolphinExecutionMapper.parseDateTime(taskInstance.getStartTime());
        LocalDateTime endTime = DolphinExecutionMapper.parseDateTime(taskInstance.getEndTime());
        status.setExecutionId(
                taskInstance.getId() == null ? null : String.valueOf(taskInstance.getId()));
        status.setStatus(DolphinExecutionMapper.mapStatus(taskInstance.getState()));
        status.setStartTime(startTime);
        status.setEndTime(endTime);
        status.setDurationSeconds(
                DolphinExecutionMapper.durationSeconds(
                        startTime, endTime, taskInstance.getDuration()));
        status.setLogUrl(taskInstance.getLogPath());
        status.setTriggerType(
                DolphinExecutionMapper.mapTriggerType(workflowInstance.getCommandType()));
        return status;
    }

    private TaskExecutionStatus buildMissingTaskExecutionStatus(
            DataTask task, DataWorkflow workflow, WorkflowInstanceSummary workflowInstance) {
        TaskExecutionStatus status = buildDolphinStatusBase(task, workflow);
        status.setExecutionId(
                workflowInstance.getInstanceId() == null
                        ? null
                        : String.valueOf(workflowInstance.getInstanceId()));
        status.setStatus(
                DolphinExecutionMapper.isWorkflowActive(workflowInstance.getState())
                        ? "waiting"
                        : "not_run");
        status.setStartTime(DolphinExecutionMapper.parseDateTime(workflowInstance.getStartTime()));
        status.setEndTime(DolphinExecutionMapper.parseDateTime(workflowInstance.getEndTime()));
        status.setTriggerType(
                DolphinExecutionMapper.mapTriggerType(workflowInstance.getCommandType()));
        return status;
    }

    private TaskExecutionStatus buildUnavailableExecutionStatus(
            DataTask task, DataWorkflow workflow) {
        TaskExecutionStatus status = buildDolphinStatusBase(task, workflow);
        status.setStatus("unavailable");
        return status;
    }

    private TaskExecutionStatus buildDolphinStatusBase(DataTask task, DataWorkflow workflow) {
        TaskExecutionStatus status = new TaskExecutionStatus();
        status.setTaskId(task.getId());
        status.setDolphinWorkflowCode(workflow.getWorkflowCode());
        status.setDolphinTaskCode(task.getDolphinTaskCode());
        status.setDolphinProjectName(workflow.getWorkflowName());
        try {
            status.setDolphinWorkflowUrl(
                    dolphinSchedulerService.getWorkflowDefinitionUrl(
                            workflow.getDolphinConfigId(), workflow.getWorkflowCode()));
            status.setDolphinTaskUrl(
                    dolphinSchedulerService.getTaskDefinitionUrl(
                            workflow.getDolphinConfigId(), task.getDolphinTaskCode()));
        } catch (Exception ex) {
            log.debug(
                    "Failed to build Dolphin links for task {}: {}", task.getId(), ex.getMessage());
        }
        return status;
    }

    private TaskExecutionStatus buildLocalExecutionStatus(
            TaskExecutionLog executionLog, DataTask task, DataWorkflow workflow) {
        if (executionLog == null || task == null) {
            return null;
        }
        TaskExecutionStatus status = new TaskExecutionStatus();
        status.setTaskId(task.getId());
        status.setExecutionId(executionLog.getExecutionId());
        status.setStatus(executionLog.getStatus());
        status.setStartTime(executionLog.getStartTime());
        status.setEndTime(executionLog.getEndTime());
        status.setDurationSeconds(executionLog.getDurationSeconds());
        status.setErrorMessage(executionLog.getErrorMessage());
        status.setLogUrl(executionLog.getLogUrl());
        status.setTriggerType(executionLog.getTriggerType());
        status.setDolphinWorkflowCode(
                workflow == null ? task.getDolphinProcessCode() : workflow.getWorkflowCode());
        status.setDolphinTaskCode(task.getDolphinTaskCode());
        status.setDolphinProjectName(resolveWorkflowDisplayName(task));
        return status;
    }

    private void validateTask(
            DataTask task,
            List<Long> finalInputTableIds,
            List<Long> finalOutputTableIds,
            LineageValidationMode validationMode) {
        validateTask(task, finalInputTableIds, finalOutputTableIds, validationMode, null);
    }

    /**
     * 校验合并后的最终血缘。
     *
     * <p>血缘入参必须是合并结果而非原始请求：只替换一侧时，另一侧为 {@code null} 并不代表被清空。
     *
     * <p>任务字段同理。更新请求只携带调用方显式提交的字段， 而 {@code updateById} 只写非空字段，因此保存后生效的是"请求覆盖在旧值之上"的结果。
     * 校验必须针对这个有效任务，否则只提交 {@code taskName} 的部分更新会因为 {@code dolphinNodeType} 为空而被当成非 SQL 任务，直接绕过 SQL
     * 一致性校验。
     *
     * @param exists 库中旧值；创建时为 {@code null}
     */
    private void validateTask(
            DataTask task,
            List<Long> finalInputTableIds,
            List<Long> finalOutputTableIds,
            LineageValidationMode validationMode,
            DataTask exists) {
        if (task == null) {
            throw new IllegalArgumentException("任务不能为空");
        }
        // dolphinFlag 归一化会写回请求对象本身，必须作用在真实入参上，不能挪到副本里。
        task.setDolphinFlag(normalizeDolphinFlag(task.getDolphinFlag()));

        DataTask effectiveTask = buildEffectiveTaskForValidation(task, exists);
        if (isEmptyTableSelection(finalOutputTableIds)) {
            if ("SQL".equalsIgnoreCase(effectiveTask.getDolphinNodeType())) {
                throw new IllegalArgumentException("SQL 任务必须至少配置一个输出表");
            }
            throw new IllegalArgumentException("任务必须至少配置一个输出表");
        }
        if (validationMode == LineageValidationMode.STRICT) {
            validateHighConfidenceLineage(effectiveTask, finalInputTableIds, finalOutputTableIds);
        }
    }

    /**
     * 构造"请求覆盖旧值"后的有效任务视图，仅用于校验。
     *
     * <p>只填充校验真正读取的字段。返回副本而非修改入参，避免把旧值意外写回请求对象、 进而被 {@code updateById} 当成本次提交的变更持久化。
     */
    private DataTask buildEffectiveTaskForValidation(DataTask task, DataTask exists) {
        if (exists == null) {
            return task;
        }
        // 判定条件必须是 != null，而不是 hasText。
        // MyBatis-Plus 默认字段策略是 NOT_NULL：空字符串会被 updateById 真正写库，
        // 它是一个显式提交的新值，不是"字段省略"。用 hasText 会把"把 SQL 清空成草稿"
        // 误判成未提交，继续拿旧 SQL 校验，导致同时清空血缘的合法操作被拒。
        DataTask effective = new DataTask();
        effective.setId(task.getId() != null ? task.getId() : exists.getId());
        effective.setTaskName(
                task.getTaskName() != null ? task.getTaskName() : exists.getTaskName());
        effective.setDolphinNodeType(
                task.getDolphinNodeType() != null
                        ? task.getDolphinNodeType()
                        : exists.getDolphinNodeType());
        effective.setTaskSql(task.getTaskSql() != null ? task.getTaskSql() : exists.getTaskSql());
        effective.setDolphinTaskCode(
                task.getDolphinTaskCode() != null
                        ? task.getDolphinTaskCode()
                        : exists.getDolphinTaskCode());
        return effective;
    }

    /**
     * STRICT 模式下的 SQL 高可信缺失校验。
     *
     * <p>只拒绝 SQL 已明确匹配、但最终血缘里没有的表。多余血缘、未匹配、歧义都不拒绝， 否则引用外部表或方言 SQL 的任务会连保存都做不到。
     */
    private void validateHighConfidenceLineage(
            DataTask effectiveTask, List<Long> finalInputTableIds, List<Long> finalOutputTableIds) {
        TaskLineageConsistencyChecker.HighConfidenceGap gap =
                taskLineageConsistencyChecker.findHighConfidenceGap(
                        effectiveTask, finalInputTableIds, finalOutputTableIds);
        if (gap.isEmpty()) {
            return;
        }
        throw new BusinessException(
                String.format(
                        "任务[%s] 的 SQL 已解析出以下表，但未包含在提交的血缘中：%s。"
                                + "请补全 inputTableIds/outputTableIds 后重试；若只想更新其中一侧，可省略另一侧字段以保留原值。",
                        taskLabel(effectiveTask), gap.describe()));
    }

    private boolean isEmptyTableSelection(List<Long> tableIds) {
        if (tableIds == null || tableIds.isEmpty()) {
            return true;
        }
        return tableIds.stream().noneMatch(Objects::nonNull);
    }

    private List<Long> findTaskIdsByWorkflow(Long workflowId) {
        List<WorkflowTaskRelation> relations =
                workflowTaskRelationMapper.selectList(
                        Wrappers.<WorkflowTaskRelation>lambdaQuery()
                                .eq(WorkflowTaskRelation::getWorkflowId, workflowId));
        return relations.stream()
                .map(WorkflowTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    private List<Long> findDownstreamTaskIds(Long upstreamTaskId) {
        List<TableTaskRelation> writes =
                tableTaskRelationMapper.selectList(
                        Wrappers.<TableTaskRelation>lambdaQuery()
                                .eq(TableTaskRelation::getTaskId, upstreamTaskId)
                                .eq(TableTaskRelation::getRelationType, "write"));
        Set<Long> tableIds =
                writes.stream()
                        .map(TableTaskRelation::getTableId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        if (tableIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<TableTaskRelation> reads =
                tableTaskRelationMapper.selectList(
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
        List<TableTaskRelation> reads =
                tableTaskRelationMapper.selectList(
                        Wrappers.<TableTaskRelation>lambdaQuery()
                                .eq(TableTaskRelation::getTaskId, downstreamTaskId)
                                .eq(TableTaskRelation::getRelationType, "read"));
        Set<Long> tableIds =
                reads.stream()
                        .map(TableTaskRelation::getTableId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet());
        if (tableIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<TableTaskRelation> writes =
                tableTaskRelationMapper.selectList(
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
        List<Long> taskIds =
                tasks.stream()
                        .map(DataTask::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());
        if (taskIds.isEmpty()) {
            return;
        }
        List<WorkflowTaskRelation> relations =
                workflowTaskRelationMapper.selectList(
                        Wrappers.<WorkflowTaskRelation>lambdaQuery()
                                .in(WorkflowTaskRelation::getTaskId, taskIds));
        if (relations.isEmpty()) {
            return;
        }
        Map<Long, WorkflowTaskRelation> relationMap =
                relations.stream()
                        .collect(Collectors.toMap(WorkflowTaskRelation::getTaskId, r -> r));
        List<Long> workflowIds =
                relations.stream()
                        .map(WorkflowTaskRelation::getWorkflowId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());
        Map<Long, DataWorkflow> workflowMap =
                workflowIds.isEmpty()
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

    private static final int MAX_LIMIT = 200;

    private final ObjectMapper objectMapper;

    public Object createFromDefinition(TaskDefinitionRequest request, String operator) {
        DataTask task = toTask(request);
        applyAuditDefaults(task, operator, true);
        return this.create(
                task,
                request.getInputTableIds(),
                request.getOutputTableIds(),
                LineageValidationMode.STRICT);
    }

    public Object updateFromDefinition(
            Long taskId, TaskDefinitionRequest request, String operator) {
        DataTask task = toTask(request);
        task.setId(taskId);
        // DataTask.workflowId is a relation-management field, not a persisted
        // column: DataTaskService.update() treats a null value as "detach the
        // task from its workflow". The agent write contract only documents task
        // fields (see opendataworks-data-dev skill), so agent payloads never
        // include workflowId; without this, every agent-driven update silently
        // dropped the task's workflow binding. Preserve the current binding
        // unless the caller explicitly sets workflowId (including to null).
        if (!request.getTask().containsKey("workflowId")) {
            DataTask existing = this.getById(taskId);
            if (existing != null) {
                task.setWorkflowId(existing.getWorkflowId());
            }
        }
        applyAuditDefaults(task, operator, false);
        // 血缘列表原样下传，不做 null -> emptyList 转换：
        // null 表示"本次未提供该侧血缘，保留原值"，一旦转成空列表就等于静默清空。
        return this.update(
                task,
                request.getInputTableIds(),
                request.getOutputTableIds(),
                LineageValidationMode.STRICT);
    }

    public Object getTaskOrThrow(Long taskId) {
        DataTask task = this.getById(taskId);
        if (task == null) {
            // A missing task would otherwise serialize as a null body (empty
            // response), which the portal MCP client rejects as "not valid JSON".
            // Throw so the global handler returns a proper JSON error instead.
            throw new BusinessException("任务不存在: " + taskId);
        }
        return task;
    }

    public Object listTasks(String keyword, String status, int limit) {
        int pageSize = limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
        return this.list(
                1,
                pageSize,
                null,
                null,
                StringUtils.hasText(status) ? status : null,
                StringUtils.hasText(keyword) ? keyword : null,
                null,
                null,
                null);
    }

    private DataTask toTask(TaskDefinitionRequest request) {
        return objectMapper.convertValue(request.getTask(), DataTask.class);
    }

    private void applyAuditDefaults(DataTask task, String operator, boolean creating) {
        if (creating && !StringUtils.hasText(task.getOwner()) && StringUtils.hasText(operator)) {
            task.setOwner(operator);
        }
        if (creating && !StringUtils.hasText(task.getStatus())) {
            task.setStatus("draft");
        }
    }
}
