package com.onedata.portal.service.freshness;

import java.util.Locale;
import java.util.Optional;


/**
 * 新鲜度取值模式。由用户显式选择，没有自动阶梯、没有自动兜底。
 *
 * <ul>
 *   <li>{@code COLUMN} —— {@code MAX(loadedAtField)}，等价 dbt {@code loaded_at_field}</li>
 *   <li>{@code CUSTOM_SQL} —— 自定义查询返回时间戳，等价 dbt 1.10 {@code loaded_at_query}</li>
 *   <li>{@code PARTITION} —— 解析最新分区值为业务日期</li>
 *   <li>{@code METADATA} —— {@code information_schema.tables.UPDATE_TIME}，只能发现「没人写了」，
 *       发现不了「写入了旧数据」</li>
 * </ul>
 */
public enum FreshnessMode {

    COLUMN,
    CUSTOM_SQL,
    PARTITION,
    METADATA;

    public static Optional<FreshnessMode> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (FreshnessMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
