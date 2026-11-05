package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import com.onedata.portal.service.DorisConnectionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;



/**
 * 检查 Tablet 数量。
 */
@Component
@RequiredArgsConstructor
public class TabletCountRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;
    private final DorisConnectionService dorisConnectionService;

    @Override
    public String ruleType() {
        return "tablet_count";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());
        int maxTablets = ((Number) config.getOrDefault("maxTablets", 200)).intValue();
        int warningTablets = ((Number) config.getOrDefault("warningTablets", 100)).intValue();
        Set<Long> dorisClusterIds = support.resolveDorisClusterIds();
        if (dorisClusterIds.isEmpty()) {
            return issues;
        }

        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .isNotNull(DataTable::getClusterId)
            .in(DataTable::getClusterId, dorisClusterIds)
            .isNotNull(DataTable::getDbName)
            .isNotNull(DataTable::getTableName);
        support.applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            if (support.isViewTable(table)) {
                continue;
            }
            String actualTableName = support.resolveActualTableName(table.getTableName());
            if (!StringUtils.hasText(actualTableName)) {
                continue;
            }

            Optional<DorisConnectionService.TableTabletStats> tabletStatsOptional =
                dorisConnectionService.getTableTabletStats(table.getClusterId(), table.getDbName(), actualTableName);
            if (!tabletStatsOptional.isPresent()) {
                continue;
            }
            DorisConnectionService.TableTabletStats tabletStats = tabletStatsOptional.get();
            long tabletCount = tabletStats.getTabletCount();
            if (tabletCount <= 0) {
                continue;
            }
            long totalDataSize = tabletStats.getTotalDataSizeBytes();
            long avgTabletSize = tabletStats.getAvgTabletSizeBytes();

            if (tabletCount > maxTablets) {
                InspectionIssue issue = support.createIssue(recordId, rule, table);
                issue.setSeverity("high");
                issue.setIssueDescription("Tablet数量过多,可能影响性能");
                issue.setCurrentValue(String.valueOf(tabletCount));
                issue.setExpectedValue("<= " + maxTablets);
                issue.setSuggestion(String.format(
                    "当前总数据量 %s，平均Tablet大小 %s。建议优先调整分桶数和分区策略，使Tablet数量降到 %d 以下",
                    support.formatBytes(totalDataSize), support.formatBytes(avgTabletSize), maxTablets));

                support.insertIssue(issue);
                issues.add(issue);
            } else if (tabletCount > warningTablets) {
                InspectionIssue issue = support.createIssue(recordId, rule, table);
                issue.setSeverity("medium");
                issue.setIssueDescription("Tablet数量较多,需要关注");
                issue.setCurrentValue(String.valueOf(tabletCount));
                issue.setExpectedValue("<= " + warningTablets + " (推荐)");
                issue.setSuggestion(String.format(
                    "当前总数据量 %s，平均Tablet大小 %s。建议关注分桶与分区增长趋势，必要时提前调整",
                    support.formatBytes(totalDataSize), support.formatBytes(avgTabletSize)));
                support.insertIssue(issue);
                issues.add(issue);
            }
        }

        return issues;
    }
}
