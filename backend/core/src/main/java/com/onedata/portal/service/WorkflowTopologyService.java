package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.onedata.portal.dto.workflow.WorkflowTopologyResult;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;



/**
 * 根据血缘数据构建工作流拓扑
 */
@Service
@RequiredArgsConstructor
public class WorkflowTopologyService {

    private final TableTaskRelationMapper tableTaskRelationMapper;

    public WorkflowTopologyResult buildTopology(List<Long> taskIds) {
        if (CollectionUtils.isEmpty(taskIds)) {
            return WorkflowTopologyResult.empty();
        }
        List<TableTaskRelation> relations = tableTaskRelationMapper.selectList(
            Wrappers.<TableTaskRelation>lambdaQuery()
                .in(TableTaskRelation::getTaskId, taskIds)
        );
        if (CollectionUtils.isEmpty(relations)) {
            return WorkflowTopologyResult.builder()
                .upstreamMap(Collections.emptyMap())
                .downstreamMap(Collections.emptyMap())
                .entryTaskIds(new LinkedHashSet<>(taskIds))
                .exitTaskIds(new LinkedHashSet<>(taskIds))
                .build();
        }
        Map<Long, List<TableTaskRelation>> relationsByTable = relations.stream()
            .filter(relation -> relation.getTableId() != null)
            .collect(Collectors.groupingBy(TableTaskRelation::getTableId));
        Map<Long, Set<Long>> upstreamMap = new HashMap<>();
        Map<Long, Set<Long>> downstreamMap = new HashMap<>();
        relationsByTable.values().forEach(relationList -> {
            List<Long> writers = relationList.stream()
                .filter(rel -> "write".equalsIgnoreCase(rel.getRelationType()))
                .map(TableTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            List<Long> readers = relationList.stream()
                .filter(rel -> "read".equalsIgnoreCase(rel.getRelationType()))
                .map(TableTaskRelation::getTaskId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
            if (writers.isEmpty() || readers.isEmpty()) {
                return;
            }
            writers.forEach(writer -> readers.forEach(reader -> {
                if (Objects.equals(writer, reader)) {
                    return;
                }
                downstreamMap
                    .computeIfAbsent(writer, key -> new LinkedHashSet<>())
                    .add(reader);
                upstreamMap
                    .computeIfAbsent(reader, key -> new LinkedHashSet<>())
                    .add(writer);
            }));
        });
        Set<Long> entryTaskIds = new LinkedHashSet<>();
        Set<Long> exitTaskIds = new LinkedHashSet<>();
        taskIds.forEach(taskId -> {
            Set<Long> upstream = upstreamMap.getOrDefault(taskId, Collections.emptySet());
            Set<Long> downstream = downstreamMap.getOrDefault(taskId, Collections.emptySet());
            if (CollectionUtils.isEmpty(upstream)) {
                entryTaskIds.add(taskId);
            }
            if (CollectionUtils.isEmpty(downstream)) {
                exitTaskIds.add(taskId);
            }
        });
        if (entryTaskIds.isEmpty()) {
            entryTaskIds.addAll(taskIds);
        }
        if (exitTaskIds.isEmpty()) {
            exitTaskIds.addAll(taskIds);
        }
        return WorkflowTopologyResult.builder()
            .upstreamMap(upstreamMap)
            .downstreamMap(downstreamMap)
            .entryTaskIds(entryTaskIds)
            .exitTaskIds(exitTaskIds)
            .build();
    }
}
