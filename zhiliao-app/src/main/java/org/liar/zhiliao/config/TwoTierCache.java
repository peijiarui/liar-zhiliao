package org.liar.zhiliao.config;

import org.jspecify.annotations.NonNull;
import org.springframework.cache.Cache;

import java.util.concurrent.Callable;

/**
 * 两级缓存包装：L1 Caffeine（JVM 堆内）→ L2 Redis（分布式）。
 *
 * <p>读：先查 L1，命中直接返回；未命中查 L2，命中后回填 L1。</p>
 * <p>写：同时写入 L1 和 L2。</p>
 * <p>淘汰：同时淘汰 L1 和 L2。</p>
 */
public class TwoTierCache implements Cache {

    private final String name;
    private final Cache l1;
    private final Cache l2;

    public TwoTierCache(String name, Cache l1, Cache l2) {
        this.name = name;
        this.l1 = l1;
        this.l2 = l2;
    }

    @Override
    public @NonNull String getName() {
        return name;
    }

    @Override
    public @NonNull Object getNativeCache() {
        return this;
    }

    @Override
    public ValueWrapper get(@NonNull Object key) {
        // L1 快速命中
        ValueWrapper wrapper = l1.get(key);
        if (wrapper != null) {
            return wrapper;
        }
        // L2 查询 + 回填 L1
        wrapper = l2.get(key);
        if (wrapper != null) {
            l1.put(key, wrapper.get());
        }
        return wrapper;
    }

    /**
     * 获取缓存项。
     * <p>如果缓存项不存在，则从 L2 获取并缓存。</p>
     */
    @Override
    public <T> T get(@NonNull Object key, Class<T> type) {
        T value = l1.get(key, type);
        if (value != null) {
            return value;
        }
        value = l2.get(key, type);
        if (value != null) {
            l1.put(key, value);
        }
        return value;
    }

    /**
     * 获取缓存项。
     * <p>如果缓存项不存在，则调用 valueLoader 获取并缓存。</p>
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(@NonNull Object key, @NonNull Callable<T> valueLoader) {
        ValueWrapper wrapper = l1.get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }
        // 从 valueLoader 获取（实际调用 @Cacheable 方法体）
        T value = l2.get(key, valueLoader);
        if (value != null) {
            l1.put(key, value);
        }
        return value;
    }

    /**
     * 写缓存项。
     */
    @Override
    public void put(@NonNull Object key, Object value) {
        l1.put(key, value);
        l2.put(key, value);
    }

    /**
     * 删除缓存项。
     */
    @Override
    public void evict(@NonNull Object key) {
        l1.evict(key);
        l2.evict(key);
    }

    /**
     * 清空缓存。
     */
    @Override
    public void clear() {
        l1.clear();
        l2.clear();
    }
}
