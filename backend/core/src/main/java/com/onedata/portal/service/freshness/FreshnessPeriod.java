package com.onedata.portal.service.freshness;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;


/**
 * 新鲜度阈值时间单位，对齐 dbt 的 {@code TimePeriod}（minute | hour | day）。
 */
public enum FreshnessPeriod {

    MINUTE {
        @Override
        public Duration toDuration(int count) {
            return Duration.ofMinutes(count);
        }
    },
    HOUR {
        @Override
        public Duration toDuration(int count) {
            return Duration.ofHours(count);
        }
    },
    DAY {
        @Override
        public Duration toDuration(int count) {
            return Duration.ofDays(count);
        }
    };

    public abstract Duration toDuration(int count);

    /**
     * 解析单位字符串，大小写不敏感，接受单复数（minute/minutes）。
     */
    public static Optional<FreshnessPeriod> parse(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("s")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        switch (normalized) {
            case "minute":
                return Optional.of(MINUTE);
            case "hour":
                return Optional.of(HOUR);
            case "day":
                return Optional.of(DAY);
            default:
                return Optional.empty();
        }
    }

    /**
     * 存库/接口使用的小写标识。
     */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
