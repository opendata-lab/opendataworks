package com.onedata.portal.service;

import com.onedata.portal.dto.workflow.WorkflowBackfillRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.util.StringUtils;



/**
 * Validates workflow backfill requests before anything is persisted or sent to
 * DolphinScheduler.
 *
 * <p>Backfill only ever targets schedule periods that already elapsed, so any
 * date later than today is rejected. The rule is day-granular — the whole of
 * today stays selectable — and matches the frontend guard in
 * {@code frontend/src/views/workflows/backfillForm.js}.</p>
 */
public final class WorkflowBackfillValidator {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String FORMAT_HINT = "yyyy-MM-dd HH:mm:ss";

    private WorkflowBackfillValidator() {
    }

    public static void validate(WorkflowBackfillRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("补数参数不能为空");
        }
        if (isListMode(request)) {
            validateScheduleDateList(request.getScheduleDateList());
        } else {
            validateRange(request.getStartTime(), request.getEndTime());
        }
        if ("RUN_MODE_PARALLEL".equalsIgnoreCase(request.getRunMode())) {
            Integer parallelism = request.getExpectedParallelismNumber();
            if (parallelism == null || parallelism < 1) {
                throw new IllegalArgumentException("并行度必须大于 0");
            }
        }
    }

    public static boolean isListMode(WorkflowBackfillRequest request) {
        return "list".equalsIgnoreCase(request.getMode());
    }

    private static void validateRange(String startTime, String endTime) {
        if (!StringUtils.hasText(startTime) || !StringUtils.hasText(endTime)) {
            throw new IllegalArgumentException("补数时间范围不能为空");
        }
        LocalDateTime start = parse(startTime);
        LocalDateTime end = parse(endTime);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始时间不能晚于结束时间");
        }
        rejectAfterToday(end);
    }

    private static void validateScheduleDateList(String scheduleDateList) {
        if (!StringUtils.hasText(scheduleDateList)) {
            throw new IllegalArgumentException("补数时间列表不能为空");
        }
        boolean hasItem = false;
        for (String raw : scheduleDateList.split(",")) {
            String item = raw.trim();
            if (item.isEmpty()) {
                continue;
            }
            hasItem = true;
            rejectAfterToday(parse(item));
        }
        if (!hasItem) {
            throw new IllegalArgumentException("补数时间列表不能为空");
        }
    }

    private static LocalDateTime parse(String value) {
        try {
            return LocalDateTime.parse(value.trim(), FORMATTER);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("时间格式不正确：" + value + "，应为 " + FORMAT_HINT);
        }
    }

    private static void rejectAfterToday(LocalDateTime value) {
        if (value.toLocalDate().isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("补数时间不能晚于今天：" + value.format(FORMATTER));
        }
    }
}
