package com.onedata.portal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onedata.portal.dto.workflow.WorkflowScheduleRequest;
import com.onedata.portal.entity.DataWorkflow;
import com.onedata.portal.mapper.DataWorkflowMapper;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;



/**
 * 工作流定时调度（DolphinScheduler Schedule）管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowScheduleService {

    private static final DateTimeFormatter OUTPUT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter[] INPUT_FORMATS = new DateTimeFormatter[] {
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    };

    private final DataWorkflowMapper dataWorkflowMapper;
    private final DolphinSchedulerService dolphinSchedulerService;
    private final ObjectMapper objectMapper;

    @Transactional
    public DataWorkflow upsertSchedule(Long workflowId, WorkflowScheduleRequest request) {
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId is required");
        }
        if (request == null) {
            throw new IllegalArgumentException("schedule request is required");
        }

        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        if (Boolean.TRUE.equals(request.getEnabled()) && !"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，无法启用定时调度");
        }

        String cron = normalizeText(request.getScheduleCron());
        if (!StringUtils.hasText(cron)) {
            throw new IllegalArgumentException("scheduleCron is required");
        }

        String timezone = normalizeText(request.getScheduleTimezone());
        if (!StringUtils.hasText(timezone)) {
            timezone = "Asia/Shanghai";
        }

        LocalDateTime start = parseFlexibleDateTime(request.getScheduleStartTime(), "scheduleStartTime");
        LocalDateTime end = parseFlexibleDateTime(request.getScheduleEndTime(), "scheduleEndTime");
        if (end.isBefore(start)) {
            throw new IllegalArgumentException("scheduleEndTime must be after scheduleStartTime");
        }

        String failureStrategy = normalizeText(request.getScheduleFailureStrategy());
        if (!StringUtils.hasText(failureStrategy)) {
            failureStrategy = "CONTINUE";
        }
        String warningType = normalizeText(request.getScheduleWarningType());
        if (!StringUtils.hasText(warningType)) {
            warningType = "NONE";
        }
        if ("SUCCESS_FAILURE".equalsIgnoreCase(warningType)) {
            warningType = "ALL";
        }
        Long warningGroupId = request.getScheduleWarningGroupId();
        if (warningGroupId == null) {
            warningGroupId = 0L;
        }
        if (!"NONE".equalsIgnoreCase(warningType) && warningGroupId <= 0) {
            throw new IllegalArgumentException("告警类型非 NONE 时，必须选择告警组");
        }

        String processInstancePriority = normalizeText(request.getScheduleProcessInstancePriority());
        if (!StringUtils.hasText(processInstancePriority)) {
            processInstancePriority = normalizeText(workflow.getScheduleProcessInstancePriority());
        }
        if (!StringUtils.hasText(processInstancePriority)) {
            processInstancePriority = "MEDIUM";
        }

        String workerGroup = normalizeText(request.getScheduleWorkerGroup());
        if (!StringUtils.hasText(workerGroup)) {
            workerGroup = normalizeText(workflow.getScheduleWorkerGroup());
        }
        if (!StringUtils.hasText(workerGroup)) {
            workerGroup = dolphinSchedulerService.getDefaultWorkerGroup();
        }

        String tenantCode = normalizeText(request.getScheduleTenantCode());
        if (!StringUtils.hasText(tenantCode)) {
            tenantCode = normalizeText(workflow.getScheduleTenantCode());
        }
        if (!StringUtils.hasText(tenantCode)) {
            tenantCode = dolphinSchedulerService.getDefaultTenantCode();
        }

        Long environmentCode = request.getScheduleEnvironmentCode();
        if (environmentCode == null) {
            environmentCode = workflow.getScheduleEnvironmentCode();
        }
        if (environmentCode == null) {
            environmentCode = -1L;
        }

        String scheduleJson = buildScheduleJson(start, end, timezone, cron);
        Long scheduleId = workflow.getDolphinScheduleId();
        boolean newSchedule = scheduleId == null || scheduleId <= 0;
        if (newSchedule) {
            scheduleId = dolphinSchedulerService.createWorkflowSchedule(
                    workflow.getDolphinConfigId(),
                    workflow.getWorkflowCode(),
                    scheduleJson,
                    warningType,
                    failureStrategy,
                    warningGroupId,
                    processInstancePriority,
                    workerGroup,
                    tenantCode,
                    environmentCode);
            if (scheduleId == null || scheduleId <= 0) {
                throw new IllegalStateException("创建 DolphinScheduler 调度失败，未返回调度ID");
            }
            workflow.setDolphinScheduleId(scheduleId);
            if (!StringUtils.hasText(workflow.getScheduleState())) {
                workflow.setScheduleState("OFFLINE");
            }
        } else {
            dolphinSchedulerService.updateWorkflowSchedule(
                    workflow.getDolphinConfigId(),
                    scheduleId,
                    workflow.getWorkflowCode(),
                    scheduleJson,
                    warningType,
                    failureStrategy,
                    warningGroupId,
                    processInstancePriority,
                    workerGroup,
                    tenantCode,
                    environmentCode);
        }

        workflow.setScheduleCron(cron);
        workflow.setScheduleTimezone(timezone);
        workflow.setScheduleStartTime(start);
        workflow.setScheduleEndTime(end);
        workflow.setScheduleFailureStrategy(failureStrategy);
        workflow.setScheduleWarningType(warningType);
        workflow.setScheduleWarningGroupId(warningGroupId);
        workflow.setScheduleProcessInstancePriority(processInstancePriority);
        workflow.setScheduleWorkerGroup(workerGroup);
        workflow.setScheduleTenantCode(tenantCode);
        workflow.setScheduleEnvironmentCode(environmentCode);
        if (request.getScheduleAutoOnline() != null) {
            workflow.setScheduleAutoOnline(Boolean.TRUE.equals(request.getScheduleAutoOnline()));
        }

        // Apply enable/disable if provided
        if (request.getEnabled() != null) {
            if (Boolean.TRUE.equals(request.getEnabled())) {
                dolphinSchedulerService.onlineWorkflowSchedule(workflow.getDolphinConfigId(), scheduleId);
                workflow.setScheduleState("ONLINE");
            } else {
                dolphinSchedulerService.offlineWorkflowSchedule(workflow.getDolphinConfigId(), scheduleId);
                workflow.setScheduleState("OFFLINE");
            }
        }

        dataWorkflowMapper.updateById(workflow);
        return workflow;
    }

    @Transactional
    public DataWorkflow onlineSchedule(Long workflowId) {
        DataWorkflow workflow = requireWorkflow(workflowId);
        Long scheduleId = workflow.getDolphinScheduleId();
        if (scheduleId == null || scheduleId <= 0) {
            throw new IllegalStateException("尚未创建调度配置");
        }
        if (!"online".equalsIgnoreCase(workflow.getStatus())) {
            throw new IllegalStateException("工作流未上线，无法启用定时调度");
        }
        dolphinSchedulerService.onlineWorkflowSchedule(workflow.getDolphinConfigId(), scheduleId);
        workflow.setScheduleState("ONLINE");
        dataWorkflowMapper.updateById(workflow);
        return workflow;
    }

    @Transactional
    public DataWorkflow offlineSchedule(Long workflowId) {
        DataWorkflow workflow = requireWorkflow(workflowId);
        Long scheduleId = workflow.getDolphinScheduleId();
        if (scheduleId == null || scheduleId <= 0) {
            throw new IllegalStateException("尚未创建调度配置");
        }
        dolphinSchedulerService.offlineWorkflowSchedule(workflow.getDolphinConfigId(), scheduleId);
        workflow.setScheduleState("OFFLINE");
        dataWorkflowMapper.updateById(workflow);
        return workflow;
    }

    private DataWorkflow requireWorkflow(Long workflowId) {
        if (workflowId == null) {
            throw new IllegalArgumentException("workflowId is required");
        }
        DataWorkflow workflow = dataWorkflowMapper.selectById(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("Workflow not found: " + workflowId);
        }
        if (workflow.getWorkflowCode() == null || workflow.getWorkflowCode() <= 0) {
            throw new IllegalStateException("工作流尚未部署或缺少 Dolphin 编码");
        }
        return workflow;
    }

    private String buildScheduleJson(LocalDateTime start,
            LocalDateTime end,
            String timezoneId,
            String crontab) {
        Map<String, Object> schedule = new HashMap<>();
        schedule.put("startTime", start.format(OUTPUT_FORMAT));
        schedule.put("endTime", end.format(OUTPUT_FORMAT));
        schedule.put("timezoneId", timezoneId);
        schedule.put("crontab", crontab);
        try {
            return objectMapper.writeValueAsString(schedule);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize schedule JSON", e);
        }
    }

    private LocalDateTime parseFlexibleDateTime(String raw, String fieldName) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        String candidate = raw.trim().replace("Z", "");
        for (DateTimeFormatter formatter : INPUT_FORMATS) {
            try {
                return LocalDateTime.parse(candidate, formatter);
            } catch (DateTimeParseException ignore) {
                // try next
            }
        }
        throw new IllegalArgumentException(fieldName + " format is invalid: " + raw);
    }

    private String normalizeText(String value) {
        return value == null ? null : value.trim();
    }
}
