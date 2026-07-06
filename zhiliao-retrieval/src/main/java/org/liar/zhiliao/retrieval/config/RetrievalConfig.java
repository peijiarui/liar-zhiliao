package org.liar.zhiliao.retrieval.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 检索配置 - 负责构建内容检索器，为 ChatService 提供 RAG 检索能力。
 * <p>
 * 职责归属 {@code zhiliao-retrieval} 模块，仅关注检索逻辑。
 * {@link EmbeddingStore} 由 {@code zhiliao-ingestion} 模块提供，此处通过方法参数注入。
 *
 * @author Pei
 * @since 2026-07-05
 */
@Configuration
@AllArgsConstructor
public class RetrievalConfig {

    /**
     * Langchain4j自动注入的向量模型，如果在yaml中配置的话就默认使用
     */
    private final EmbeddingModel embeddingModel;

    /**
     * 创建内容检索器，用于处理交由大模型处理的请求体
     *
     * @param embeddingStore embeddingStore
     * @return 内容检索对象
     */
    @Bean
    public ContentRetriever contentRetriever(EmbeddingStore<TextSegment> embeddingStore) {
        return EmbeddingStoreContentRetriever.builder()
                //接收的向量存储对象
                .embeddingStore(embeddingStore)
                //接收的向量模型对象，用于检索
                .embeddingModel(embeddingModel)
                //设置最小的余弦相似度。0-1
                .minScore(0.5)
                //设置最大的返回数量
                .maxResults(3)
                .build();
    }

}
