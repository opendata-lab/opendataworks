package com.onedata.portal.scheduled;

import com.onedata.portal.dto.workflow.WorkflowInstanceSummary;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 新鲜度触发的「新变为成功」判定：手动/定时触发、补数排除、已知成功去重、取最近实例。
 */
class WorkflowExecutionSyncJobTest {

    private final WorkflowExecutionSyncJob job = new WorkflowExecutionSyncJob(null, null, null, null);

    private WorkflowInstanceSummary instance(long id, String state, String commandType) {
        return WorkflowInstanceSummary.builder()
            .instanceId(id)
            .state(state)
            .commandType(commandType)
            .build();
    }

    private Optional<Long> pick(java.util.List<WorkflowInstanceSummary> instances, Set<Long> prior) {
        return job.latestNewlySucceeded(instances, prior).map(WorkflowInstanceSummary::getInstanceId);
    }

    @Test
    void scheduledSuccess_triggers() {
        assertEquals(Optional.of(1L),
            pick(Collections.singletonList(instance(1L, "SUCCESS", "SCHEDULER")), new HashSet<>()));
    }

    @Test
    void manualSuccess_triggers() {
        assertEquals(Optional.of(1L),
            pick(Collections.singletonList(instance(1L, "SUCCESS", "START_PROCESS")), new HashSet<>()));
    }

    @Test
    void backfillSuccess_doesNotTrigger() {
        // 补数写过去的调度日期，不改变当前新鲜度 → 不触发
        assertFalse(pick(Collections.singletonList(instance(1L, "SUCCESS", "COMPLEMENT_DATA")), new HashSet<>())
            .isPresent());
    }

    @Test
    void backfillMixedWithScheduled_triggersOnScheduledOnly() {
        assertEquals(Optional.of(2L), pick(Arrays.asList(
            instance(1L, "SUCCESS", "COMPLEMENT_DATA"),
            instance(2L, "SUCCESS", "SCHEDULER")), new HashSet<>()));
    }

    @Test
    void multipleNewSuccesses_picksLatestInstanceId() {
        assertEquals(Optional.of(5L), pick(Arrays.asList(
            instance(3L, "SUCCESS", "SCHEDULER"),
            instance(5L, "SUCCESS", "SCHEDULER"),
            instance(4L, "SUCCESS", "SCHEDULER")), new HashSet<>()));
    }

    @Test
    void onlyBackfillSuccesses_doesNotTrigger() {
        assertFalse(pick(Arrays.asList(
            instance(1L, "SUCCESS", "COMPLEMENT_DATA"),
            instance(2L, "SUCCESS", "COMPLEMENT_DATA")), new HashSet<>()).isPresent());
    }

    @Test
    void alreadyKnownSuccess_doesNotTrigger() {
        Set<Long> prior = new HashSet<>(Collections.singletonList(1L));
        assertFalse(pick(Collections.singletonList(instance(1L, "SUCCESS", "SCHEDULER")), prior).isPresent());
    }

    @Test
    void nonSuccess_doesNotTrigger() {
        assertFalse(pick(Collections.singletonList(instance(1L, "RUNNING_EXECUTION", "SCHEDULER")), new HashSet<>())
            .isPresent());
    }
}
