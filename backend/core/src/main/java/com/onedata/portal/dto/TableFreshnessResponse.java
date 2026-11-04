package com.onedata.portal.dto;

import com.onedata.portal.entity.TableFreshnessConfig;
import com.onedata.portal.entity.TableFreshnessResult;
import java.util.Map;
import lombok.Data;



/**
 * 表级新鲜度契约查询响应：存储的表级配置（供编辑）+ 生效契约（供展示，含字段来源）+ 最近一次结果。
 */
@Data
public class TableFreshnessResponse {

    private Long tableId;

    /** 是否配置了表级契约。 */
    private boolean configured;

    /** 表级契约原样（未配置时为 null），供编辑表单回填。 */
    private TableFreshnessConfig config;

    /** 生效契约（逐字段合并后），无可用契约时为 null。 */
    private EffectiveContract effective;

    /** 最近一次检查结果，无历史时为 null。 */
    private TableFreshnessResult latestResult;

    @Data
    public static class EffectiveContract {
        private String mode;
        private String loadedAtField;
        private String loadedAtQuery;
        private String partitionFormat;
        private String filterExpr;
        private Threshold warnAfter;
        private Threshold errorAfter;
        /** 字段 → 来源（table / rule_default）。 */
        private Map<String, String> fieldSources;
    }

    @Data
    public static class Threshold {
        private Integer count;
        private String period;

        public Threshold() {
        }

        public Threshold(Integer count, String period) {
            this.count = count;
            this.period = period;
        }
    }
}
