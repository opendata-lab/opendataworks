package com.onedata.portal.service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;


/**
 * Shared mapping helpers for DolphinScheduler workflow and task instances.
 */
public final class DolphinExecutionMapper {

    private static final List<DateTimeFormatter> LOCAL_DATE_TIME_FORMATTERS = Arrays.asList(
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"));

    private DolphinExecutionMapper() {
    }

    public static String mapStatus(String dolphinStatus) {
        if (dolphinStatus == null) {
            return "pending";
        }
        switch (dolphinStatus.toUpperCase(Locale.ROOT)) {
            case "SUCCESS":
            case "FORCED_SUCCESS":
                return "success";
            case "FAILURE":
            case "FAILED":
                return "failed";
            case "RUNNING_EXECUTION":
            case "RUNNING":
            case "SUBMITTED_SUCCESS":
            case "DELAY_EXECUTION":
                return "running";
            case "STOP":
            case "KILL":
            case "KILLED":
                return "killed";
            case "READY_PAUSE":
            case "PAUSE":
                return "paused";
            default:
                return "pending";
        }
    }

    public static boolean isWorkflowActive(String dolphinStatus) {
        String status = mapStatus(dolphinStatus);
        return "running".equals(status) || "pending".equals(status);
    }

    public static String mapTriggerType(String commandType) {
        if (commandType == null) {
            return "api";
        }
        String normalized = commandType.trim().toUpperCase(Locale.ROOT);
        if (normalized.contains("COMPLEMENT") || "BACKFILL".equals(normalized)) {
            return "backfill";
        }
        if (normalized.contains("SCHEDULER") || normalized.contains("SCHEDULE")) {
            return "schedule";
        }
        if ("START_PROCESS".equals(normalized) || "MANUAL".equals(normalized)) {
            return "manual";
        }
        if ("API".equals(normalized)) {
            return "api";
        }
        return "api";
    }

    /**
     * Build the DolphinScheduler 3.x Web UI deep link for one workflow instance.
     * Returns {@code null} when any part is missing so callers can degrade to
     * plain text instead of rendering a broken link.
     */
    public static String workflowInstanceUrl(String baseUrl,
            Long projectCode,
            Long workflowCode,
            Long instanceId) {
        if (baseUrl == null || baseUrl.trim().isEmpty()
                || projectCode == null || workflowCode == null || instanceId == null) {
            return null;
        }
        return String.format("%s/ui/projects/%d/workflow/instances/%d?code=%d",
                baseUrl.replaceAll("/+$", ""), projectCode, instanceId, workflowCode);
    }

    public static LocalDateTime parseDateTime(String value) {
        if (value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim())) {
            return null;
        }
        String normalized = value.trim();
        try {
            return OffsetDateTime.parse(normalized, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime();
        } catch (Exception ignored) {
            // Try the local DolphinScheduler formats below.
        }
        for (DateTimeFormatter formatter : LOCAL_DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(normalized, formatter);
            } catch (Exception ignored) {
                // Continue to the next known format.
            }
        }
        return null;
    }

    public static Integer durationSeconds(LocalDateTime startTime,
            LocalDateTime endTime,
            String rawDuration) {
        if (startTime != null && endTime != null) {
            long seconds = Math.max(0, Duration.between(startTime, endTime).getSeconds());
            return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
        }
        if (rawDuration == null || rawDuration.trim().isEmpty()) {
            return null;
        }
        String value = rawDuration.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(seconds, 0);
        } catch (NumberFormatException ignored) {
            // Dolphin commonly formats this field as HH:mm:ss.
        }
        String[] parts = value.split(":");
        if (parts.length == 3) {
            try {
                long seconds = Long.parseLong(parts[0]) * 3600
                        + Long.parseLong(parts[1]) * 60
                        + Long.parseLong(parts[2]);
                return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(seconds, 0);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
