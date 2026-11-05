package com.onedata.portal.service.lineage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;


/**
 * 血缘一致性校验的上线开关。
 *
 * <p>默认 {@code warn}，只提示不阻断，保证存量工作流的发布能力不受影响。
 * 用只读接口扫描并修复存量后，再切换到 {@code block-missing}。
 */
@Data
@Component
@ConfigurationProperties(prefix = "workflow.lineage-consistency")
public class LineageConsistencyProperties {

    /**
     * 仅提示，不阻断任何发布。
     */
    public static final String MODE_WARN = "warn";

    /**
     * 仅阻断 SQL 已明确匹配但关系表缺失的情况。
     */
    public static final String MODE_BLOCK_MISSING = "block-missing";

    private String enforcementMode = MODE_WARN;

    /**
     * 是否阻断 SQL 高可信缺失。多余关系、unmatched、ambiguous 在任何模式下都只告警。
     */
    public boolean isBlockMissing() {
        return MODE_BLOCK_MISSING.equalsIgnoreCase(enforcementMode == null ? null : enforcementMode.trim());
    }
}
