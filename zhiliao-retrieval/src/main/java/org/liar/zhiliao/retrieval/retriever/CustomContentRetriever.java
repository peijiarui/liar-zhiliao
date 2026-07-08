package org.liar.zhiliao.retrieval.retriever;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 自定义内容检索器 — 在向量模型调用、向量数据库查询及结果返回三个节点输出日志。
 * <p>
 * 替代 {@code EmbeddingStoreContentRetriever}，提供完整的检索过程可见性。
 *
 * @author Pei
 * @since 2026-07-10
 */
@Slf4j
public class CustomContentRetriever implements ContentRetriever {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> embeddingStore;
    private final String embeddingBaseUrl;
    private final String embeddingModelName;
    private final int maxResults;
    private final double minScore;

    public CustomContentRetriever(EmbeddingModel embeddingModel,
                                  EmbeddingStore<TextSegment> embeddingStore,
                                  String embeddingBaseUrl,
                                  String embeddingModelName,
                                  int maxResults,
                                  double minScore) {
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
        this.embeddingBaseUrl = embeddingBaseUrl;
        this.embeddingModelName = embeddingModelName;
        this.maxResults = maxResults;
        this.minScore = minScore;
    }

    @Override
    public List<Content> retrieve(Query query) {
        // 1. 向量模型调用日志
        log.info("========开始调用向量模型========");
        log.info("base-url : {}", embeddingBaseUrl);
        log.info("model-name : {}", embeddingModelName);
        log.info("text : {}", query.text());

        Embedding queryEmbedding = embeddingModel.embed(query.text()).content();

        // 2. 查询向量数据库日志
        log.info("========开始查询向量数据库========");

        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(maxResults)
                .minScore(minScore)
                .build();
        EmbeddingSearchResult<TextSegment> result = embeddingStore.search(request);

        // 3. 结果日志
        log.info("========向量数据库查询结果========");
        for (EmbeddingMatch<TextSegment> match : result.matches()) {
            log.info("{} ---> {}", match.embeddingId(), String.format("%.1f", match.score()));
        }

        return result.matches().stream()
                .map(m -> {
                    Map<ContentMetadata, Object> metadata = new HashMap<>();
                    metadata.put(ContentMetadata.SCORE, m.score());
                    metadata.put(ContentMetadata.EMBEDDING_ID, m.embeddingId());
                    return Content.from(m.embedded(), metadata);
                })
                .toList();
    }
}
