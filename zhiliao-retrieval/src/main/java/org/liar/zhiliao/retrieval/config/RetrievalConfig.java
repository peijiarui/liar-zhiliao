package org.liar.zhiliao.retrieval.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.retrieval.mapper.SysRoutingSeedMapper;
import org.liar.zhiliao.retrieval.retriever.CustomContentRetriever;
import org.liar.zhiliao.retrieval.retriever.EmptyContentRetriever;
import org.liar.zhiliao.retrieval.router.EmbeddingQueryRouter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

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
public class RetrievalConfig {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> milvusEmbeddingStore;
    private final SysRoutingSeedMapper routingSeedMapper;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @Value("${langchain4j.open-ai.embedding-model.base-url}")
    private String embeddingBaseUrl;

    @Value("${langchain4j.open-ai.embedding-model.model-name}")
    private String embeddingModelName;

    public RetrievalConfig(EmbeddingModel embeddingModel,
                           EmbeddingStore<TextSegment> milvusEmbeddingStore,
                           SysRoutingSeedMapper routingSeedMapper,
                           StringRedisTemplate redisTemplate,
                           ObjectMapper objectMapper) {
        this.embeddingModel = embeddingModel;
        this.milvusEmbeddingStore = milvusEmbeddingStore;
        this.routingSeedMapper = routingSeedMapper;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 知识库内容检索器（Milvus），用于知识类查询。
     */
    @Bean("knowledgeContentRetriever")
    public ContentRetriever knowledgeContentRetriever() {
        log.info("Initializing CustomContentRetriever (minScore=0.5, maxResults=5)");
        return new CustomContentRetriever(
                embeddingModel,
                milvusEmbeddingStore,
                embeddingBaseUrl,
                embeddingModelName,
                5,
                0.5);
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
                routingSeedMapper,
                embeddingBaseUrl,
                embeddingModelName,
                redisTemplate,
                objectMapper);
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

}
