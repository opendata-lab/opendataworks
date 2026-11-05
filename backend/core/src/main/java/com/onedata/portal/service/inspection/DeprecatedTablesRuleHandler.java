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
 * 检查废弃表 - 状态为 deprecated 且没有依赖的表。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeprecatedTablesRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;

    @Override
    public String ruleType() {
        return "deprecated_tables";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());

        // 废弃表的最短存在时间(天),超过这个时间建议删除
        int deprecatedThresholdDays = ((Number) config.getOrDefault("deprecatedThresholdDays", 90)).intValue();
        // 是否检查有下游依赖的废弃表
        boolean checkWithDownstream = (boolean) config.getOrDefault("checkWithDownstream", false);

        LocalDateTime deprecatedThreshold = LocalDateTime.now().minusDays(deprecatedThresholdDays);

        // 查询废弃状态的表
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "deprecated");
        support.applyTableScope(tableWrapper, config);
        List<DataTable> deprecatedTables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : deprecatedTables) {
            // 检查血缘关系
            boolean hasUpstream = support.hasUpstreamLineage(table.getId());
            boolean hasDownstream = support.hasDownstreamLineage(table.getId());

            // 如果不检查有下游的表,且该表有下游,则跳过
            if (!checkWithDownstream && hasDownstream) {
                continue;
            }

            // 计算废弃时长
            long deprecatedDays = 0;
            if (table.getUpdatedAt() != null) {
                deprecatedDays = Duration.between(table.getUpdatedAt(), LocalDateTime.now()).toDays();
            }

            InspectionIssue issue = support.createIssue(recordId, rule, table);

            if (hasDownstream) {
                // 有下游依赖的废弃表,严重程度更高
                issue.setSeverity("high");
                issue.setIssueDescription(String.format("废弃表仍有下游依赖,已废弃 %d 天", deprecatedDays));
                issue.setCurrentValue(String.format("状态: deprecated, 有下游依赖"));
                issue.setExpectedValue("清理下游依赖后删除");
                issue.setSuggestion("紧急处理:\n1. 检查下游表和任务的使用情况\n2. 迁移下游依赖到其他表\n3. 确认下游已停止使用后删除该表\n4. 或者恢复该表的 active 状态");
            } else if (!hasUpstream && !hasDownstream) {
                // 没有任何依赖的废弃表
                if (deprecatedDays >= deprecatedThresholdDays) {
                    issue.setSeverity("medium");
                    issue.setIssueDescription(String.format("废弃表无依赖关系,已废弃 %d 天,建议删除", deprecatedDays));
                    issue.setCurrentValue(String.format("状态: deprecated, 无依赖, 数据量: %s",
                        table.getRowCount() != null ? String.format("%,d 行", table.getRowCount()) : "未知"));
                    issue.setExpectedValue("已删除");
                    issue.setSuggestion(generateDeprecatedTableSuggestion(table, deprecatedDays));
                } else {
                    issue.setSeverity("low");
                    issue.setIssueDescription(String.format("废弃表无依赖关系,已废弃 %d 天", deprecatedDays));
                    issue.setCurrentValue(String.format("状态: deprecated, 无依赖"));
                    issue.setExpectedValue(String.format("废弃超过 %d 天后删除", deprecatedThresholdDays));
                    issue.setSuggestion(String.format("建议:\n1. 废弃时间未超过 %d 天,暂时保留\n2. 确认表确实不再使用\n3. 如需恢复,可修改状态为 active", deprecatedThresholdDays));
                }
            } else {
                // 只有上游依赖的废弃表
                issue.setSeverity("medium");
                issue.setIssueDescription(String.format("废弃表仅有上游依赖,已废弃 %d 天", deprecatedDays));
                issue.setCurrentValue("状态: deprecated, 有上游无下游");
                issue.setExpectedValue("评估后删除");
                issue.setSuggestion("建议:\n1. 确认上游写入已停止\n2. 检查是否还需要保留历史数据\n3. 考虑归档后删除\n4. 或者将数据迁移到其他表");
            }

            support.insertIssue(issue);
            issues.add(issue);
        }

        log.info("Found {} deprecated tables need attention", issues.size());
        return issues;
    }

    /**
     * 生成废弃表的修复建议
     */
    private String generateDeprecatedTableSuggestion(DataTable table, long deprecatedDays) {
        StringBuilder suggestion = new StringBuilder();

        suggestion.append(String.format("该表已废弃 %d 天,建议执行以下操作:\n", deprecatedDays));
        suggestion.append("1. 确认数据是否需要归档备份\n");

        if (table.getRowCount() != null && table.getRowCount() > 0) {
            suggestion.append(String.format("2. 该表有 %,d 行数据,如需保留请先备份\n", table.getRowCount()));
            suggestion.append("3. 可以先清空数据,观察是否有影响\n");
            suggestion.append("4. 确认无影响后删除表结构\n");
        } else {
            suggestion.append("2. 该表数据量很少或为空,可以直接删除\n");
        }

        if (table.getStorageSize() != null && table.getStorageSize() > 0) {
            suggestion.append(String.format("5. 删除可释放约 %s 存储空间\n",
                support.formatBytes(table.getStorageSize())));
        }

        suggestion.append("6. 执行删除命令: DROP TABLE IF EXISTS `" + table.getTableName() + "`");

        return suggestion.toString();
    }
}
