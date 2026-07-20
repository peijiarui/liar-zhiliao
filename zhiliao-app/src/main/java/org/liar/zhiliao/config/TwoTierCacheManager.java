package org.liar.zhiliao.config;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 两级缓存管理器：L1 Caffeine → L2 Redis。
 * 代理两个底层 CacheManager，为每个缓存区创建 {@link TwoTierCache} 实例。
 */
public class TwoTierCacheManager implements CacheManager {

    private final CacheManager l1;
    private final CacheManager l2;
    private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<>();

    public TwoTierCacheManager(CacheManager l1, CacheManager l2) {
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public Cache getCache(String name) {
        return caches.computeIfAbsent(name, n -> {
            Cache caffeine = l1.getCache(n);
            Cache redis = l2.getCache(n);
            if (caffeine == null || redis == null) {
                return null;
            }
            return new TwoTierCache(n, caffeine, redis);
        });
    }

    @Override
    public Collection<String> getCacheNames() {
        return l1.getCacheNames();
    }
}
