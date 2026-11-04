package com.onedata.portal.service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;



/**
 * 访问统计的短时进程内缓存。
 */
@Component
public class TableAccessSummaryCache {

    private static final long TTL_MILLIS = 60_000L;

    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T getOrLoad(String key, Supplier<T> loader) {
        long now = System.currentTimeMillis();
        CacheEntry cached = entries.get(key);
        if (cached != null && cached.expiresAt >= now) {
            return (T) cached.value;
        }
        T value = loader.get();
        entries.put(key, new CacheEntry(value, now + TTL_MILLIS));
        return value;
    }

    public void evictCluster(Long clusterId) {
        String token = clusterId == null ? "all" : String.valueOf(clusterId);
        entries.keySet().removeIf(key -> key.startsWith("cluster:" + token + ":") || key.startsWith("cluster:all:"));
    }

    public void clear() {
        entries.clear();
    }

    private static class CacheEntry {
        private final Object value;
        private final long expiresAt;

        private CacheEntry(Object value, long expiresAt) {
            this.value = value;
            this.expiresAt = expiresAt;
        }
    }
}
