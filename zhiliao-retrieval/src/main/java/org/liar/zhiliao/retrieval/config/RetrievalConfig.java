package org.liar.zhiliao.retrieval.config;

import org.liar.zhiliao.retrieval.repository.ChunkRepository;
import org.liar.zhiliao.retrieval.service.impl.PgBm25Searcher;
import org.liar.zhiliao.retrieval.service.Reranker;
import org.liar.zhiliao.retrieval.service.impl.RrfReranker;
import org.liar.zhiliao.retrieval.service.SparseSearcher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RetrievalConfig {

    /**
     * 稀疏检索实现。
     * zhiliao.retrieval.sparse=pg（默认）— PG tsvector BM25
     * zhiliao.retrieval.sparse=es         — Elasticsearch BM25（未来）
     */
    @Bean
    @ConditionalOnProperty(value = "zhiliao.retrieval.sparse", havingValue = "pg", matchIfMissing = true)
    public SparseSearcher pgBm25Searcher(ChunkRepository chunkRepository) {
        return new PgBm25Searcher(chunkRepository);
    }

    /**
     * 精排实现。
     * zhiliao.retrieval.reranker=rrf（默认）— RRF 融合
     * zhiliao.retrieval.reranker=bge         — BGE-Reranker（未来）
     */
    @Bean
    @ConditionalOnProperty(value = "zhiliao.retrieval.reranker", havingValue = "rrf", matchIfMissing = true)
    public Reranker rrfReranker() {
        return new RrfReranker();
    }
}
