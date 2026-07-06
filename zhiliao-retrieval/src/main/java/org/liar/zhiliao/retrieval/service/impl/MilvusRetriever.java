//package org.liar.zhiliao.retrieval.service.impl;
//
//import dev.langchain4j.data.embedding.Embedding;
//import dev.langchain4j.data.segment.TextSegment;
//import dev.langchain4j.store.embedding.EmbeddingMatch;
//import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
//import dev.langchain4j.store.embedding.EmbeddingSearchResult;
//import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
//import lombok.RequiredArgsConstructor;
//import lombok.extern.slf4j.Slf4j;
//import org.liar.zhiliao.retrieval.service.EmbeddingService;
//import org.liar.zhiliao.retrieval.service.RetrieverService;
//import org.springframework.stereotype.Service;
//
//import java.util.Comparator;
//import java.util.List;
//
///**
// * Milvus-backed {@link RetrieverService} implementation.
// * <p>
// * Performs pure vector similarity search using COSINE distance.
// * Results are fetched from Milvus via {@link MilvusEmbeddingStore#search(EmbeddingSearchRequest)},
// * then sorted by score descending and trimmed to the requested {@code maxResults}.
// * <p>
// * The pre-fetch multiplier (maxResults * 2) provides headroom for post-filtering
// * while the {@code minScore} parameter is passed directly to Milvus for early filtering.
// *
// * @author Pei
// * @since 2026-07-06
// */
//@Slf4j
//@Service
//@RequiredArgsConstructor
//public class MilvusRetriever implements RetrieverService {
//
//    private final MilvusEmbeddingStore milvusEmbeddingStore;
//    private final EmbeddingService embeddingService;
//
//    @Override
//    public List<TextSegment> retrieve(String query, int maxResults) {
//        return retrieve(query, maxResults, 0.0);
//    }
//
//    @Override
//    public List<TextSegment> retrieve(String query, int maxResults, double minScore) {
//        // 1. Embed the query text into a vector
//        float[] queryVector = embeddingService.embed(query);
//        Embedding queryEmbedding = Embedding.from(queryVector);
//
//        // 2. Build search request — pre-fetch extra results for post-filtering headroom
//        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
//                .queryEmbedding(queryEmbedding)
//                .maxResults(maxResults * 2)
//                .minScore(minScore)
//                .build();
//
//        // 3. Execute search against Milvus
//        EmbeddingSearchResult<TextSegment> result = milvusEmbeddingStore.search(request);
//        List<EmbeddingMatch<TextSegment>> matches = result.matches();
//
//        if (matches.isEmpty()) {
//            log.debug("No results found for query (maxResults={}, minScore={})", maxResults, minScore);
//            return List.of();
//        }
//
//        // 4. Sort by score descending and limit to maxResults
//        return matches.stream()
//                .sorted(Comparator.<EmbeddingMatch<TextSegment>>comparingDouble(
//                                m -> m.score() != null ? m.score() : 0.0)
//                        .reversed())
//                .limit(maxResults)
//                .map(EmbeddingMatch::embedded)
//                .filter(segment -> segment != null)
//                .toList();
//    }
//}
