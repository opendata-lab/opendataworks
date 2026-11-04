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
 * 检查 Tablet 大小（真实值）。
 * 通过 Doris SHOW TABLETS 获取真实 Tablet DataSize，避免按分区/分桶估算带来的误差。
 */
@Component
@RequiredArgsConstructor
public class TabletSizeRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;
    private final DorisConnectionService dorisConnectionService;

    @Override
    public String ruleType() {
        return "tablet_size";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());

        int minTabletSizeMb = ((Number) config.getOrDefault("minTabletSizeMb", 1024)).intValue(); // 1 GB
        int maxTabletSizeMb = ((Number) config.getOrDefault("maxTabletSizeMb", 10240)).intValue(); // 10 GB
        int targetTabletSizeMb = ((Number) config.getOrDefault("targetTabletSizeMb", 4096)).intValue(); // 4 GB
        int minTableSizeGbForSmallCheck = ((Number) config.getOrDefault("minTableSizeGbForSmallCheck", 20)).intValue();

        long minTabletBytes = minTabletSizeMb * 1024L * 1024;
        long maxTabletBytes = maxTabletSizeMb * 1024L * 1024;
        long targetTabletBytes = targetTabletSizeMb * 1024L * 1024;
        long minTableBytesForSmallCheck = minTableSizeGbForSmallCheck * 1024L * 1024 * 1024;
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
            if (table == null || table.getClusterId() == null || !StringUtils.hasText(table.getDbName())
                || !StringUtils.hasText(table.getTableName())) {
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
            long dataSize = tabletStats.getTotalDataSizeBytes();
            long tabletCount = tabletStats.getTabletCount();
            if (dataSize <= 0 || tabletCount <= 0) {
                continue;
            }

            long avgTabletBytes = tabletStats.getAvgTabletSizeBytes();

            boolean tooLarge = avgTabletBytes > maxTabletBytes;
            boolean tooSmall = avgTabletBytes < minTabletBytes && dataSize >= minTableBytesForSmallCheck;
            if (!tooLarge && !tooSmall) {
                continue;
            }

            InspectionIssue issue = support.createIssue(recordId, rule, table);
            if (tooLarge) {
                issue.setSeverity("high");
                issue.setIssueDescription("平均Tablet大小过大,可能影响 compaction/导入性能");
            } else {
                issue.setSeverity("medium");
                issue.setIssueDescription("平均Tablet大小偏小,可能导致Tablet数量过多");
            }

            issue.setCurrentValue(String.format("%s (真实: tablets=%d, total=%s)", support.formatBytes(avgTabletBytes),
                tabletCount, support.formatBytes(dataSize)));
            issue.setExpectedValue(String.format("%s ~ %s (目标: %s)", support.formatBytes(minTabletBytes),
                support.formatBytes(maxTabletBytes), support.formatBytes(targetTabletBytes)));
            issue.setSuggestion(generateTabletSizeSuggestion(dataSize, tabletCount,
                minTabletBytes, maxTabletBytes, targetTabletBytes, tooLarge, tooSmall));

            support.insertIssue(issue);
            issues.add(issue);
        }

        return issues;
    }

    private String generateTabletSizeSuggestion(long dataSize, long tabletCount,
                                                long minTabletBytes, long maxTabletBytes, long targetTabletBytes,
                                                boolean tooLarge, boolean tooSmall) {
        long targetTabletCount = Math.max(1L, (dataSize + targetTabletBytes - 1) / targetTabletBytes);

        if (tooLarge) {
            long minTabletsToMeetMax = Math.max(1L, (dataSize + maxTabletBytes - 1) / maxTabletBytes);
            return String.format(
                "当前Tablet数量约 %d，建议提升到 %d 以上(目标约 %d)，可通过增加分桶数或优化分区粒度实现",
                tabletCount, minTabletsToMeetMax, targetTabletCount);
        }

        if (tooSmall) {
            long maxTabletsToMeetMin = Math.max(1L, dataSize / minTabletBytes);
            return String.format(
                "当前Tablet数量约 %d，建议收敛到 %d 以下(目标约 %d)，可通过减少分桶数或减少动态分区数量实现",
                tabletCount, maxTabletsToMeetMin, targetTabletCount);
        }

        return "建议联合调整分桶与分区策略，使单Tablet大小落在推荐范围内";
    }
}
