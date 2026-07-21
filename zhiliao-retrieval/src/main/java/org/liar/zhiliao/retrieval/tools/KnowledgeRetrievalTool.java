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
import java.nio.charset.StandardCharsets;

import org.springframework.util.DigestUtils;

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
        // Step 0: 查询规范化
        String normalized = normalize(query);

        // 规范化后为空的极端情况，回退使用 MD5 兜底
        String canonicalKey = normalized.isEmpty()
                ? DigestUtils.md5DigestAsHex(query.getBytes(StandardCharsets.UTF_8))
                : normalized;

        // 超长规范化查询（>200字符）压缩为 MD5，避免 Redis key 过长
        if (canonicalKey.length() > 200) {
            canonicalKey = DigestUtils.md5DigestAsHex(canonicalKey.getBytes(StandardCharsets.UTF_8));
        }

        // Step 1: 部门后缀（用于缓存 key 权限隔离）
        String deptSuffix = extractDeptSuffix();

        // Step 2: 尝试从 rewrite 缓存获取改写结果
        List<String> subQueries;
        String rewritten = retrievalCacheService.getRewrite(canonicalKey);
        if (rewritten != null) {
            String[] parts = rewritten.split("\\n");
            subQueries = Arrays.stream(parts).map(String::trim).filter(s -> !s.isEmpty()).toList();
            log.debug("Rewrite cache hit for '{}': {}", canonicalKey, rewritten);
        } else if (!normalized.equals(query)) {
            // normalize 后有变化，说明需要 LLM 改写
            Timer.Sample rewriteSample = retrievalMetrics.startTimer();
            try {
                subQueries = rewriteQuery(query);
            } finally {
                rewriteSample.stop(retrievalMetrics.getRewrite());
            }
            if (subQueries.isEmpty()) {
                subQueries = List.of(canonicalKey);
            } else {
                // 缓存改写结果（仅 LLM 成功返回时）
                String joined = String.join("\n", subQueries);
                retrievalCacheService.putRewrite(canonicalKey, joined);
            }
        } else {
            // normalize 后无变化（如 "请假流程" 已是规范形态），直接用原值
            subQueries = List.of(canonicalKey);
            // 同时填充 rewrite 缓存，确保后续请求可以命中
            retrievalCacheService.putRewrite(canonicalKey, canonicalKey);
        }

        // Step 3: 尝试从 retrieval 缓存获取检索结果（使用 canonicalKey 而非原始 query）
        List<RankedChunk> ranked = retrievalCacheService.getCachedRetrieval(canonicalKey, deptSuffix);
        if (ranked != null) {
            log.debug("Retrieval cache hit for key: {}:{}", canonicalKey, deptSuffix);
            return ranked.isEmpty() ? "" : buildContextFromRanked(ranked);
        }

        // Step 4: 对每个子查询执行双路检索
        List<EmbeddingMatch<TextSegment>> allDenseResults = new ArrayList<>();
        List<SparseSearchResult> allSparseResults = new ArrayList<>();

        for (String subQuery : subQueries) {
            // 4a: Milvus 稠密检索（计时）
            log.debug("======== 调用向量模型获取向量 ========");
            Embedding queryEmbedding = embeddingModel.embed(subQuery).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(10)
                    .minScore(0.5)
                    .build();
            log.debug("======== 稠密检索：调用向量数据库进行相似度匹配 ========");
            Timer.Sample denseSample = retrievalMetrics.startTimer();   // 稠密检索耗时统计埋点
            try {
                EmbeddingSearchResult<TextSegment> result = milvusEmbeddingStore.search(request);
                allDenseResults.addAll(result.matches());
            } finally {
                denseSample.stop(retrievalMetrics.getDenseSearch());    // 埋点结束，必须调用stop
            }

            // 4b: PG BM25 稀疏检索（计时）
            CurrentUser currentUser = UserContextHolder.get();
            List<Long> visibleDeptIds = currentUser != null
                    ? currentUser.visibleDeptIds()
                    : List.of(1L);
            log.debug("======== 稀疏检索：调用PG进行关键词匹配 ========");
            Timer.Sample sparseSample = retrievalMetrics.startTimer();  // 稀疏检索耗时统计埋点
            try {
                allSparseResults.addAll(sparseSearcher.search(subQuery, 10, visibleDeptIds));
            } finally {
                sparseSample.stop(retrievalMetrics.getSparseSearch());  // 埋点结束，必须调用stop
            }
        }

        // Step 5: RRF 融合排序
        ranked = reranker.rerank(query, allDenseResults, allSparseResults, 10);
        retrievalMetrics.recordRrfResultCount(ranked.size());

        // Step 6: 空结果计数
        if (ranked.isEmpty()) {
            retrievalMetrics.getEmptyResult().increment();
            return "";
        }

        // Step 7: 写入 retrieval 缓存（使用 canonicalKey）
        retrievalCacheService.putRetrieval(canonicalKey, deptSuffix, ranked);

        // Step 8: 父子文档替换 + 构建上下文
        return buildContextFromRanked(ranked);
    }

    /**
     * 从当前用户上下文中提取部门 ID 后缀。
     */
    private String extractDeptSuffix() {
        CurrentUser currentUser = UserContextHolder.get();
        List<Long> deptIds = currentUser != null
                ? currentUser.visibleDeptIds()
                : List.of(1L);
        // 排序保证相同部门集合产生相同的缓存 key 后缀
        List<Long> sorted = new ArrayList<>(deptIds);
        sorted.sort(Long::compareTo);
        if (sorted.isEmpty()) {
            sorted = new ArrayList<>(List.of(1L));
        }
        return sorted.stream()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining("_"));
    }

    /**
     * 将用户查询归一化为规范形态，去除语气词、标点符号和重复词。
     * 若结果为空，调用方应使用 MD5(原始查询) 作为兜底。
     */
    static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw.trim();
        s = s.replaceAll("^(请问|我想问|我要|我想|我来|我查一下|帮我|能不能|可以|麻烦)", "");
        s = s.replaceAll("(是什么|怎么做|如何操作|怎么弄|怎么办|有哪些|在哪|怎么走|呢|吗|啊|呀|嘛|哦)$", "");
        s = s.replaceAll("[\\pP\\pZ\\s]", "");
        s = s.replaceAll("(.+?)\\1+", "$1");
        return s;
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
