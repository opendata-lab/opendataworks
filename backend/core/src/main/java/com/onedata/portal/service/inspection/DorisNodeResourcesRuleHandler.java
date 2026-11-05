package com.onedata.portal.service.inspection;

import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.service.HealthCheckService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



/**
 * 检查 Doris 节点资源使用。
 */
@Component
@RequiredArgsConstructor
public class DorisNodeResourcesRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final HealthCheckService healthCheckService;

    @Override
    public String ruleType() {
        return "doris_node_resources";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());

        // 磁盘使用率告警阈值
        double diskWarningThreshold = ((Number) config.getOrDefault("diskWarningThreshold", 80.0)).doubleValue();
        double diskCriticalThreshold = ((Number) config.getOrDefault("diskCriticalThreshold", 90.0)).doubleValue();

        // 内存使用率告警阈值
        double memoryWarningThreshold = ((Number) config.getOrDefault("memoryWarningThreshold", 80.0)).doubleValue();
        double memoryCriticalThreshold = ((Number) config.getOrDefault("memoryCriticalThreshold", 90.0)).doubleValue();

        List<HealthCheckService.DorisNodeResourceStatus> nodeStatuses =
            healthCheckService.checkDorisNodeResources();

        for (HealthCheckService.DorisNodeResourceStatus nodeStatus : nodeStatuses) {
            // 检查磁盘使用率
            if (nodeStatus.getDiskUsagePercent() >= diskCriticalThreshold) {
                InspectionIssue issue = createDorisNodeIssue(recordId, rule, nodeStatus);
                issue.setSeverity("critical");
                issue.setIssueDescription(String.format("节点 %s 磁盘使用率过高", nodeStatus.getHost()));
                issue.setCurrentValue(String.format("%.2f%%", nodeStatus.getDiskUsagePercent()));
                issue.setExpectedValue(String.format("< %.0f%%", diskCriticalThreshold));
                issue.setSuggestion("立即处理:\n1. 清理过期数据和临时文件\n2. 检查数据归档策略\n3. 考虑扩容磁盘\n4. 检查是否有异常大表");
                support.insertIssue(issue);
                issues.add(issue);
            } else if (nodeStatus.getDiskUsagePercent() >= diskWarningThreshold) {
                InspectionIssue issue = createDorisNodeIssue(recordId, rule, nodeStatus);
                issue.setSeverity("high");
                issue.setIssueDescription(String.format("节点 %s 磁盘使用率接近上限", nodeStatus.getHost()));
                issue.setCurrentValue(String.format("%.2f%%", nodeStatus.getDiskUsagePercent()));
                issue.setExpectedValue(String.format("< %.0f%%", diskWarningThreshold));
                issue.setSuggestion("建议:\n1. 规划磁盘扩容\n2. 检查数据增长趋势\n3. 优化数据生命周期策略");
                support.insertIssue(issue);
                issues.add(issue);
            }

            // 检查内存使用率
            if (nodeStatus.getMemoryUsagePercent() >= memoryCriticalThreshold) {
                InspectionIssue issue = createDorisNodeIssue(recordId, rule, nodeStatus);
                issue.setSeverity("critical");
                issue.setIssueDescription(String.format("节点 %s 内存使用率过高", nodeStatus.getHost()));
                issue.setCurrentValue(String.format("%.2f%% (%s / %s)",
                    nodeStatus.getMemoryUsagePercent(),
                    support.formatBytes(nodeStatus.getMemoryUsedBytes()),
                    support.formatBytes(nodeStatus.getMemoryLimitBytes())));
                issue.setExpectedValue(String.format("< %.0f%%", memoryCriticalThreshold));
                issue.setSuggestion("立即处理:\n1. 检查是否有慢查询占用过多内存\n2. 优化查询计划\n3. 考虑增加节点内存配置\n4. 重启服务释放内存(谨慎操作)");
                support.insertIssue(issue);
                issues.add(issue);
            } else if (nodeStatus.getMemoryUsagePercent() >= memoryWarningThreshold) {
                InspectionIssue issue = createDorisNodeIssue(recordId, rule, nodeStatus);
                issue.setSeverity("high");
                issue.setIssueDescription(String.format("节点 %s 内存使用率较高", nodeStatus.getHost()));
                issue.setCurrentValue(String.format("%.2f%% (%s / %s)",
                    nodeStatus.getMemoryUsagePercent(),
                    support.formatBytes(nodeStatus.getMemoryUsedBytes()),
                    support.formatBytes(nodeStatus.getMemoryLimitBytes())));
                issue.setExpectedValue(String.format("< %.0f%%", memoryWarningThreshold));
                issue.setSuggestion("建议:\n1. 监控内存使用趋势\n2. 优化频繁执行的查询\n3. 规划内存扩容");
                support.insertIssue(issue);
                issues.add(issue);
            }

            // 检查节点存活状态
            if (!nodeStatus.isAlive()) {
                InspectionIssue issue = createDorisNodeIssue(recordId, rule, nodeStatus);
                issue.setSeverity("critical");
                issue.setIssueDescription(String.format("节点 %s 离线", nodeStatus.getHost()));
                issue.setCurrentValue("离线");
                issue.setExpectedValue("在线");
                issue.setSuggestion("紧急处理:\n1. 检查节点服务是否运行\n2. 检查网络连接\n3. 查看节点日志排查问题\n4. 联系运维团队");
                support.insertIssue(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * 创建 Doris 节点问题记录
     */
    private InspectionIssue createDorisNodeIssue(Long recordId, InspectionRule rule,
                                                   HealthCheckService.DorisNodeResourceStatus nodeStatus) {
        InspectionIssue issue = new InspectionIssue();
        issue.setRecordId(recordId);
        issue.setIssueType(rule.getRuleType());
        issue.setSeverity(rule.getSeverity());
        issue.setResourceType("doris_node");
        issue.setResourceName(String.format("%s:%d", nodeStatus.getHost(), nodeStatus.getPort()));
        issue.setStatus("open");
        return issue;
    }
}
