package org.liar.zhiliao.retrieval.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 检索配置 - 负责构建内容检索器，为 ChatService 提供 RAG 检索能力。
 * <p>
 * MVP 使用 MilvusEmbeddingStore。可通过 {@code zhiliao.retrieval.store-type} 切换：
 * <ul>
 *   <li>{@code milvus}（默认）- 使用 Milvus 向量数据库</li>
 *   <li>{@code in-memory} - 使用内存向量存储（开发/测试用）</li>
 * </ul>
 *
 * @author Pei
 * @since 2026-07-06
 */
@Slf4j
@Configuration
@AllArgsConstructor
public class RetrievalConfig {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;

    /**
     * 默认的内容检索器（Milvus），使用 MilvusEmbeddingStore 进行检索。
     */
    @Bean("contentRetriever")
//    @Primary
//    @ConditionalOnProperty(name = "zhiliao.retrieval.store-type", havingValue = "milvus", matchIfMissing = true)
    public ContentRetriever milvusContentRetriever() {
        log.info("Initializing Milvus ContentRetriever (minScore=0.5, maxResults=5)");
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .minScore(0.5)
                .maxResults(5)
                .build();
    }

    /**
     * 备选的内容检索器（In-Memory），用于本地开发/测试。
     */
//    @Bean("contentRetriever")
//    @ConditionalOnProperty(name = "zhiliao.retrieval.store-type", havingValue = "in-memory")
    public ContentRetriever inMemoryContentRetriever() {
        log.info("Initializing In-Memory ContentRetriever (minScore=0.5, maxResults=3)");
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .minScore(0.5)
                .maxResults(3)
                .build();
    }
}
