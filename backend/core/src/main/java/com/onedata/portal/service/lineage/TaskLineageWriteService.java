package com.onedata.portal.service.lineage;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.entity.DataLineage;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataLineageMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import com.onedata.portal.service.WorkflowService;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;



/**
 * 血缘持久化的统一入口。
 *
 * <p>所有替换 {@code data_lineage} 与 {@code table_task_relation} 的路径都必须经过这里，
 * 否则工作流的 {@code definitionJson} 会与关系表漂移。
 *
 * <p><strong>依赖红线：本组件不得依赖 {@code DataTaskService}。</strong>
 * {@code WorkflowRuntimeSyncService} 同时持有 {@code SqlTableMatcherService} 与
 * {@code DataTaskService}，一旦反接即形成构造器注入环，应用将启动失败。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskLineageWriteService {

    private final DataLineageMapper dataLineageMapper;
    private final TableTaskRelationMapper tableTaskRelationMapper;
    private final WorkflowTaskRelationMapper workflowTaskRelationMapper;
    private final WorkflowService workflowService;

    /**
     * 替换单个任务的血缘与表任务关系，不刷新工作流定义。
     *
     * <p>供已经自带定义刷新流程的调用方使用（{@code DataTaskService} 在其后会执行
     * {@code syncWorkflowRelation} 与 {@code normalizeAndPersistMetadata}），避免重复刷新。
     */
    @Transactional
    public void replaceTaskLineage(Long taskId, List<Long> inputTableIds, List<Long> outputTableIds) {
        if (taskId == null) {
            return;
        }
        clearTaskLineage(taskId);

        for (Long tableId : distinct(inputTableIds)) {
            DataLineage lineage = new DataLineage();
            lineage.setTaskId(taskId);
            lineage.setUpstreamTableId(tableId);
            lineage.setLineageType("input");
            dataLineageMapper.insert(lineage);

            TableTaskRelation relation = new TableTaskRelation();
            relation.setTaskId(taskId);
            relation.setTableId(tableId);
            relation.setRelationType("read");
            tableTaskRelationMapper.insert(relation);
        }

        for (Long tableId : distinct(outputTableIds)) {
            DataLineage lineage = new DataLineage();
            lineage.setTaskId(taskId);
            lineage.setDownstreamTableId(tableId);
            lineage.setLineageType("output");
            dataLineageMapper.insert(lineage);

            TableTaskRelation relation = new TableTaskRelation();
            relation.setTaskId(taskId);
            relation.setTableId(tableId);
            relation.setRelationType("write");
            tableTaskRelationMapper.insert(relation);
        }
    }

    /**
     * 替换血缘并立即刷新受影响工作流的拓扑与定义。
     *
     * <p>供本身没有定义刷新流程的旁路使用，例如 SQL 解析后的血缘绑定。
     */
    @Transactional
    public void replaceTaskLineageAndRefresh(Long taskId,
            List<Long> inputTableIds,
            List<Long> outputTableIds,
            String operator) {
        if (taskId == null) {
            return;
        }
        // 先取工作流归属：替换血缘本身不会改变 workflow_task_relation，
        // 但先取可以保证即使后续实现变化也拿得到刷新目标。
        Set<Long> workflowIds = findWorkflowIdsByTaskIds(java.util.Collections.singletonList(taskId));
        replaceTaskLineage(taskId, inputTableIds, outputTableIds);
        refreshWorkflowDefinitions(workflowIds, operator);
    }

    /**
     * 清空单个任务的血缘与表任务关系。
     *
     * <p>{@code table_task_relation} 使用物理删除，避免逻辑删除记录命中唯一索引 {@code uk_table_task}。
     */
    @Transactional
    public void clearTaskLineage(Long taskId) {
        if (taskId == null) {
            return;
        }
        dataLineageMapper.delete(
                new LambdaQueryWrapper<DataLineage>()
                        .eq(DataLineage::getTaskId, taskId));
        tableTaskRelationMapper.hardDeleteByTaskId(taskId);
    }

    /**
     * 查询这些任务归属的工作流 ID，已去重。
     */
    public Set<Long> findWorkflowIdsByTaskIds(Collection<Long> taskIds) {
        Set<Long> distinctTaskIds = distinct(taskIds);
        if (distinctTaskIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        List<WorkflowTaskRelation> relations = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .in(WorkflowTaskRelation::getTaskId, distinctTaskIds));
        return relations.stream()
                .map(WorkflowTaskRelation::getWorkflowId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 查询引用了该表的全部任务 ID，已去重。
     */
    public Set<Long> findTaskIdsByTableId(Long tableId) {
        if (tableId == null) {
            return new LinkedHashSet<>();
        }
        List<TableTaskRelation> relations = tableTaskRelationMapper.selectList(
                Wrappers.<TableTaskRelation>lambdaQuery()
                        .eq(TableTaskRelation::getTableId, tableId));
        return relations.stream()
                .map(TableTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * 按 workflowId 去重刷新拓扑与 {@code definitionJson}。
     *
     * <p>去重是必要的：批量删表时同一个工作流可能被多张表牵连，不去重会把同一份定义重写多次。
     */
    @Transactional
    public void refreshWorkflowDefinitions(Collection<Long> workflowIds, String operator) {
        Set<Long> distinctWorkflowIds = distinct(workflowIds);
        if (distinctWorkflowIds.isEmpty()) {
            return;
        }
        for (Long workflowId : distinctWorkflowIds) {
            workflowService.refreshTaskRelations(workflowId);
            workflowService.normalizeAndPersistMetadata(workflowId, operator);
        }
        log.debug("Refreshed workflow definitions after lineage change: {}", distinctWorkflowIds);
    }

    private LinkedHashSet<Long> distinct(Collection<Long> values) {
        if (CollectionUtils.isEmpty(values)) {
            return new LinkedHashSet<>();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
