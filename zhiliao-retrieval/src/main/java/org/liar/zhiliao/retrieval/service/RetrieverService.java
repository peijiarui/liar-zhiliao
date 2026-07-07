//package org.liar.zhiliao.retrieval.service;
//
//import dev.langchain4j.data.segment.TextSegment;
//
//import java.util.List;
//
///**
// * Retriever service for vector similarity search.
// * <p>
// * MVP: {@code MilvusRetriever} — pure vector search, COSINE similarity, top-K.
// * Future: {@code HybridRetriever} — Milvus dense + ES sparse BM25 + RRF fusion.
// *         {@code RerankedRetriever} — BGE-Reranker re-ranking, Top-30 → Top-5.
// *
// * @author Pei
// * @since 2026-07-06
// */
//public interface RetrieverService {
//
//    /**
//     * Retrieve the top-K most similar text segments for the given query.
//     *
//     * @param query      the query text
//     * @param maxResults maximum number of results to return
//     * @return list of relevant text segments, ordered by relevance descending
//     */
//    List<TextSegment> retrieve(String query, int maxResults);
//
//    /**
//     * Retrieve the top-K most similar text segments for the given query,
//     * filtering out results below a minimum relevance score.
//     *
//     * @param query      the query text
//     * @param maxResults maximum number of results to return
//     * @param minScore   minimum relevance score (cosine similarity, range [0, 1])
//     * @return list of relevant text segments meeting the score threshold,
//     *         ordered by relevance descending
//     */
//    List<TextSegment> retrieve(String query, int maxResults, double minScore);
//}
