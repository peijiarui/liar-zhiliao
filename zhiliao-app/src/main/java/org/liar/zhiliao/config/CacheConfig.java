package org.liar.zhiliao.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * 两级缓存配置。
 *
 * <p>架构：</p>
 * <pre>
 * 请求 → L1 Caffeine（JVM 堆内，<0.01ms，1000 条，10min TTL）
 *          → 未命中 → L2 Redis（分布式，~1ms，1h/24h TTL）
 *                        → 未命中 → 实际调用（LLM/检索）
 * </pre>
 *
 * <p>缓存区：</p>
 * <ul>
 *   <li>{@code qa_answer} — 热点问答缓存，L1 10min / L2 1h，MD5(query) → 完整答案</li>
 *   <li>{@code retrieval_result} — 检索结果缓存，L1 10min / L2 24h，MD5(query) → Top chunk 列表</li>
 * </ul>
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** L1 Caffeine 缓存管理器 */
    @Bean
    public CacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager("qa_answer", "retrieval_result");
        manager.setCaffeine(Caffeine.newBuilder()

                .maximumSize(1000)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats());
        return manager;
    }

    /** L2 Redis 缓存管理器 */
    @Bean
    public CacheManager redisCacheManager(RedisConnectionFactory factory) {
        var stringKey = RedisSerializationContext.SerializationPair
                .fromSerializer(new StringRedisSerializer());
        var jsonValue = RedisSerializationContext.SerializationPair
                .fromSerializer(new GenericJackson2JsonRedisSerializer());

        return RedisCacheManager.builder(factory)
                .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                        .serializeKeysWith(stringKey)
                        .serializeValuesWith(jsonValue)
                        .disableCachingNullValues())
                // 热点问题的答案缓存1小时
                .withCacheConfiguration("qa_answer",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(stringKey)
                                .serializeValuesWith(jsonValue)
                                .entryTtl(Duration.ofHours(1))
                                .prefixCacheNameWith("cache:qa:")
                                .disableCachingNullValues())
                // 检索结果缓存24小时
                .withCacheConfiguration("retrieval_result",
                        RedisCacheConfiguration.defaultCacheConfig()
                                .serializeKeysWith(stringKey)
                                .serializeValuesWith(jsonValue)
                                .entryTtl(Duration.ofHours(24))
                                .prefixCacheNameWith("cache:retrieval:")
                                .disableCachingNullValues())
                .build();
    }

    /** 两级缓存管理器：@Cacheable 默认走此 Bean，实现 L1 → L2 的自动回填 */
    @Primary
    @Bean
    public CacheManager twoTierCacheManager(
            CacheManager caffeineCacheManager,
            CacheManager redisCacheManager) {
        return new TwoTierCacheManager(caffeineCacheManager, redisCacheManager);
    }
}
