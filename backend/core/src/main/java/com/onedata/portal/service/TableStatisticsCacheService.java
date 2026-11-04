package com.onedata.portal.service;

import com.onedata.portal.dto.TableStatistics;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;



/**
 * 表统计信息缓存服务
 * 使用内存缓存减少对 Doris 的频繁查询
 */
@Slf4j
@Service
public class TableStatisticsCacheService {

    /**
     * 缓存过期时间（分钟）
     */
    private static final int CACHE_EXPIRE_MINUTES = 5;

    /**
     * 缓存存储: key = tableId + "_" + clusterId, value = CacheEntry
     */
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    /**
     * 获取缓存的统计信息
     */
    public TableStatistics get(Long tableId, Long clusterId) {
        String key = buildKey(tableId, clusterId);
        CacheEntry entry = cache.get(key);

        if (entry == null) {
            log.debug("Cache miss for table {} cluster {}", tableId, clusterId);
            return null;
        }

        // 检查是否过期
        if (entry.isExpired()) {
            log.debug("Cache expired for table {} cluster {}", tableId, clusterId);
            cache.remove(key);
            return null;
        }

        log.debug("Cache hit for table {} cluster {}", tableId, clusterId);
        return entry.getStatistics();
    }

    /**
     * 放入缓存
     */
    public void put(Long tableId, Long clusterId, TableStatistics statistics) {
        String key = buildKey(tableId, clusterId);
        CacheEntry entry = new CacheEntry(statistics, LocalDateTime.now().plusMinutes(CACHE_EXPIRE_MINUTES));
        cache.put(key, entry);
        log.debug("Cached statistics for table {} cluster {}", tableId, clusterId);
    }

    /**
     * 移除缓存
     */
    public void remove(Long tableId, Long clusterId) {
        String key = buildKey(tableId, clusterId);
        cache.remove(key);
        log.debug("Removed cache for table {} cluster {}", tableId, clusterId);
    }

    /**
     * 清空指定表的所有缓存
     */
    public void removeAll(Long tableId) {
        cache.keySet().removeIf(key -> key.startsWith(tableId + "_"));
        log.debug("Removed all cache for table {}", tableId);
    }

    /**
     * 清空所有缓存
     */
    public void clear() {
        cache.clear();
        log.info("Cleared all statistics cache");
    }

    /**
     * 获取缓存大小
     */
    public int size() {
        // 清理过期的缓存
        cleanupExpired();
        return cache.size();
    }

    /**
     * 清理过期的缓存
     */
    public void cleanupExpired() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }

    /**
     * 构建缓存 key
     */
    private String buildKey(Long tableId, Long clusterId) {
        return tableId + "_" + (clusterId != null ? clusterId : "default");
    }

    /**
     * 缓存条目
     */
    private static class CacheEntry {
        private final TableStatistics statistics;
        private final LocalDateTime expireTime;

        public CacheEntry(TableStatistics statistics, LocalDateTime expireTime) {
            this.statistics = statistics;
            this.expireTime = expireTime;
        }

        public TableStatistics getStatistics() {
            return statistics;
        }

        public boolean isExpired() {
            return LocalDateTime.now().isAfter(expireTime);
        }
    }
}
