package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;



/**
 * 检查孤立表 - 没有上下游依赖关系的表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrphanTablesRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;

    @Override
    public String ruleType() {
        return "orphan_tables";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());

        // 是否检查所有状态的表,默认只检查 active 和 inactive 状态
        boolean includeDeprecated = (boolean) config.getOrDefault("includeDeprecated", false);
        // 孤立表的最短存在时间(天),避免刚创建的表被误判
        int minExistDays = ((Number) config.getOrDefault("minExistDays", 30)).intValue();

        LocalDateTime minCreateTime = LocalDateTime.now().minusDays(minExistDays);

        // 查询候选表
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .le(DataTable::getCreatedAt, minCreateTime);

        if (!includeDeprecated) {
            tableWrapper.in(DataTable::getStatus, "active", "inactive");
        }

        support.applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            // 检查是否有血缘关系
            boolean hasUpstream = support.hasUpstreamLineage(table.getId());
            boolean hasDownstream = support.hasDownstreamLineage(table.getId());

            if (!hasUpstream && !hasDownstream) {
                // 没有任何上下游依赖关系,是孤立表
                InspectionIssue issue = support.createIssue(recordId, rule, table);

                // 根据表的状态和数据量判断严重程度
                String severity = calculateOrphanTableSeverity(table);
                issue.setSeverity(severity);

                long existDays = Duration.between(table.getCreatedAt(), LocalDateTime.now()).toDays();
                issue.setIssueDescription(String.format("表没有任何上下游依赖关系,已存在 %d 天", existDays));
                issue.setCurrentValue("无上游,无下游");
                issue.setExpectedValue("至少有一个上游或下游依赖");
                issue.setSuggestion(generateOrphanTableSuggestion(table, existDays));

                support.insertIssue(issue);
                issues.add(issue);
            }
        }

        log.info("Found {} orphan tables", issues.size());
        return issues;
    }

    /**
     * 计算孤立表的严重程度
     */
    private String calculateOrphanTableSeverity(DataTable table) {
        // 如果是 deprecated 状态,严重程度较低
        if ("deprecated".equals(table.getStatus())) {
            return "low";
        }

        // 如果是 inactive 状态
        if ("inactive".equals(table.getStatus())) {
            return "medium";
        }

        // 如果是 active 状态的孤立表,需要关注
        // 根据数据量判断
        if (table.getRowCount() != null && table.getRowCount() > 0) {
            // 有数据的 active 孤立表,严重程度较高
            return "high";
        } else {
            // 没有数据的 active 孤立表
            return "medium";
        }
    }

    /**
     * 生成孤立表的修复建议
     */
    private String generateOrphanTableSuggestion(DataTable table, long existDays) {
        StringBuilder suggestion = new StringBuilder();

        if ("active".equals(table.getStatus())) {
            suggestion.append("该表状态为 active 但没有任何依赖关系,建议:\n");
            suggestion.append("1. 确认该表是否仍在使用\n");
            suggestion.append("2. 如果不再使用,修改状态为 deprecated\n");
            suggestion.append("3. 如果仍在使用,建立正确的血缘关系\n");
            suggestion.append("4. 检查是否为临时表或测试表\n");

            if (table.getRowCount() != null && table.getRowCount() > 0) {
                suggestion.append(String.format("5. 该表有数据(%,d 行),请谨慎处理\n", table.getRowCount()));
            }
        } else if ("inactive".equals(table.getStatus())) {
            suggestion.append("该表状态为 inactive 且无依赖关系,建议:\n");
            suggestion.append("1. 确认该表是否还需要\n");
            suggestion.append("2. 如确认不需要,修改状态为 deprecated\n");
            suggestion.append(String.format("3. 该表已存在 %d 天,考虑清理\n", existDays));
        } else if ("deprecated".equals(table.getStatus())) {
            suggestion.append("该表已废弃且无依赖关系,建议:\n");
            suggestion.append("1. 确认数据已备份(如需要)\n");
            suggestion.append("2. 可以安全删除该表\n");
            suggestion.append(String.format("3. 该表已存在 %d 天\n", existDays));
        }

        if (existDays > 180) {
            suggestion.append(String.format("注意: 该表已存在 %d 天(超过6个月),建议尽快处理", existDays));
        }

        return suggestion.toString();
    }
}
