package com.onedata.portal.service.inspection;

import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import java.util.List;



/**
 * 巡检规则策略接口。
 *
 * <p>每个实现对应一种 {@code rule_type}，由 {@link InspectionRuleRegistry} 按 {@link #ruleType()}
 * 注册并分发。实现内部完成检查、问题构造与入库（沿用「检查即入库并返回」的既有副作用契约），
 * 返回本次产生的问题列表，仅用于统计计数。
 */
public interface InspectionRuleHandler {

    /**
     * 该 handler 处理的规则类型（与 {@link InspectionRule#getRuleType()} 对应）。
     */
    String ruleType();

    /**
     * 执行单条规则检查。
     *
     * @param recordId 当前巡检记录 ID
     * @param rule     规则定义
     * @return 本次产生的问题列表（已入库）
     */
    List<InspectionIssue> check(Long recordId, InspectionRule rule);
}
