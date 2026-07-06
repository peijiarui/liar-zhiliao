//package org.liar.zhiliao.retrieval.service.impl;
//
//import dev.langchain4j.data.embedding.Embedding;
//import dev.langchain4j.data.segment.TextSegment;
//import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
//import lombok.RequiredArgsConstructor;
//import org.liar.zhiliao.retrieval.service.VectorStore;
//import org.springframework.stereotype.Service;
//
//import java.util.List;
//
///**
// * Milvus-backed {@link VectorStore} implementation.
// * <p>
// * Delegates all write operations to {@link MilvusEmbeddingStore}.
// * Collection lifecycle (auto-creation on first write) is handled by the underlying
// * {@code MilvusEmbeddingStore} configured in {@link org.liar.zhiliao.retrieval.config.MilvusConfig}.
// *
// * @author Pei
// * @since 2026-07-06
// */
//@Service
//@RequiredArgsConstructor
//public class MilvusVectorStore implements VectorStore {
//
//    private final MilvusEmbeddingStore milvusEmbeddingStore;
//
//    @Override
//    public String store(float[] vector, TextSegment segment) {
//        Embedding embedding = Embedding.from(vector);
//        return milvusEmbeddingStore.add(embedding, segment);
//    }
//
//    @Override
//    public List<String> storeBatch(List<float[]> vectors, List<TextSegment> segments) {
//        List<Embedding> embeddings = vectors.stream()
//                .map(Embedding::from)
//                .toList();
//        return milvusEmbeddingStore.addAll(embeddings, segments);
//    }
//}
