package com.onedata.portal.service.inspection;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验真实的 15 个规则 handler 集合：注册表可无冲突地构建（重复 ruleType 会在构造期抛错），
 * 且覆盖的 ruleType 与原 {@code InspectionService.executeRule} switch 分支完全一致。
 *
 * <p>handler 的 {@code ruleType()} 返回常量、不触碰注入依赖，故可用 null 依赖实例化，
 * 无需 Spring 上下文与数据库。
 */
class InspectionRuleHandlerCoverageTest {

    /**
     * 巡检规则类型集合。{@code data_freshness} 已从巡检子系统移除——新鲜度改为独立的
     * 事件驱动子系统（工作流完成触发 + 表级契约），不再作为巡检规则参与分发。
     */
    private static final Set<String> EXPECTED_RULE_TYPES = new HashSet<>(Arrays.asList(
            "table_naming", "replica_count", "tablet_count", "tablet_size",
            "table_owner", "table_comment", "task_failure", "task_schedule",
            "table_layer", "data_volume_spike", "service_health",
            "doris_node_resources", "orphan_tables", "deprecated_tables"));

    private static List<InspectionRuleHandler> allHandlers() {
        return Arrays.asList(
                new TableNamingRuleHandler(null, null),
                new ReplicaCountRuleHandler(null, null),
                new TabletCountRuleHandler(null, null, null),
                new TabletSizeRuleHandler(null, null, null),
                new TableOwnerRuleHandler(null, null),
                new TableCommentRuleHandler(null, null),
                new TaskFailureRuleHandler(null, null, null),
                new TaskScheduleRuleHandler(null, null, null),
                new TableLayerRuleHandler(null, null),
                new DataVolumeSpikeRuleHandler(null, null),
                new ServiceHealthRuleHandler(null, null),
                new DorisNodeResourcesRuleHandler(null, null),
                new OrphanTablesRuleHandler(null, null),
                new DeprecatedTablesRuleHandler(null, null));
    }

    @Test
    void registryBuildsFromRealHandlersWithoutDuplicates() {
        // 重复 ruleType 会在 InspectionRuleRegistry 构造期 fail-fast
        InspectionRuleRegistry registry = new InspectionRuleRegistry(allHandlers());
        for (String ruleType : EXPECTED_RULE_TYPES) {
            assertTrue(registry.find(ruleType).isPresent(), "缺少规则 handler: " + ruleType);
        }
    }

    @Test
    void handlersCoverExactlyTheLegacySwitchRuleTypes() {
        Set<String> actual = allHandlers().stream()
                .map(InspectionRuleHandler::ruleType)
                .collect(Collectors.toSet());
        assertEquals(EXPECTED_RULE_TYPES, actual);
        assertEquals(14, allHandlers().size());
    }
}
