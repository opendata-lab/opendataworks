package com.onedata.portal.service.freshness;

import java.util.Collections;
import java.util.Map;


/**
 * 生效的新鲜度契约，由 {@link FreshnessContractResolver} 逐字段合并得到。
 *
 * <p>不可变。除取值/阈值字段外，携带每个字段的来源标记（{@link FreshnessSource}），
 * 供接口回显。使用 {@link Builder} 的「首个非空值胜出」语义实现逐字段合并。
 */
public final class FreshnessContract {

    /** 字段来源标记的 key。 */
    public static final String F_MODE = "mode";
    public static final String F_LOADED_AT_FIELD = "loadedAtField";
    public static final String F_LOADED_AT_QUERY = "loadedAtQuery";
    public static final String F_PARTITION_FORMAT = "partitionFormat";
    public static final String F_FILTER = "filterExpr";
    public static final String F_WARN_AFTER = "warnAfter";
    public static final String F_ERROR_AFTER = "errorAfter";

    private final FreshnessMode mode;
    private final String loadedAtField;
    private final String loadedAtQuery;
    private final String partitionFormat;
    private final String filterExpr;
    private final FreshnessThreshold warnAfter;
    private final FreshnessThreshold errorAfter;
    private final Map<String, FreshnessSource> fieldSources;

    private FreshnessContract(Builder b) {
        this.mode = b.mode;
        this.loadedAtField = b.loadedAtField;
        this.loadedAtQuery = b.loadedAtQuery;
        this.partitionFormat = b.partitionFormat;
        this.filterExpr = b.filterExpr;
        this.warnAfter = b.warnAfter;
        this.errorAfter = b.errorAfter;
        this.fieldSources = Collections.unmodifiableMap(b.sources);
    }

    public FreshnessMode getMode() {
        return mode;
    }

    public String getLoadedAtField() {
        return loadedAtField;
    }

    public String getLoadedAtQuery() {
        return loadedAtQuery;
    }

    public String getPartitionFormat() {
        return partitionFormat;
    }

    public String getFilterExpr() {
        return filterExpr;
    }

    public FreshnessThreshold getWarnAfter() {
        return warnAfter;
    }

    public FreshnessThreshold getErrorAfter() {
        return errorAfter;
    }

    public Map<String, FreshnessSource> getFieldSources() {
        return fieldSources;
    }

    public FreshnessSource sourceOf(String field) {
        return fieldSources.get(field);
    }

    /**
     * 契约是否可用于检查：模式已知且至少有一档阈值。
     */
    public boolean isCheckable() {
        return mode != null && (warnAfter != null || errorAfter != null);
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 逐字段合并构造器。每个 setter 采用「首个非空值胜出」语义：
     * 已被赋值的字段不再被后续来源覆盖，null 值视为未提供。
     */
    public static final class Builder {

        private FreshnessMode mode;
        private String loadedAtField;
        private String loadedAtQuery;
        private String partitionFormat;
        private String filterExpr;
        private FreshnessThreshold warnAfter;
        private FreshnessThreshold errorAfter;
        private final Map<String, FreshnessSource> sources = new java.util.LinkedHashMap<>();

        public Builder mode(FreshnessMode value, FreshnessSource source) {
            if (mode == null && value != null) {
                mode = value;
                sources.put(F_MODE, source);
            }
            return this;
        }

        public Builder loadedAtField(String value, FreshnessSource source) {
            if (loadedAtField == null && value != null) {
                loadedAtField = value;
                sources.put(F_LOADED_AT_FIELD, source);
            }
            return this;
        }

        public Builder loadedAtQuery(String value, FreshnessSource source) {
            if (loadedAtQuery == null && value != null) {
                loadedAtQuery = value;
                sources.put(F_LOADED_AT_QUERY, source);
            }
            return this;
        }

        public Builder partitionFormat(String value, FreshnessSource source) {
            if (partitionFormat == null && value != null) {
                partitionFormat = value;
                sources.put(F_PARTITION_FORMAT, source);
            }
            return this;
        }

        public Builder filterExpr(String value, FreshnessSource source) {
            if (filterExpr == null && value != null) {
                filterExpr = value;
                sources.put(F_FILTER, source);
            }
            return this;
        }

        public Builder warnAfter(FreshnessThreshold value, FreshnessSource source) {
            if (warnAfter == null && value != null) {
                warnAfter = value;
                sources.put(F_WARN_AFTER, source);
            }
            return this;
        }

        public Builder errorAfter(FreshnessThreshold value, FreshnessSource source) {
            if (errorAfter == null && value != null) {
                errorAfter = value;
                sources.put(F_ERROR_AFTER, source);
            }
            return this;
        }

        public FreshnessContract build() {
            return new FreshnessContract(this);
        }
    }
}
