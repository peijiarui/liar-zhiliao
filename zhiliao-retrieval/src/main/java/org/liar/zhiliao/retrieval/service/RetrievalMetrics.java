package org.liar.zhiliao.retrieval.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * RAG 检索管线自定义指标。
 *
 * <p>8 个 Micrometer 指标，通过 {@code /actuator/prometheus} 暴露，
 * Grafana 可拉取 P50/P95/P99 延迟分布、调用量、空结果率等。</p>
 *
 * <table border="1">
 *   <tr><th>指标名</th><th>类型</th><th>说明</th></tr>
 *   <tr><td>rag_dense_search_duration</td><td>Timer</td><td>Milvus 稠密检索耗时</td></tr>
 *   <tr><td>rag_sparse_search_duration</td><td>Timer</td><td>PG BM25 稀疏检索耗时</td></tr>
 *   <tr><td>rag_rewrite_duration</td><td>Timer</td><td>LLM 查询改写耗时</td></tr>
 *   <tr><td>rag_rrf_result_count</td><td>Gauge</td><td>RRF 融合后返回的 Chunk 数</td></tr>
 *   <tr><td>rag_empty_result_total</td><td>Counter</td><td>检索空结果累计次数</td></tr>
 *   <tr><td>rag_llm_first_token_latency</td><td>Timer</td><td>从请求到首个 token 返回的耗时</td></tr>
 *   <tr><td>rag_cache_hit_rate</td><td>Gauge</td><td>Level 1 + Level 2 缓存综合命中率（0.0 ~ 1.0）</td></tr>
 * </table>
 */
@Component
public class RetrievalMetrics {

    // ========== Timers ==========

    /** Milvus 稠密检索耗时 */
    @Getter
    private final Timer denseSearch;

    /** PG BM25 稀疏检索耗时 */
    @Getter
    private final Timer sparseSearch;

    /** LLM 查询改写耗时 */
    @Getter
    private final Timer rewrite;

    /** 首个 Token 返回耗时 */
    @Getter
    private final Timer firstTokenLatency;

    // ========== Counters ==========

    /** 检索空结果累计次数 */
    @Getter
    private final Counter emptyResult;

    // ========== Gauges ==========

    /** RRF 融合后结果数（最近一次） */
    private final AtomicInteger rrfResultCount;

    /** 缓存命中率（0.0 ~ 1.0） */
    private final AtomicLong cacheHitRateRaw;

    /** MeterRegistry，用于创建 Timer.Sample */
    @Getter
    private final MeterRegistry registry;

    public RetrievalMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Timers
        this.denseSearch = Timer.builder("rag.dense_search.duration")
                .description("Milvus dense search latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.sparseSearch = Timer.builder("rag.sparse_search.duration")
                .description("PG BM25 search latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.rewrite = Timer.builder("rag.rewrite.duration")
                .description("LLM query rewrite latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        this.firstTokenLatency = Timer.builder("rag.llm.first_token_latency")
                .description("Time from request to first token")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);

        // Counters
        this.emptyResult = Counter.builder("rag.empty_result.total")
                .description("Total empty retrieval results")
                .register(registry);

        // Gauges
        this.rrfResultCount = new AtomicInteger(0);
        Gauge.builder("rag.rrf.result_count", rrfResultCount, AtomicInteger::get)
                .description("RRF result count")
                .register(registry);

        this.cacheHitRateRaw = new AtomicLong(0);
        Gauge.builder("rag.cache.hit_rate", cacheHitRateRaw, v -> v.get() / 1000.0)
                .description("Cache hit rate (0.0 ~ 1.0)")
                .register(registry);
    }

    /** 更新 RRF 结果数 Gauge */
    public void recordRrfResultCount(int count) {
        rrfResultCount.set(count);
    }

    /** 更新缓存命中率 Gauge（0.0 ~ 1.0） */
    public void recordCacheHitRate(double rate) {
        cacheHitRateRaw.set((long) (rate * 1000));
    }

    /** 创建计时样本，配合 Timer 使用：sample.stop(timer) */
    public Timer.Sample startTimer() {
        return Timer.start(registry);
    }
}
