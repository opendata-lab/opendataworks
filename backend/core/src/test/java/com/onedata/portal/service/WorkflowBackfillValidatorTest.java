package com.onedata.portal.service;

import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 与前端 backfillForm.js 对齐的同一组边界。
 */
class WorkflowBackfillValidatorTest {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String at(LocalDateTime value) {
        return value.format(FORMATTER);
    }

    private static final String YESTERDAY = at(LocalDate.now().minusDays(1).atStartOfDay());
    private static final String TODAY_START = at(LocalDate.now().atStartOfDay());
    private static final String TODAY_END = at(LocalDate.now().atTime(23, 59, 59));
    private static final String TOMORROW = at(LocalDate.now().plusDays(1).atStartOfDay());

    private WorkflowBackfillRequest rangeRequest(String startTime, String endTime) {
        WorkflowBackfillRequest request = new WorkflowBackfillRequest();
        request.setMode("range");
        request.setStartTime(startTime);
        request.setEndTime(endTime);
        return request;
    }

    private WorkflowBackfillRequest listRequest(String scheduleDateList) {
        WorkflowBackfillRequest request = new WorkflowBackfillRequest();
        request.setMode("list");
        request.setScheduleDateList(scheduleDateList);
        return request;
    }

    @Test
    void rejectsNullRequest() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> WorkflowBackfillValidator.validate(null));
        assertEquals("补数参数不能为空", thrown.getMessage());
    }

    @Test
    void rejectsEmptyRange() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> WorkflowBackfillValidator.validate(new WorkflowBackfillRequest()));
        assertEquals("补数时间范围不能为空", thrown.getMessage());
    }

    @Test
    void rejectsInvertedRange() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> WorkflowBackfillValidator.validate(rangeRequest(TODAY_START, YESTERDAY)));
        assertEquals("开始时间不能晚于结束时间", thrown.getMessage());
    }

    @Test
    void rejectsRangeReachingPastToday() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> WorkflowBackfillValidator.validate(rangeRequest(YESTERDAY, TOMORROW)));
        assertEquals("补数时间不能晚于今天：" + TOMORROW, thrown.getMessage());
    }

    @Test
    void acceptsRangeEndingAtTheLastMomentOfToday() {
        assertDoesNotThrow(() -> WorkflowBackfillValidator.validate(rangeRequest(YESTERDAY, TODAY_END)));
    }

    @Test
    void rejectsMalformedTimestamp() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> WorkflowBackfillValidator.validate(rangeRequest("2026/08/01", TODAY_END)));
        assertEquals("时间格式不正确：2026/08/01，应为 yyyy-MM-dd HH:mm:ss", thrown.getMessage());
    }

    @Test
    void rejectsEmptyScheduleDateList() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> WorkflowBackfillValidator.validate(listRequest(" , ")));
        assertEquals("补数时间列表不能为空", thrown.getMessage());
    }

    @Test
    void rejectsScheduleDateListEntryAfterToday() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> WorkflowBackfillValidator.validate(listRequest(YESTERDAY + "," + TOMORROW)));
        assertEquals("补数时间不能晚于今天：" + TOMORROW, thrown.getMessage());
    }

    @Test
    void acceptsPastScheduleDateList() {
        assertDoesNotThrow(() -> WorkflowBackfillValidator.validate(
                listRequest(" " + YESTERDAY + " , " + TODAY_START + " ")));
    }

    @Test
    void requiresPositiveParallelismOnlyInParallelMode() {
        WorkflowBackfillRequest parallel = rangeRequest(YESTERDAY, TODAY_END);
        parallel.setRunMode("RUN_MODE_PARALLEL");
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> WorkflowBackfillValidator.validate(parallel));
        assertEquals("并行度必须大于 0", thrown.getMessage());

        WorkflowBackfillRequest serial = rangeRequest(YESTERDAY, TODAY_END);
        serial.setRunMode("RUN_MODE_SERIAL");
        assertDoesNotThrow(() -> WorkflowBackfillValidator.validate(serial));
    }
}
