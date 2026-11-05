package com.onedata.portal.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.entity.TableTaskRelation;
import com.onedata.portal.entity.WorkflowTaskRelation;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.DataWorkflowMapper;
import com.onedata.portal.mapper.TableTaskRelationMapper;
import com.onedata.portal.mapper.WorkflowTaskRelationMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
@TestPropertySource(properties = {
        "spring.task.scheduling.enabled=false",
        "workflow.runtime-sync.enabled=false"
})
@DisplayName("工作流任务关系服务集成测试")
class WorkflowTaskRelationServiceIntegrationTest {

    @Autowired
    private WorkflowTaskRelationService workflowTaskRelationService;
    @Autowired
    private DataWorkflowMapper dataWorkflowMapper;
    @Autowired
    private DataTaskMapper dataTaskMapper;
    @Autowired
    private WorkflowTaskRelationMapper workflowTaskRelationMapper;
    @Autowired
    private TableTaskRelationMapper tableTaskRelationMapper;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("刷新任务关系应基于真实血缘重建 entry/exit 和上下游计数")
    void refreshTaskRelationsShouldRebuildPersistedRelationsFromLineage() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        DataWorkflow workflow = workflow("it_rel_wf_" + suffix);
        dataWorkflowMapper.insert(workflow);

        DataTask upstream = task("it_rel_up_" + suffix);
        DataTask downstream = task("it_rel_down_" + suffix);
        dataTaskMapper.insert(upstream);
        dataTaskMapper.insert(downstream);

        WorkflowTaskRelation upstreamRelation = relation(workflow.getId(), upstream.getId(), false, false, 101L, "{\"x\":100}");
        WorkflowTaskRelation downstreamRelation = relation(workflow.getId(), downstream.getId(), false, false, 101L, "{\"x\":300}");
        workflowTaskRelationMapper.insert(upstreamRelation);
        workflowTaskRelationMapper.insert(downstreamRelation);
        Long oldUpstreamRelationId = upstreamRelation.getId();
        Long oldDownstreamRelationId = downstreamRelation.getId();

        Long tableId = Math.abs(UUID.randomUUID().getMostSignificantBits());
        tableTaskRelationMapper.insert(tableRelation(tableId, upstream.getId(), "write"));
        tableTaskRelationMapper.insert(tableRelation(tableId, downstream.getId(), "read"));

        workflowTaskRelationService.refreshTaskRelations(workflow.getId());

        List<WorkflowTaskRelation> refreshed = workflowTaskRelationMapper.selectList(
                Wrappers.<WorkflowTaskRelation>lambdaQuery()
                        .eq(WorkflowTaskRelation::getWorkflowId, workflow.getId())
                        .orderByAsc(WorkflowTaskRelation::getTaskId));
        assertEquals(2, refreshed.size());

        WorkflowTaskRelation refreshedUpstream = findByTaskId(refreshed, upstream.getId());
        WorkflowTaskRelation refreshedDownstream = findByTaskId(refreshed, downstream.getId());
        assertNotEquals(oldUpstreamRelationId, refreshedUpstream.getId());
        assertNotEquals(oldDownstreamRelationId, refreshedDownstream.getId());

        assertTrue(refreshedUpstream.getIsEntry());
        assertFalse(refreshedUpstream.getIsExit());
        assertEquals(Integer.valueOf(0), refreshedUpstream.getUpstreamTaskCount());
        assertEquals(Integer.valueOf(1), refreshedUpstream.getDownstreamTaskCount());
        assertEquals(Long.valueOf(101L), refreshedUpstream.getVersionId());
        assertEquals(100, objectMapper.readTree(refreshedUpstream.getNodeAttrs()).path("x").asInt());

        assertFalse(refreshedDownstream.getIsEntry());
        assertTrue(refreshedDownstream.getIsExit());
        assertEquals(Integer.valueOf(1), refreshedDownstream.getUpstreamTaskCount());
        assertEquals(Integer.valueOf(0), refreshedDownstream.getDownstreamTaskCount());
        assertEquals(Long.valueOf(101L), refreshedDownstream.getVersionId());
        assertEquals(300, objectMapper.readTree(refreshedDownstream.getNodeAttrs()).path("x").asInt());
    }

    private WorkflowTaskRelation findByTaskId(List<WorkflowTaskRelation> relations, Long taskId) {
        return relations.stream()
                .filter(relation -> taskId.equals(relation.getTaskId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing relation for task " + taskId));
    }

    private DataWorkflow workflow(String name) {
        DataWorkflow workflow = new DataWorkflow();
        workflow.setWorkflowName(name);
        workflow.setStatus("draft");
        workflow.setPublishStatus("never");
        workflow.setDefinitionJson("{}");
        workflow.setEntryTaskIds("[]");
        workflow.setExitTaskIds("[]");
        workflow.setCreatedBy("it-task-relation");
        workflow.setUpdatedBy("it-task-relation");
        workflow.setCreatedAt(LocalDateTime.now());
        workflow.setUpdatedAt(LocalDateTime.now());
        return workflow;
    }

    private DataTask task(String name) {
        DataTask task = new DataTask();
        task.setTaskName(name);
        task.setTaskCode(name);
        task.setTaskType("batch");
        task.setEngine("dolphin");
        task.setDolphinNodeType("SQL");
        task.setDatasourceType("MYSQL");
        task.setTaskSql("select 1");
        task.setStatus("draft");
        task.setOwner("it-task-relation");
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }

    private WorkflowTaskRelation relation(Long workflowId,
            Long taskId,
            boolean entry,
            boolean exit,
            Long versionId,
            String nodeAttrs) {
        WorkflowTaskRelation relation = new WorkflowTaskRelation();
        relation.setWorkflowId(workflowId);
        relation.setTaskId(taskId);
        relation.setIsEntry(entry);
        relation.setIsExit(exit);
        relation.setVersionId(versionId);
        relation.setNodeAttrs(nodeAttrs);
        relation.setUpstreamTaskCount(0);
        relation.setDownstreamTaskCount(0);
        relation.setCreatedAt(LocalDateTime.now());
        relation.setUpdatedAt(LocalDateTime.now());
        return relation;
    }

    private TableTaskRelation tableRelation(Long tableId, Long taskId, String relationType) {
        TableTaskRelation relation = new TableTaskRelation();
        relation.setTableId(tableId);
        relation.setTaskId(taskId);
        relation.setRelationType(relationType);
        relation.setCreatedAt(LocalDateTime.now());
        relation.setUpdatedAt(LocalDateTime.now());
        return relation;
    }
}
