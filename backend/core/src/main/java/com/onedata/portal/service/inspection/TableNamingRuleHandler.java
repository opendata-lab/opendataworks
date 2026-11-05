package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



/**
 * 检查表命名规范。
 */
@Component
@RequiredArgsConstructor
public class TableNamingRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;

    @Override
    public String ruleType() {
        return "table_naming";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();
        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());
        String patternStr = (String) config.getOrDefault("pattern", "^(ods|dwd|dim|dws|ads)_[a-z][a-z0-9_]*$");
        String errorMessage = (String) config.getOrDefault("errorMessage", "表名不符合命名规范");
        Pattern pattern = Pattern.compile(patternStr);

        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active");
        support.applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            if (!pattern.matcher(table.getTableName()).matches()) {
                InspectionIssue issue = support.createIssue(recordId, rule, table);
                issue.setIssueDescription(errorMessage);
                issue.setCurrentValue(table.getTableName());
                issue.setExpectedValue("符合正则: " + patternStr);
                issue.setSuggestion("请修改表名使其符合命名规范,格式: {layer}_xxx_xxx");
                support.insertIssue(issue);
                issues.add(issue);
            }
        }

        return issues;
    }
}
