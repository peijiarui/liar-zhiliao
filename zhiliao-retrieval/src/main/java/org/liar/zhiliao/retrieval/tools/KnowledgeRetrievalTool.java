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
import org.liar.zhiliao.retrieval.service.SparseSearcher;
import org.springframework.stereotype.Component;

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

    @Tool("检索企业知识库：查找公司制度、政策、流程、产品信息等企业内部知识。仅当用户明确询问企业内部知识时调用，日常闲聊无需调用")
    public String retrieveKnowledge(@P("查询内容") String query) {
        // Step 1: 查询改写
        List<String> subQueries = rewriteQuery(query);
        if (subQueries.isEmpty()) {
            subQueries = List.of(query);
        }
        log.debug("Rewritten queries: {}", subQueries);

        // Step 2: 对每个子查询执行双路检索
        List<EmbeddingMatch<TextSegment>> allDenseResults = new ArrayList<>();
        List<SparseSearchResult> allSparseResults = new ArrayList<>();

        for (String subQuery : subQueries) {
            // 2a: Milvus 稠密检索
            Embedding queryEmbedding = embeddingModel.embed(subQuery).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(10)
                    .minScore(0.5)
                    .build();
            EmbeddingSearchResult<TextSegment> result = milvusEmbeddingStore.search(request);
            allDenseResults.addAll(result.matches());

            // 2b: PG BM25 稀疏检索
            CurrentUser currentUser = UserContextHolder.get();
            List<Long> visibleDeptIds = currentUser != null
                    ? currentUser.visibleDeptIds()
                    : List.of(1L); // fallback: dept 1
            allSparseResults.addAll(sparseSearcher.search(subQuery, 10, visibleDeptIds));
        }

        // Step 3: RRF 融合排序
        List<RankedChunk> ranked = reranker.rerank(query, allDenseResults, allSparseResults, 10);

        if (ranked.isEmpty()) {
            return "";
        }

        // Step 4: 父子文档替换（child → parent content）
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
