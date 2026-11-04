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
 * 检查服务健康状态。
 */
@Component
@RequiredArgsConstructor
public class ServiceHealthRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final HealthCheckService healthCheckService;

    @Override
    public String ruleType() {
        return "service_health";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();

        Map<String, HealthCheckService.ServiceHealthStatus> healthStatuses =
            healthCheckService.checkAllServices();

        for (Map.Entry<String, HealthCheckService.ServiceHealthStatus> entry : healthStatuses.entrySet()) {
            HealthCheckService.ServiceHealthStatus status = entry.getValue();

            if (!status.isHealthy()) {
                InspectionIssue issue = new InspectionIssue();
                issue.setRecordId(recordId);
                issue.setIssueType(rule.getRuleType());
                issue.setSeverity("critical");
                issue.setResourceType("service");
                issue.setResourceName(status.getServiceName());
                issue.setIssueDescription("服务健康检查失败: " + status.getMessage());
                issue.setCurrentValue("不健康");
                issue.setExpectedValue("服务正常运行");
                issue.setSuggestion(generateServiceHealthSuggestion(status));
                issue.setStatus("open");
                support.insertIssue(issue);
                issues.add(issue);
            }
        }

        return issues;
    }

    /**
     * 生成服务健康问题的建议
     */
    private String generateServiceHealthSuggestion(HealthCheckService.ServiceHealthStatus status) {
        StringBuilder suggestion = new StringBuilder();
        suggestion.append("服务 ").append(status.getServiceName()).append(" 异常,请检查:\n");
        suggestion.append("1. 检查服务是否正常运行\n");
        suggestion.append("2. 检查网络连接是否正常\n");
        suggestion.append("3. 查看服务日志排查问题\n");
        suggestion.append("4. 检查服务配置是否正确\n");

        if (status.getError() != null) {
            suggestion.append("5. 错误类型: ").append(status.getError()).append("\n");
        }

        suggestion.append("6. 如问题持续,请联系运维团队");
        return suggestion.toString();
    }
}
