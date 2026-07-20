package org.liar.zhiliao.retrieval.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.liar.zhiliao.common.model.CurrentUser;
import org.liar.zhiliao.common.utils.UserContextHolder;
import org.liar.zhiliao.retrieval.repository.ChunkRepository;
import org.liar.zhiliao.retrieval.records.RankedChunk;
import org.liar.zhiliao.retrieval.service.Reranker;
import org.liar.zhiliao.retrieval.records.SparseSearchResult;
import org.liar.zhiliao.retrieval.service.RetrievalCacheService;
import org.liar.zhiliao.retrieval.service.RetrievalMetrics;
import org.liar.zhiliao.retrieval.service.SparseSearcher;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Timer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author liar
 * @since 11/07/26
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeRetrievalTool {

    private final EmbeddingModel embeddingModel;
    private final EmbeddingStore<TextSegment> milvusEmbeddingStore;
    private final SparseSearcher sparseSearcher;
    private final Reranker reranker;
    private final ChunkRepository chunkRepository;
    private final ChatModel chatModel;
    private final RetrievalCacheService retrievalCacheService;
    private final RetrievalMetrics retrievalMetrics;

    @Tool("检索企业知识库：查找公司制度、政策、流程、产品信息等企业内部知识。仅当用户明确询问企业内部知识时调用，日常闲聊无需调用")
    public String retrieveKnowledge(@P("查询内容") String query) {
        // Step 1: 查询改写（计时）
        List<String> subQueries;
        Timer.Sample rewriteSample = retrievalMetrics.startTimer();
        try {
            subQueries = rewriteQuery(query);
        } finally {
            rewriteSample.stop(retrievalMetrics.getRewrite());
        }
        if (subQueries.isEmpty()) {
            subQueries = List.of(query);
        }
        log.debug("Rewritten queries: {}", subQueries);

        // Step 2: 尝试 Level 2 缓存（检索结果缓存）
        List<RankedChunk> ranked = tryCache(query, subQueries);
        if (ranked != null) {
            return ranked.isEmpty() ? "" : buildContextFromRanked(ranked);
        }

        // Step 3: 对每个子查询执行双路检索
        List<EmbeddingMatch<TextSegment>> allDenseResults = new ArrayList<>();
        List<SparseSearchResult> allSparseResults = new ArrayList<>();

        for (String subQuery : subQueries) {
            // 3a: Milvus 稠密检索（计时）
            log.debug("======== 调用向量模型获取向量 ========");
            Embedding queryEmbedding = embeddingModel.embed(subQuery).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(10)
                    .minScore(0.5)
                    .build();
            log.debug("======== 稠密检索：调用向量数据库进行相似度匹配 ========");
            Timer.Sample denseSample = retrievalMetrics.startTimer();
            try {
                EmbeddingSearchResult<TextSegment> result = milvusEmbeddingStore.search(request);
                allDenseResults.addAll(result.matches());
            } finally {
                denseSample.stop(retrievalMetrics.getDenseSearch());
            }

            // 3b: PG BM25 稀疏检索（计时）
            CurrentUser currentUser = UserContextHolder.get();
            List<Long> visibleDeptIds = currentUser != null
                    ? currentUser.visibleDeptIds()
                    : List.of(1L);
            log.debug("======== 稀疏检索：调用PG进行关键词匹配 ========");
            Timer.Sample sparseSample = retrievalMetrics.startTimer();
            try {
                allSparseResults.addAll(sparseSearcher.search(subQuery, 10, visibleDeptIds));
            } finally {
                sparseSample.stop(retrievalMetrics.getSparseSearch());
            }
        }

        // Step 4: RRF 融合排序
        ranked = reranker.rerank(query, allDenseResults, allSparseResults, 10);
        retrievalMetrics.recordRrfResultCount(ranked.size());

        // Step 5: 空结果计数 + 写入 Level 2 缓存
        if (ranked.isEmpty()) {
            retrievalMetrics.getEmptyResult().increment();
            return "";
        }
        putCache(query, subQueries, ranked);

        // Step 6: 父子文档替换 + 构建上下文
        return buildContextFromRanked(ranked);
    }

    /**
     * 尝试从 Level 2 缓存读取检索结果。
     * 仅对未改写（单子查询 = 原始 query）的情况生效。
     *
     * @return 缓存的 RankedChunk 列表，未命中返回 null
     */
    private List<RankedChunk> tryCache(String query, List<String> subQueries) {
        if (subQueries.size() == 1 && subQueries.get(0).equals(query)) {
            List<RankedChunk> cached = retrievalCacheService.getCachedRetrieval(query);
            if (cached != null) {
                log.debug("Level 2 cache hit for query: {}", query);
                return cached;
            }
        }
        return null;
    }

    /**
     * 写入 Level 2 缓存。仅对未改写的单子查询缓存，
     * 避免组合子查询场景导致缓存膨胀。
     */
    private void putCache(String query, List<String> subQueries, List<RankedChunk> ranked) {
        if (subQueries.size() == 1 && subQueries.get(0).equals(query)) {
            retrievalCacheService.putRetrieval(query, ranked);
        }
    }

    /**
     * 从 RankedChunk 列表构建 LLM 上下文。
     * 优先 parent content，parent 不存在时退回到 child content。
     */
    private String buildContextFromRanked(List<RankedChunk> ranked) {
        StringBuilder context = new StringBuilder();
        for (RankedChunk chunk : ranked) {
            String content;
            if (chunk.parentId() != null) {
                try {
                    content = chunkRepository.findContentById(chunk.parentId());
                } catch (Exception e) {
                    log.warn("Parent content not found for parentId={}, using child content", chunk.parentId());
                    content = chunk.content();
                }
            } else {
                content = chunk.content();
            }
            if (!context.isEmpty()) {
                context.append("\n---\n");
            }
            context.append(content);
        }
        return context.toString();
    }

    /** 调用 LLM 改写查询，支持多维度子查询 */
    private List<String> rewriteQuery(String query) {
        String prompt = """
                你是一个查询优化助手。将用户的问题改写成更适合检索的关键词形式。
                如果问题包含多个方面，用换行分隔多个查询。
                不要添加与问题无关的关键词。不要回答用户的问题，只输出改写后的关键词。

                用户问题：%s
                改写后：
                """.formatted(query);

        try {
            String result = chatModel.chat(prompt);
            return Arrays.stream(result.split("\\n"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .toList();
        } catch (Exception e) {
            log.warn("Query rewriting failed, using original query: {}", e.getMessage());
            return List.of();
        }
    }
}
