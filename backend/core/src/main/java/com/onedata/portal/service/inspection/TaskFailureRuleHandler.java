package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataTask;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.entity.TaskExecutionLog;
import com.onedata.portal.mapper.DataTaskMapper;
import com.onedata.portal.mapper.TaskExecutionLogMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



/**
 * 检查任务失败。
 */
@Component
@RequiredArgsConstructor
public class TaskFailureRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final TaskExecutionLogMapper executionLogMapper;
    private final DataTaskMapper dataTaskMapper;

    @Override
    public String ruleType() {
        return "task_failure";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());
        int checkDays = ((Number) config.getOrDefault("checkDays", 1)).intValue();
        int maxFailures = ((Number) config.getOrDefault("maxFailures", 3)).intValue();

        LocalDateTime since = LocalDateTime.now().minusDays(checkDays);

        // 查询最近失败的任务执行
        List<TaskExecutionLog> failedLogs = executionLogMapper.selectList(
            new LambdaQueryWrapper<TaskExecutionLog>()
                .eq(TaskExecutionLog::getStatus, "failed")
                .ge(TaskExecutionLog::getStartTime, since)
                .orderByDesc(TaskExecutionLog::getStartTime)
        );

        // 按任务ID分组统计失败次数
        Map<Long, Long> failureCountByTask = new HashMap<>();
        for (TaskExecutionLog log : failedLogs) {
            failureCountByTask.merge(log.getTaskId(), 1L, Long::sum);
        }

        // 检查失败次数超过阈值的任务
        for (Map.Entry<Long, Long> entry : failureCountByTask.entrySet()) {
            if (entry.getValue() >= maxFailures) {
                DataTask task = dataTaskMapper.selectById(entry.getKey());
                if (task != null) {
                    InspectionIssue issue = new InspectionIssue();
                    issue.setRecordId(recordId);
                    issue.setIssueType(rule.getRuleType());
                    issue.setSeverity("critical");
                    issue.setResourceType("task");
                    issue.setResourceId(task.getId());
                    issue.setResourceName(task.getTaskName());
                    issue.setIssueDescription(String.format("任务最近%d天内失败%d次", checkDays, entry.getValue()));
                    issue.setCurrentValue(String.format("%d次失败", entry.getValue()));
                    issue.setExpectedValue("< " + maxFailures + "次");
                    issue.setSuggestion("请检查任务执行日志,排查失败原因并修复");
                    issue.setStatus("open");
                    support.insertIssue(issue);
                    issues.add(issue);
                }
            }
        }

        return issues;
    }
}
