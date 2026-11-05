package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



/**
 * 检查数据量暴增/暴降 - 通过对比历史数据。
 */
@Component
@RequiredArgsConstructor
public class DataVolumeSpikeRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;

    @Override
    public String ruleType() {
        return "data_volume_spike";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());

        // 暴增阈值,默认增长超过2倍视为异常
        double increaseThreshold = ((Number) config.getOrDefault("increaseThreshold", 2.0)).doubleValue();
        // 暴降阈值,默认降低到50%以下视为异常
        double decreaseThreshold = ((Number) config.getOrDefault("decreaseThreshold", 0.5)).doubleValue();
        // 对比的历史天数,默认对比7天前的数据
        int compareDays = ((Number) config.getOrDefault("compareDays", 7)).intValue();
        // 最小行数阈值,小于此值的表不检查(避免小表波动)
        long minRowThreshold = ((Number) config.getOrDefault("minRowThreshold", 1000)).longValue();

        LocalDateTime compareTime = LocalDateTime.now().minusDays(compareDays);

        // 查询有数据量记录的表
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .isNotNull(DataTable::getRowCount)
            .gt(DataTable::getRowCount, minRowThreshold); // 只检查数据量超过阈值的表
        support.applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            Long currentRowCount = table.getRowCount();
            if (currentRowCount == null || currentRowCount < minRowThreshold) {
                continue;
            }

            // 获取历史数据量
            // 注意:这需要有表统计历史记录,如果没有历史数据则跳过
            // 这里假设有 TableStatisticsHistory 表和相应的 Mapper
            // 由于当前可能没有实现,我们使用一个简化的逻辑

            // TODO: 查询 table_statistics_history 表获取历史数据
            // 临时实现:检查数据量是否异常大或异常小
            if (currentRowCount > 100_000_000) {
                // 数据量超过1亿,给出告警
                InspectionIssue issue = support.createIssue(recordId, rule, table);
                issue.setSeverity("medium");
                issue.setIssueDescription("表数据量异常大,可能存在数据堆积");
                issue.setCurrentValue(String.format("%,d 行", currentRowCount));
                issue.setExpectedValue("正常数据量范围");
                issue.setSuggestion("请检查:\n1. 是否存在数据重复写入\n2. 是否需要启用数据归档\n3. 是否需要调整分区策略\n4. 考虑数据生命周期管理");
                support.insertIssue(issue);
                issues.add(issue);
            } else if (currentRowCount < 100 && table.getDorisUpdateTime() != null &&
                       table.getDorisUpdateTime().isBefore(LocalDateTime.now().minusDays(1))) {
                // 数据量很小且长时间未更新
                InspectionIssue issue = support.createIssue(recordId, rule, table);
                issue.setSeverity("low");
                issue.setIssueDescription("表数据量异常少");
                issue.setCurrentValue(String.format("%,d 行", currentRowCount));
                issue.setExpectedValue("正常数据量");
                issue.setSuggestion("请检查:\n1. 数据是否正常写入\n2. 是否存在数据丢失\n3. 上游数据源是否正常");
                support.insertIssue(issue);
                issues.add(issue);
            }
        }

        return issues;
    }
}
