package com.onedata.portal.service.inspection;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.onedata.portal.entity.DataTable;
import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import com.onedata.portal.mapper.DataTableMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;



/**
 * 检查表负责人。
 */
@Component
@RequiredArgsConstructor
public class TableOwnerRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;

    @Override
    public String ruleType() {
        return "table_owner";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();

        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .and(wrapper -> wrapper.isNull(DataTable::getOwner)
                .or().eq(DataTable::getOwner, ""));
        support.applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            InspectionIssue issue = support.createIssue(recordId, rule, table);
            issue.setIssueDescription("表未配置负责人");
            issue.setCurrentValue("null");
            issue.setExpectedValue("有效的负责人");
            issue.setSuggestion("请为表配置负责人,以便问题追踪和权限管理");
            support.insertIssue(issue);
            issues.add(issue);
        }

        return issues;
    }
}
