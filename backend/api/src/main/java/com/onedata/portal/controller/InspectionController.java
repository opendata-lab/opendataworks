package com.onedata.portal.controller;

import com.onedata.portal.dto.Result;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRecord;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.service.InspectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检控制器
 */
@Slf4j
@RestController
@RequestMapping("/v1/inspections")
@RequiredArgsConstructor
public class InspectionController {

    private final InspectionService inspectionService;

    /**
     * 手动触发全量巡检
     *
     * @param request 请求参数
     * @return 巡检记录
     */
    @PostMapping("/run")
    public Result<InspectionRecord> runInspection(@RequestBody RunInspectionRequest request) {
        log.info("Manual inspection triggered by: {}", request.getCreatedBy());
        InspectionRecord record = inspectionService.runFullInspection("manual", request.getCreatedBy());
        return Result.success(record);
    }

    /**
     * 获取巡检记录列表
     *
     * @param limit 返回条数
     * @return 巡检记录列表
     */
    @GetMapping("/records")
    public Result<List<InspectionRecord>> getInspectionRecords(
            @RequestParam(defaultValue = "20") Integer limit) {
        log.info("Query inspection records: limit={}", limit);
        List<InspectionRecord> records = inspectionService.getInspectionRecords(limit);
        return Result.success(records);
    }

    /**
     * 获取巡检记录详情
     *
     * @param recordId 巡检记录ID
     * @return 巡检详情(包含问题列表)
     */
    @GetMapping("/records/{recordId}")
    public Result<Map<String, Object>> getInspectionDetail(@PathVariable Long recordId) {
        log.info("Query inspection detail: recordId={}", recordId);

        // 获取问题列表
        List<InspectionIssue> issues = inspectionService.getInspectionIssues(recordId, null, null);

        // 统计问题数量
        Map<String, Long> severityCount = new HashMap<>();
        severityCount.put("critical", issues.stream().filter(i -> "critical".equals(i.getSeverity())).count());
        severityCount.put("high", issues.stream().filter(i -> "high".equals(i.getSeverity())).count());
        severityCount.put("medium", issues.stream().filter(i -> "medium".equals(i.getSeverity())).count());
        severityCount.put("low", issues.stream().filter(i -> "low".equals(i.getSeverity())).count());

        Map<String, Long> statusCount = new HashMap<>();
        statusCount.put("open", issues.stream().filter(i -> "open".equals(i.getStatus())).count());
        statusCount.put("acknowledged", issues.stream().filter(i -> "acknowledged".equals(i.getStatus())).count());
        statusCount.put("resolved", issues.stream().filter(i -> "resolved".equals(i.getStatus())).count());
        statusCount.put("ignored", issues.stream().filter(i -> "ignored".equals(i.getStatus())).count());

        Map<String, Object> result = new HashMap<>();
        result.put("issues", issues);
        result.put("severityCount", severityCount);
        result.put("statusCount", statusCount);

        return Result.success(result);
    }

    /**
     * 获取巡检问题列表
     *
     * @param recordId 巡检记录ID (可选)
     * @param status   问题状态 (可选)
     * @param severity 严重程度 (可选)
     * @return 问题列表
     */
    @GetMapping("/issues")
    public Result<List<InspectionIssue>> getInspectionIssues(
            @RequestParam(required = false) Long recordId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String severity,
            @RequestParam(required = false) Long clusterId,
            @RequestParam(required = false) String dbName,
            @RequestParam(required = false) String tableName) {
        log.info("Query inspection issues: recordId={}, status={}, severity={}, clusterId={}, dbName={}, tableName={}",
                recordId, status, severity, clusterId, dbName, tableName);
        List<InspectionIssue> issues = inspectionService.getInspectionIssues(recordId, status, severity, clusterId, dbName, tableName);
        return Result.success(issues);
    }

    /**
     * 更新问题状态
     *
     * @param issueId 问题ID
     * @param request 请求参数
     * @return 成功消息
     */
    @PutMapping("/issues/{issueId}/status")
    public Result<Map<String, Object>> updateIssueStatus(
            @PathVariable Long issueId,
            @RequestBody UpdateIssueStatusRequest request) {
        log.info("Update issue status: issueId={}, status={}, resolvedBy={}",
                issueId, request.getStatus(), request.getResolvedBy());

        inspectionService.updateIssueStatus(
                issueId,
                request.getStatus(),
                request.getResolvedBy(),
                request.getResolutionNote()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "问题状态更新成功");
        return Result.success(result);
    }

    /**
     * 一键修复问题（按问题类型执行）
     */
    @PostMapping("/issues/{issueId}/fix")
    public Result<Map<String, Object>> fixIssue(
            @PathVariable Long issueId,
            @RequestBody(required = false) FixIssueRequest request) {
        String fixedBy = request != null ? request.getFixedBy() : null;
        log.info("Fix inspection issue: issueId={}, fixedBy={}", issueId, fixedBy);
        Map<String, Object> result = inspectionService.fixIssue(issueId, fixedBy);
        return Result.success(result);
    }

    /**
     * 查看问题修复方案
     */
    @GetMapping("/issues/{issueId}/fix-plan")
    public Result<Map<String, Object>> getIssueFixPlan(@PathVariable Long issueId) {
        log.info("Get issue fix plan: issueId={}", issueId);
        Map<String, Object> result = inspectionService.getIssueFixPlan(issueId);
        return Result.success(result);
    }

    /**
     * 获取巡检概览统计
     *
     * @return 统计信息
     */
    @GetMapping("/overview")
    public Result<Map<String, Object>> getInspectionOverview() {
        log.info("Query inspection overview");

        // 获取最近的巡检记录
        List<InspectionRecord> recentRecords = inspectionService.getInspectionRecords(10);

        // 获取所有未解决的问题
        List<InspectionIssue> openIssues = inspectionService.getInspectionIssues(null, "open", null);

        // 统计问题严重程度分布
        Map<String, Long> severityDistribution = new HashMap<>();
        severityDistribution.put("critical", openIssues.stream().filter(i -> "critical".equals(i.getSeverity())).count());
        severityDistribution.put("high", openIssues.stream().filter(i -> "high".equals(i.getSeverity())).count());
        severityDistribution.put("medium", openIssues.stream().filter(i -> "medium".equals(i.getSeverity())).count());
        severityDistribution.put("low", openIssues.stream().filter(i -> "low".equals(i.getSeverity())).count());

        // 统计问题类型分布
        Map<String, Long> typeDistribution = new HashMap<>();
        openIssues.forEach(issue -> {
            typeDistribution.merge(issue.getIssueType(), 1L, Long::sum);
        });

        Map<String, Object> result = new HashMap<>();
        result.put("recentRecords", recentRecords);
        result.put("totalOpenIssues", openIssues.size());
        result.put("severityDistribution", severityDistribution);
        result.put("typeDistribution", typeDistribution);

        return Result.success(result);
    }

    /**
     * 获取巡检规则列表
     */
    @GetMapping("/rules")
    public Result<List<InspectionRule>> getInspectionRules(@RequestParam(required = false) Boolean enabled) {
        List<InspectionRule> rules = inspectionService.getInspectionRules(enabled);
        return Result.success(rules);
    }

    /**
     * 更新巡检规则启用状态
     */
    @PutMapping("/rules/{ruleId}/enabled")
    public Result<Map<String, Object>> updateRuleEnabled(
            @PathVariable Long ruleId,
            @RequestBody UpdateRuleEnabledRequest request) {
        inspectionService.updateRuleEnabled(ruleId, request.getEnabled());

        Map<String, Object> result = new HashMap<>();
        result.put("success", true);
        result.put("message", "规则状态更新成功");
        return Result.success(result);
    }

    /**
     * 更新巡检规则配置（rule_config / severity / 名称 / 描述）。ruleType 与 ruleCode 不可改。
     */
    @PutMapping("/rules/{ruleId}")
    public Result<Map<String, Object>> updateRule(
            @PathVariable Long ruleId,
            @RequestBody UpdateRuleRequest request) {
        try {
            inspectionService.updateRule(ruleId, request.getRuleName(), request.getDescription(),
                request.getSeverity(), request.getRuleConfig());
            Map<String, Object> result = new HashMap<>();
            result.put("success", true);
            return Result.success(result, "规则更新成功");
        } catch (Exception e) {
            return Result.fail(e.getMessage());
        }
    }

    /**
     * 手动触发巡检请求
     */
    @lombok.Data
    public static class RunInspectionRequest {
        private String createdBy;
    }

    /**
     * 更新问题状态请求
     */
    @lombok.Data
    public static class UpdateIssueStatusRequest {
        private String status; // open, acknowledged, resolved, ignored
        private String resolvedBy;
        private String resolutionNote;
    }

    /**
     * 更新规则启用状态请求
     */
    @lombok.Data
    public static class UpdateRuleEnabledRequest {
        private Boolean enabled;
    }

    /**
     * 更新规则配置请求
     */
    @lombok.Data
    public static class UpdateRuleRequest {
        private String ruleName;
        private String description;
        private String severity;
        private String ruleConfig;
    }

    /**
     * 一键修复请求
     */
    @lombok.Data
    public static class FixIssueRequest {
        private String fixedBy;
    }
}
