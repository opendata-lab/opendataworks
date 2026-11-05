package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;



/**
 * 检查副本数。
 */
@Component
@RequiredArgsConstructor
public class ReplicaCountRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;

    @Override
    public String ruleType() {
        return "replica_count";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());
        int minReplicas = ((Number) config.getOrDefault("minReplicas", 1)).intValue();
        Integer maxReplicas = null;
        if (config.containsKey("maxReplicas") && config.get("maxReplicas") != null) {
            maxReplicas = ((Number) config.get("maxReplicas")).intValue();
        }
        int recommendedReplicas = ((Number) config.getOrDefault("recommendedReplicas", 3)).intValue();
        Set<Long> dorisClusterIds = support.resolveDorisClusterIds();
        if (dorisClusterIds.isEmpty()) {
            return issues;
        }

        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .isNotNull(DataTable::getClusterId)
            .in(DataTable::getClusterId, dorisClusterIds)
            .isNotNull(DataTable::getReplicaNum);
        support.applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            if (support.isViewTable(table)) {
                continue;
            }
            Integer replicaNum = table.getReplicaNum();
            boolean outOfRange = replicaNum < minReplicas || (maxReplicas != null && replicaNum > maxReplicas);
            if (outOfRange) {
                InspectionIssue issue = support.createIssue(recordId, rule, table);
                issue.setIssueDescription("副本数不在合理范围内");
                issue.setCurrentValue(String.valueOf(replicaNum));
                if (maxReplicas == null) {
                    issue.setExpectedValue(String.format(">= %d (推荐: %d)", minReplicas, recommendedReplicas));
                } else {
                    issue.setExpectedValue(String.format("%d-%d (推荐: %d)", minReplicas, maxReplicas, recommendedReplicas));
                }
                String tableName = support.resolveActualTableName(table.getTableName());
                String sql = (StringUtils.hasText(table.getDbName()) && StringUtils.hasText(tableName))
                    ? String.format("ALTER TABLE `%s`.`%s` SET (\"replication_num\" = \"%d\")",
                        table.getDbName(), tableName, recommendedReplicas)
                    : String.format("ALTER TABLE <db>.<table> SET (\"replication_num\" = \"%d\")",
                        recommendedReplicas);
                issue.setSuggestion(String.format("建议设置副本数为 %d 以保证数据可靠性\n修复脚本: %s",
                    recommendedReplicas, sql));
                support.insertIssue(issue);
                issues.add(issue);
            }
        }

        return issues;
    }
}
