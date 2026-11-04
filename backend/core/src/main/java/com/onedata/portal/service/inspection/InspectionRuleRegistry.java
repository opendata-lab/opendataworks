package com.onedata.portal.service.inspection;

import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;



/**
 * 巡检规则注册表。
 *
 * <p>启动期收集容器内全部 {@link InspectionRuleHandler}，按 {@link InspectionRuleHandler#ruleType()}
 * 建立索引；同一 {@code ruleType} 出现多个实现时直接 fail-fast，避免分发歧义。
 * 运行期由 {@link #dispatch(Long, InspectionRule)} 按规则类型分发，未注册类型记录告警并返回空列表，
 * 复刻原 {@code switch} 默认分支的「Unknown rule type」行为。
 */
@Slf4j
@Component
public class InspectionRuleRegistry {

    private final Map<String, InspectionRuleHandler> handlers;

    public InspectionRuleRegistry(List<InspectionRuleHandler> handlerList) {
        Map<String, InspectionRuleHandler> map = new HashMap<>();
        for (InspectionRuleHandler handler : handlerList) {
            String ruleType = handler.ruleType();
            if (!StringUtils.hasText(ruleType)) {
                throw new IllegalStateException(
                    "InspectionRuleHandler 必须声明非空 ruleType: " + handler.getClass().getName());
            }
            InspectionRuleHandler existing = map.putIfAbsent(ruleType, handler);
            if (existing != null) {
                throw new IllegalStateException(String.format(
                    "重复的巡检规则类型 '%s'：%s 与 %s",
                    ruleType, existing.getClass().getName(), handler.getClass().getName()));
            }
        }
        this.handlers = Collections.unmodifiableMap(map);
        log.info("Registered {} inspection rule handlers: {}", this.handlers.size(), this.handlers.keySet());
    }

    /**
     * 按规则类型查找 handler。
     */
    public Optional<InspectionRuleHandler> find(String ruleType) {
        return Optional.ofNullable(handlers.get(ruleType));
    }

    /**
     * 分发执行单条规则；未知类型告警并返回空列表。
     */
    public List<InspectionIssue> dispatch(Long recordId, InspectionRule rule) {
        InspectionRuleHandler handler = handlers.get(rule.getRuleType());
        if (handler == null) {
            log.warn("Unknown rule type: {}", rule.getRuleType());
            return Collections.emptyList();
        }
        return handler.check(recordId, rule);
    }
}
