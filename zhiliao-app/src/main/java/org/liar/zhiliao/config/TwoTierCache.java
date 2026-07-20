package org.liar.zhiliao.config;

import org.springframework.cache.Cache;
import org.springframework.cache.support.SimpleValueWrapper;

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
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return this;
    }

    @Override
    public ValueWrapper get(Object key) {
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

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
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

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
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

    @Override
    public void put(Object key, Object value) {
        l1.put(key, value);
        l2.put(key, value);
    }

    @Override
    public void evict(Object key) {
        l1.evict(key);
        l2.evict(key);
    }

    @Override
    public void clear() {
        l1.clear();
        l2.clear();
    }
}
