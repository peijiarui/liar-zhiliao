package org.liar.zhiliao.retrieval.service;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import org.liar.zhiliao.retrieval.records.RankedChunk;
import org.liar.zhiliao.retrieval.records.SparseSearchResult;

import java.util.List;

/**
 * 精排接口。
 * 当前：RrfReranker — RRF 融合，零依赖
 * 未来：BgeReranker — BGE-Reranker 本地模型 / Cohere Rerank API
 */
public interface Reranker {

    /**
     * @param query        原始用户问题
     * @param denseResults  Milvus 稠密检索结果
     * @param sparseResults PG BM25 稀疏检索结果
     * @param topK          返回 Top-K 个结果
     * @return 融合排序后的结果列表（含 chunkId、content、parentId、score）
     */
    List<RankedChunk> rerank(
            String query,
            List<EmbeddingMatch<TextSegment>> denseResults,
            List<SparseSearchResult> sparseResults,
            int topK
    );
}
