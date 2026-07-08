package org.liar.zhiliao.retrieval.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.retrieval.mapper.SysRoutingSeedMapper;
import org.liar.zhiliao.retrieval.retriever.EmptyContentRetriever;
import org.liar.zhiliao.retrieval.router.EmbeddingQueryRouter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 检索配置 - 负责构建内容检索器，为 ChatService 提供 RAG 检索能力。
 * <p>
 * MVP 使用 MilvusEmbeddingStore + EmbeddingQueryRouter 实现智能路由：
 * 闲聊类查询跳过 RAG，知识类查询执行向量检索。
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
    private final SysRoutingSeedMapper routingSeedMapper;

    /**
     * 知识库内容检索器（Milvus），用于知识类查询。
     */
    @Bean("knowledgeContentRetriever")
    public ContentRetriever knowledgeContentRetriever() {
        log.info("Initializing Milvus ContentRetriever (minScore=0.5, maxResults=5)");
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .minScore(0.5)
                .maxResults(5)
                .build();
    }

    /**
     * 空内容检索器，用于闲聊类查询（跳过 RAG）。
     */
    @Bean("emptyContentRetriever")
    public ContentRetriever emptyContentRetriever() {
        return new EmptyContentRetriever();
    }

    /**
     * Embedding 相似度查询路由器，根据意图将查询路由到不同的检索器。
     */
    @Bean
    public EmbeddingQueryRouter embeddingQueryRouter() {
        var router = new EmbeddingQueryRouter(
                embeddingModel,
                knowledgeContentRetriever(),
                emptyContentRetriever(),
                routingSeedMapper);
        router.init();
        return router;
    }

    /**
     * 装配检索增强器，供 ChatService 的 @AiService 引用。
     */
    @Bean("retrievalAugmentor")
    public RetrievalAugmentor retrievalAugmentor(EmbeddingQueryRouter router) {
        return DefaultRetrievalAugmentor.builder()
                .queryRouter(router)
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
