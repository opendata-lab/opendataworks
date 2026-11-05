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
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



/**
 * 检查任务调度 - 长期未执行的已发布任务。
 */
@Component
@RequiredArgsConstructor
public class TaskScheduleRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTaskMapper dataTaskMapper;
    private final TaskExecutionLogMapper executionLogMapper;

    @Override
    public String ruleType() {
        return "task_schedule";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());
        int checkDays = ((Number) config.getOrDefault("checkDays", 7)).intValue();

        LocalDateTime since = LocalDateTime.now().minusDays(checkDays);

        // 查询已发布但长期未执行的任务
        List<DataTask> publishedTasks = dataTaskMapper.selectList(
            new LambdaQueryWrapper<DataTask>()
                .eq(DataTask::getStatus, "published")
        );

        for (DataTask task : publishedTasks) {
            // 查询最近的执行记录
            TaskExecutionLog latestLog = executionLogMapper.selectOne(
                new LambdaQueryWrapper<TaskExecutionLog>()
                    .eq(TaskExecutionLog::getTaskId, task.getId())
                    .orderByDesc(TaskExecutionLog::getStartTime)
                    .last("LIMIT 1")
            );

            // 如果没有执行记录或最近执行时间超过阈值
            // Defensive null handling for start_time
            boolean isOverdue = latestLog == null ||
                                latestLog.getStartTime() == null ||
                                latestLog.getStartTime().isBefore(since);

            if (isOverdue) {
                InspectionIssue issue = new InspectionIssue();
                issue.setRecordId(recordId);
                issue.setIssueType(rule.getRuleType());
                issue.setSeverity("medium");
                issue.setResourceType("task");
                issue.setResourceId(task.getId());
                issue.setResourceName(task.getTaskName());
                issue.setIssueDescription(String.format("已发布任务超过%d天未执行", checkDays));
                issue.setCurrentValue(latestLog != null && latestLog.getStartTime() != null
                    ? latestLog.getStartTime().toString()
                    : "从未执行");
                issue.setExpectedValue("定期执行");
                issue.setSuggestion("请检查任务调度配置或下线不需要的任务");
                issue.setStatus("open");
                support.insertIssue(issue);
                issues.add(issue);
            }
        }

        return issues;
    }
}
