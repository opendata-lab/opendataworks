package com.onedata.portal.service.freshness;

import java.time.Duration;


/**
 * 新鲜度阈值：数量 + 单位。保留 count/period 表示以便接口回显与存库，
 * 检查时经 {@link #toDuration()} 换算为时长。
 */
public final class FreshnessThreshold {

    private final int count;
    private final FreshnessPeriod period;

    public FreshnessThreshold(int count, FreshnessPeriod period) {
        if (period == null) {
            throw new IllegalArgumentException("period 不能为空");
        }
        this.count = count;
        this.period = period;
    }

    public int getCount() {
        return count;
    }

    public FreshnessPeriod getPeriod() {
        return period;
    }

    public Duration toDuration() {
        return period.toDuration(count);
    }

    public long toSeconds() {
        return toDuration().getSeconds();
    }

    @Override
    public String toString() {
        return count + " " + period.code();
    }
}
