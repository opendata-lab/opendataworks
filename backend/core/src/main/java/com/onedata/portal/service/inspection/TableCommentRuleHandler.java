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
 * 检查表注释。
 */
@Component
@RequiredArgsConstructor
public class TableCommentRuleHandler implements InspectionRuleHandler {

    private final InspectionSupport support;
    private final DataTableMapper dataTableMapper;

    @Override
    public String ruleType() {
        return "table_comment";
    }

    @Override
    public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
        List<InspectionIssue> issues = new ArrayList<>();

        Map<String, Object> config = support.parseRuleConfig(rule.getRuleConfig());
        LambdaQueryWrapper<DataTable> tableWrapper = new LambdaQueryWrapper<DataTable>()
            .eq(DataTable::getStatus, "active")
            .and(wrapper -> wrapper.isNull(DataTable::getTableComment)
                .or().eq(DataTable::getTableComment, ""));
        support.applyTableScope(tableWrapper, config);
        List<DataTable> tables = dataTableMapper.selectList(tableWrapper);

        for (DataTable table : tables) {
            InspectionIssue issue = support.createIssue(recordId, rule, table);
            issue.setIssueDescription("表缺少注释说明");
            issue.setCurrentValue("null");
            issue.setExpectedValue("有意义的注释");
            issue.setSuggestion("请为表添加注释,说明表的用途和业务含义");
            support.insertIssue(issue);
            issues.add(issue);
        }

        return issues;
    }
}
