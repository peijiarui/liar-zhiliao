package org.liar.zhiliao.retrieval.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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

    @Tool("检索企业知识库：查找公司制度、政策、流程、产品信息等企业内部知识。仅当用户明确询问企业内部知识时调用，日常闲聊无需调用")
    public String retrieveKnowledge(@P("查询内容") String query) {
        log.debug("KnowledgeRetrievalService 检索: {}", query);

        // 1. 将用户问题向量化（仅此一次调用）
        Embedding queryEmbedding = embeddingModel.embed(query).content();

        // 2. 在 Milvus 中执行相似度检索
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(5)
                .minScore(0.5)
                .build();
        EmbeddingSearchResult<TextSegment> result = milvusEmbeddingStore.search(request);

        // 3. 无匹配结果时返回空字符串，LLM 据此告知用户未找到相关信息
        List<EmbeddingMatch<TextSegment>> matches = result.matches();
        if (matches.isEmpty()) {
            return "";
        }

        // 4. 拼接检索结果，多个文档间用分隔线隔开，LLM 会自动格式化回答
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < matches.size(); i++) {
            if (i > 0) {
                sb.append("\n---\n");
            }
            sb.append(matches.get(i).embedded().text());
        }
        return sb.toString();
    }

}
