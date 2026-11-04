package com.onedata.portal.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.runtime.RuntimeDiffSummary;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeTaskEdge;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowDefinition;
import com.onedata.portal.dto.workflow.runtime.RuntimeWorkflowSchedule;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowRuntimeDiffServiceTest {

    private final WorkflowRuntimeDiffService diffService = new WorkflowRuntimeDiffService(new ObjectMapper());

    @Test
    void buildDiffShouldDetectWorkflowFieldChanges() {
        RuntimeWorkflowDefinition baselineDef = definition("wf_a");
        baselineDef.setDescription("desc-a");
        baselineDef.setGlobalParams("[{\"prop\":\"k\",\"value\":\"v1\"}]");
        baselineDef.setTasks(Collections.singletonList(task(1L, "task_1", "SELECT 1", 11L, 21L)));

        RuntimeWorkflowDefinition currentDef = definition("wf_b");
        currentDef.setDescription("desc-b");
        currentDef.setGlobalParams("[{\"prop\":\"k\",\"value\":\"v2\"}]");
        currentDef.setTasks(Collections.singletonList(task(1L, "task_1", "SELECT 1", 11L, 21L)));

        RuntimeDiffSummary summary = buildDiff(baselineDef, currentDef,
                Collections.<RuntimeTaskEdge>emptyList(),
                Collections.<RuntimeTaskEdge>emptyList());

        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getWorkflowFieldChanges().stream()
                .anyMatch(item -> item.contains("workflow.workflowName")
                        && item.contains("wf_a")
                        && item.contains("wf_b")));
        assertTrue(summary.getWorkflowFieldChanges().stream()
                .anyMatch(item -> item.contains("workflow.description")
                        && item.contains("desc-a")
                        && item.contains("desc-b")));
        assertTrue(summary.getWorkflowFieldChanges().stream()
                .anyMatch(item -> item.contains("workflow.globalParams")
                        && item.contains("v1")
                        && item.contains("v2")));
        assertTrue(summary.getTaskAdded().isEmpty());
        assertTrue(summary.getTaskRemoved().isEmpty());
        assertTrue(summary.getTaskModified().isEmpty());
        assertTrue(summary.getEdgeAdded().isEmpty());
        assertTrue(summary.getEdgeRemoved().isEmpty());
        assertTrue(summary.getScheduleChanges().isEmpty());
    }

    @Test
    void buildDiffShouldDetectScheduleChanges() {
        RuntimeWorkflowDefinition baselineDef = definition("wf_a");
        baselineDef.setSchedule(schedule("0 0 1 * * ? *", "Asia/Shanghai"));
        baselineDef.setTasks(Collections.singletonList(task(1L, "task_1", "SELECT 1", 11L, 21L)));

        RuntimeWorkflowDefinition currentDef = definition("wf_a");
        RuntimeWorkflowSchedule schedule = schedule("0 30 2 * * ? *", "UTC");
        schedule.setWorkerGroup("wg_rt");
        currentDef.setSchedule(schedule);
        currentDef.setTasks(Collections.singletonList(task(1L, "task_1", "SELECT 1", 11L, 21L)));

        RuntimeDiffSummary summary = buildDiff(baselineDef, currentDef,
                Collections.<RuntimeTaskEdge>emptyList(),
                Collections.<RuntimeTaskEdge>emptyList());

        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.crontab")
                        && item.contains("0 0 1 * * ? *")
                        && item.contains("0 30 2 * * ? *")));
        assertTrue(summary.getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.timezoneId")
                        && item.contains("Asia/Shanghai")
                        && item.contains("UTC")));
        assertTrue(summary.getScheduleChanges().stream()
                .anyMatch(item -> item.contains("schedule.workerGroup")));
        assertTrue(summary.getWorkflowFieldChanges().isEmpty());
        assertTrue(summary.getTaskAdded().isEmpty());
        assertTrue(summary.getTaskRemoved().isEmpty());
        assertTrue(summary.getTaskModified().isEmpty());
        assertTrue(summary.getEdgeAdded().isEmpty());
        assertTrue(summary.getEdgeRemoved().isEmpty());
    }

    @Test
    void buildDiffShouldDetectTaskSqlChanges() {
        RuntimeWorkflowDefinition baselineDef = definition("wf_a");
        baselineDef.setTasks(Collections.singletonList(task(1L, "task_1", "SELECT * FROM ods.t1", 11L, 21L)));

        RuntimeWorkflowDefinition currentDef = definition("wf_a");
        currentDef.setTasks(Collections.singletonList(task(1L, "task_1", "SELECT * FROM ods.t1 WHERE dt='2026-02-24'", 11L, 21L)));

        RuntimeDiffSummary summary = buildDiff(baselineDef, currentDef,
                Collections.<RuntimeTaskEdge>emptyList(),
                Collections.<RuntimeTaskEdge>emptyList());

        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getTaskModified().stream()
                .anyMatch(item -> item.contains("task_1") && item.contains("sql")));
        assertTrue(summary.getTaskAdded().isEmpty());
        assertTrue(summary.getTaskRemoved().isEmpty());
        assertTrue(summary.getEdgeAdded().isEmpty());
        assertTrue(summary.getEdgeRemoved().isEmpty());
        assertTrue(summary.getWorkflowFieldChanges().isEmpty());
        assertTrue(summary.getScheduleChanges().isEmpty());
    }

    @Test
    void buildDiffShouldDetectTaskGroupNameChangesWhenTaskGroupIdMatches() {
        RuntimeWorkflowDefinition baselineDef = definition("wf_a");
        RuntimeTaskDefinition baselineTask = task(1L, "task_1", "SELECT 1", 11L, 21L);
        baselineTask.setTaskGroupId(77);
        baselineTask.setTaskGroupName("tg_alpha");
        baselineDef.setTasks(Collections.singletonList(baselineTask));

        RuntimeWorkflowDefinition currentDef = definition("wf_a");
        RuntimeTaskDefinition currentTask = task(1L, "task_1", "SELECT 1", 11L, 21L);
        currentTask.setTaskGroupId(77);
        currentTask.setTaskGroupName("tg_beta");
        currentDef.setTasks(Collections.singletonList(currentTask));

        RuntimeDiffSummary summary = buildDiff(baselineDef, currentDef,
                Collections.<RuntimeTaskEdge>emptyList(),
                Collections.<RuntimeTaskEdge>emptyList());

        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getTaskModified().stream()
                .anyMatch(item -> item.contains("task.taskGroupName")),
                "taskGroupId 一致时仍应比对 taskGroupName");
    }

    @Test
    void buildDiffShouldDetectTaskFlagChanges() {
        RuntimeWorkflowDefinition baselineDef = definition("wf_a");
        RuntimeTaskDefinition baselineTask = task(1L, "task_1", "SELECT 1", 11L, 21L);
        baselineTask.setFlag("YES");
        baselineDef.setTasks(Collections.singletonList(baselineTask));

        RuntimeWorkflowDefinition currentDef = definition("wf_a");
        RuntimeTaskDefinition currentTask = task(1L, "task_1", "SELECT 1", 11L, 21L);
        currentTask.setFlag("NO");
        currentDef.setTasks(Collections.singletonList(currentTask));

        RuntimeDiffSummary summary = buildDiff(baselineDef, currentDef,
                Collections.<RuntimeTaskEdge>emptyList(),
                Collections.<RuntimeTaskEdge>emptyList());

        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getTaskModified().stream()
                .anyMatch(item -> item.contains("task.flag") && item.contains("YES") && item.contains("NO")));
    }

    @Test
    void buildDiffShouldDetectTaskRelationChanges() {
        RuntimeWorkflowDefinition baselineDef = definition("wf_a");
        baselineDef.setTasks(Arrays.asList(
                task(1L, "task_1", "SELECT 1", 11L, 21L),
                task(2L, "task_2", "SELECT 2", 21L, 31L),
                task(3L, "task_3", "SELECT 3", 31L, 41L)));

        RuntimeWorkflowDefinition currentDef = definition("wf_a");
        currentDef.setTasks(Arrays.asList(
                task(1L, "task_1", "SELECT 1", 11L, 21L),
                task(2L, "task_2", "SELECT 2", 21L, 31L),
                task(3L, "task_3", "SELECT 3", 31L, 41L)));

        RuntimeDiffSummary summary = buildDiff(
                baselineDef,
                currentDef,
                Collections.singletonList(new RuntimeTaskEdge(1L, 2L)),
                Collections.singletonList(new RuntimeTaskEdge(2L, 3L)));

        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getEdgeAdded().stream()
                .anyMatch(item -> item.contains("task_2")
                        && item.contains("task_3")
                        && item.contains("2->3")));
        assertTrue(summary.getEdgeRemoved().stream()
                .anyMatch(item -> item.contains("task_1")
                        && item.contains("task_2")
                        && item.contains("1->2")));
        assertTrue(summary.getTaskAdded().isEmpty());
        assertTrue(summary.getTaskRemoved().isEmpty());
        assertTrue(summary.getTaskModified().isEmpty());
        assertTrue(summary.getWorkflowFieldChanges().isEmpty());
        assertTrue(summary.getScheduleChanges().isEmpty());
    }

    @Test
    void buildDiffShouldDetectEntryEdgeChanges() {
        RuntimeWorkflowDefinition baselineDef = definition("wf_a");
        baselineDef.setTasks(Arrays.asList(
                task(1L, "task_1", "SELECT 1", 11L, 21L),
                task(2L, "task_2", "SELECT 2", 21L, 31L)));

        RuntimeWorkflowDefinition currentDef = definition("wf_a");
        currentDef.setTasks(Arrays.asList(
                task(1L, "task_1", "SELECT 1", 11L, 21L),
                task(2L, "task_2", "SELECT 2", 21L, 31L)));

        RuntimeDiffSummary summary = buildDiff(
                baselineDef,
                currentDef,
                Collections.singletonList(new RuntimeTaskEdge(0L, 1L)),
                Collections.singletonList(new RuntimeTaskEdge(0L, 2L)));

        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getEdgeRemoved().stream()
                .anyMatch(item -> Boolean.TRUE.equals(item.getEntryEdge())
                        && item.getPreTaskCode() == 0L
                        && item.getPostTaskCode() == 1L));
        assertTrue(summary.getEdgeAdded().stream()
                .anyMatch(item -> Boolean.TRUE.equals(item.getEntryEdge())
                        && item.getPreTaskCode() == 0L
                        && item.getPostTaskCode() == 2L));
    }

    @Test
    void buildDiffShouldDetectTaskAdded() {
        RuntimeWorkflowDefinition baselineDef = definition("wf_a");
        baselineDef.setTasks(Collections.singletonList(task(1L, "task_1", "SELECT 1", 11L, 21L)));

        RuntimeWorkflowDefinition currentDef = definition("wf_a");
        currentDef.setTasks(Arrays.asList(
                task(1L, "task_1", "SELECT 1", 11L, 21L),
                task(2L, "task_2_new", "SELECT 2", 21L, 31L)));

        RuntimeDiffSummary summary = buildDiff(baselineDef, currentDef,
                Collections.<RuntimeTaskEdge>emptyList(),
                Collections.<RuntimeTaskEdge>emptyList());

        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getTaskAdded().stream()
                .anyMatch(item -> item.contains("task_2_new") && item.contains("taskCode=2")));
        assertTrue(summary.getTaskRemoved().isEmpty());
    }

    @Test
    void buildDiffShouldDetectTaskRemoved() {
        RuntimeWorkflowDefinition baselineDef = definition("wf_a");
        baselineDef.setTasks(Arrays.asList(
                task(1L, "task_1", "SELECT 1", 11L, 21L),
                task(2L, "task_2_removed", "SELECT 2", 21L, 31L)));

        RuntimeWorkflowDefinition currentDef = definition("wf_a");
        currentDef.setTasks(Collections.singletonList(task(1L, "task_1", "SELECT 1", 11L, 21L)));

        RuntimeDiffSummary summary = buildDiff(baselineDef, currentDef,
                Collections.<RuntimeTaskEdge>emptyList(),
                Collections.<RuntimeTaskEdge>emptyList());

        assertTrue(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getTaskRemoved().stream()
                .anyMatch(item -> item.contains("task_2_removed") && item.contains("taskCode=2")));
        assertTrue(summary.getTaskAdded().isEmpty());
    }

    @Test
    void buildDiffShouldBeEmptyWhenSnapshotsAreAligned() {
        RuntimeWorkflowDefinition definition = definition("wf_aligned");
        definition.setDescription("aligned");
        definition.setGlobalParams("[{\"prop\":\"k\",\"value\":\"v\"}]");
        definition.setSchedule(schedule("0 0 1 * * ? *", "Asia/Shanghai"));
        definition.setTasks(Arrays.asList(
                task(1L, "task_1", "SELECT * FROM ods.t1", 11L, 21L),
                task(2L, "task_2", "SELECT * FROM dwd.t1", 21L, 31L)));

        RuntimeDiffSummary summary = buildDiff(
                definition,
                definition,
                Collections.singletonList(new RuntimeTaskEdge(1L, 2L)),
                Collections.singletonList(new RuntimeTaskEdge(1L, 2L)));

        assertFalse(Boolean.TRUE.equals(summary.getChanged()));
        assertTrue(summary.getWorkflowFieldChanges().isEmpty());
        assertTrue(summary.getTaskAdded().isEmpty());
        assertTrue(summary.getTaskRemoved().isEmpty());
        assertTrue(summary.getTaskModified().isEmpty());
        assertTrue(summary.getEdgeAdded().isEmpty());
        assertTrue(summary.getEdgeRemoved().isEmpty());
        assertTrue(summary.getScheduleChanges().isEmpty());
    }

    private RuntimeDiffSummary buildDiff(RuntimeWorkflowDefinition baselineDef,
            RuntimeWorkflowDefinition currentDef,
            java.util.Collection<RuntimeTaskEdge> baselineEdges,
            java.util.Collection<RuntimeTaskEdge> currentEdges) {
        WorkflowRuntimeDiffService.RuntimeSnapshot baselineSnapshot = diffService.buildSnapshot(baselineDef, baselineEdges);
        WorkflowRuntimeDiffService.RuntimeSnapshot currentSnapshot = diffService.buildSnapshot(currentDef, currentEdges);
        return diffService.buildDiff(baselineSnapshot.getSnapshotJson(), currentSnapshot);
    }

    private RuntimeWorkflowDefinition definition(String name) {
        RuntimeWorkflowDefinition definition = new RuntimeWorkflowDefinition();
        definition.setProjectCode(1L);
        definition.setWorkflowCode(1001L);
        definition.setWorkflowName(name);
        definition.setGlobalParams("[]");
        return definition;
    }

    private RuntimeWorkflowSchedule schedule(String cron, String timezone) {
        RuntimeWorkflowSchedule schedule = new RuntimeWorkflowSchedule();
        schedule.setScheduleId(11L);
        schedule.setCrontab(cron);
        schedule.setTimezoneId(timezone);
        schedule.setReleaseState("ONLINE");
        return schedule;
    }

    private RuntimeTaskDefinition task(Long code, String name, String sql, Long inputTableId, Long outputTableId) {
        RuntimeTaskDefinition task = new RuntimeTaskDefinition();
        task.setTaskCode(code);
        task.setTaskName(name);
        task.setNodeType("SQL");
        task.setSql(sql);
        task.setDatasourceId(10L);
        task.setDatasourceName("doris_ds");
        task.setDatasourceType("DORIS");
        task.setFlag("YES");
        task.setInputTableIds(Collections.singletonList(inputTableId));
        task.setOutputTableIds(Collections.singletonList(outputTableId));
        return task;
    }
}
