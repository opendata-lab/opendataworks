package com.onedata.portal.service.inspection;

import com.onedata.portal.entity.InspectionIssue;
import com.onedata.portal.entity.InspectionRule;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InspectionRuleRegistryTest {

    private static InspectionRule rule(String ruleType) {
        InspectionRule rule = new InspectionRule();
        rule.setRuleType(ruleType);
        return rule;
    }

    /** 简单桩 handler，记录是否被调用并返回固定问题数。 */
    private static class StubHandler implements InspectionRuleHandler {
        private final String ruleType;
        private final List<InspectionIssue> issues;
        private Long lastRecordId;

        StubHandler(String ruleType, int issueCount) {
            this.ruleType = ruleType;
            this.issues = new java.util.ArrayList<>();
            for (int i = 0; i < issueCount; i++) {
                this.issues.add(new InspectionIssue());
            }
        }

        @Override
        public String ruleType() {
            return ruleType;
        }

        @Override
        public List<InspectionIssue> check(Long recordId, InspectionRule rule) {
            this.lastRecordId = recordId;
            return issues;
        }
    }

    @Test
    void dispatchRoutesToHandlerByRuleType() {
        StubHandler naming = new StubHandler("table_naming", 2);
        StubHandler owner = new StubHandler("table_owner", 1);
        InspectionRuleRegistry registry = new InspectionRuleRegistry(Arrays.asList(naming, owner));

        List<InspectionIssue> result = registry.dispatch(42L, rule("table_owner"));

        assertEquals(1, result.size());
        assertEquals(42L, owner.lastRecordId);
        assertTrue(registry.find("table_naming").isPresent());
        assertSame(owner, registry.find("table_owner").orElse(null));
    }

    @Test
    void dispatchUnknownRuleTypeReturnsEmptyAndDoesNotThrow() {
        InspectionRuleRegistry registry = new InspectionRuleRegistry(
                Collections.singletonList(new StubHandler("table_naming", 3)));

        List<InspectionIssue> result = registry.dispatch(7L, rule("does_not_exist"));

        assertTrue(result.isEmpty());
        assertFalse(registry.find("does_not_exist").isPresent());
    }

    @Test
    void duplicateRuleTypeFailsFast() {
        StubHandler first = new StubHandler("replica_count", 0);
        StubHandler second = new StubHandler("replica_count", 0);

        assertThrows(IllegalStateException.class,
                () -> new InspectionRuleRegistry(Arrays.asList(first, second)));
    }

    @Test
    void blankRuleTypeFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> new InspectionRuleRegistry(Collections.singletonList(new StubHandler("  ", 0))));
    }
}
