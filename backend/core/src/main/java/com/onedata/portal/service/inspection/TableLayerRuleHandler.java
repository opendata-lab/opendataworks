package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



/**
 * 检查数据层级。
 */
@Component
@RequiredArgsConstructor
public class TableLayerRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;

    @Override
    public String ruleType() {
        return "table_layer";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());
        @SuppressWarnings("unchecked")
        List<String> validLayers = (List<String>) config.getOrDefault("validLayers",
            Arrays.asList("ODS", "DWD", "DIM", "DWS", "ADS"));

        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .and(wrapper -> wrapper.isNull(DataTable::getLayer)
                .or().eq(DataTable::getLayer, ""));
        support.applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            InspectionIssue issue = support.createIssue(recordId, rule, table);
            issue.setIssueDescription("表未配置数据层级");
            issue.setCurrentValue("null");
            issue.setExpectedValue(String.join(", ", validLayers));
            issue.setSuggestion("请为表配置正确的数据层级(ODS/DWD/DIM/DWS/ADS)");
            support.insertIssue(issue);
            issues.add(issue);
        }

        return issues;
    }
}
